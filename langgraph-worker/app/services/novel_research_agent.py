from __future__ import annotations

import asyncio
import json
import re
from collections.abc import AsyncGenerator
from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from app.config import settings
from app.models.agent_task import EvidencePack, TaskGraph, TaskType, ToolPlan, ToolRun
from app.models.knowledge import (
    AnalysisMaterial,
    BookCandidate,
    BookProfile,
    BookResearchPack,
    ChapterMaterial,
    KnowledgeChatRequest,
    KnowledgeChatResponse,
    KnowledgeSource,
    RankLookupResult,
    RankResearchPack,
)
from app.services.agents import create_context, run_specialists_parallel
from app.services.checkpointing import build_langgraph_checkpointer, checkpoint_store_name
from app.services.intents import AnswerBoundary, Intent, IntentDecision, IntentRouter
from app.services.knowledge_client import KnowledgeBackendClient
from app.services.provider_client import OpenAICompatibleProviderClient
from app.services.retrieval_fusion import fuse_and_rerank_sources
from app.models.agent_runtime import ContextBundle, SourcePolicy
from app.services.skills import SkillRegistry
from app.services.task_graph import DomainTaskToolExecutor, DomainToolPlanner, EvidencePackBuilder, TaskGraphDecomposer
from app.services.tools.domain_tools import build_domain_tool_registry
from app.services.tools.registry import DomainToolRegistry
from app.services.runtime import AgentSupervisor, ContextAssembler
from app.services.runtime.memory_candidates import MemoryCandidateExtractor


TREND_ANSWER_MAX_TOKENS = 8000
EVIDENCE_ANSWER_MAX_TOKENS = 16000
LONG_CREATIVE_ANSWER_MAX_TOKENS = 64000
CREATIVE_ANSWER_MAX_TOKENS = 16000
CONVERSATION_CONTEXT_PROMPT_CHARS = 900000
CONTEXT_SUMMARY_PROMPT_CHARS = 720000
HISTORY_PROMPT_MESSAGES = 12
HISTORY_PROMPT_CHARS = 64000
HISTORY_PROMPT_MAX_CHARS = 256000
MEMORY_SUMMARY_ANSWER_CHARS = 64000
MEMORY_SUMMARY_CHARS = 240000
STICKY_CONTEXT_CHARS = 12000


class ResearchState(TypedDict, total=False):
    request: KnowledgeChatRequest
    intent: str
    book_name: str | None
    book_id: int | None
    platform: str | None
    in_scope: bool
    candidates: list[BookCandidate]
    sources: list[KnowledgeSource]
    actions: list[str]
    response: KnowledgeChatResponse
    rank_lookup: dict[str, Any]
    needs_structured_rank: bool
    needs_vector_evidence: bool
    needs_creative_advice: bool
    answer_boundary: str | None
    intent_decision: dict[str, Any]
    domain_intent: str | None
    source_policy: dict[str, Any]
    selected_skills: list[str]
    skill_prompt: str
    specialist_results: list[dict[str, Any]]
    task_graph: dict[str, Any]
    task_tool_plan: list[dict[str, Any]]
    evidence_pack_summary: dict[str, Any]
    supervisor: dict[str, Any]
    perspective_results: list[dict[str, Any]]
    tool_plan: list[dict[str, Any]]
    tool_runs: list[dict[str, Any]]
    retry_counts: dict[str, int]
    memory_candidates: list[dict[str, Any]]
    context_bundle: ContextBundle


class NovelResearchAgent:
    def __init__(
        self,
        knowledge_client: KnowledgeBackendClient | None = None,
        provider_client: OpenAICompatibleProviderClient | None = None,
    ) -> None:
        self.knowledge_client = knowledge_client or KnowledgeBackendClient(
            base_url=settings.backend_base_url,
            internal_api_key=settings.backend_internal_api_key,
        )
        self.provider_client = provider_client or OpenAICompatibleProviderClient()
        self.intent_router = IntentRouter()
        self.skill_registry = SkillRegistry()
        self.task_graph_decomposer = TaskGraphDecomposer()
        self.domain_tool_planner = DomainToolPlanner()
        self.evidence_pack_builder = EvidencePackBuilder()
        self.agent_supervisor = AgentSupervisor()
        self.context_assembler = ContextAssembler(memory_client=self.knowledge_client)
        self.memory_candidate_extractor = MemoryCandidateExtractor()
        self._llm_semaphore = asyncio.Semaphore(settings.max_active_llm_calls)
        self._checkpointer = build_langgraph_checkpointer("novel-research-agent")
        self._tool_registry = self._build_tool_registry()
        self._task_tool_executor = DomainTaskToolExecutor(self._tool_registry)
        self._graph = self._build_graph()

    def _build_tool_registry(self) -> DomainToolRegistry:
        registry = build_domain_tool_registry(
            self.knowledge_client,
            skill_registry=self.skill_registry,
        )
        registry.register(
            "memory.project_context",
            "memory",
            {"type": "object"},
            self._project_context_tool,
        )
        return registry

    async def run(self, request: KnowledgeChatRequest) -> KnowledgeChatResponse:
        initial_state = await self._initial_state(request)
        state = await self._graph.ainvoke(
            initial_state,
            config=self._graph_config(request),
        )
        return state["response"]

    async def _initial_state(self, request: KnowledgeChatRequest) -> ResearchState:
        return {
            "request": request,
            "actions": [],
            "context_bundle": await self.context_assembler.assemble_async(request),
        }

    async def aclose(self) -> None:
        close_fn = getattr(self.knowledge_client, "aclose", None)
        if callable(close_fn):
            await close_fn()

    async def stream(self, request: KnowledgeChatRequest) -> AsyncGenerator[dict[str, Any], None]:
        yield {"event": "start", "phase": "langgraph", "status": "running"}

        state = await self._initial_state(request)
        yield self._progress_event("intent", "正在识别创作意图")
        state.update(await self._intent_router_node(state))

        if state.get("response") is not None:
            async for event in self._emit_response(state["response"]):
                yield event
            return

        if state.get("intent") == "creative_advice":
            yield self._progress_event("generate", "正在生成创作方案")
            async for event in self._stream_creative_answer(request, state):
                yield event
            return

        yield self._progress_event("resolve", "正在定位作品和榜单")
        state.update(await self._book_resolver_node(state))
        if state.get("response") is not None:
            async for event in self._emit_response(state["response"]):
                yield event
            return

        state.update(await self._data_completer_node(state))
        yield self._progress_event("rank", "正在读取榜单趋势")
        state.update(await self._structured_rank_lookup_node(state))
        if state.get("response") is not None:
            async for event in self._emit_response(state["response"]):
                yield event
            return
        yield self._progress_event("evidence", "正在检索知识库证据")
        async for event in self._run_stream_step_with_progress(
            self._evidence_retriever_node(state),
            phase="evidence_wait",
            message="正在等待知识库证据返回",
        ):
            if self._is_step_result_event(event):
                state.update(event["data"])
            else:
                yield event
        sources = state.get("sources", [])

        if not sources:
            if self._should_search_book_after_missing_evidence(state):
                state.update(await self._build_book_candidates_response(state))
            else:
                state.update(await self._answer_writer_node(state))
            async for event in self._emit_response(state["response"]):
                yield event
            return

        yield self._progress_event("generate", "正在生成回答")
        async for event in self._stream_answer_with_sources(state):
            yield event

    def _progress_event(self, phase: str, message: str) -> dict[str, Any]:
        return {"event": "progress", "phase": phase, "message": message}

    def _is_step_result_event(self, event: dict[str, Any]) -> bool:
        return event.get("event") == "__result__"

    async def _run_stream_step_with_progress(
        self,
        awaitable: Any,
        *,
        phase: str,
        message: str,
        interval_seconds: float = 8.0,
    ) -> AsyncGenerator[dict[str, Any], None]:
        task = asyncio.create_task(awaitable)
        heartbeat = 0
        try:
            yield self._progress_event(phase, message)
            while True:
                try:
                    result = await asyncio.wait_for(asyncio.shield(task), timeout=interval_seconds)
                    yield {"event": "__result__", "data": result}
                    return
                except TimeoutError:
                    heartbeat += 1
                    yield self._progress_event(phase, f"{message} {heartbeat}")
        finally:
            if not task.done():
                task.cancel()

    async def _emit_response(self, response: KnowledgeChatResponse) -> AsyncGenerator[dict[str, Any], None]:
        if response.answer:
            yield {"event": "delta", "delta": response.answer, "phase": "answer"}
        yield {"event": "done", "data": response.model_dump()}

    async def _emit_done(self, response: KnowledgeChatResponse) -> AsyncGenerator[dict[str, Any], None]:
        yield {"event": "done", "data": response.model_dump()}

    async def _stream_creative_answer(
        self,
        request: KnowledgeChatRequest,
        state: ResearchState | None = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        collected: list[str] = []
        meta: dict[str, bool] = {}
        async for event in self._stream_generated_text(
            messages=self._build_creative_messages(request),
            temperature=0.4,
            max_tokens=self._creative_max_tokens(request),
            timeout_millis=self._request_timeout_millis(request),
            fallback_text="模型暂时不可用，我先按网文创作方向给出一个简版建议：先明确主角短期目标，再安排高频反馈的阻力和代价，让爽点从目标推进中自然出现。",
            collected=collected,
            meta=meta,
        ):
            yield event
        response = KnowledgeChatResponse(
            status="answered",
            answer="".join(collected).strip(),
            candidates=[],
            sources=[],
            actions=[],
            resultJson={
                "status": "answered",
                "intent": "creative_advice",
                "answerMode": "creative",
                "answerStatus": "creative_answer",
                "answerBoundary": "creative_inference",
                "fallbackUsed": bool(meta.get("fallbackUsed")),
            },
        )
        if state is not None:
            state.update(await self._specialist_agents_node({**state, "sources": []}))
            self._attach_domain_intent_metadata(response, state)
        self._attach_memory_metadata(response, request)
        verified = await self._citation_verifier_node({**(state or {}), "request": request, "response": response})
        async for event in self._emit_done(verified["response"]):
            yield event

    async def _stream_answer_with_sources(self, state: ResearchState) -> AsyncGenerator[dict[str, Any], None]:
        request = state["request"]
        sources = state.get("sources", [])
        answer_mode = self._answer_mode(request, sources, str(state.get("intent") or ""), state=state)
        collected: list[str] = []
        meta: dict[str, bool] = {}
        state.update(await self._specialist_agents_node({**state, "sources": sources}))
        async for event in self._stream_generated_text(
            messages=self._build_answer_messages(request, sources, answer_mode, state=state),
            temperature=0.2,
            max_tokens=self._answer_max_tokens(request, answer_mode),
            timeout_millis=self._request_timeout_millis(request),
            fallback_text=self._compose_fallback_answer(request.question, sources, answer_mode=answer_mode),
            collected=collected,
            meta=meta,
        ):
            yield event
        final_answer = "".join(collected).strip()
        final_answer = self._postprocess_answer_for_mode(final_answer, sources, answer_mode)
        response = KnowledgeChatResponse(
            status="answered",
            answer=final_answer,
            candidates=[],
            sources=sources,
            actions=self._dedupe(list(state.get("actions", []))),
            resultJson={
                "status": "answered",
                "intent": state.get("intent"),
                "bookId": state.get("book_id"),
                "bookName": state.get("book_name"),
                "answerMode": answer_mode,
                "answerStatus": self._answer_status(answer_mode, sources, state.get("intent")),
                "answerBoundary": self._answer_boundary(answer_mode, sources, state.get("intent"), state.get("answer_boundary")),
                "sourceCount": len(sources),
                "diagnostics": self._answer_diagnostics(sources, final_answer),
                "fallbackUsed": bool(meta.get("fallbackUsed")),
            },
        )
        self._attach_domain_intent_metadata(response, state)
        self._attach_memory_metadata(response, request)
        verified = await self._citation_verifier_node({**state, "request": request, "response": response})
        async for event in self._emit_done(verified["response"]):
            yield event

    async def _stream_generated_text(
        self,
        *,
        messages: list[dict[str, str]],
        temperature: float,
        max_tokens: int,
        timeout_millis: int,
        fallback_text: str,
        collected: list[str],
        meta: dict[str, bool],
    ) -> AsyncGenerator[dict[str, Any], None]:
        stream_fn = getattr(self.provider_client, "stream", None)
        meta["fallbackUsed"] = False

        if callable(stream_fn):
            try:
                async for event in self._provider_stream(
                    stream_fn,
                    messages=messages,
                    model=settings.default_model,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    require_json=False,
                    timeout_millis=timeout_millis,
                ):
                    if event.get("event") != "delta":
                        continue
                    delta = str(event.get("delta") or "")
                    if not delta:
                        continue
                    collected.append(delta)
                    yield {"event": "delta", "delta": delta, "phase": "answer"}
                answer = "".join(collected).strip()
                if answer:
                    return
            except Exception:
                answer = "".join(collected).strip()
                if answer:
                    meta["fallbackUsed"] = True
                    collected.clear()
                else:
                    meta["fallbackUsed"] = True

        try:
            result = await self._provider_invoke(
                messages=messages,
                model=settings.default_model,
                temperature=temperature,
                max_tokens=max_tokens,
                require_json=False,
                timeout_millis=timeout_millis,
            )
            content = str(result.get("content") or "").strip()
            if content:
                if not collected:
                    for chunk in self._synthetic_stream_chunks(content):
                        collected.append(chunk)
                        yield {"event": "delta", "delta": chunk, "phase": "answer"}
                meta["fallbackUsed"] = True
                return
        except Exception:
            pass

        if fallback_text:
            if not collected:
                for chunk in self._synthetic_stream_chunks(fallback_text):
                    collected.append(chunk)
                    yield {"event": "delta", "delta": chunk, "phase": "answer"}
            meta["fallbackUsed"] = True

    def _synthetic_stream_chunks(self, text: str, *, chunk_size: int = 24) -> list[str]:
        if len(text) <= chunk_size:
            return [text]
        chunks: list[str] = []
        start = 0
        while start < len(text):
            end = min(len(text), start + chunk_size)
            if end < len(text):
                split = max(text.rfind(" ", start, end), text.rfind("\n", start, end))
                if split > start + 8:
                    end = split + 1
            chunks.append(text[start:end])
            start = end
        return chunks

    async def _provider_invoke(self, **kwargs: Any) -> dict[str, Any]:
        async with self._llm_semaphore:
            return await self.provider_client.invoke(**kwargs)

    async def _provider_stream(self, stream_fn: Any, **kwargs: Any) -> AsyncGenerator[dict[str, Any], None]:
        async with self._llm_semaphore:
            async for event in stream_fn(**kwargs):
                yield event

    def _build_creative_messages(self, request: KnowledgeChatRequest) -> list[dict[str, str]]:
        return [
            {
                "role": "system",
                "content": (
                    "You are a web-novel writing and research assistant. "
                    "Only answer questions about web-novel writing, book analysis, and genre/ranking trends. "
                    "Do not answer weather, programming, medical, finance, legal, travel, food, or news questions. "
                    "Be concrete and useful from an author/editor perspective. "
                    "For outlines, chapter outlines, book-opening plans, and long-form writing plans, produce a complete draft with enough detail for direct writing. "
                    "Do not present creative suggestions as knowledge-base evidence. "
                    "When giving uncited advice, label it as author-side inference or creative advice."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"task output guidance:\n{self._creative_output_rule(request)}\n\n"
                    f"conversation context:\n{self._format_conversation_context(request)}\n\n"
                    f"current question:\n{request.question}"
                ),
            },
        ]

    def _creative_output_rule(self, request: KnowledgeChatRequest) -> str:
        if self._needs_long_creative_output(request):
            return (
                "Write a substantial Chinese long-form answer. If the task is an outline, include ## 核心定位, "
                "## 卖点钩子, ## 完整大纲, ## 分卷/分章推进, ## 角色线, ## 爽点节奏, ## 风险修正. "
                "Start with ## 本次输出结构 so the user sees the plan immediately. "
                "Then 分段 output with headings such as 第一段, 第二段, 第三段 or by volume/chapter sections. "
                "收到增量后就继续自然输出分段内容; do not wait to compress the whole outline into one late block. "
                "Do not stop at a brief suggestion."
            )
        return "Write concrete author-side advice with enough examples to be actionable."

    def _build_graph(self):
        graph = StateGraph(ResearchState)
        graph.add_node("assemble_context", self._assemble_context_node)
        graph.add_node("classify_intent", self._classify_intent_node)
        graph.add_node("plan_tasks", self._plan_tasks_node)
        graph.add_node("execute_tools", self._execute_tools_node)
        graph.add_node("supervise_evidence", self._supervise_evidence_node)
        graph.add_node("compose_answer", self._compose_answer_node)
        graph.add_node("extract_memory_candidates", self._extract_memory_candidates_node)
        graph.add_node("finalize_trace", self._finalize_trace_node)
        graph.set_entry_point("assemble_context")
        graph.add_edge("assemble_context", "classify_intent")
        graph.add_edge("classify_intent", "plan_tasks")
        graph.add_edge("plan_tasks", "execute_tools")
        graph.add_edge("execute_tools", "supervise_evidence")
        graph.add_conditional_edges("supervise_evidence", self._route_after_runtime_supervisor, {
            "execute_tools": "execute_tools",
            "compose_answer": "compose_answer",
            "finalize_trace": "finalize_trace",
        })
        graph.add_edge("compose_answer", "extract_memory_candidates")
        graph.add_edge("extract_memory_candidates", "finalize_trace")
        graph.add_edge("finalize_trace", END)
        return graph.compile(checkpointer=self._checkpointer)

    async def _assemble_context_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        if isinstance(state.get("context_bundle"), ContextBundle):
            return {}
        return {"context_bundle": await self.context_assembler.assemble_async(request)}

    async def _classify_intent_node(self, state: ResearchState) -> ResearchState:
        return await self._intent_router_node(state)

    async def _plan_tasks_node(self, state: ResearchState) -> ResearchState:
        return {}

    async def _execute_tools_node(self, state: ResearchState) -> ResearchState:
        if state.get("response") is not None or state.get("intent") == "creative_advice":
            return {}
        working: ResearchState = dict(state)
        working.update(await self._book_resolver_node(working))
        if working.get("response") is not None:
            return working
        working.update(await self._data_completer_node(working))
        working.update(await self._structured_rank_lookup_node(working))
        if working.get("response") is not None:
            return working
        working.update(await self._evidence_retriever_node(working))
        return working

    async def _supervise_evidence_node(self, state: ResearchState) -> ResearchState:
        if state.get("response") is not None:
            return {}
        evidence_pack = self.evidence_pack_builder.from_sources(
            list(state.get("sources") or []),
            inference_signals=self._inference_signals_for_trace(state),
        )
        decision = self._supervisor_decision_for_trace(state, evidence_pack)
        result: ResearchState = {
            "evidence_pack_summary": evidence_pack.summary(),
            "supervisor": decision,
        }
        if decision.get("status") == "needs_book_selection" and self._should_search_book_after_missing_evidence(state):
            return {
                **result,
                **(await self._build_book_candidates_response(state)),
            }
        if decision.get("status") == "needs_fresh_rank":
            retry_counts = dict(state.get("retry_counts") or {})
            market_refresh_count = int(retry_counts.get("market_refresh") or 0)
            if market_refresh_count < 1 and self._should_retry_market_refresh(state):
                retry_counts["market_refresh"] = market_refresh_count + 1
                tool_runs = self._drop_latest_rank_attempts_for_retry(state)
                return {
                    **result,
                    "sources": [],
                    "retry_counts": retry_counts,
                    "tool_runs": tool_runs + [{
                        "name": "supervisor_retry",
                        "status": "succeeded",
                        "resultCount": 0,
                        "reason": "needs_fresh_rank",
                    }],
                }
            if self._should_block_on_fresh_rank_gap(state):
                result["response"] = self._build_supervisor_block_response(state, decision)
        if decision.get("status") in {"needs_clarification", "needs_book_selection"}:
            result["response"] = self._build_supervisor_block_response(state, decision)
        return result

    def _should_retry_market_refresh(self, state: ResearchState) -> bool:
        source_policy = dict(state.get("source_policy") or {})
        return (
            bool(source_policy.get("trendGateFailed"))
            and source_policy.get("trendGateReason") == "missing_structured_rank_snapshot"
            and int(source_policy.get("structuredTopRankCount") or 0) > 0
        )

    def _should_block_on_fresh_rank_gap(self, state: ResearchState) -> bool:
        source_policy = dict(state.get("source_policy") or {})
        return bool(source_policy.get("trendGateFailed"))

    def _drop_latest_rank_attempts_for_retry(self, state: ResearchState) -> list[dict[str, Any]]:
        dropped_names = {"rank.lookup", "rank_lookup", "trend_rank_gate"}
        retained: list[dict[str, Any]] = []
        for run in list(state.get("tool_runs") or []):
            if not isinstance(run, dict):
                continue
            if str(run.get("name") or "") in dropped_names:
                continue
            retained.append(dict(run))
        return retained

    def _build_supervisor_block_response(
        self,
        state: ResearchState,
        decision: dict[str, Any],
    ) -> KnowledgeChatResponse:
        status = str(decision.get("status") or "needs_more_data")
        if status == "needs_fresh_rank":
            response_status = "insufficient_evidence"
            answer = "证据不足：当前缺少可校验的最新榜单快照，不能把历史或向量材料当作最近市场结论。"
        elif status == "needs_book_selection":
            response_status = "candidates_required"
            answer = "需要先确定要拆解的作品，再继续做单书拆解。"
        else:
            response_status = "needs_clarification"
            answer = "需要先补充当前项目或会话上下文，再继续修改建议。"
        request = state["request"]
        response = KnowledgeChatResponse(
            status=response_status,
            answer=answer,
            candidates=[],
            sources=[],
            actions=self._dedupe(list(state.get("actions", [])) + list(decision.get("requiredActions") or [])),
            resultJson={
                "status": response_status,
                "answerStatus": "needs_data",
                "answerBoundary": "needs_more_data",
                "intent": state.get("intent"),
                "domainIntent": state.get("domain_intent"),
                "intentDecision": state.get("intent_decision"),
                "sourcePolicy": dict(state.get("source_policy") or {}),
                "supervisorDecision": decision,
                "bookId": state.get("book_id") or request.bookId,
                "bookName": state.get("book_name") or request.bookName,
            },
        )
        self._attach_domain_intent_metadata(response, state)
        return response

    def _route_after_runtime_supervisor(self, state: ResearchState) -> str:
        if state.get("response") is not None:
            return "finalize_trace"
        supervisor = state.get("supervisor") or {}
        if (
            isinstance(supervisor, dict)
            and supervisor.get("status") == "needs_fresh_rank"
            and int((state.get("retry_counts") or {}).get("market_refresh") or 0) <= 1
            and self._should_retry_market_refresh(state)
        ):
            return "execute_tools"
        return "compose_answer"

    async def _compose_answer_node(self, state: ResearchState) -> ResearchState:
        if state.get("response") is not None:
            return {}
        if state.get("intent") == "creative_advice":
            return await self._creative_answer_node(state)
        return await self._answer_writer_node(state)

    async def _extract_memory_candidates_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        candidates = self.memory_candidate_extractor.extract(request)
        payload = [candidate.model_dump(mode="json", exclude_none=True) for candidate in candidates]
        response = state.get("response")
        if response is not None:
            response.resultJson["memoryCandidates"] = payload
        return {
            "memory_candidates": payload,
            "response": response,
        }

    async def _finalize_trace_node(self, state: ResearchState) -> ResearchState:
        response = state.get("response")
        if response is None:
            response = KnowledgeChatResponse(
                status="insufficient_evidence",
                answer="证据不足：当前运行没有生成可返回结果。",
                candidates=[],
                sources=[],
                actions=self._dedupe(list(state.get("actions", []))),
                resultJson={
                    "status": "insufficient_evidence",
                    "answerStatus": "needs_data",
                    "answerBoundary": "needs_more_data",
                    "intent": state.get("intent"),
                },
            )
        finalized_state: ResearchState = {**state, "response": response}
        finalized_state.update(await self._citation_verifier_node(finalized_state))
        finalized = finalized_state["response"]
        if "memoryCandidates" not in finalized.resultJson:
            memory_candidates = list(finalized_state.get("memory_candidates") or [])
            if not memory_candidates and finalized_state.get("request") is not None:
                memory_candidates = [
                    candidate.model_dump(mode="json", exclude_none=True)
                    for candidate in self.memory_candidate_extractor.extract(finalized_state["request"])
                ]
            finalized.resultJson["memoryCandidates"] = memory_candidates
        if isinstance(finalized.resultJson.get("trace"), dict):
            finalized.resultJson["trace"]["memoryCandidates"] = list(finalized.resultJson.get("memoryCandidates") or [])
            for node in finalized.resultJson["trace"].get("nodes") or []:
                if isinstance(node, dict) and node.get("name") == "extract_memory_candidates":
                    node["status"] = "completed"
                    node["candidateCount"] = len(finalized.resultJson.get("memoryCandidates") or [])
        if "trace" not in finalized.resultJson:
            self._attach_trace_metadata(finalized, {**finalized_state, "sources": finalized.sources})
        return {"response": finalized}

    def _graph_config(self, request: KnowledgeChatRequest) -> dict[str, Any]:
        return {"configurable": {"thread_id": self._graph_thread_id(request)}}

    def _graph_thread_id(self, request: KnowledgeChatRequest) -> str:
        return (
            request.traceId
            or request.conversationId
            or f"knowledge-chat:{id(request)}"
        )

    async def _intent_router_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        domain_decision = await self._classify_domain_intent(request)
        task_graph = self.task_graph_decomposer.decompose(
            request.question or "",
            intent_decision=domain_decision,
        )
        task_graph_payload = self._task_graph_payload(task_graph)
        task_tool_plan = self._task_tool_plan_payload(task_graph)
        if task_graph.adminOperationRequested:
            response = self._build_admin_skill_governance_response(request, domain_decision, task_graph)
            admin_state: ResearchState = {
                "request": request,
                "intent": "admin_skill_governance",
                "domain_intent": "skill_governance",
                "intent_decision": self._intent_decision_payload(domain_decision),
                "source_policy": dict(domain_decision.sourcePolicy or {}),
                "selected_skills": [],
                "skill_prompt": "",
                "task_graph": task_graph_payload,
                "task_tool_plan": task_tool_plan,
                "tool_plan": [],
                "tool_runs": [],
                "sources": [],
            }
            self._attach_domain_intent_metadata(response, admin_state)
            self._attach_memory_metadata(response, request)
            return {
                "in_scope": True,
                "intent": "admin_skill_governance",
                "domain_intent": "skill_governance",
                "intent_decision": self._intent_decision_payload(domain_decision),
                "source_policy": dict(domain_decision.sourcePolicy or {}),
                "selected_skills": [],
                "skill_prompt": "",
                "task_graph": task_graph_payload,
                "task_tool_plan": task_tool_plan,
                "tool_plan": [],
                "tool_runs": [],
                "sources": [],
                "response": response,
            }
        decision = await self._classify_question(request, domain_decision)
        if (
            decision.get("inScope") is False
            and self._task_graph_implies_webnovel_scope(task_graph)
            and self._task_graph_is_creative_only(task_graph)
            and self._is_project_creation_request(request)
        ):
            decision = {"inScope": True, "intent": "creative_advice", "bookName": None}
        skill_selection = self.skill_registry.select_for_intent(domain_decision)
        if self._should_request_clarification(domain_decision):
            clarification_response = self._build_clarification_response(request, domain_decision)
            clarification_state: ResearchState = {
                "request": request,
                "intent": "followup_context",
                "domain_intent": domain_decision.primaryIntent.value,
                "intent_decision": self._intent_decision_payload(domain_decision),
                "source_policy": dict(domain_decision.sourcePolicy or {}),
                "selected_skills": [],
                "skill_prompt": "",
                "task_graph": task_graph_payload,
                "task_tool_plan": task_tool_plan,
                "tool_plan": [],
                "tool_runs": [],
                "sources": [],
            }
            self._attach_domain_intent_metadata(clarification_response, clarification_state)
            self._attach_memory_metadata(clarification_response, request)
            return {
                "in_scope": True,
                "intent": "followup_context",
                "domain_intent": domain_decision.primaryIntent.value,
                "intent_decision": self._intent_decision_payload(domain_decision),
                "selected_skills": [],
                "skill_prompt": "",
                "task_graph": task_graph_payload,
                "task_tool_plan": task_tool_plan,
                "response": clarification_response,
            }
        if not bool(decision.get("inScope", True)):
            return {
                "in_scope": False,
                "intent": "out_of_scope",
                "domain_intent": Intent.out_of_scope.value,
                "intent_decision": self._intent_decision_payload(domain_decision),
                "source_policy": dict(domain_decision.sourcePolicy or {}),
                "selected_skills": list(skill_selection.skill_ids),
                "skill_prompt": skill_selection.prompt,
                "task_graph": task_graph_payload,
                "task_tool_plan": task_tool_plan,
                "book_name": None,
                "book_id": None,
                "platform": None,
                "response": KnowledgeChatResponse(
                    status="out_of_scope",
                    answer="我只能回答网文创作、网文作品分析、榜单趋势和相关知识库问题。这个问题超出网文研究范围。",
                    candidates=[],
                    sources=[],
                    actions=["ask_web_novel_question"],
                    resultJson={
                        "status": "out_of_scope",
                        "intent": "out_of_scope",
                        "domainIntent": Intent.out_of_scope.value,
                        "intentDecision": self._intent_decision_payload(domain_decision),
                    },
                ),
            }
        domain_legacy_intent = self._legacy_intent_for_domain_decision(domain_decision, request)
        if self._should_use_domain_legacy_intent(request, domain_decision, decision):
            legacy_intent = domain_legacy_intent
        else:
            legacy_intent = str(decision.get("intent") or domain_legacy_intent)
        if (
            not self._has_explicit_book_context(request)
            and self._task_graph_is_creative_only(task_graph)
            and self._is_project_creation_request(request)
        ):
            legacy_intent = "creative_advice"
            decision = {**decision, "bookName": None}
        return {
            "in_scope": True,
            "intent": legacy_intent,
            "domain_intent": domain_decision.primaryIntent.value,
            "intent_decision": self._intent_decision_payload(domain_decision),
            "source_policy": dict(domain_decision.sourcePolicy or {}),
            "selected_skills": list(skill_selection.skill_ids),
            "skill_prompt": skill_selection.prompt,
            "task_graph": task_graph_payload,
            "task_tool_plan": task_tool_plan,
            "tool_plan": self._build_tool_plan(domain_decision, request, legacy_intent),
            "tool_runs": [],
            "book_name": self._resolve_book_name(request, decision),
            "book_id": request.bookId,
            "platform": request.selectedCandidate.platform if request.selectedCandidate else None,
            "needs_structured_rank": bool(decision.get("needsStructuredRank", False)),
            "needs_vector_evidence": bool(decision.get("needsVectorEvidence", True)),
            "needs_creative_advice": bool(decision.get("needsCreativeAdvice", False)),
            "answer_boundary": decision.get("answerBoundary"),
        }

    async def _classify_domain_intent(self, request: KnowledgeChatRequest) -> IntentDecision:
        history = [
            str(message.get("content") or "")
            for message in (request.history or [])
            if str(message.get("content") or "").strip()
        ]
        rule_decision = self.intent_router.classify(
            request.question or "",
            context_summary=request.contextSummary,
            history=history,
        )
        if not self._should_use_domain_intent_llm_fallback(request, rule_decision):
            return rule_decision
        fallback_decision = await self._provider_domain_intent_fallback(request, rule_decision)
        return fallback_decision or rule_decision

    def _should_use_domain_intent_llm_fallback(
        self,
        request: KnowledgeChatRequest,
        decision: IntentDecision,
    ) -> bool:
        if not isinstance(self.intent_router, IntentRouter):
            return False
        if request.bookId is not None or request.selectedCandidate is not None:
            return False
        if request.bookName and request.bookName.strip():
            return False
        if self._resolve_book_name_by_rules(request):
            return False
        notes = set(decision.routingNotes or [])
        if decision.primaryIntent is Intent.out_of_scope:
            return False
        question = (request.question or "").strip().lower()
        has_market_or_creation_cue = any(
            marker in question
            for marker in (
                "recent",
                "trend",
                "topics",
                "write next",
                "opening",
                "market",
                "榜",
                "趋势",
                "题材",
                "开书",
                "开篇",
                "大纲",
                "人设",
            )
        )
        if not has_market_or_creation_cue:
            return False
        return (
            float(decision.confidence or 0.0) < 0.75
            or "rule:ambiguous-intent" in notes
        )

    async def _provider_domain_intent_fallback(
        self,
        request: KnowledgeChatRequest,
        rule_decision: IntentDecision,
    ) -> IntentDecision | None:
        try:
            result = await self._provider_invoke(
                messages=self._build_domain_intent_messages(request, rule_decision),
                model=settings.default_model,
                temperature=0,
                max_tokens=700,
                require_json=True,
                timeout_millis=self._request_timeout_millis(request),
            )
            payload = json.loads(str(result.get("content") or "{}"))
            decision = self.intent_router.coerce_fallback(payload)
            notes = list(decision.routingNotes or [])
            if not any(note.startswith("llm:") for note in notes):
                notes.append("llm:v3-fallback")
                decision = decision.model_copy(update={"routingNotes": notes})
            return decision
        except Exception:
            return None

    def _route_after_intent_router(self, state: ResearchState) -> str:
        if state.get("response") is not None:
            return "terminal"
        if state.get("intent") == "creative_advice":
            return "creative"
        return "book_or_retrieval"

    def _route_after_specialist_agents(self, state: ResearchState) -> str:
        if state.get("intent") == "creative_advice":
            return "creative"
        return "answer"

    def _legacy_intent_for_domain_decision(self, decision: IntentDecision, request: KnowledgeChatRequest) -> str:
        primary = decision.primaryIntent
        if primary is Intent.out_of_scope:
            return "out_of_scope"
        if primary in {Intent.market_scan, Intent.mixed_creation_research}:
            return "trend_research"
        if (
            request.bookId is not None
            or request.selectedCandidate is not None
            or (
                request.bookName
                and not self._should_ignore_request_book_name_for_market_question(request)
            )
        ):
            return "single_book_research"
        if primary is Intent.book_breakdown:
            return "single_book_research"
        if primary in {
            Intent.opening_strategy,
            Intent.outline_building,
            Intent.chapter_outline,
            Intent.inspiration_expand,
            Intent.character_design,
            Intent.worldbuilding,
            Intent.revision_advice,
            Intent.followup_context,
        }:
            if decision.toolNeeds.needsRankData:
                return "trend_research"
            if decision.toolNeeds.needsBookResearch:
                return "single_book_research"
            return "creative_advice"
        return self._route_intent(request)

    def _should_use_domain_legacy_intent(
        self,
        request: KnowledgeChatRequest,
        domain_decision: IntentDecision,
        legacy_decision: dict[str, Any],
    ) -> bool:
        if domain_decision.primaryIntent is Intent.out_of_scope:
            return False
        if not self._is_authoritative_domain_decision(domain_decision):
            return False
        if legacy_decision.get("bookName"):
            return False
        if request.bookId is not None or request.selectedCandidate is not None or (request.bookName and request.bookName.strip()):
            if (
                request.bookId is None
                and request.selectedCandidate is None
                and self._should_ignore_request_book_name_for_market_question(request, legacy_decision)
            ):
                return domain_decision.primaryIntent in {Intent.market_scan, Intent.mixed_creation_research}
            return domain_decision.primaryIntent in {Intent.book_breakdown, Intent.followup_context}
        return domain_decision.primaryIntent in {
            Intent.market_scan,
            Intent.mixed_creation_research,
            Intent.opening_strategy,
            Intent.outline_building,
            Intent.chapter_outline,
            Intent.inspiration_expand,
            Intent.character_design,
            Intent.worldbuilding,
            Intent.revision_advice,
            Intent.book_breakdown,
            Intent.followup_context,
        }

    def _intent_decision_payload(self, decision: IntentDecision | None) -> dict[str, Any] | None:
        if decision is None:
            return None
        return decision.model_dump(mode="json")

    def _should_request_clarification(self, decision: IntentDecision) -> bool:
        return "rule:ambiguous-intent" in set(decision.routingNotes or [])

    def _build_clarification_response(
        self,
        request: KnowledgeChatRequest,
        decision: IntentDecision,
    ) -> KnowledgeChatResponse:
        needs_rank = bool(decision.toolNeeds.needsRankData)
        needs_creative = bool(decision.toolNeeds.needsCreativeGeneration)
        if needs_rank and needs_creative:
            answer = "这个问题同时像榜单趋势和开文建议，我先不猜。请先选：1. 只看最近榜单趋势 2. 直接要开文/大纲建议。"
            options = ["先看榜单趋势", "直接要开文建议"]
        elif needs_rank:
            answer = "这个问题更像榜单趋势查询，但信息还不够明确。请补充你要看的平台、频道或题材。"
            options = ["补充平台频道", "补充题材范围"]
        elif needs_creative:
            answer = "这个问题更像开文/大纲需求，但信息还不够明确。请补充你要写的题材、目标人设或开局方向。"
            options = ["补充题材定位", "补充开局方向"]
        else:
            answer = "这个问题目前还不够明确，请补充你更想看榜单趋势、单书分析，还是开文建议。"
            options = ["看榜单趋势", "要开文建议"]
        response = KnowledgeChatResponse(
            status="needs_clarification",
            answer=answer,
            candidates=[],
            sources=[],
            actions=["clarify_intent"],
            resultJson={
                "status": "needs_clarification",
                "intent": "followup_context",
                "domainIntent": decision.primaryIntent.value,
                "answerStatus": "needs_data",
                "answerBoundary": "needs_more_data",
                "clarificationOptions": options,
                "intentDecision": self._intent_decision_payload(decision),
            },
        )
        self._attach_memory_metadata(response, request)
        return response

    def _build_admin_skill_governance_response(
        self,
        request: KnowledgeChatRequest,
        decision: IntentDecision,
        task_graph: TaskGraph,
    ) -> KnowledgeChatResponse:
        return KnowledgeChatResponse(
            status="admin_required",
            answer=(
                "Skill 管理属于管理员治理能力，普通问答不会执行新增、安装、发布、禁用或回滚。"
                "我已把这次请求标记为 admin-only，可由管理员在技能治理面板处理。"
            ),
            candidates=[],
            sources=[],
            actions=["admin_skill_governance_required"],
            resultJson={
                "status": "admin_required",
                "intent": "admin_skill_governance",
                "domainIntent": "skill_governance",
                "answerStatus": "admin_required",
                "answerBoundary": task_graph.answerBoundary,
                "adminOperationRequested": True,
                "intentDecision": self._intent_decision_payload(decision),
            },
        )

    def _task_graph_payload(self, task_graph: TaskGraph) -> dict[str, Any]:
        return task_graph.model_dump(mode="json")

    def _task_tool_plan_payload(self, task_graph: TaskGraph) -> list[dict[str, Any]]:
        return [plan.model_dump(mode="json") for plan in self.domain_tool_planner.plan(task_graph)]

    def _task_graph_implies_webnovel_scope(self, task_graph: TaskGraph) -> bool:
        scoped_types = {
            TaskType.market_scan,
            TaskType.book_breakdown,
            TaskType.topic_strategy,
            TaskType.outline_building,
            TaskType.chapter_outline,
            TaskType.character_design,
            TaskType.worldbuilding,
            TaskType.revision_advice,
            TaskType.reader_risk,
            TaskType.editor_risk,
        }
        return any(TaskType(task.get("type")) in scoped_types for task in task_graph.model_dump(mode="json").get("tasks", []))

    def _task_graph_is_creative_only(self, task_graph: TaskGraph) -> bool:
        task_types = {
            TaskType(task.get("type"))
            for task in task_graph.model_dump(mode="json").get("tasks", [])
            if task.get("type") in TaskType._value2member_map_
        }
        if not task_types:
            return False
        if task_types & {TaskType.market_scan, TaskType.book_breakdown}:
            return False
        return bool(task_types & {
            TaskType.topic_strategy,
            TaskType.outline_building,
            TaskType.chapter_outline,
            TaskType.character_design,
            TaskType.worldbuilding,
            TaskType.revision_advice,
            TaskType.reader_risk,
            TaskType.editor_risk,
        })

    def _has_explicit_book_context(
        self,
        request: KnowledgeChatRequest,
        decision: dict[str, Any] | None = None,
    ) -> bool:
        return bool(
            request.bookId is not None
            or request.selectedCandidate is not None
            or (request.bookName and request.bookName.strip())
        )

    def _is_project_creation_request(self, request: KnowledgeChatRequest) -> bool:
        question = request.question or ""
        return any(marker in question for marker in (
            "我想写",
            "我要写",
            "准备写",
            "打算写",
            "帮我写",
            "帮我设计",
            "设计主角",
            "设计角色",
            "开一本",
            "开书",
            "开文",
            "新题材",
            "新书",
        ))

    def _attach_domain_intent_metadata(self, response: KnowledgeChatResponse, state: ResearchState) -> None:
        domain_intent = state.get("domain_intent")
        intent_decision = state.get("intent_decision")
        if domain_intent:
            response.resultJson["domainIntent"] = domain_intent
        if intent_decision:
            response.resultJson["intentDecision"] = intent_decision
            if isinstance(intent_decision, dict) and intent_decision.get("answerBoundary"):
                response.resultJson["domainAnswerBoundary"] = intent_decision.get("answerBoundary")
        if state.get("selected_skills") is not None:
            response.resultJson["selectedSkills"] = list(state.get("selected_skills") or [])
        if state.get("specialist_results") is not None:
            specialist_results = list(state.get("specialist_results") or [])
            response.resultJson["specialistAgents"] = [
                str(result.get("agentName"))
                for result in specialist_results
                if isinstance(result, dict) and result.get("agentName")
            ]
            response.resultJson["specialistDiagnostics"] = specialist_results
        if state.get("tool_plan") is not None:
            response.resultJson["toolPlan"] = list(state.get("tool_plan") or [])
        if state.get("tool_runs") is not None:
            tool_runs = list(state.get("tool_runs") or [])
            response.resultJson["toolRuns"] = tool_runs + self._legacy_tool_runs_for_trace(tool_runs)
        if state.get("task_graph") is not None:
            task_graph = dict(state.get("task_graph") or {})
            response.resultJson["taskGraph"] = task_graph
            response.resultJson["adminOperationRequested"] = bool(task_graph.get("adminOperationRequested"))
        if state.get("task_tool_plan") is not None:
            response.resultJson["taskToolPlan"] = list(state.get("task_tool_plan") or [])
        if state.get("source_policy") is not None:
            response.resultJson["sourcePolicy"] = dict(state.get("source_policy") or {})
        if state.get("retry_counts") is not None:
            response.resultJson["retryCounts"] = dict(state.get("retry_counts") or {})
        response.resultJson["businessRoute"] = self._business_route_for_state(state, response)
        response.resultJson["routeDiagnostics"] = self._route_diagnostics_for_trace(state, response)
        if "sources" not in state:
            state["sources"] = list(response.sources or [])
        self._attach_evidence_and_perspective_metadata(response, state)
        response.resultJson["budgets"] = {
            "maxParallelToolCalls": settings.agent_max_parallel_tool_calls,
            "maxSkillChars": settings.agent_max_skill_chars,
            "maxMaterialChars": settings.agent_max_material_chars,
            "marketTopNDefault": settings.agent_market_topn_default,
            "chaptersPerRankBook": settings.agent_chapters_per_rank_book,
        }
        response.resultJson["materialChars"] = self._material_chars(state.get("sources", []))
        self._attach_trace_metadata(response, state)

    def _attach_evidence_and_perspective_metadata(
        self,
        response: KnowledgeChatResponse,
        state: ResearchState,
    ) -> None:
        sources = list(state.get("sources", response.sources or []))
        inference_signals = self._inference_signals_for_trace(state)
        evidence_pack = self.evidence_pack_builder.from_sources(
            sources,
            inference_signals=inference_signals,
        )
        response.resultJson["evidencePackSummary"] = evidence_pack.summary()
        response.resultJson["supervisorDecision"] = self._supervisor_decision_for_trace(
            state,
            evidence_pack,
        )
        response.resultJson["perspectiveResults"] = self._perspective_results_for_trace(
            dict(state.get("task_graph") or {}),
            evidence_pack.summary(max_items=2),
        )

    def _supervisor_decision_for_trace(
        self,
        state: ResearchState,
        evidence_pack: EvidencePack,
    ) -> dict[str, Any]:
        request = state.get("request")
        raw_policy = state.get("source_policy") or {}
        try:
            source_policy = SourcePolicy.model_validate(raw_policy or {})
        except Exception:
            source_policy = SourcePolicy(freshness="any", requireSnapshotTime=False)
        route = self._supervisor_route_for_state(state)
        decision = self.agent_supervisor.evaluate(
            route=route,
            source_policy=source_policy,
            evidence=evidence_pack,
            has_book_context=bool(
                state.get("book_id")
                or (request is not None and (request.bookId is not None or request.selectedCandidate is not None or bool(request.bookName)))
            ),
            has_thread_or_project_context=bool(
                request is None
                or request.projectId is not None
                or request.contextSummary
                or request.history
            ),
        )
        return decision.model_dump(mode="json", exclude_none=True)

    def _supervisor_route_for_state(self, state: ResearchState) -> str:
        domain_intent = str(state.get("domain_intent") or "")
        legacy_intent = str(state.get("intent") or "")
        if domain_intent == "mixed_creation_research":
            return "mixed_creation_research"
        if domain_intent == "market_scan" or legacy_intent == "trend_research":
            return "market_scan"
        if domain_intent == "book_breakdown" or legacy_intent == "single_book_research":
            return "book_breakdown"
        if domain_intent == "followup_context":
            return "followup_revision"
        if legacy_intent == "creative_advice":
            return "project_creation"
        return domain_intent or legacy_intent or "project_creation"

    def _business_route_for_state(
        self,
        state: ResearchState,
        response: KnowledgeChatResponse | None = None,
    ) -> str:
        status = response.status if response is not None else ""
        if status == "needs_clarification":
            return "needs_clarification"
        if status == "out_of_scope":
            return "out_of_scope"
        domain_intent = str(state.get("domain_intent") or "")
        legacy_intent = str(state.get("intent") or "")
        answer_mode = str((response.resultJson.get("answerMode") if response is not None else "") or "")
        if domain_intent == "skill_governance" or legacy_intent == "admin_skill_governance":
            return "admin_governance"
        if domain_intent == "mixed_creation_research":
            return "mixed_creation_research"
        if domain_intent == "market_scan" or legacy_intent == "trend_research":
            return "market_scan"
        if domain_intent == "book_breakdown" or legacy_intent == "single_book_research" or answer_mode == "single_book":
            return "book_breakdown"
        if domain_intent == "followup_context":
            return "followup_revision"
        if domain_intent in {
            "opening_strategy",
            "outline_building",
            "chapter_outline",
            "inspiration_expand",
            "character_design",
            "worldbuilding",
            "revision_advice",
        } or legacy_intent == "creative_advice":
            return "project_creation"
        if domain_intent == "out_of_scope" or legacy_intent == "out_of_scope":
            return "out_of_scope"
        return domain_intent or legacy_intent or "project_creation"

    def _route_diagnostics_for_trace(
        self,
        state: ResearchState,
        response: KnowledgeChatResponse | None = None,
    ) -> dict[str, Any]:
        return {
            "domainIntent": state.get("domain_intent"),
            "legacyIntent": state.get("intent"),
            "responseStatus": response.status if response is not None else None,
            "businessRoute": self._business_route_for_state(state, response),
        }

    def _inference_signals_for_trace(self, state: ResearchState) -> list[dict[str, Any]]:
        signals: list[dict[str, Any]] = []
        for result in list(state.get("specialist_results") or []):
            if not isinstance(result, dict):
                continue
            signals.append({
                "perspective": result.get("answerMode") or result.get("agentName") or "author",
                "summary": result.get("agentName") or "specialist_agent",
                "diagnostics": result.get("diagnostics") or {},
            })
        return signals

    def _perspective_results_for_trace(
        self,
        task_graph: dict[str, Any],
        evidence_summary: dict[str, Any],
    ) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        for task in list(task_graph.get("tasks") or []):
            if not isinstance(task, dict):
                continue
            perspective = str(task.get("perspective") or "author")
            task_type = str(task.get("type") or "followup_context")
            summary = self._perspective_summary(task_type, perspective, evidence_summary)
            results.append({
                "taskType": task_type,
                "perspective": perspective,
                "summary": summary,
                "evidenceRefs": self._evidence_refs_for_perspective(perspective, evidence_summary),
            })
        return results

    def _perspective_summary(
        self,
        task_type: str,
        perspective: str,
        evidence_summary: dict[str, Any],
    ) -> str:
        if perspective == "market":
            return f"Use {evidence_summary.get('factCount', 0)} rank facts before market conclusions."
        if perspective == "book":
            return f"Use {evidence_summary.get('exampleCount', 0)} book/chapter examples for craft extraction."
        if perspective == "reader":
            return "Check clarity, reward timing, poison points, and drop-off risk."
        if perspective == "editor":
            return "Check market fit, opening hook, differentiation, and submission risk."
        if task_type == "skill_governance":
            return "Admin-only governance request; no runtime skill mutation executed."
        return "Author-side inference can proceed, clearly separated from evidence-backed facts."

    def _evidence_refs_for_perspective(
        self,
        perspective: str,
        evidence_summary: dict[str, Any],
    ) -> list[str]:
        key = "facts" if perspective == "market" else "examples"
        refs = []
        for item in evidence_summary.get(key, []):
            if isinstance(item, dict) and item.get("ref"):
                refs.append(str(item["ref"]))
        return refs

    def _attach_trace_metadata(self, response: KnowledgeChatResponse, state: ResearchState) -> None:
        result = response.resultJson
        sources = state.get("sources", response.sources or [])
        request = state.get("request")
        result.setdefault("businessRoute", self._business_route_for_state(state, response))
        result.setdefault("routeDiagnostics", self._route_diagnostics_for_trace(state, response))
        if request is not None and "contextUsed" not in result:
            result["contextUsed"] = self._context_used_for_trace(request, state)
        result["trace"] = {
            "traceId": request.traceId if request is not None else None,
            "checkpointThreadId": self._graph_thread_id(request) if request is not None else None,
            "checkpointStore": checkpoint_store_name(self._checkpointer),
            "nodes": self._runtime_nodes_for_trace(response, result, state),
            "intent": result.get("intent"),
            "domainIntent": result.get("domainIntent"),
            "businessRoute": result.get("businessRoute"),
            "routeDiagnostics": dict(result.get("routeDiagnostics") or {}),
            "answerMode": result.get("answerMode"),
            "answerStatus": result.get("answerStatus"),
            "answerBoundary": result.get("answerBoundary"),
            "sourcePolicy": dict(result.get("sourcePolicy") or {}),
            "supervisorDecision": dict(result.get("supervisorDecision") or {}),
            "retryCounts": dict(result.get("retryCounts") or {}),
            "contextUsed": dict(result.get("contextUsed") or {}),
            "memoryCandidates": list(result.get("memoryCandidates") or []),
            "promptPolicy": self._prompt_policy_for_trace(result),
            "toolPlan": list(result.get("toolPlan") or []),
            "toolRuns": list(result.get("toolRuns") or []),
            "selectedSkills": list(result.get("selectedSkills") or []),
            "specialistAgents": list(result.get("specialistAgents") or []),
            "sourceCount": len(sources),
            "sourceTypes": sorted({str(source.sourceType or "unknown").upper() for source in sources}),
            "sourcePriority": self._source_priority_for_trace(result),
            "contextChars": self._conversation_context_chars(state),
            "evidenceChars": self._evidence_chars(sources),
            "materialChars": result.get("materialChars", self._material_chars(sources)),
            "fallbackUsed": bool(result.get("fallbackUsed", False)),
            "citationRepairUsed": bool(result.get("citationRepairUsed", False)),
            "actions": list(response.actions or []),
            "diagnostics": dict(result.get("diagnostics") or {}),
        }

    def _runtime_nodes_for_trace(
        self,
        response: KnowledgeChatResponse,
        result: dict[str, Any],
        state: ResearchState,
    ) -> list[dict[str, Any]]:
        tool_runs = list(result.get("toolRuns") or [])
        return [
            {
                "name": "assemble_context",
                "status": "completed" if result.get("contextUsed") else "skipped",
                "legacyNode": "intent_router",
            },
            {
                "name": "classify_intent",
                "status": "completed" if result.get("intentDecision") else "skipped",
                "legacyNode": "intent_router",
            },
            {
                "name": "plan_tasks",
                "status": "completed" if result.get("taskGraph") else "skipped",
                "legacyNode": "intent_router",
            },
            {
                "name": "execute_tools",
                "status": "completed" if tool_runs else "skipped",
                "legacyNode": "structured_rank_lookup/evidence_retriever",
                "toolRunCount": len(tool_runs),
            },
            {
                "name": "supervise_evidence",
                "status": "completed" if result.get("supervisorDecision") else "skipped",
                "legacyNode": "citation_verifier",
            },
            {
                "name": "compose_answer",
                "status": "completed" if response.answer else "skipped",
                "legacyNode": self._compose_legacy_node_for_state(state),
            },
            {
                "name": "extract_memory_candidates",
                "status": "completed",
                "legacyNode": None,
                "candidateCount": len(result.get("memoryCandidates") or []),
            },
            {
                "name": "finalize_trace",
                "status": "completed",
                "legacyNode": "citation_verifier",
            },
        ]

    def _compose_legacy_node_for_state(self, state: ResearchState) -> str:
        if state.get("intent") == "creative_advice":
            return "creative_answer"
        return "answer_writer"

    def _context_used_for_trace(
        self,
        request: KnowledgeChatRequest,
        state: ResearchState | None = None,
    ) -> dict[str, Any]:
        bundle = state.get("context_bundle") if state is not None else None
        if not isinstance(bundle, ContextBundle):
            bundle = self.context_assembler.assemble(request)
        payload = bundle.model_dump(mode="json", exclude_none=True)
        project_payload = payload.get("projectProfile") if isinstance(payload.get("projectProfile"), dict) else {}
        project_content = project_payload.get("content") if isinstance(project_payload.get("content"), dict) else {}
        project_memories = project_content.get("memories") if isinstance(project_content.get("memories"), dict) else {}
        return {
            "layers": [key for key in ("systemBaseline", "userProfile", "projectProfile", "threadSummary", "currentTurn") if key in payload],
            "projectId": request.projectId,
            "conversationId": request.conversationId,
            "hasUserProfile": "userProfile" in payload,
            "hasProjectProfile": "projectProfile" in payload,
            "hasThreadSummary": "threadSummary" in payload,
            "projectMemoryKeys": sorted(str(key) for key in project_memories.keys()),
            "projectMemorySourceIds": list(project_payload.get("sourceIds") or []),
        }

    def _prompt_policy_for_trace(self, result: dict[str, Any]) -> str:
        answer_mode = str(result.get("answerMode") or "")
        if answer_mode == "creative":
            return "long_context_creative_continuation"
        if answer_mode == "mixed_creation":
            return "rank_first_market_then_author_inference"
        if answer_mode in {"trend", "single_book", "general_evidence", "rank_fact"}:
            return "evidence_first_fact_grounding"
        return "current_question_first"

    def _source_priority_for_trace(self, result: dict[str, Any]) -> list[str]:
        answer_mode = str(result.get("answerMode") or "")
        if answer_mode in {"trend", "mixed_creation", "rank_fact"} or str(result.get("intent") or "") == "trend_research":
            return ["RANK", "CHAPTER", "CHAPTER_PACK", "INTRO", "ANALYSIS"]
        if answer_mode == "single_book":
            return ["CHAPTER", "CHAPTER_PACK", "ANALYSIS", "INTRO", "RANK"]
        return ["CHAPTER", "CHAPTER_PACK", "INTRO", "ANALYSIS", "RANK"]

    def _conversation_context_chars(self, state: ResearchState) -> int:
        request = state.get("request")
        if request is None:
            return 0
        return len(self._format_conversation_context(request))

    def _evidence_chars(self, sources: list[KnowledgeSource]) -> int:
        total = 0
        for source in sources:
            total += len(source.material or "")
            total += len(source.preview or "")
            total += len(source.title or "")
            total += len(source.bookName or "")
        return total

    def _append_tool_run(
        self,
        state: ResearchState,
        name: str,
        status: str,
        *,
        result_count: int = 0,
        reason: str | None = None,
    ) -> None:
        runs = list(state.get("tool_runs") or [])
        payload: dict[str, Any] = {
            "name": name,
            "status": status,
            "resultCount": max(0, int(result_count)),
        }
        if reason:
            payload["reason"] = reason
        runs.append(payload)
        state["tool_runs"] = runs

    def _project_context_tool(self, payload: dict[str, Any]) -> dict[str, Any]:
        summary = str(payload.get("contextSummary") or "").strip()
        history = [
            {
                "role": str(message.get("role") or "user"),
                "content": self._short_text(str(message.get("content") or ""), 500),
            }
            for message in list(payload.get("history") or [])[-6:]
            if isinstance(message, dict) and str(message.get("content") or "").strip()
        ]
        return {
            "projectId": payload.get("projectId"),
            "conversationId": payload.get("conversationId"),
            "bookId": payload.get("bookId"),
            "bookName": payload.get("bookName"),
            "contextSummary": self._short_text(summary, 4000) if summary else "",
            "history": history,
        }

    async def _execute_task_graph_tools(self, request: KnowledgeChatRequest, state: ResearchState) -> list[KnowledgeSource]:
        graph_payload = state.get("task_graph")
        plan_payload = state.get("task_tool_plan")
        if not isinstance(graph_payload, dict) or not isinstance(plan_payload, list):
            return []
        try:
            graph = TaskGraph.model_validate(graph_payload)
            plans = [
                ToolPlan.model_validate(item)
                for item in plan_payload
                if isinstance(item, dict)
            ]
            plans = self._filter_task_graph_tool_plans(request, state, plans)
        except Exception:
            self._append_tool_run(state, "task_graph_tools", "failed", reason="invalid_task_graph")
            return []
        runs = await self._task_tool_executor.execute(
            graph,
            plans,
            context=self._task_tool_context(request, state),
        )
        self._merge_task_tool_runs(state, runs)
        return self._sources_from_tool_runs(runs)

    def _task_tool_context(self, request: KnowledgeChatRequest, state: ResearchState) -> dict[str, Any]:
        context: dict[str, Any] = {
            "question": request.question,
            "query": self._build_retrieval_query(request, state),
            "projectId": request.projectId,
            "conversationId": request.conversationId,
            "userId": request.userId,
            "bookId": state.get("book_id") or request.bookId,
            "bookName": state.get("book_name") or request.bookName,
            "platform": state.get("platform") or (request.selectedCandidate.platform if request.selectedCandidate else None) or "fanqie",
            "contextSummary": request.contextSummary,
            "history": list(request.history or []),
            "limit": self._limit(request, "rankLimit", default=settings.agent_market_topn_default, maximum=20),
            "evidenceLimit": self._limit(request, "evidenceLimit", default=5, maximum=20),
            "chapterLimit": self._limit(request, "chapterLimit", default=3, maximum=20),
            "analysisLimit": self._limit(request, "analysisLimit", default=3, maximum=20),
            "toolTimeoutMillis": self._tool_timeout_millis(request),
            "chapterLimitPerBook": self._limit(
                request,
                "chapterLimitPerBook",
                default=settings.agent_chapters_per_rank_book,
                maximum=5,
            ),
        }
        lookup = self._parse_trend_rank_lookup_for_request(request) or {}
        context.update({key: value for key, value in lookup.items() if value is not None})
        return {key: value for key, value in context.items() if value is not None}

    def _merge_task_tool_runs(self, state: ResearchState, runs: list[ToolRun]) -> None:
        existing = list(state.get("tool_runs") or [])
        existing.extend(run.model_dump(mode="json", exclude_none=True) for run in runs)
        state["tool_runs"] = existing

    def _filter_task_graph_tool_plans(
        self,
        request: KnowledgeChatRequest,
        state: ResearchState,
        plans: list[ToolPlan],
    ) -> list[ToolPlan]:
        if self._should_use_rank_research_pack(request, state):
            return plans
        filtered: list[ToolPlan] = []
        for plan in plans:
            tools = [tool for tool in plan.tools if tool != "rank.research_pack"]
            if tools == plan.tools:
                filtered.append(plan)
            elif tools:
                filtered.append(plan.model_copy(update={"tools": tools}))
        return filtered

    def _legacy_tool_runs_for_trace(self, tool_runs: list[dict[str, Any]]) -> list[dict[str, Any]]:
        legacy: list[dict[str, Any]] = []
        for run in tool_runs:
            if not isinstance(run, dict):
                continue
            legacy_run = dict(run)
            name = str(legacy_run.get("name") or "")
            if name == "rank.lookup":
                legacy_run["name"] = name.replace(".", "_")
                legacy.append(legacy_run)
        return legacy

    def _existing_generic_vector_sources(self, state: ResearchState) -> list[KnowledgeSource] | None:
        sources: list[KnowledgeSource] = []
        found_run = False
        for run in state.get("tool_runs") or []:
            if not isinstance(run, dict):
                continue
            if run.get("name") != "knowledge.vector_search" or run.get("status") != "succeeded":
                continue
            input_payload = run.get("input")
            if isinstance(input_payload, dict) and input_payload.get("sourceType"):
                continue
            found_run = True
            output = run.get("output")
            if isinstance(output, dict):
                sources.extend(self._knowledge_search_output_to_sources(output))
        return sources if found_run else None

    def _existing_rank_pack_sources(self, state: ResearchState) -> list[KnowledgeSource] | None:
        sources: list[KnowledgeSource] = []
        found_run = False
        for run in state.get("tool_runs") or []:
            if not isinstance(run, dict):
                continue
            if run.get("name") != "rank.research_pack" or run.get("status") != "succeeded":
                continue
            found_run = True
            output = run.get("output")
            if isinstance(output, dict):
                sources.extend(self._rank_pack_output_to_sources(output))
        return sources if found_run else None

    def _existing_book_pack_sources(self, state: ResearchState) -> list[KnowledgeSource] | None:
        sources: list[KnowledgeSource] = []
        found_run = False
        for run in state.get("tool_runs") or []:
            if not isinstance(run, dict):
                continue
            if run.get("name") != "book.research_pack" or run.get("status") != "succeeded":
                continue
            found_run = True
            output = run.get("output")
            if isinstance(output, dict):
                sources.extend(self._book_pack_output_to_sources(output))
        return sources if found_run else None

    def _sources_from_tool_runs(self, runs: list[ToolRun]) -> list[KnowledgeSource]:
        sources: list[KnowledgeSource] = []
        for run in runs:
            if run.status != "succeeded":
                continue
            if run.name == "rank.lookup":
                sources.extend(self._rank_lookup_output_to_sources(run.output))
            elif run.name == "rank.research_pack":
                sources.extend(self._rank_pack_output_to_sources(run.output))
            elif run.name == "book.research_pack":
                sources.extend(self._book_pack_output_to_sources(run.output))
            elif run.name == "knowledge.vector_search":
                sources.extend(self._knowledge_search_output_to_sources(run.output))
        return sources

    def _rank_lookup_output_to_sources(self, output: dict[str, Any]) -> list[KnowledgeSource]:
        return [
            self._rank_result_to_source(RankLookupResult.model_validate(item))
            for item in self._items_from_output(output)
            if isinstance(item, dict)
        ]

    def _rank_pack_output_to_sources(self, output: dict[str, Any]) -> list[KnowledgeSource]:
        try:
            return self._rank_pack_to_sources(RankResearchPack.model_validate(output))
        except Exception:
            return []

    def _book_pack_output_to_sources(self, output: dict[str, Any]) -> list[KnowledgeSource]:
        try:
            return self._book_pack_to_sources(BookResearchPack.model_validate(output))
        except Exception:
            return []

    def _knowledge_search_output_to_sources(self, output: dict[str, Any]) -> list[KnowledgeSource]:
        sources: list[KnowledgeSource] = []
        for item in self._items_from_output(output):
            if not isinstance(item, dict):
                continue
            try:
                sources.append(KnowledgeSource.model_validate(item))
            except Exception:
                continue
        return sources

    def _items_from_output(self, output: dict[str, Any]) -> list[Any]:
        items = output.get("items")
        return list(items) if isinstance(items, list) else []

    def _build_tool_plan(
        self,
        decision: IntentDecision,
        request: KnowledgeChatRequest,
        legacy_intent: str,
    ) -> list[dict[str, Any]]:
        plan: list[dict[str, Any]] = []
        if decision.toolNeeds.needsRankData or legacy_intent == "trend_research":
            plan.append({
                "name": "rank_research_pack",
                "required": True,
                "maxItems": self._limit(request, "rankLimit", default=settings.agent_market_topn_default, maximum=20),
                "fallback": "continue_with_vector_evidence",
            })
            plan.append({
                "name": "rank_lookup",
                "required": False,
                "maxItems": self._limit(request, "rankLimit", default=settings.agent_market_topn_default, maximum=20),
                "fallback": "skip_structured_rank_sources",
            })
            plan.append({
                "name": "vector_rank_search",
                "required": False,
                "maxItems": self._limit(request, "evidenceLimit", default=5, maximum=20),
                "fallback": "answer_from_structured_market_evidence",
            })
        if decision.toolNeeds.needsBookResearch or legacy_intent == "single_book_research":
            plan.append({
                "name": "book_research_pack",
                "required": bool(request.bookId or request.bookName or request.selectedCandidate),
                "maxItems": self._limit(request, "chapterLimit", default=3, maximum=20),
                "fallback": "candidate_selection_or_needs_data",
            })
        if decision.toolNeeds.needsVectorEvidence and not any(step["name"] == "generic_vector_search" for step in plan):
            plan.append({
                "name": "generic_vector_search",
                "required": False,
                "maxItems": self._limit(request, "evidenceLimit", default=5, maximum=20),
                "fallback": "partial_answer",
            })
        if decision.toolNeeds.needsCreativeGeneration:
            plan.append({
                "name": "creative_generation",
                "required": True,
                "maxItems": 1,
                "fallback": "template_based_author_advice",
            })
        return plan

    def _material_chars(self, sources: list[KnowledgeSource]) -> int:
        total = 0
        for source in sources:
            total += len(source.material or "")
            total += len(source.preview or "")
        return min(total, settings.agent_max_material_chars)

    async def _prepare_specialist_results(
        self,
        state: ResearchState,
        sources: list[KnowledgeSource],
    ) -> list[dict[str, Any]]:
        decision_payload = state.get("intent_decision")
        if not isinstance(decision_payload, dict):
            return []
        try:
            decision = IntentDecision(**decision_payload)
        except Exception:
            return []
        context = create_context(
            request=state["request"],
            intent_decision=decision,
            sources=sources,
            skill_fragments=list(state.get("selected_skills") or []),
            actions=list(state.get("actions", [])),
        )
        return [
            {
                "agentName": result.agentName,
                "answerMode": result.answerMode,
                "generationInstructions": result.generationInstructions,
                "evidencePolicy": result.evidencePolicy,
                "actions": result.actions,
                "diagnostics": result.diagnostics,
            }
            for result in await run_specialists_parallel(
                context,
                max_parallel=settings.agent_max_parallel_tool_calls,
            )
        ]

    def _format_specialist_plan(self, state: ResearchState) -> str:
        results = list(state.get("specialist_results") or [])
        if not results:
            return "(none)"
        blocks: list[str] = []
        for result in results:
            if not isinstance(result, dict):
                continue
            instructions = "\n".join(f"- {item}" for item in result.get("generationInstructions", [])[:4])
            evidence_policy = "\n".join(f"- {item}" for item in result.get("evidencePolicy", [])[:4])
            blocks.append(
                f"agent: {result.get('agentName')}\n"
                f"answerMode: {result.get('answerMode')}\n"
                f"instructions:\n{instructions}\n"
                f"evidencePolicy:\n{evidence_policy}"
            )
        return "\n\n".join(blocks) if blocks else "(none)"

    async def _specialist_agents_node(self, state: ResearchState) -> ResearchState:
        if state.get("specialist_results") is not None:
            return {}
        return {
            "specialist_results": await self._prepare_specialist_results(
                state,
                list(state.get("sources", [])),
            )
        }

    async def _creative_answer_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        state.update(await self._specialist_agents_node({**state, "sources": []}))
        answer, fallback_used = await self._compose_creative_answer(request)
        response = KnowledgeChatResponse(
            status="answered",
            answer=answer,
            candidates=[],
            sources=[],
            actions=[],
            resultJson={
                "status": "answered",
                "intent": "creative_advice",
                "answerMode": "creative",
                "answerStatus": "creative_answer",
                "answerBoundary": "creative_inference",
                "fallbackUsed": fallback_used,
            },
        )
        self._attach_domain_intent_metadata(response, state)
        self._attach_memory_metadata(response, request)
        return {"response": response}

    async def _book_resolver_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        selected = request.selectedCandidate
        if selected is not None:
            actions = list(state.get("actions", []))
            if not selected.local:
                actions.append("fetch_book")
                actions.append("index_book")
            return {
                "book_id": selected.bookId,
                "book_name": selected.bookName,
                "platform": selected.platform,
                "actions": self._dedupe(actions),
            }

        if state.get("book_id") is not None:
            return {}

        if self._should_try_global_evidence_before_book_search(state):
            return {}

        book_name = state.get("book_name")
        if not book_name:
            return {"candidates": []}

        limit = self._limit(request, "candidateLimit", default=5, maximum=20)
        candidates = await self.knowledge_client.search_books(platform="fanqie", keyword=book_name, limit=limit)
        exact_candidate = self._select_exact_book_candidate(book_name, candidates)
        if exact_candidate is not None:
            actions = list(state.get("actions", []))
            if not exact_candidate.local:
                actions.append("fetch_book")
                actions.append("index_book")
            return {
                "book_id": exact_candidate.bookId,
                "book_name": exact_candidate.bookName,
                "platform": exact_candidate.platform,
                "candidates": candidates,
                "actions": self._dedupe(actions),
            }
        response = KnowledgeChatResponse(
            status="candidates_required",
            answer="找到了多个可能的书籍，请选择正确作品后继续。" if candidates else "未找到匹配书籍，请补充更准确的书名。",
            candidates=candidates,
            sources=[],
            actions=["select_candidate"] if candidates else ["refine_book_name"],
            resultJson={
                "status": "candidates_required",
                "answerStatus": "needs_data",
                "answerBoundary": "needs_more_data",
                "intent": state.get("intent"),
                "bookName": book_name,
                "candidateCount": len(candidates),
            },
        )
        self._attach_domain_intent_metadata(response, state)
        return {"candidates": candidates, "response": response}

    def _route_after_book_resolver(self, state: ResearchState) -> str:
        return "candidate_response" if state.get("response") is not None else "continue"

    async def _data_completer_node(self, state: ResearchState) -> ResearchState:
        actions = list(state.get("actions", []))
        if state.get("book_id") is None and state.get("book_name"):
            actions.append("resolve_book")
        return {"actions": self._dedupe(actions)}

    async def _structured_rank_lookup_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        lookup = self._parse_exact_rank_lookup(request.question or "")
        if not lookup:
            return {}
        lookup_fn = getattr(self.knowledge_client, "lookup_rank", None)
        if not callable(lookup_fn):
            return {"rank_lookup": lookup}
        try:
            results = await self._with_tool_timeout(lookup_fn(**lookup), default=[], request=request)
        except Exception:
            actions = self._dedupe(list(state.get("actions", [])) + ["rank_lookup_failed"])
            return {"rank_lookup": lookup, "actions": actions}
        if not results:
            return {"rank_lookup": lookup}
        response = self._build_rank_lookup_response(request, results, lookup)
        self._attach_domain_intent_metadata(response, state)
        return {"rank_lookup": lookup, "response": response}

    def _route_after_structured_rank_lookup(self, state: ResearchState) -> str:
        return "rank_response" if state.get("response") is not None else "continue"

    def _parse_exact_rank_lookup(self, question: str) -> dict[str, Any] | None:
        normalized = (question or "").strip()
        if not normalized:
            return None
        if self._has_mixed_trend_or_advice_request(normalized):
            return None
        if not any(keyword in normalized for keyword in ("排名", "榜一", "第一", "第1", "Top1", "top1", "TOP1")):
            return None
        if not any(keyword in normalized for keyword in ("榜", "男频", "女频", "都市脑洞")):
            return None
        rank_no = 1
        match = re.search(r"(?:排名第|第)\s*(\d+)", normalized, re.IGNORECASE)
        if match:
            rank_no = max(1, int(match.group(1)))
        channel_code = None
        if "男频" in normalized:
            channel_code = "male-new" if "新书榜" in normalized else "male"
        elif "女频" in normalized:
            channel_code = "female-new" if "新书榜" in normalized else "female"
        category = "都市脑洞" if "都市脑洞" in normalized else None
        return {
            "platform": "fanqie",
            "channel_code": channel_code,
            "board_code": None,
            "category": category,
            "rank_no": rank_no,
            "limit": 1 if rank_no == 1 else 10,
        }

    def _has_mixed_trend_or_advice_request(self, question: str) -> bool:
        return any(keyword in question for keyword in (
            "热门",
            "题材",
            "趋势",
            "开书",
            "开文",
            "建议",
            "机会",
            "怎么写",
            "如何写",
        ))

    def _build_rank_lookup_response(
        self,
        request: KnowledgeChatRequest,
        results: list[RankLookupResult],
        lookup: dict[str, Any],
    ) -> KnowledgeChatResponse:
        first = results[0]
        source = self._rank_result_to_source(first)
        rank_label = first.sourceLabel or self._rank_source_label(first)
        rank_no = first.rankNo or lookup.get("rank_no") or 1
        book_name = first.bookName or "未命名作品"
        author = first.author or "未知作者"
        answer = (
            f"## 结论\n"
            f"最近{rank_label}排名第{rank_no}的是《{book_name}》，作者是{author}。[1]\n\n"
            f"## 可核验证据\n"
            f"- {rank_label}：第{rank_no}名，《{book_name}》，作者{author}。[1]"
        )
        if first.intro:
            answer += f"\n- 简介线索：{self._short_text(first.intro, 160)}[1]"
        response = KnowledgeChatResponse(
            status="answered",
            answer=answer,
            candidates=[],
            sources=[source],
            actions=[],
            resultJson={
                "status": "answered",
                "intent": "rank_lookup",
                "answerMode": "rank_fact",
                "answerStatus": "answered_with_evidence",
                "answerBoundary": "structured_fact",
                "rankLookup": lookup,
                "sourceCount": 1,
                "diagnostics": self._answer_diagnostics([source], answer),
            },
        )
        self._attach_memory_metadata(response, request)
        return response

    def _rank_result_to_source(self, result: RankLookupResult) -> KnowledgeSource:
        title = result.sourceLabel or self._rank_source_label(result)
        preview = (
            f"榜单：{title}。排名：第{result.rankNo or 1}名。"
            f"书名：{result.bookName or '未命名作品'}。作者：{result.author or '未知作者'}。"
        )
        if result.intro:
            preview += f"简介：{result.intro}"
        return KnowledgeSource(
            chunkId=None,
            documentId=None,
            score=1.0,
            bookId=result.bookId,
            bookName=result.bookName,
            platform=result.platform,
            sourceType="RANK",
            sourceRefId=result.rankId,
            snapshotId=result.snapshotId,
            snapshotTime=result.snapshotTime,
            channelCode=result.channelCode,
            boardCode=result.boardCode,
            channelName=result.channelName,
            boardName=result.boardName,
            rankNo=result.rankNo,
            author=result.author,
            category=result.category,
            title=title,
            preview=preview,
        )

    def _rank_source_label(self, result: RankLookupResult) -> str:
        parts = [
            result.channelName or result.channelCode,
            result.boardName or result.category or result.boardCode,
        ]
        label = " / ".join(part for part in parts if part)
        if result.rankNo is not None:
            label = f"{label} #{result.rankNo}" if label else f"榜单 #{result.rankNo}"
        return label or "榜单"

    async def _evidence_retriever_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        try:
            task_tool_sources = await self._execute_task_graph_tools(request, state)
            pack_sources: list[KnowledgeSource] = []
            use_rank_pack = self._should_use_rank_research_pack(request, state)
            if use_rank_pack:
                pack_sources = await self._rank_research_pack_sources(request, state)
            elif self._should_use_book_research_pack(request, state):
                pack_sources = await self._book_research_pack_sources(request, state)

            structured_rank_sources: list[KnowledgeSource] = []
            if self._should_search_rank_evidence(request, state):
                structured_rank_sources = await self._lookup_rank_sources_for_trend(request, state)

            source_policy: dict[str, Any] = {}
            lookup_available = callable(getattr(self.knowledge_client, "lookup_rank", None))
            if self._should_search_rank_evidence(request, state) and lookup_available and not use_rank_pack:
                source_policy = self._build_trend_source_policy(request, structured_rank_sources)
                if source_policy.get("trendGateFailed"):
                    actions = self._dedupe(list(state.get("actions", [])) + ["refresh_rank_board"])
                    self._append_tool_run(
                        state,
                        "trend_rank_gate",
                        "blocked",
                        reason=str(source_policy.get("trendGateReason") or "missing_current_top_rank"),
                    )
                    return {
                        "sources": [],
                        "actions": actions,
                        "tool_runs": list(state.get("tool_runs") or []),
                        "source_policy": source_policy,
                    }

            if self._can_answer_rank_advice_from_pack(request, state, pack_sources + structured_rank_sources):
                sources = structured_rank_sources
                actions = self._dedupe(list(state.get("actions", [])) + ["vector_evidence_skipped"])
            elif self._needs_chapter_level_evidence(request) and state.get("book_id") is not None:
                sources = await self._with_tool_timeout(
                    self.knowledge_client.search_evidence(
                        query=self._build_chapter_level_retrieval_query(request),
                        book_id=state.get("book_id"),
                        platform=state.get("platform"),
                        analysis_type=None,
                        limit=self._limit(request, "evidenceLimit", default=5, maximum=20),
                        source_type="CHAPTER",
                    ),
                    default=[],
                    request=request,
                )
                self._append_tool_run(state, "chapter_vector_search", "succeeded", result_count=len(sources))
                actions = list(state.get("actions", []))
            else:
                existing_vector_sources = self._existing_generic_vector_sources(state)
                if existing_vector_sources is not None:
                    sources = existing_vector_sources
                else:
                    sources = await self._with_tool_timeout(
                        self.knowledge_client.search_evidence(
                            query=self._build_retrieval_query(request, state),
                            book_id=state.get("book_id"),
                            platform=state.get("platform"),
                            analysis_type=self._analysis_type(request),
                            limit=self._limit(request, "evidenceLimit", default=5, maximum=20),
                        ),
                        default=[],
                        request=request,
                    )
                self._append_tool_run(state, "generic_vector_search", "succeeded", result_count=len(sources))
                actions = list(state.get("actions", []))
            if self._should_search_rank_evidence(request, state):
                rank_sources = await self._search_rank_evidence(request, state)
                if not self._should_use_rank_research_pack(request, state):
                    rank_sources = self._filter_rank_sources_to_front_ranks(rank_sources, max_rank=5)
                if "vector_evidence_skipped" not in actions:
                    sources = structured_rank_sources + rank_sources + sources
                else:
                    sources = structured_rank_sources + sources
            sources = task_tool_sources + pack_sources + sources
            if self._should_search_rank_evidence(request, state) and not self._should_use_rank_research_pack(request, state):
                sources = self._filter_plain_trend_sources_to_structured_front_ranks(structured_rank_sources, sources)
            sources = await self._augment_chapter_sources_for_chapter_level_question(request, state, sources)
            sources = self._filter_sources_for_requested_book(state, sources)
            sources = self._rerank_sources(request, state, sources)
            return {
                "sources": sources,
                "actions": self._dedupe(actions),
                "tool_runs": list(state.get("tool_runs") or []),
                "source_policy": source_policy,
            }
        except Exception:
            actions = self._dedupe(list(state.get("actions", [])) + ["evidence_search_failed"])
            return {"sources": [], "actions": actions, "tool_runs": list(state.get("tool_runs") or [])}

    def _should_search_rank_evidence(self, request: KnowledgeChatRequest, state: ResearchState) -> bool:
        intent = str(state.get("intent") or "")
        return state.get("book_id") is None and (intent == "trend_research" or self._is_trend_question(request.question or ""))

    def _build_trend_source_policy(
        self,
        request: KnowledgeChatRequest,
        structured_rank_sources: list[KnowledgeSource],
    ) -> dict[str, Any]:
        lookup = self._parse_trend_rank_lookup_for_request(request) or {}
        rank_sources = self._dedupe_rank_sources_by_book([
            source
            for source in structured_rank_sources
            if (source.sourceType or "").upper() == "RANK"
        ])
        top_rank_limit = 3
        top_sources = [
            source
            for source in rank_sources
            if source.rankNo is not None and source.rankNo <= top_rank_limit
        ]
        snapshot_times = {source.snapshotTime for source in top_sources if source.snapshotTime}
        missing_snapshot = any(not source.snapshotTime for source in top_sources)
        category = lookup.get("category")
        channel_code = lookup.get("channel_code")
        board_code = lookup.get("board_code")
        category_mismatch = bool(category) and any(
            not self._trend_category_matches(source, str(category))
            for source in top_sources
        )
        channel_mismatch = bool(channel_code) and any(
            not self._trend_channel_matches(source, str(channel_code))
            for source in top_sources
        )
        board_mismatch = bool(board_code) and any(
            not self._trend_board_matches(source, str(board_code))
            for source in top_sources
        )
        failed = (
            not top_sources
            or missing_snapshot
            or len(snapshot_times) > 1
            or category_mismatch
            or channel_mismatch
            or board_mismatch
        )
        if not top_sources:
            reason = "missing_current_structured_top_rank"
        elif missing_snapshot:
            reason = "missing_structured_rank_snapshot"
        elif len(snapshot_times) > 1:
            reason = "mixed_structured_rank_snapshot"
        elif category_mismatch:
            reason = "category_mismatch"
        elif channel_mismatch:
            reason = "channel_mismatch"
        elif board_mismatch:
            reason = "board_mismatch"
        else:
            reason = None
        return {
            "trendGateFailed": failed,
            "trendGateReason": reason,
            "structuredRankCount": len(rank_sources),
            "structuredTopRankCount": len(top_sources),
            "topRankLimit": top_rank_limit,
            "requiredEvidence": "current_structured_rank_topn",
            "requestedCategory": category,
            "requestedChannelCode": channel_code,
            "requestedBoardCode": board_code,
            "snapshotTime": max(snapshot_times) if snapshot_times else None,
        }

    def _trend_channel_matches(self, source: KnowledgeSource, requested_channel: str) -> bool:
        aliases = self._trend_channel_aliases(requested_channel)
        candidate_values = [
            source.channelCode,
            source.channelName,
            source.title,
            source.preview,
        ]
        return any(
            self._text_matches_any(candidate, aliases)
            for candidate in candidate_values
        )

    def _trend_category_matches(self, source: KnowledgeSource, requested_category: str) -> bool:
        candidate_values = [
            source.category,
            source.boardName,
            source.boardCode,
            source.title,
            source.preview,
        ]
        return self._text_matches_any(candidate_values, {requested_category})

    def _trend_board_matches(self, source: KnowledgeSource, requested_board_code: str) -> bool:
        candidate_values = [
            source.boardCode,
            source.boardName,
            source.title,
            source.preview,
        ]
        return self._text_matches_any(candidate_values, {requested_board_code})

    def _trend_channel_aliases(self, channel_code: str) -> set[str]:
        normalized = self._normalize_trend_match_text(channel_code)
        if not normalized:
            return set()
        if normalized in {"male-new", "male"}:
            return {"male-new", "male", "男频新书榜", "男频"}
        if normalized in {"female-new", "female"}:
            return {"female-new", "female", "女频新书榜", "女频"}
        return {normalized}

    def _text_matches_any(self, candidates: Any, expected_values: set[str]) -> bool:
        if not expected_values:
            return True
        if isinstance(candidates, str) or candidates is None:
            candidate_values = [candidates]
        else:
            candidate_values = list(candidates)
        normalized_expected = set()
        for value in expected_values:
            normalized_value = self._normalize_trend_match_text(value)
            if normalized_value:
                normalized_expected.add(normalized_value)
        if not normalized_expected:
            return False
        for candidate in candidate_values:
            normalized_candidate = self._normalize_trend_match_text(candidate)
            if not normalized_candidate:
                continue
            if normalized_candidate in normalized_expected:
                return True
            if any(expected in normalized_candidate or normalized_candidate in expected for expected in normalized_expected):
                return True
        return False

    def _normalize_trend_match_text(self, value: Any) -> str:
        if value is None:
            return ""
        return re.sub(r"\s+", "", str(value)).casefold()

    def _dedupe_rank_sources_by_book(self, sources: list[KnowledgeSource]) -> list[KnowledgeSource]:
        best_by_book: dict[int | None, KnowledgeSource] = {}
        ordered: list[KnowledgeSource] = []
        for source in sources:
            if source.bookId is None:
                ordered.append(source)
                continue
            current = best_by_book.get(source.bookId)
            if current is None or self._rank_source_preference(source) > self._rank_source_preference(current):
                best_by_book[source.bookId] = source
        ordered.extend(best_by_book.values())
        return sorted(ordered, key=lambda source: self._rank_source_preference(source), reverse=True)

    def _rank_source_preference(self, source: KnowledgeSource) -> tuple[str, int, float]:
        snapshot_time = str(source.snapshotTime or "")
        rank_no = -(source.rankNo or 9999)
        score = float(source.score or 0.0)
        return (snapshot_time, rank_no, score)

    def _should_use_rank_research_pack(self, request: KnowledgeChatRequest, state: ResearchState) -> bool:
        if not self._should_search_rank_evidence(request, state):
            return False
        if self._existing_rank_lookup_sources(state) is not None and not self._needs_rank_research_pack_sources(request, state):
            return False
        decision = state.get("intent_decision") or {}
        primary_intent = ""
        sub_intents: list[str] = []
        if isinstance(decision, dict):
            primary_intent = str(decision.get("primaryIntent") or "")
            raw_sub_intents = decision.get("subIntents") or []
            if isinstance(raw_sub_intents, list):
                sub_intents = [str(item) for item in raw_sub_intents]
        creative_intents = {
            "mixed_creation_research",
            "opening_strategy",
            "outline_building",
            "chapter_outline",
            "inspiration_expand",
        }
        if primary_intent in creative_intents or any(intent in creative_intents for intent in sub_intents):
            return True
        return self._is_rank_imitation_or_outline_request(request.question or "")

    def _needs_rank_research_pack_sources(self, request: KnowledgeChatRequest, state: ResearchState) -> bool:
        decision = state.get("intent_decision") or {}
        if isinstance(decision, dict):
            tool_needs = decision.get("toolNeeds") or {}
            if isinstance(tool_needs, dict) and tool_needs.get("needsCreativeGeneration"):
                return True
        return self._is_rank_imitation_or_outline_request(request.question or "")

    def _filter_rank_sources_to_front_ranks(self, sources: list[KnowledgeSource], *, max_rank: int) -> list[KnowledgeSource]:
        return [
            source for source in sources
            if (source.sourceType or "").upper() != "RANK"
            or source.rankNo is None
            or source.rankNo <= max_rank
        ]

    def _filter_plain_trend_sources_to_structured_front_ranks(
        self,
        structured_rank_sources: list[KnowledgeSource],
        sources: list[KnowledgeSource],
    ) -> list[KnowledgeSource]:
        front_rank_book_ids = {
            source.bookId
            for source in structured_rank_sources
            if source.bookId is not None and ((source.rankNo or 9999) <= 5)
        }
        if not front_rank_book_ids:
            return sources
        filtered: list[KnowledgeSource] = []
        for source in sources:
            source_type = (source.sourceType or "").upper()
            if source_type == "RANK":
                if source.rankNo is None or source.rankNo <= 5 or source.bookId in front_rank_book_ids:
                    filtered.append(source)
                continue
            if source_type in {"INTRO", "ANALYSIS", "CHAPTER", "CHAPTER_PACK"} and source.bookId is not None:
                if source.bookId not in front_rank_book_ids:
                    continue
            filtered.append(source)
        return filtered

    def _should_use_book_research_pack(self, request: KnowledgeChatRequest, state: ResearchState) -> bool:
        if state.get("book_id") is None and not state.get("book_name"):
            return False
        intent = str(state.get("intent") or "")
        if intent == "trend_research" or self._is_trend_question(request.question or ""):
            return False
        return intent == "single_book_research" or self._needs_chapter_level_evidence(request) or bool(request.bookId or request.bookName or request.selectedCandidate)

    def _can_answer_rank_advice_from_pack(
        self,
        request: KnowledgeChatRequest,
        state: ResearchState,
        pack_sources: list[KnowledgeSource],
    ) -> bool:
        if not pack_sources or not self._should_search_rank_evidence(request, state):
            return False
        if not any((source.sourceType or "").upper() == "RANK" for source in pack_sources):
            return False
        decision = state.get("intent_decision") or {}
        tool_needs = decision.get("toolNeeds") if isinstance(decision, dict) else {}
        if isinstance(tool_needs, dict) and tool_needs.get("needsVectorEvidence"):
            return False
        return self._is_rank_imitation_or_outline_request(request.question or "")

    def _is_rank_imitation_or_outline_request(self, question: str) -> bool:
        normalized = question or ""
        has_rank_context = any(keyword in normalized for keyword in ("榜", "榜一", "第一", "第1", "男频", "女频", "都市脑洞"))
        has_creation_context = any(keyword in normalized for keyword in ("模仿", "大纲", "细纲", "怎么设计", "如何设计", "怎么写", "如何写"))
        return has_rank_context and has_creation_context

    async def _book_research_pack_sources(self, request: KnowledgeChatRequest, state: ResearchState) -> list[KnowledgeSource]:
        existing_sources = self._existing_book_pack_sources(state)
        if existing_sources is not None:
            return existing_sources
        pack_fn = getattr(self.knowledge_client, "get_book_research_pack", None)
        if not callable(pack_fn):
            self._append_tool_run(state, "book_research_pack", "skipped", reason="tool_unavailable")
            return []
        try:
            pack = await self._with_tool_timeout(
                pack_fn(
                    platform=state.get("platform") or "fanqie",
                    book_id=state.get("book_id"),
                    book_name=state.get("book_name") or request.bookName,
                    chapter_limit=self._limit(request, "chapterLimit", default=3, maximum=20),
                    analysis_limit=self._limit(request, "analysisLimit", default=3, maximum=20),
                ),
                default=None,
                request=request,
            )
        except Exception:
            self._append_tool_run(state, "book_research_pack", "failed", reason="exception")
            return []
        if pack is None:
            self._append_tool_run(state, "book_research_pack", "skipped", reason="empty_pack")
            return []
        sources = self._book_pack_to_sources(pack)
        self._append_tool_run(state, "book_research_pack", "succeeded", result_count=len(sources))
        return sources

    async def _rank_research_pack_sources(self, request: KnowledgeChatRequest, state: ResearchState) -> list[KnowledgeSource]:
        existing_sources = self._existing_rank_pack_sources(state)
        if existing_sources is not None:
            return existing_sources
        pack_fn = getattr(self.knowledge_client, "get_rank_research_pack", None)
        if not callable(pack_fn):
            self._append_tool_run(state, "rank_research_pack", "skipped", reason="tool_unavailable")
            return []
        lookup = self._parse_trend_rank_lookup_for_request(request)
        if not lookup:
            self._append_tool_run(state, "rank_research_pack", "skipped", reason="missing_rank_lookup")
            return []
        try:
            pack = await self._with_tool_timeout(
                pack_fn(
                    platform=lookup["platform"],
                    channel_code=lookup.get("channel_code"),
                    board_code=lookup.get("board_code"),
                    category=lookup.get("category"),
                    rank_no=lookup.get("rank_no"),
                    limit=self._limit(request, "rankLimit", default=lookup.get("limit", 10), maximum=20),
                    chapter_limit_per_book=self._limit(
                        request,
                        "chapterLimitPerBook",
                        default=settings.agent_chapters_per_rank_book,
                        maximum=5,
                    ),
                ),
                default=None,
                request=request,
            )
        except Exception:
            self._append_tool_run(state, "rank_research_pack", "failed", reason="exception")
            return []
        if pack is None:
            self._append_tool_run(state, "rank_research_pack", "skipped", reason="empty_pack")
            return []
        sources = self._rank_pack_to_sources(pack)
        self._append_tool_run(state, "rank_research_pack", "succeeded", result_count=len(sources))
        return sources

    def _book_pack_to_sources(self, pack: BookResearchPack) -> list[KnowledgeSource]:
        sources: list[KnowledgeSource] = []
        book = pack.book
        if book and (book.intro or book.bookName):
            sources.append(self._book_profile_to_source(book))
        sources.extend(self._rank_result_to_source(rank) for rank in pack.ranks)
        sources.extend(self._chapter_material_to_source(chapter, book, source_type="CHAPTER_PACK") for chapter in pack.chapters)
        sources.extend(self._analysis_material_to_source(analysis, book) for analysis in pack.analyses)
        return [source for source in sources if source.preview or source.bookName]

    def _rank_pack_to_sources(self, pack: RankResearchPack) -> list[KnowledgeSource]:
        sources: list[KnowledgeSource] = [self._rank_result_to_source(rank) for rank in pack.ranks]
        ranked_book_ids = {rank.bookId for rank in pack.ranks if rank.bookId is not None}
        sources.extend(self._book_profile_to_source(book) for book in pack.books if book.bookId in ranked_book_ids or not ranked_book_ids)
        for chapter in pack.chapters:
            book = self._find_book_profile(pack.books, chapter.bookId)
            sources.append(self._chapter_material_to_source(chapter, book, source_type="CHAPTER_PACK"))
        for analysis in pack.analyses:
            book = self._find_book_profile(pack.books, analysis.bookId)
            sources.append(self._analysis_material_to_source(analysis, book))
        return [source for source in sources if source.preview or source.bookName]

    def _find_book_profile(self, books: list[BookProfile], book_id: int | None) -> BookProfile | None:
        if book_id is None:
            return None
        for book in books:
            if book.bookId == book_id:
                return book
        return None

    def _book_profile_to_source(self, book: BookProfile) -> KnowledgeSource:
        title = book.latestRankLabel or (f"{book.bookName} 简介" if book.bookName else "book profile")
        return KnowledgeSource(
            score=0.96,
            bookId=book.bookId,
            bookName=book.bookName,
            platform=book.platform,
            sourceType="INTRO",
            sourceRefId=book.bookId,
            rankNo=book.latestRankNo,
            author=book.author,
            category=book.category,
            title=title,
            preview=book.intro or book.bookName,
        )

    def _chapter_material_to_source(self, chapter: ChapterMaterial, book: BookProfile | None, *, source_type: str) -> KnowledgeSource:
        return KnowledgeSource(
            score=0.98,
            bookId=chapter.bookId or (book.bookId if book else None),
            bookName=chapter.bookName or (book.bookName if book else None),
            platform=chapter.platform or (book.platform if book else None),
            sourceType=source_type,
            sourceRefId=chapter.sourceRefId or chapter.chapterId,
            chapterNo=chapter.chapterNo,
            author=book.author if book else None,
            category=book.category if book else None,
            title=chapter.title,
            preview=chapter.preview or chapter.content,
            material=chapter.content or chapter.preview,
        )

    def _analysis_material_to_source(self, analysis: AnalysisMaterial, book: BookProfile | None) -> KnowledgeSource:
        return KnowledgeSource(
            score=0.97,
            bookId=analysis.bookId or (book.bookId if book else None),
            bookName=analysis.bookName or (book.bookName if book else None),
            platform=analysis.platform or (book.platform if book else None),
            sourceType="ANALYSIS",
            sourceRefId=analysis.sourceRefId or analysis.analysisId,
            analysisType=analysis.analysisType,
            author=book.author if book else None,
            category=book.category if book else None,
            title=analysis.title or analysis.analysisType,
            preview=analysis.preview or analysis.summary or analysis.content,
            material=analysis.content or analysis.summary or analysis.preview,
        )

    async def _lookup_rank_sources_for_trend(self, request: KnowledgeChatRequest, state: ResearchState) -> list[KnowledgeSource]:
        existing_sources = self._existing_rank_lookup_sources(state)
        if existing_sources is not None:
            return existing_sources
        lookup_fn = getattr(self.knowledge_client, "lookup_rank", None)
        if not callable(lookup_fn):
            self._append_tool_run(state, "rank_lookup", "skipped", reason="tool_unavailable")
            return []
        lookup = self._parse_trend_rank_lookup_for_request(request)
        if not lookup:
            self._append_tool_run(state, "rank_lookup", "skipped", reason="missing_rank_lookup")
            return []
        try:
            results = await self._with_tool_timeout(lookup_fn(**lookup), default=[], request=request)
        except Exception:
            self._append_tool_run(state, "rank_lookup", "failed", reason="exception")
            return []
        sources = [self._rank_result_to_source(result) for result in results]
        self._append_tool_run(state, "rank_lookup", "succeeded", result_count=len(sources))
        return sources

    def _existing_rank_lookup_sources(self, state: ResearchState) -> list[KnowledgeSource] | None:
        sources: list[KnowledgeSource] = []
        found_run = False
        for run in state.get("tool_runs") or []:
            if not isinstance(run, dict):
                continue
            if run.get("name") != "rank.lookup" or run.get("status") != "succeeded":
                continue
            found_run = True
            output = run.get("output")
            if isinstance(output, dict):
                sources.extend(self._rank_lookup_output_to_sources(output))
        return sources if found_run else None

    def _parse_trend_rank_lookup_for_request(self, request: KnowledgeChatRequest) -> dict[str, Any] | None:
        question = request.question or ""
        lookup = self._parse_trend_rank_lookup(question)
        if not lookup:
            context = self._format_context_for_sticky_extraction(request.contextSummary or "")
            if self._looks_like_contextual_trend_followup(question) and any(
                keyword in context for keyword in ("男频", "女频", "都市脑洞", "新书榜")
            ):
                lookup = self._parse_trend_rank_lookup(f"{context}\n{question}")
        if not lookup:
            return None
        context_text = self._format_context_for_sticky_extraction(
            "\n".join([
                request.contextSummary or "",
                "\n".join(str(message.get("content") or "") for message in request.history or []),
            ])
        )
        combined = f"{question}\n{context_text}"
        if not lookup.get("channel_code"):
            if "男频" in combined:
                lookup["channel_code"] = "male-new" if ("新书榜" in combined or "最近" in combined) else "male"
            elif "女频" in combined:
                lookup["channel_code"] = "female-new" if ("新书榜" in combined or "最近" in combined) else "female"
        if not lookup.get("category") and "都市脑洞" in combined:
            lookup["category"] = "都市脑洞"
        return lookup

    def _looks_like_contextual_trend_followup(self, question: str) -> bool:
        return any(keyword in question for keyword in ("热门", "题材", "趋势", "最近", "榜单", "开书", "开文", "扫榜"))

    def _parse_trend_rank_lookup(self, question: str) -> dict[str, Any] | None:
        normalized = (question or "").strip()
        if not normalized:
            return None
        if not any(keyword in normalized for keyword in ("热门", "题材", "趋势", "最近", "榜单", "开书", "开文")):
            return None
        if not any(keyword in normalized for keyword in ("男频", "女频", "都市脑洞", "新书榜")):
            return None
        channel_code = None
        if "男频" in normalized:
            channel_code = "male-new" if ("新书榜" in normalized or "最近" in normalized) else "male"
        elif "女频" in normalized:
            channel_code = "female-new" if ("新书榜" in normalized or "最近" in normalized) else "female"
        category = "都市脑洞" if "都市脑洞" in normalized else None
        return {
            "platform": "fanqie",
            "channel_code": channel_code,
            "board_code": None,
            "category": category,
            "rank_no": None,
            "limit": self._limit_from_question(question, default=10, maximum=20),
        }

    def _limit_from_question(self, question: str, *, default: int, maximum: int) -> int:
        match = re.search(r"(?:top|Top|TOP|前)\s*(\d+)", question or "")
        if not match:
            return default
        return max(1, min(int(match.group(1)), maximum))

    async def _search_rank_evidence(self, request: KnowledgeChatRequest, state: ResearchState) -> list[KnowledgeSource]:
        search_fn = getattr(self.knowledge_client, "search_evidence")
        try:
            sources = await self._with_tool_timeout(
                search_fn(
                    query=self._build_rank_retrieval_query(request),
                    book_id=None,
                    platform=state.get("platform"),
                    analysis_type=None,
                    limit=self._limit(request, "evidenceLimit", default=5, maximum=20),
                    source_type="RANK",
                ),
                default=[],
                request=request,
            )
            self._append_tool_run(state, "vector_rank_search", "succeeded", result_count=len(sources))
            return sources
        except TypeError:
            self._append_tool_run(state, "vector_rank_search", "failed", reason="type_error")
            return []

    async def _augment_chapter_sources_for_chapter_level_question(
        self,
        request: KnowledgeChatRequest,
        state: ResearchState,
        sources: list[KnowledgeSource],
    ) -> list[KnowledgeSource]:
        if not self._needs_chapter_level_evidence(request) or self._has_chapter_level_evidence(sources):
            return sources
        target_book_id = state.get("book_id") or self._first_source_book_id(sources)
        if target_book_id is None:
            return sources
        search_fn = getattr(self.knowledge_client, "search_evidence")
        try:
            chapter_sources = await self._with_tool_timeout(
                search_fn(
                    query=self._build_chapter_level_retrieval_query(request),
                    book_id=target_book_id,
                    platform=state.get("platform"),
                    analysis_type=None,
                    limit=self._limit(request, "evidenceLimit", default=5, maximum=20),
                    source_type="CHAPTER",
                ),
                default=[],
                request=request,
            )
        except TypeError:
            return sources
        except Exception:
            return sources
        return chapter_sources + sources

    def _first_source_book_id(self, sources: list[KnowledgeSource]) -> int | None:
        for source in sources:
            if source.bookId is not None:
                return source.bookId
        return None

    def _build_chapter_level_retrieval_query(self, request: KnowledgeChatRequest) -> str:
        question = self._short_text(request.question or "", 360)
        return self._short_text("前三章 第一章 第二章 第三章 金手指 剧情 手法 钩子 伏笔 爽点 开篇 章节 " + question, 700)

    def _build_rank_retrieval_query(self, request: KnowledgeChatRequest) -> str:
        question = self._short_text(request.question or "", 360)
        return self._short_text(
            "榜单 排名 男频 女频 新书榜 都市脑洞 热门书 近期热门 趋势 " + question,
            700,
        )

    def _build_retrieval_query(self, request: KnowledgeChatRequest, state: ResearchState | None = None) -> str:
        question = self._short_text(request.question or "", 360)
        intent = str((state or {}).get("intent") or self._route_intent(request))
        book_name = (state or {}).get("book_name") or request.bookName

        if intent == "trend_research" or self._is_trend_question(question):
            return self._short_text(
                "题材趋势 网文市场 榜单风向 作者开文机会 "
                + question,
                700,
            )

        slots: list[str] = []
        if book_name:
            slots.append(f"current book: {book_name}")
        elif request.selectedCandidate and request.selectedCandidate.bookName:
            slots.append(f"current book: {request.selectedCandidate.bookName}")
        goal = self._extract_context_slot(request.contextSummary, ("最近用户目标", "用户目标", "current goal", "previous goal"))
        if goal:
            slots.append(f"目标：{goal}")
        if self._is_followup_reference(question) and book_name:
            slots.append(f"追问指代：{book_name}")
            recent_user = self._latest_history_content(request, "user")
            if recent_user:
                slots.append(f"previous user: {recent_user}")
        slots.append(f"问题：{question}")
        return self._short_text("\n".join(slot for slot in slots if slot), 850)

    def _extract_context_slot(self, summary: str | None, labels: tuple[str, ...]) -> str | None:
        if not summary:
            return None
        for raw_line in summary.splitlines():
            line = raw_line.strip()
            for label in labels:
                if line.startswith(label):
                    value = re.sub(rf"^{re.escape(label)}\s*[:：]\s*", "", line).strip()
                    return self._short_text(value, 160) if value else None
        return None

    def _is_followup_reference(self, question: str) -> bool:
        return any(keyword in question for keyword in ("它", "这本", "这个", "上面", "刚才", "前面", "该书", "本书"))

    def _latest_history_content(self, request: KnowledgeChatRequest, role: str) -> str | None:
        for message in reversed(request.history or []):
            if str(message.get("role") or "").strip() != role:
                continue
            content = str(message.get("content") or "").strip()
            if content:
                return self._short_text(content, 180)
        return None

    async def _answer_writer_node(self, state: ResearchState) -> ResearchState:
        sources = state.get("sources", [])
        source_policy = dict(state.get("source_policy") or {})
        if source_policy.get("trendGateFailed"):
            response = KnowledgeChatResponse(
                status="insufficient_evidence",
                answer=(
                    "证据不足：当前没有命中最新结构化榜单前排数据。"
                    "旧向量或低排名材料不会用于最近趋势结论，请先刷新榜单后再分析。"
                ),
                candidates=[],
                sources=[],
                actions=self._dedupe(list(state.get("actions", [])) + ["refresh_rank_board"]),
                resultJson={
                    "status": "insufficient_evidence",
                    "answerStatus": "needs_data",
                    "answerBoundary": "needs_more_data",
                    "intent": state.get("intent"),
                    "bookId": state.get("book_id"),
                    "bookName": state.get("book_name"),
                    "sourcePolicy": source_policy,
                },
            )
            self._attach_domain_intent_metadata(response, state)
            self._attach_memory_metadata(response, state["request"])
            return {"response": response}
        if not sources:
            if self._should_search_book_after_missing_evidence(state):
                return await self._build_book_candidates_response(state)
            response = KnowledgeChatResponse(
                status="insufficient_evidence",
                answer="证据不足：当前知识库没有检索到可引用材料，无法可靠回答这个问题。",
                candidates=[],
                sources=[],
                actions=self._dedupe(list(state.get("actions", [])) + ["index_book"]),
                resultJson={
                    "status": "insufficient_evidence",
                    "answerStatus": "needs_data",
                    "answerBoundary": "needs_more_data",
                    "intent": state.get("intent"),
                    "bookId": state.get("book_id"),
                    "bookName": state.get("book_name"),
                },
            )
            self._attach_domain_intent_metadata(response, state)
            self._attach_memory_metadata(response, state["request"])
            return {"response": response}

        if self._needs_chapter_level_evidence(state["request"]) and not self._has_chapter_level_evidence(sources):
            if self._should_search_book_after_missing_evidence(state):
                return await self._build_book_candidates_response(state)
            response = KnowledgeChatResponse(
                status="insufficient_evidence",
                answer="证据不足：这个问题需要章节正文或已有单书分析结果，当前检索到的材料不足以可靠拆解金手指、前三章剧情、钩子或结构技法。",
                candidates=[],
                sources=[],
                actions=self._dedupe(list(state.get("actions", [])) + ["index_book"]),
                resultJson={
                    "status": "insufficient_evidence",
                    "answerStatus": "needs_chapter_evidence",
                    "answerBoundary": "needs_more_data",
                    "intent": state.get("intent"),
                    "bookId": state.get("book_id"),
                    "bookName": state.get("book_name"),
                },
            )
            self._attach_domain_intent_metadata(response, state)
            self._attach_memory_metadata(response, state["request"])
            return {"response": response}

        state.update(await self._specialist_agents_node({**state, "sources": sources}))
        answer_mode = self._answer_mode(state["request"], sources, str(state.get("intent") or ""), state=state)
        answer, fallback_used = await self._compose_answer(state["request"], sources, answer_mode, state=state)
        response = KnowledgeChatResponse(
            status="answered",
            answer=answer,
            candidates=[],
            sources=sources,
            actions=self._dedupe(list(state.get("actions", []))),
            resultJson={
                "status": "answered",
                "intent": state.get("intent"),
                "bookId": state.get("book_id"),
                "bookName": state.get("book_name"),
                "answerMode": answer_mode,
                "answerStatus": self._answer_status(answer_mode, sources, state.get("intent")),
                "answerBoundary": self._answer_boundary(answer_mode, sources, state.get("intent"), state.get("answer_boundary")),
                "sourceCount": len(sources),
                "diagnostics": self._answer_diagnostics(sources, answer),
                "fallbackUsed": fallback_used,
            },
        )
        self._attach_domain_intent_metadata(response, state)
        self._attach_memory_metadata(response, state["request"])
        return {"response": response}

    async def _build_book_candidates_response(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        book_name = state.get("book_name")
        if not book_name:
            result = {
                "response": KnowledgeChatResponse(
                    status="insufficient_evidence",
                    answer="证据不足：当前知识库没有检索到可引用材料，无法可靠回答这个问题。",
                    candidates=[],
                    sources=[],
                    actions=self._dedupe(list(state.get("actions", [])) + ["index_book"]),
                    resultJson={
                        "status": "insufficient_evidence",
                        "answerStatus": "needs_data",
                        "answerBoundary": "needs_more_data",
                        "intent": state.get("intent"),
                        "bookId": state.get("book_id"),
                        "bookName": state.get("book_name"),
                    },
                )
            }
            self._attach_domain_intent_metadata(result["response"], state)
            return result
        candidates = await self.knowledge_client.search_books(
            platform="fanqie",
            keyword=book_name,
            limit=self._limit(request, "candidateLimit", default=5, maximum=20),
        )
        result = {
            "response": KnowledgeChatResponse(
                status="candidates_required",
                answer=("知识库暂未命中这本书的可靠材料，请选择正确作品后继续补采和分析。" if candidates else "知识库暂未命中可用材料，也未找到匹配书籍，请补充更准确的书名。"),
                candidates=candidates,
                sources=[],
                actions=self._dedupe(list(state.get("actions", [])) + (["select_candidate"] if candidates else ["refine_book_name"])),
                resultJson={
                    "status": "candidates_required",
                    "answerStatus": "needs_data",
                    "answerBoundary": "needs_more_data",
                    "intent": state.get("intent"),
                    "bookName": book_name,
                    "candidateCount": len(candidates),
                },
            )
        }
        self._attach_domain_intent_metadata(result["response"], state)
        return result

    async def _citation_verifier_node(self, state: ResearchState) -> ResearchState:
        response = state["response"]
        if response.status != "answered":
            return {"response": response}
        if response.resultJson.get("intent") == "creative_advice":
            return {"response": response}
        if response.sources and (
            not self._has_valid_citation(response.answer, len(response.sources))
            or not self._has_claim_level_citations(response.answer, len(response.sources))
        ):
            request = state.get("request")
            question = request.question if request is not None else ""
            response.answer = self._compose_fallback_answer(question, response.sources)
            response.status = "answered"
            response.resultJson["status"] = response.status
            response.resultJson["fallbackUsed"] = True
            response.resultJson["citationRepairUsed"] = True
            response.resultJson["diagnostics"] = self._answer_diagnostics(response.sources, response.answer)
            self._attach_trace_metadata(response, {**state, "sources": response.sources})
            return {"response": response}
        if not response.sources:
            response.status = "insufficient_evidence"
            response.answer = "证据不足：回答缺少可核验引用，已拒绝生成结论。"
            response.sources = []
            response.actions = self._dedupe(response.actions + ["index_book"])
            response.resultJson["status"] = response.status
            response.resultJson["answerStatus"] = "needs_data"
            response.resultJson["answerBoundary"] = "needs_more_data"
            self._attach_trace_metadata(response, {**state, "sources": response.sources})
        return {"response": response}

    def _route_intent(self, request: KnowledgeChatRequest) -> str:
        question = (request.question or "").strip()
        if self._is_trend_question(question):
            return "trend_research"
        if any(keyword in question for keyword in ("找书", "哪本", "搜索", "候选")):
            return "book_resolution"
        if self._is_single_book_question(question):
            return "single_book_research"
        if any(keyword in question for keyword in ("找书", "哪本", "搜索", "候选")):
            return "book_resolution"
        return "answer_question"

    def _should_try_global_evidence_before_book_search(self, state: ResearchState) -> bool:
        if state.get("book_id") is not None:
            return False
        request = state["request"]
        if request.selectedCandidate is not None:
            return False
        if self._requires_book_resolution_before_global_evidence(request):
            return False
        return bool(state.get("book_name"))

    def _should_search_book_after_missing_evidence(self, state: ResearchState) -> bool:
        if state.get("book_id") is not None:
            return False
        request = state["request"]
        if request.selectedCandidate is not None:
            return False
        if str(state.get("intent") or "") == "trend_research" or self._is_trend_question(request.question or ""):
            return False
        return bool(state.get("book_name"))

    def _needs_chapter_level_evidence(self, request: KnowledgeChatRequest) -> bool:
        question = request.question or ""
        return any(keyword in question for keyword in (
            "前三章",
            "前3章",
            "第一章",
            "第1章",
            "金手指",
            "剧情",
            "手法",
            "钩子",
            "三幕式",
            "三翻四震",
            "伏笔",
            "爽点",
            "开篇",
            "章节",
        ))

    def _requires_book_resolution_before_global_evidence(self, request: KnowledgeChatRequest) -> bool:
        question = request.question or ""
        return any(keyword in question for keyword in (
            "前三章",
            "前3章",
            "第一章",
            "第1章",
            "金手指",
            "剧情",
            "手法",
            "钩子",
            "三幕式",
            "三翻四震",
            "伏笔",
            "章节",
        ))

    def _has_chapter_level_evidence(self, sources: list[KnowledgeSource]) -> bool:
        return any((source.sourceType or "").upper() in {"CHAPTER", "CHAPTER_PACK", "ANALYSIS"} for source in sources)

    def _filter_sources_for_requested_book(
        self,
        state: ResearchState,
        sources: list[KnowledgeSource],
    ) -> list[KnowledgeSource]:
        request = state.get("request")
        if str(state.get("intent") or "") == "trend_research" or (
            request is not None and self._is_trend_question(request.question or "")
        ):
            return sources
        book_name = state.get("book_name")
        if not book_name or state.get("book_id") is not None:
            return sources
        normalized_book_name = self._normalize_book_name(book_name)
        if not normalized_book_name:
            return sources
        return [
            source for source in sources
            if self._normalize_book_name(source.bookName) == normalized_book_name
        ]

    def _rerank_sources(
        self,
        request: KnowledgeChatRequest,
        state: ResearchState,
        sources: list[KnowledgeSource],
    ) -> list[KnowledgeSource]:
        limit = self._limit(request, "evidenceLimit", default=5, maximum=20)
        return fuse_and_rerank_sources(
            request=request,
            state=state,
            sources=sources,
            limit=limit,
        )

    def _select_trend_sources(self, ranked: list[KnowledgeSource], limit: int) -> list[KnowledgeSource]:
        selected: list[KnowledgeSource] = []

        def add(source: KnowledgeSource | None) -> None:
            if source is not None and len(selected) < limit and source not in selected:
                selected.append(source)

        add(next((source for source in ranked if (source.sourceType or "").upper() == "RANK" and source.rankNo == 1), None))
        self._add_sources_by_type(selected, ranked, limit, {"RANK"}, max_count=min(3, limit))
        self._add_sources_by_type(selected, ranked, limit, {"CHAPTER", "CHAPTER_PACK"}, max_count=2)
        self._add_sources_by_type(selected, ranked, limit, {"INTRO", "ANALYSIS"}, max_count=1 if limit <= 5 else 2)
        add(self._first_supplemental_trend_source(ranked, selected))
        if len(selected) < limit:
            for source in ranked:
                add(source)
        return selected[:limit]

    def _first_supplemental_trend_source(
        self,
        ranked: list[KnowledgeSource],
        selected: list[KnowledgeSource],
    ) -> KnowledgeSource | None:
        selected_book_ids = {source.bookId for source in selected if source.bookId is not None}
        for source in ranked:
            source_type = (source.sourceType or "").upper()
            if source in selected or source_type not in {"INTRO", "ANALYSIS", "CHAPTER", "CHAPTER_PACK"}:
                continue
            if source.chunkId is not None or source.documentId is not None:
                return source
        for source in ranked:
            source_type = (source.sourceType or "").upper()
            if source in selected or source_type not in {"INTRO", "ANALYSIS", "CHAPTER", "CHAPTER_PACK"}:
                continue
            if source.bookId is None:
                return source
            if source.bookId not in selected_book_ids:
                return source
        return None

    def _add_sources_by_type(
        self,
        selected: list[KnowledgeSource],
        ranked: list[KnowledgeSource],
        limit: int,
        source_types: set[str],
        *,
        max_count: int,
    ) -> None:
        count = sum(1 for source in selected if (source.sourceType or "").upper() in source_types)
        for source in ranked:
            if len(selected) >= limit or count >= max_count:
                return
            if source in selected or (source.sourceType or "").upper() not in source_types:
                continue
            selected.append(source)
            count += 1

    def _source_rank_score(self, source: KnowledgeSource, question_terms: set[str], intent: str, needs_chapter_level_evidence: bool = False) -> float:
        score = float(source.score or 0)
        text = f"{source.bookName or ''} {source.title or ''} {source.preview or ''}"
        normalized_text = self._normalize_book_name(text)
        overlap = sum(1 for term in question_terms if term and term in normalized_text)
        source_type = (source.sourceType or "").upper()
        if intent == "trend_research":
            source_weight = {"RANK": 0.35, "ANALYSIS": 0.18, "INTRO": 0.08, "CHAPTER": 0.04, "CHAPTER_PACK": 0.04}.get(source_type, 0.0)
            rank_bonus = max(0.0, (30 - float(source.rankNo or 30)) * 0.02) if source_type == "RANK" else 0.0
            return score + overlap * 0.04 + source_weight + rank_bonus
        elif needs_chapter_level_evidence:
            source_weight = {"CHAPTER": 0.45, "CHAPTER_PACK": 0.45, "ANALYSIS": 0.35, "INTRO": 0.04, "RANK": -0.15}.get(source_type, 0.0)
        else:
            source_weight = {"CHAPTER": 0.18, "CHAPTER_PACK": 0.18, "INTRO": 0.1, "RANK": 0.1, "ANALYSIS": 0.08}.get(source_type, 0.0)
        return score + overlap * 0.04 + source_weight

    def _extract_query_terms(self, question: str) -> set[str]:
        normalized = self._normalize_book_name(question)
        terms: set[str] = set()
        for size in (6, 4, 3, 2):
            for index in range(0, max(0, len(normalized) - size + 1)):
                term = normalized[index:index + size]
                if term and not self._is_low_value_term(term):
                    terms.add(term)
        return terms

    def _is_low_value_term(self, term: str) -> bool:
        low_value = {"什么", "怎么", "分析", "一下", "这个", "那个", "它的", "的是", "方向"}
        return term in low_value

    def _normalize_book_name(self, value: str | None) -> str:
        if not value:
            return ""
        return re.sub(r"[\s《》【】（）()，。！？、]+", "", value).lower()

    def _select_exact_book_candidate(self, book_name: str | None, candidates: list[BookCandidate]) -> BookCandidate | None:
        normalized_book_name = self._normalize_book_name(book_name)
        if not normalized_book_name:
            return None
        exact_matches = [
            candidate for candidate in candidates
            if candidate.local and candidate.bookId is not None and self._normalize_book_name(candidate.bookName) == normalized_book_name
        ]
        if len(exact_matches) == 1:
            return exact_matches[0]
        return None

    def _resolve_book_name(self, request: KnowledgeChatRequest, decision: dict[str, Any] | None = None) -> str | None:
        if self._should_ignore_request_book_name_for_market_question(request, decision):
            return None
        if request.bookName and request.bookName.strip():
            return request.bookName.strip()
        ai_book_name = str((decision or {}).get("bookName") or "").strip()
        if ai_book_name:
            return ai_book_name
        question = request.question or ""
        bracket_match = re.search(r"[《【(](.{1,80}?)[》】)]", question)
        if bracket_match and not self._is_example_title_marker(question, bracket_match.start()):
            return bracket_match.group(1).strip()
        if str((decision or {}).get("intent") or "").strip() == "trend_research":
            return None
        if bool((decision or {}).get("needsStructuredRank")):
            return None
        if self._is_trend_question(question) and self._is_rank_imitation_or_outline_request(question):
            return None
        plain_book_name = self._extract_plain_book_name(question)
        if plain_book_name:
            return plain_book_name
        if self._is_trend_question(question):
            return None
        match = re.search(r"^([^《【]{2,80}?)(?:的|这本|这部)", question or "")
        if match:
            return match.group(1).strip()
        return None

    def _should_ignore_request_book_name_for_market_question(
        self,
        request: KnowledgeChatRequest,
        decision: dict[str, Any] | None = None,
    ) -> bool:
        if not request.bookName or not request.bookName.strip():
            return False
        if request.bookId is not None or request.selectedCandidate is not None:
            return False
        question = request.question or ""
        if self._is_followup_reference(question):
            return False
        intent = str((decision or {}).get("intent") or "").strip()
        return (
            intent == "trend_research"
            or bool((decision or {}).get("needsStructuredRank"))
            or self._is_trend_question(question)
        )

    def _is_single_book_question(self, question: str) -> bool:
        if not question or self._is_trend_question(question):
            return False
        return any(keyword in question for keyword in (
            "分析",
            "拆文",
            "开篇",
            "开局",
            "怎么写",
            "怎么设计",
            "如何设计",
            "卖点",
            "爽点",
            "主角",
            "设定",
            "人设",
            "节奏",
            "剧情",
            "情节",
            "世界观",
            "金手指",
            "前三章",
            "前3章",
            "三幕式",
            "三翻四震",
            "钩子",
        ))

    def _is_trend_question(self, question: str) -> bool:
        return any(keyword in question for keyword in (
            "趋势",
            "题材",
            "榜单",
            "最近",
            "哪些书火",
            "什么火",
            "赛道",
            "风向",
            "排名",
            "第一",
            "第1",
            "新书榜",
            "男频",
            "女频",
            "都市脑洞",
        ))

    def _extract_plain_book_name(self, question: str) -> str | None:
        normalized = re.sub(r"\s+", "", question or "")
        if not normalized:
            return None
        split_pattern = "|".join(map(re.escape, (
            "开篇",
            "开局",
            "开头",
            "怎么写",
            "怎么设计",
            "如何设计",
            "卖点",
            "爽点",
            "主角",
            "设定",
            "人设",
            "节奏",
            "剧情",
            "情节",
            "世界观",
            "金手指",
            "前三章",
            "前3章",
            "三幕式",
            "三翻四震",
            "钩子",
            "用了",
            "埋了",
            "有用",
            "分析",
            "拆文",
        )))
        match = re.match(rf"^(.{{2,40}}?)(?:的)?[，。！？、,;；：:]?(?:{split_pattern})", normalized)
        if not match:
            return None
        candidate = match.group(1).strip("，。！？、")
        if candidate in {"这本书", "这本小说", "这个作品", "本书"}:
            return None
        if candidate.endswith(("问题", "题材", "赛道", "流派")):
            return None
        if self._is_generic_web_novel_topic(candidate):
            return None
        return candidate or None

    def _is_generic_web_novel_topic(self, candidate: str) -> bool:
        normalized = self._normalize_book_name(candidate)
        generic_topics = {
            "网文",
            "小说",
            "爽文",
            "修仙文",
            "玄幻文",
            "都市文",
            "娱乐圈文",
            "娱乐文",
            "明星文",
            "综艺文",
            "男频文",
            "女频文",
            "美食文",
            "旅行题材",
            "旅游题材",
            "公路文",
        }
        return normalized in {self._normalize_book_name(topic) for topic in generic_topics}

    def _is_example_title_marker(self, question: str, title_start: int) -> bool:
        prefix = (question or "")[max(0, title_start - 16):title_start]
        return any(marker in prefix for marker in (
            "书名示例",
            "书名示范",
            "标题示例",
            "题名示例",
            "暂定书名",
            "暂命名",
            "示例",
            "例如",
            "比如",
        ))

    def _analysis_type(self, request: KnowledgeChatRequest) -> str | None:
        mode = (request.mode or "").strip().lower()
        if mode in {"deconstruct", "theme", "trend_theme"}:
            return "theme" if mode == "trend_theme" else mode
        return None

    async def _classify_question(
        self,
        request: KnowledgeChatRequest,
        domain_decision: IntentDecision | None = None,
    ) -> dict[str, Any]:
        fallback = self._rule_based_decision(request)
        if domain_decision is not None:
            fallback = self._merge_domain_decision_into_legacy_fallback(request, domain_decision, fallback)
        if fallback.get("inScope") is False:
            return fallback
        if fallback.get("bookName"):
            return fallback
        if request.bookId is not None or request.selectedCandidate is not None or (request.bookName and request.bookName.strip()):
            return fallback
        if fallback.get("intent") == "trend_research":
            return fallback
        if (
            fallback.get("intent") == "creative_advice"
            and domain_decision is not None
            and self._is_authoritative_domain_decision(domain_decision)
            and domain_decision.primaryIntent in {Intent.outline_building, Intent.chapter_outline}
        ):
            return fallback
        try:
            result = await self._provider_invoke(
                messages=self._build_intent_messages(request, fallback),
                model=settings.default_model,
                temperature=0,
                max_tokens=240,
                require_json=True,
                timeout_millis=settings.timeout_millis,
            )
            parsed = json.loads(str(result.get("content") or "{}"))
            return self._normalize_decision(parsed, fallback)
        except Exception:
            return fallback

    def _merge_domain_decision_into_legacy_fallback(
        self,
        request: KnowledgeChatRequest,
        domain_decision: IntentDecision,
        fallback: dict[str, Any],
    ) -> dict[str, Any]:
        if domain_decision.primaryIntent is Intent.out_of_scope:
            notes = set(domain_decision.routingNotes or [])
            if "rule:oos-domain" in notes or fallback.get("inScope") is False:
                return {"inScope": False, "intent": "out_of_scope", "bookName": None}
            return fallback
        if not self._is_authoritative_domain_decision(domain_decision):
            return fallback
        merged = dict(fallback)
        if not merged.get("bookName"):
            merged["intent"] = self._legacy_intent_for_domain_decision(domain_decision, request)
        merged["needsStructuredRank"] = bool(domain_decision.toolNeeds.needsRankData)
        merged["needsVectorEvidence"] = bool(domain_decision.toolNeeds.needsVectorEvidence)
        merged["needsCreativeAdvice"] = bool(domain_decision.toolNeeds.needsCreativeGeneration)
        return merged

    def _is_authoritative_domain_decision(self, decision: IntentDecision) -> bool:
        notes = set(decision.routingNotes or [])
        return float(decision.confidence or 0.0) >= 0.75 and not any(note.startswith("example:") for note in notes)

    def _rule_based_decision(self, request: KnowledgeChatRequest) -> dict[str, Any]:
        question = (request.question or "").strip()
        if self._is_obviously_out_of_scope(question):
            return {"inScope": False, "intent": "out_of_scope", "bookName": None}
        book_name = None if self._is_trend_question(question) else self._resolve_book_name_by_rules(request)
        has_explicit_book_context = (
            request.bookId is not None
            or request.selectedCandidate is not None
            or book_name is not None
        )
        if has_explicit_book_context:
            intent = "single_book_research"
        elif self._is_creative_advice_question(question):
            intent = "creative_advice"
        else:
            intent = self._route_intent(request)
        return {"inScope": True, "intent": intent, "bookName": book_name}

    def _normalize_decision(self, raw: dict[str, Any], fallback: dict[str, Any]) -> dict[str, Any]:
        allowed_intents = {
            "single_book_research",
            "book_resolution",
            "trend_research",
            "creative_advice",
            "answer_question",
            "out_of_scope",
        }
        in_scope = bool(raw.get("inScope", fallback.get("inScope", True)))
        intent = str(raw.get("intent") or fallback.get("intent") or "answer_question").strip()
        if intent not in allowed_intents:
            intent = str(fallback.get("intent") or "answer_question")
        if not in_scope:
            intent = "out_of_scope"
        book_name = raw.get("bookName")
        if book_name is None:
            book_name = fallback.get("bookName")
        book_name = str(book_name or "").strip() or None
        answer_boundary = str(raw.get("answerBoundary") or fallback.get("answerBoundary") or "").strip()
        allowed_boundaries = {
            "structured_fact",
            "evidence_grounded",
            "evidence_plus_inference",
            "evidence_plus_author_inference",
            "creative_inference",
            "needs_more_data",
            "out_of_scope",
        }
        if answer_boundary not in allowed_boundaries:
            answer_boundary = self._default_answer_boundary(intent)
        return {
            "inScope": in_scope,
            "intent": intent,
            "bookName": book_name,
            "needsStructuredRank": self._bool_decision(raw, fallback, "needsStructuredRank"),
            "needsVectorEvidence": self._bool_decision(raw, fallback, "needsVectorEvidence", default=True),
            "needsCreativeAdvice": self._bool_decision(raw, fallback, "needsCreativeAdvice"),
            "answerBoundary": answer_boundary,
        }

    def _bool_decision(
        self,
        raw: dict[str, Any],
        fallback: dict[str, Any],
        key: str,
        *,
        default: bool = False,
    ) -> bool:
        if key in raw:
            return bool(raw.get(key))
        if key in fallback:
            return bool(fallback.get(key))
        return default

    def _default_answer_boundary(self, intent: str) -> str:
        if intent == "rank_lookup":
            return "structured_fact"
        if intent == "trend_research":
            return "evidence_plus_author_inference"
        if intent == "creative_advice":
            return "creative_inference"
        if intent == "out_of_scope":
            return "out_of_scope"
        return "evidence_grounded"

    def _resolve_book_name_by_rules(self, request: KnowledgeChatRequest) -> str | None:
        if request.bookName and request.bookName.strip():
            return request.bookName.strip()
        question = request.question or ""
        bracket_match = re.search(r"[《【(](.{1,80}?)[》】)]", question)
        if bracket_match and not self._is_example_title_marker(question, bracket_match.start()):
            return bracket_match.group(1).strip()
        plain_book_name = self._extract_plain_book_name(question)
        if plain_book_name:
            return plain_book_name
        match = re.search(r"^([^《【]{2,80}?)(?:的|这本|这部)", request.question or "")
        if match:
            return match.group(1).strip()
        return None

    def _is_obviously_out_of_scope(self, question: str) -> bool:
        if not question:
            return False
        out_keywords = (
            "怎么做",
            "做法",
            "菜谱",
            "食谱",
            "炒菜",
            "家常",
            "配方",
            "教程",
            "番茄炒蛋",
            "怎么煮",
            "怎么炖",
            "天气",
            "股票",
            "基金",
            "汇率",
            "航班",
            "酒店",
            "旅游",
            "旅行",
            "攻略",
            "行程",
            "景点",
            "路线",
            "机票",
            "吃什么",
            "吃啥",
            "外卖",
            "点外卖",
            "餐厅",
            "菜谱",
            "感冒",
            "法律咨询",
            "合同",
            "借款合同",
            "律师",
            "诉讼",
            "起诉",
            "合同模板",
            "新闻",
            "今日新闻",
            "实时新闻",
            "国际新闻",
            "热搜",
            "娱乐",
            "八卦",
            "明星",
            "综艺",
            "电影",
            "电视剧",
            "体育",
            "比赛",
            "财经",
            "政治",
            "Python",
            "Java",
            "Docker",
            "接口",
            "代码",
            "编程",
            "数据库",
        )
        has_out_keyword = any(keyword in question for keyword in out_keywords)
        if not has_out_keyword:
            return False
        return not self._has_web_novel_context(question)

    def _is_creative_advice_question(self, question: str) -> bool:
        creative_keywords = (
            "怎么写",
            "怎么设计",
            "如何设计",
            "开局",
            "开头",
            "爽点",
            "金手指",
            "人设",
            "大纲",
            "节奏",
            "冲突",
        )
        return self._has_web_novel_keyword(question) and any(keyword in question for keyword in creative_keywords)

    def _has_web_novel_keyword(self, question: str) -> bool:
        return any(keyword in question for keyword in (
            "网文",
            "小说",
            "男频",
            "女频",
            "玄幻",
            "修仙",
            "都市",
            "番茄",
            "起点",
            "爽文",
            "美食文",
            "旅行题材",
            "旅游题材",
            "公路文",
            "娱乐圈文",
            "娱乐文",
            "明星文",
            "综艺文",
            "开篇",
            "主角",
            "剧情",
            "题材",
        ))

    def _has_web_novel_context(self, question: str) -> bool:
        if not self._has_web_novel_keyword(question):
            return False
        if any(keyword in question for keyword in (
            "番茄小说",
            "番茄网文",
            "番茄榜",
            "番茄书",
        )):
            return True
        return "番茄" not in question

    def _build_intent_messages(self, request: KnowledgeChatRequest, fallback: dict[str, Any]) -> list[dict[str, str]]:
        conversation_context = self._format_conversation_context(request)
        return [
            {
                "role": "system",
                "content": (
                    "You classify intent for a web-novel research assistant. "
                    "Only web-novel writing, book analysis, ranking trends, and knowledge-base Q&A are in scope. "
                    "Return JSON only with fields: inScope(boolean), intent(string), bookName(string|null), "
                    "needsStructuredRank(boolean), needsVectorEvidence(boolean), needsCreativeAdvice(boolean), "
                    "answerBoundary(string). "
                    "Allowed intents: single_book_research, book_resolution, trend_research, creative_advice, answer_question, out_of_scope. "
                    "Allowed answerBoundary values: structured_fact, evidence_grounded, evidence_plus_inference, "
                    "evidence_plus_author_inference, creative_inference, needs_more_data, out_of_scope. "
                    "Non-web-novel questions must use inScope=false and intent=out_of_scope."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"question: {request.question}\n"
                    f"explicit bookName: {request.bookName or ''}\n"
                    f"conversation context:\n{conversation_context}\n"
                    f"rule fallback: {json.dumps(fallback, ensure_ascii=False)}"
                ),
            },
        ]

    def _build_domain_intent_messages(
        self,
        request: KnowledgeChatRequest,
        rule_decision: IntentDecision,
    ) -> list[dict[str, str]]:
        conversation_context = self._format_conversation_context(request)
        contract = {
            "primaryIntent": "market_scan",
            "subIntents": ["opening_strategy"],
            "entities": {},
            "missingSlots": [],
            "toolNeeds": {},
            "sourcePolicy": {},
            "memoryPolicy": {},
            "confidence": 0.0,
            "routingNotes": [],
        }
        return [
            {
                "role": "system",
                "content": (
                    "You classify intent for Noval, a web-novel writing agent. "
                    "Return JSON only. Do not answer the user. "
                    "Allowed primaryIntent/subIntents values: market_scan, opening_strategy, book_breakdown, "
                    "outline_building, chapter_outline, inspiration_expand, character_design, worldbuilding, "
                    "revision_advice, followup_context, mixed_creation_research, out_of_scope. "
                    "Allowed answerBoundary values: market_evidence, market_evidence_plus_author_inference, "
                    "book_evidence_plus_craft_extraction, creative_inference, outline_generation, "
                    "needs_more_data, out_of_scope. "
                    "Use out_of_scope only for non-web-novel questions. "
                    f"JSON contract example: {json.dumps(contract, ensure_ascii=False)}"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"question: {request.question}\n"
                    f"explicit bookName: {request.bookName or ''}\n"
                    f"conversation context:\n{conversation_context}\n"
                    f"rule decision: {json.dumps(rule_decision.model_dump(mode='json'), ensure_ascii=False)}"
                ),
            },
        ]

    async def _compose_creative_answer(self, request: KnowledgeChatRequest) -> tuple[str, bool]:
        try:
            result = await self._provider_invoke(
                messages=self._build_creative_messages(request),
                model=settings.default_model,
                temperature=0.4,
                max_tokens=self._creative_max_tokens(request),
                require_json=False,
                timeout_millis=self._request_timeout_millis(request),
            )
            content = str(result.get("content") or "").strip()
            if content:
                return content, False
        except Exception:
            pass
        return (
            "模型暂时不可用，我先按网文创作方向给出一个简版建议：先明确主角短期目标，再安排高频反馈的阻力和代价，让爽点从目标推进中自然出现。",
            True,
        )

    async def _compose_answer(
        self,
        request: KnowledgeChatRequest,
        sources: list[KnowledgeSource],
        answer_mode: str | None = None,
        state: ResearchState | None = None,
    ) -> tuple[str, bool]:
        resolved_mode = answer_mode or self._answer_mode(request, sources, "", state=state)
        messages = self._build_answer_messages(
            request,
            sources,
            resolved_mode,
            state=state,
        )
        try:
            result = await self._provider_invoke(
                messages=messages,
                model=settings.default_model,
                temperature=0.2,
                max_tokens=self._answer_max_tokens(request, resolved_mode),
                require_json=False,
                timeout_millis=self._request_timeout_millis(request),
            )
            content = str(result.get("content") or "").strip()
            if content:
                content = self._postprocess_answer_for_mode(content, sources, resolved_mode)
                return content, False
        except Exception:
            pass
        return self._compose_fallback_answer(request.question, sources, answer_mode=resolved_mode), True

    def _postprocess_answer_for_mode(
        self,
        answer: str,
        sources: list[KnowledgeSource],
        answer_mode: str | None,
    ) -> str:
        if answer_mode == "trend":
            return self._ensure_rank_lead_for_trend_answer(answer, sources)
        if answer_mode == "mixed_creation":
            return self._ensure_rank_lead_for_mixed_answer(answer, sources)
        return answer

    def _compose_fallback_answer(
        self,
        question: str,
        sources: list[KnowledgeSource],
        *,
        answer_mode: str | None = None,
    ) -> str:
        if answer_mode == "mixed_creation":
            return self._compose_mixed_creation_fallback_answer(question, sources)
        rank_lead = self._rank_lead_sentence(sources)
        evidence_points: list[str] = []
        for index, source in enumerate(sources[:3], start=1):
            title = source.title or source.bookName or "未命名材料"
            preview = self._short_text((source.preview or "").strip(), 220)
            if preview:
                evidence_points.append(f"- {title}: {preview}[{index}]")
            else:
                evidence_points.append(f"- {title}: 该材料与问题相关，但缺少可展开的摘要。[{index}]")
        evidence = "\n".join(evidence_points) if evidence_points else "- 当前没有可引用材料。"
        first_citation = "[1]" if sources else ""
        return (
            f"## 结论\n"
            f"{rank_lead or f'当前只能基于已检索材料给出保守回答：{self._short_text(question, 180)}。{first_citation}'}\n\n"
            f"## 证据\n"
            f"{evidence}\n\n"
            f"## 作者侧建议\n"
            f"- 以上结论只覆盖已命中的材料；如果要做开文判断，应继续补充同题材榜单、简介和前几章样本再扩展判断。{first_citation}"
        )
        lead = f"模型暂时不可用，先基于已检索证据回答：{question}。"
        points: list[str] = []
        for index, source in enumerate(sources[:3], start=1):
            title = source.title or source.bookName or "未知标题"
            preview = (source.preview or "").strip()
            if preview:
                points.append(f"{title}：{preview}[{index}]")
            else:
                points.append(f"{title}提供了相关证据[{index}]")
        return lead + " " + " ".join(points)

    def _build_answer_messages(
        self,
        request: KnowledgeChatRequest,
        sources: list[KnowledgeSource],
        answer_mode: str | None = None,
        state: ResearchState | None = None,
    ) -> list[dict[str, str]]:
        mode = answer_mode or self._answer_mode(request, sources, "", state=state)
        evidence_lines: list[str] = []
        for index, source in enumerate(sources[:8], start=1):
            title = source.title or source.bookName or f"source {index}"
            book_name = source.bookName or "unknown book"
            source_type = source.sourceType or "unknown"
            chapter = f"chapter {source.chapterNo}" if source.chapterNo is not None else "no chapter number"
            preview = self._source_material_for_prompt(source)
            rank_meta_parts = [
                f"rankNo: {source.rankNo}" if source.rankNo is not None else None,
                f"snapshotId: {source.snapshotId}" if source.snapshotId is not None else None,
                f"snapshotTime: {source.snapshotTime}" if source.snapshotTime else None,
                f"channelCode: {source.channelCode}" if source.channelCode else None,
                f"boardCode: {source.boardCode}" if source.boardCode else None,
            ]
            rank_meta = ", ".join(part for part in rank_meta_parts if part)
            evidence_lines.append(
                f"[{index}] book: {book_name}\n"
                f"title: {title}\n"
                f"sourceType: {source_type}, {chapter}"
                + (f", {rank_meta}" if rank_meta else "")
                + "\n"
                f"material: {preview}"
            )
        evidence = "\n\n".join(evidence_lines)
        format_rule = self._answer_format_rule(mode)
        runtime_skills = (state or {}).get("skill_prompt") if state is not None else ""
        specialist_plan = self._format_specialist_plan(state) if state is not None else "(none)"
        answer_policy = self._answer_policy_block(mode, sources, state)
        return [
            {
                "role": "system",
                "content": (
                    "You are a web-novel research Q&A assistant. "
                    "Only answer questions about web-novel writing, book analysis, ranking trends, and knowledge-base Q&A. "
                    "Use only the supplied evidence and conversation context for factual claims. "
                    "Do not invent plots, rankings, market data, or conclusions that are not supported by evidence. "
                    "Answer the user's actual question first, then give author/editor oriented analysis. "
                    "Factual claims from the knowledge base must end with citation markers such as [1] or [1][2]. "
                    "Author-side inference, opportunity suggestions, and creative advice may be uncited only when clearly labeled as author-side inference/advice, and must not imply they are facts from source material. "
                    "If a claim cannot be tied to the numbered evidence, omit it or state what evidence is missing."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"answerMode: {mode}\n"
                    f"format rule:\n{format_rule}\n\n"
                    f"answer policy:\n{answer_policy}\n\n"
                    f"runtime skills:\n{runtime_skills or '(none)'}\n\n"
                    f"specialist agent plan:\n{specialist_plan}\n\n"
                    f"conversation context:\n{self._format_conversation_context(request)}\n\n"
                    f"current question:\n{request.question}\n\n"
                    f"evidence:\n{evidence}"
                ),
            },
        ]

    def _answer_policy_block(
        self,
        answer_mode: str,
        sources: list[KnowledgeSource],
        state: ResearchState | None,
    ) -> str:
        state_payload = state or {}
        source_policy = dict(state_payload.get("source_policy") or {})
        supervisor = dict(state_payload.get("supervisor") or {})
        evidence_pack = self.evidence_pack_builder.from_sources(
            sources,
            inference_signals=self._inference_signals_for_trace(state_payload),
        ).summary(max_items=0)
        context_bundle = state_payload.get("context_bundle")
        context_payload: dict[str, Any] | str
        if isinstance(context_bundle, ContextBundle):
            context_payload = context_bundle.model_dump(mode="json", exclude_none=True)
        else:
            context_payload = "(none)"
        boundary_rules: list[str] = []
        if answer_mode == "mixed_creation":
            boundary_rules.append("boundaryRule: separate cited market evidence from author-side recommendations")
        if source_policy.get("freshness") == "time_window":
            boundary_rules.append("boundaryRule: state the historical time window before trend conclusions")
        elif source_policy.get("freshness") == "latest":
            boundary_rules.append("boundaryRule: latest market facts require snapshotTime and citations")
        if not sources:
            boundary_rules.append("boundaryRule: do not invent market or book facts without evidence")
        return "\n".join([
            *(boundary_rules or ["boundaryRule: cite factual evidence and label uncited author-side inference"]),
            f"sourcePolicy: {json.dumps(source_policy, ensure_ascii=False)}",
            f"supervisorDecision: {json.dumps(supervisor, ensure_ascii=False)}",
            f"evidencePack: {json.dumps(evidence_pack, ensure_ascii=False)}",
            f"contextBundle: {json.dumps(context_payload, ensure_ascii=False)}",
            f"selectedSkillPrompt: {'present' if state_payload.get('skill_prompt') else 'none'}",
        ])

    def _source_material_for_prompt(self, source: KnowledgeSource) -> str:
        source_type = (source.sourceType or "").upper()
        if source_type in {"CHAPTER_PACK", "ANALYSIS"}:
            return (source.material or source.preview or "").strip()
        return (source.preview or "").strip()

    def _answer_diagnostics(self, sources: list[KnowledgeSource], answer: str) -> dict[str, Any]:
        citation_numbers = {
            int(match)
            for match in re.findall(r"\[(\d+)\]", answer or "")
            if match.isdigit()
        }
        scores = [float(source.score) for source in sources if source.score is not None]
        source_types = sorted({
            str(source.sourceType or "unknown").upper()
            for source in sources
        })
        return {
            "ragUsed": bool(sources),
            "sourceCount": len(sources),
            "citationCount": len(citation_numbers),
            "citationSatisfied": (not sources) or bool(citation_numbers),
            "maxSourceScore": max(scores) if scores else None,
            "minSourceScore": min(scores) if scores else None,
            "sourceTypes": source_types,
        }

    def _has_valid_citation(self, answer: str, source_count: int) -> bool:
        if source_count <= 0:
            return False
        citation_numbers = [
            int(match)
            for match in re.findall(r"\[(\d+)\]", answer or "")
            if match.isdigit()
        ]
        return any(1 <= citation <= source_count for citation in citation_numbers)

    def _has_claim_level_citations(self, answer: str, source_count: int) -> bool:
        if source_count <= 0:
            return False
        factual_markers = (
            "榜",
            "排名",
            "Top",
            "top",
            "题材",
            "趋势",
            "赛道",
            "作品",
            "作者",
            "数据",
            "证据",
            "新书",
            "男频",
            "女频",
            "claim",
            "trend",
            "rank",
        )
        for raw_line in re.split(r"[\n。！？!?.]+", answer or ""):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if re.search(r"\[(\d+)\]", line):
                continue
            if any(marker in line for marker in factual_markers):
                return False
        return True

    def _answer_mode(
        self,
        request: KnowledgeChatRequest,
        sources: list[KnowledgeSource],
        intent: str | None = None,
        *,
        state: ResearchState | None = None,
    ) -> str:
        normalized_intent = (intent or "").strip()
        question = request.question or ""
        if normalized_intent == "creative_advice":
            return "creative"
        if self._is_mixed_creation_state(state):
            return "mixed_creation"
        if normalized_intent == "trend_research" or self._is_trend_question(question):
            return "trend"
        if request.bookId is not None or request.selectedCandidate is not None or request.bookName:
            return "single_book"
        book_ids = {source.bookId for source in sources if source.bookId is not None}
        if len(book_ids) == 1:
            return "single_book"
        return "general_evidence"

    def _answer_status(self, answer_mode: str, sources: list[KnowledgeSource], intent: str | None = None) -> str:
        if answer_mode == "creative":
            return "creative_answer"
        if not sources:
            return "needs_data"
        if answer_mode in {"trend", "mixed_creation"} or str(intent or "") == "trend_research":
            return "partial_answer"
        return "answered_with_evidence"

    def _answer_boundary(
        self,
        answer_mode: str,
        sources: list[KnowledgeSource],
        intent: str | None = None,
        classifier_boundary: str | None = None,
    ) -> str:
        if sources and classifier_boundary:
            return classifier_boundary
        if answer_mode == "creative":
            return "creative_inference"
        if not sources:
            return "needs_more_data"
        if answer_mode == "mixed_creation":
            return "evidence_plus_author_inference"
        if answer_mode == "trend" or str(intent or "") == "trend_research":
            return "evidence_plus_author_inference"
        return "evidence_grounded"

    def _answer_format_rule(self, answer_mode: str) -> str:
        if answer_mode == "mixed_creation":
            return (
                "Use a rank-first evidence + creative generation structure. "
                "Start with cited current rank facts, then produce the requested author-side plan. "
                "For chapter outline requests, include a chapter outline section with concrete beats, conflicts, hooks, and escalation. "
                "Suggested Chinese sections: ## 榜单依据, ## 对标拆解, ## 题材定位, ## 细纲/大纲方案, ## 风险修正. "
                "Cite factual rank/source claims; clearly label uncited outline suggestions as author-side inference."
            )
        if answer_mode == "trend":
            return (
                "Use these Chinese markdown sections exactly: "
                "## 结论, ## 证据, ## 开文机会, ## 风险与规避. "
                "If RANK evidence is present, the conclusion must start from the rank #1 book and TopN list before vector or chapter evidence. "
                "The opportunity and risk sections should be practical for professional web-novel authors."
            )
        if answer_mode == "single_book":
            return (
                "Use these Chinese markdown sections exactly: "
                "## 直接回答, ## 证据依据, ## 写法拆解, ## 可借鉴点. "
                "Focus on extracting repeatable craft techniques from the cited material."
            )
        if answer_mode == "creative":
            return (
                "When the user asks for an outline, chapter outline, book-opening plan, character arc, "
                "or long-form writing plan, write a complete, substantial Chinese draft rather than a short note. "
                "Use sections that fit the task, such as ## 核心定位, ## 卖点钩子, ## 完整大纲, "
                "## 分卷/分章推进, ## 角色线, ## 爽点节奏, ## 风险修正. "
                "Do not cite nonexistent sources and do not present creative suggestions as knowledge-base evidence."
            )
        return (
            "Use these Chinese markdown sections when useful: "
            "## 结论, ## 证据, ## 作者侧建议. Keep unsupported claims out."
        )

    def _creative_max_tokens(self, request: KnowledgeChatRequest) -> int:
        return LONG_CREATIVE_ANSWER_MAX_TOKENS if self._needs_long_creative_output(request) else CREATIVE_ANSWER_MAX_TOKENS

    def _answer_max_tokens(self, request: KnowledgeChatRequest, answer_mode: str | None) -> int:
        if self._needs_long_creative_output(request):
            return LONG_CREATIVE_ANSWER_MAX_TOKENS
        if answer_mode == "trend":
            return TREND_ANSWER_MAX_TOKENS
        if answer_mode in {"creative", "mixed_creation"}:
            return CREATIVE_ANSWER_MAX_TOKENS
        return EVIDENCE_ANSWER_MAX_TOKENS

    def _is_mixed_creation_state(self, state: ResearchState | None) -> bool:
        if not state:
            return False
        decision = state.get("intent_decision") or {}
        if not isinstance(decision, dict):
            return False
        if str(decision.get("primaryIntent") or "") != "mixed_creation_research":
            return False
        tool_needs = decision.get("toolNeeds") or {}
        if isinstance(tool_needs, dict) and tool_needs.get("needsCreativeGeneration"):
            return True
        sub_intents = decision.get("subIntents") or []
        creative_sub_intents = {
            "opening_strategy",
            "outline_building",
            "chapter_outline",
            "inspiration_expand",
            "character_design",
            "worldbuilding",
            "revision_advice",
        }
        return any(str(intent) in creative_sub_intents for intent in sub_intents)

    def _needs_long_creative_output(self, request: KnowledgeChatRequest) -> bool:
        question = request.question or ""
        long_markers = (
            "大纲",
            "卷纲",
            "细纲",
            "章节纲",
            "分章",
            "前100章",
            "前三章",
            "三卷",
            "五卷",
            "完整",
            "长篇",
            "30万字",
            "50万字",
            "100万字",
            "开书方案",
            "开文方案",
        )
        return any(marker in question for marker in long_markers)

    def _ensure_rank_lead_for_trend_answer(self, answer: str, sources: list[KnowledgeSource]) -> str:
        lead = self._rank_lead_sentence(sources)
        if not lead:
            return answer
        first_rank = self._first_rank_source(sources)
        if first_rank and first_rank.bookName and first_rank.bookName in answer:
            return answer
        return self._compose_rank_first_trend_answer(sources)

    def _ensure_rank_lead_for_mixed_answer(self, answer: str, sources: list[KnowledgeSource]) -> str:
        lead = self._rank_lead_sentence(sources)
        if not lead:
            return answer
        first_rank = self._first_rank_source(sources)
        if first_rank and first_rank.bookName and first_rank.bookName in answer:
            return answer
        return f"## 榜单依据\n{lead}\n\n{answer}"

    def _compose_mixed_creation_fallback_answer(self, question: str, sources: list[KnowledgeSource]) -> str:
        rank_lead = self._rank_lead_sentence(sources)
        rank_lines: list[str] = []
        for index, source in enumerate(sources, start=1):
            if (source.sourceType or "").upper() != "RANK":
                continue
            rank_no = source.rankNo or index
            title = source.title or source.category or "榜单"
            book_name = source.bookName or "未命名作品"
            preview = self._short_text(source.preview or "", 110)
            suffix = f"：{preview}" if preview else ""
            rank_lines.append(f"- #{rank_no}《{book_name}》：{title}{suffix}[{index}]")
            if len(rank_lines) >= 3:
                break
        if not rank_lines:
            rank_lines.append("- 当前没有可展开的前排榜单作品明细。")
        first_rank = self._first_rank_source(sources)
        reference_title = first_rank.bookName if first_rank and first_rank.bookName else "当前榜单前排作品"
        return (
            "## 榜单依据\n"
            f"{rank_lead or '当前只能基于已检索材料给出保守判断。'}\n"
            + "\n".join(rank_lines)
            + "\n\n"
            "## 对标拆解\n"
            f"- 以《{reference_title}》的可见卖点为锚点：先抓住现实压力、身份反差或异常能力触发，再把金手指转成持续可升级的章节任务。[1]\n"
            "- 前排样本只负责提供市场方向，具体设定属于作者侧推演，不能当成榜单事实。\n\n"
            "## 细纲方案\n"
            f"- 第1章：围绕“{self._short_text(question, 60)}”拆出主角当下困境，结尾给出异常能力或身份反差钩子。\n"
            "- 第2章：让金手指第一次解决现实痛点，同时制造旁人误判和外部阻力。\n"
            "- 第3章：用一次可传播事件放大收益，形成新目标、新敌人和下一阶段升级线。\n\n"
            "## 风险修正\n"
            "- 不要只复制榜一表层设定；要复用的是钩子结构、爽点节奏和题材信号。\n"
            "- 若要继续扩展到20章细纲，需要补充目标读者、主角职业、金手指边界和反派压力。"
        )

    def _compose_rank_first_trend_answer(self, sources: list[KnowledgeSource]) -> str:
        lead = self._rank_lead_sentence(sources)
        rank_sources = [
            source
            for source in sources
            if (source.sourceType or "").upper() == "RANK"
        ]
        intro_sources = [
            source
            for source in sources
            if (source.sourceType or "").upper() != "RANK"
        ]
        book_lines: list[str] = []
        for index, source in enumerate(rank_sources[:5], start=1):
            rank_no = source.rankNo or index
            title = source.title or source.category or "榜单"
            author = f"，作者{source.author}" if source.author else ""
            book_name = source.bookName or "未命名作品"
            preview = self._short_text(source.preview or "", 120)
            suffix = f"；{preview}" if preview else ""
            book_lines.append(f"- #{rank_no}《{book_name}》{author}：{title}{suffix}[{index}]")
        if not book_lines:
            book_lines.append("- 当前没有可展开的榜单作品明细。")
        next_index = len(rank_sources[:5]) + 1
        evidence_lines = list(book_lines)
        for offset, source in enumerate(intro_sources[:2], start=next_index):
            title = source.title or source.bookName or "补充材料"
            preview = self._short_text(source.preview or "", 160)
            if preview:
                evidence_lines.append(f"- {title}: {preview}[{offset}]")
        return (
            "## 结论\n"
            f"{lead}\n\n"
            "## 榜单证据\n"
            + "\n".join(evidence_lines)
            + "\n\n"
            "## 开书建议\n"
            "- 优先围绕榜单第一和前排作品提炼题材钩子，不再用低排名旧向量结果代替当前榜单结论。\n"
            "- 可从身份反差、都市曝光、长生/特殊能力与现代生活冲突切入，前三章先完成身份钩子、危机触发和金手指展示。\n\n"
            "## 风险与规避\n"
            "- 榜单会变化，结论只代表当前结构化榜单证据；章节和简介证据用于辅助拆题，不应覆盖榜单排名事实。"
        )

    def _rank_lead_sentence(self, sources: list[KnowledgeSource]) -> str | None:
        first_rank = self._first_rank_source(sources)
        if not first_rank or not first_rank.bookName:
            return None
        rank_label = first_rank.title or "榜单"
        rank_no = first_rank.rankNo or 1
        author = f"，作者{first_rank.author}" if first_rank.author else ""
        return f"当前结构化榜单证据显示，{rank_label}第{rank_no}名是《{first_rank.bookName}》{author}。[1]"

    def _first_rank_source(self, sources: list[KnowledgeSource]) -> KnowledgeSource | None:
        for source in sources:
            if (source.sourceType or "").upper() == "RANK":
                return source
        return None

    def _format_conversation_context(self, request: KnowledgeChatRequest) -> str:
        parts: list[str] = [
            f"current question: {self._short_text(request.question or '', 2000)}"
        ]
        sticky_context = self._build_sticky_context(request)
        if sticky_context:
            parts.append(f"sticky context:\n{sticky_context}")
        summary = (request.contextSummary or "").strip()
        if summary:
            protected_chars = sum(len(part) for part in parts) + HISTORY_PROMPT_MAX_CHARS + 256
            summary_budget = max(1, min(CONTEXT_SUMMARY_PROMPT_CHARS, CONVERSATION_CONTEXT_PROMPT_CHARS - protected_chars))
            parts.append(f"compressed summary:\n{self._short_text(summary, summary_budget)}")
        history_lines: list[str] = []
        remaining_history_chars = min(
            HISTORY_PROMPT_MAX_CHARS,
            max(HISTORY_PROMPT_CHARS, CONVERSATION_CONTEXT_PROMPT_CHARS - sum(len(part) for part in parts) - 64),
        )
        history_messages = list(request.history or [])[-HISTORY_PROMPT_MESSAGES:]
        per_message_chars = max(
            1,
            min(HISTORY_PROMPT_CHARS, remaining_history_chars // max(len(history_messages), 1)),
        )
        for message in history_messages:
            role = str(message.get("role") or "user").strip()
            if role not in {"user", "assistant"}:
                role = "user"
            content = self._short_text(str(message.get("content") or ""), per_message_chars)
            if content:
                history_lines.append(f"{role}: {content}")
        if history_lines:
            parts.append("recent history:\n" + "\n".join(history_lines))
        if not parts:
            return "(no prior context)"
        return self._short_text("\n\n".join(parts), CONVERSATION_CONTEXT_PROMPT_CHARS)

    def _build_sticky_context(self, request: KnowledgeChatRequest) -> str:
        source_text = self._format_context_for_sticky_extraction(
            "\n".join([
                request.question or "",
                request.contextSummary or "",
                "\n".join(str(message.get("content") or "") for message in request.history or []),
            ])
        )
        sticky_lines: list[str] = []
        if request.bookName:
            sticky_lines.append(f"current book: {request.bookName}")
        labels = [
            "当前作品",
            "最近意图",
            "最近用户目标",
            "上一轮结论",
            "current book",
            "current goal",
            "previous goal",
        ]
        for label in labels:
            value = self._extract_context_slot(request.contextSummary, (label,))
            if value:
                sticky_lines.append(f"{label}: {value}")
        keyword_groups = [
            ("频道", ("男频", "女频")),
            ("榜单", ("新书榜", "热榜", "榜单", "扫榜")),
            ("题材", ("都市脑洞", "都市高武", "东方仙侠", "玄幻", "修仙", "同人", "衍生", "民国", "古风")),
            ("创作任务", ("开文", "开书", "大纲", "细纲", "开局", "开篇", "爽点", "金手指", "角色线")),
        ]
        for label, keywords in keyword_groups:
            hits = [keyword for keyword in keywords if keyword in source_text]
            if hits:
                sticky_lines.append(f"{label}: {'、'.join(hits)}")
        return self._short_text("\n".join(self._dedupe(sticky_lines)), STICKY_CONTEXT_CHARS)

    def _format_context_for_sticky_extraction(self, value: str) -> str:
        return (value or "").replace("\r\n", "\n").replace("\\n", "\n")

    def _attach_memory_metadata(self, response: KnowledgeChatResponse, request: KnowledgeChatRequest) -> None:
        if request.conversationId:
            response.resultJson["conversationId"] = request.conversationId
        response.resultJson["memorySummary"] = self._build_memory_summary(request, response)

    def _build_memory_summary(self, request: KnowledgeChatRequest, response: KnowledgeChatResponse) -> str:
        parts: list[str] = []
        book_name = response.resultJson.get("bookName") or request.bookName
        if book_name:
            parts.append(f"当前作品：{book_name}")
        intent = response.resultJson.get("intent")
        if intent:
            parts.append(f"最近意图：{intent}")
        question = self._short_text(request.question or "", 220)
        if question:
            parts.append(f"最近用户目标：{question}")
        answer = self._short_text(response.answer or "", MEMORY_SUMMARY_ANSWER_CHARS)
        if answer:
            parts.append(f"上一轮结论：{answer}")
        return self._short_text("\n".join(parts), MEMORY_SUMMARY_CHARS)

    def _short_text(self, value: str, max_length: int) -> str:
        compact = " ".join((value or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."

    def _limit(self, request: KnowledgeChatRequest, key: str, *, default: int, maximum: int) -> int:
        raw = request.limits.get(key, default)
        try:
            parsed = int(raw)
        except (TypeError, ValueError):
            parsed = default
        return max(1, min(parsed, maximum))

    def _request_timeout_millis(self, request: KnowledgeChatRequest) -> int:
        return self._limit(request, "timeoutMillis", default=settings.timeout_millis, maximum=600_000)

    def _tool_timeout_millis(self, request: KnowledgeChatRequest) -> int:
        return self._limit(
            request,
            "toolTimeoutMillis",
            default=settings.backend_tool_timeout_millis,
            maximum=600_000,
        )

    async def _with_tool_timeout(self, awaitable: Any, *, default: Any, request: KnowledgeChatRequest | None = None) -> Any:
        timeout_millis = self._tool_timeout_millis(request) if request is not None else settings.backend_tool_timeout_millis
        try:
            return await asyncio.wait_for(awaitable, timeout=max(0.001, timeout_millis / 1000))
        except TimeoutError:
            return default

    def _dedupe(self, values: list[str]) -> list[str]:
        deduped: list[str] = []
        for value in values:
            if value and value not in deduped:
                deduped.append(value)
        return deduped
