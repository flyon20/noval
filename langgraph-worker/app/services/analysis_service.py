from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncGenerator
from time import perf_counter
from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from app.config import settings
from app.models.analysis import PromptConfigPayload, RunRequest, RunResponse
from app.services.provider_client import OpenAICompatibleProviderClient


class AnalysisState(TypedDict, total=False):
    request: RunRequest
    input_text: str
    use_chunking: bool
    chunks: list[dict[str, Any]]
    chunk_results: list[RunResponse]
    response: RunResponse


class LangGraphAnalysisService:
    def __init__(self, provider_client: OpenAICompatibleProviderClient | None = None) -> None:
        self.provider_client = provider_client or OpenAICompatibleProviderClient()
        self._llm_semaphore = asyncio.Semaphore(settings.max_active_llm_calls)
        self._graph = self._build_graph()

    async def run(self, request: RunRequest) -> RunResponse:
        started_at = perf_counter()
        state = await self._graph.ainvoke({"request": request})
        return self._finalize_response(
            request=request,
            response=state["response"],
            total_duration_ms=self._elapsed_millis(started_at),
        )

    async def stream(self, request: RunRequest) -> AsyncGenerator[dict[str, Any], None]:
        started_at = perf_counter()
        prepared = self._prepare_request(request)
        yield {"event": "start", "taskId": request.taskId, "analysisType": self._analysis_type(request.agentType)}

        if prepared["use_chunking"]:
            chunks = prepared["chunks"]
            chunk_results: list[RunResponse] = []
            for index, chunk in enumerate(chunks, start=1):
                yield {"event": "progress", "message": f"[chunk-progress] {index}/{len(chunks)} 正在分析\n"}
                chunk_response = await self._invoke_once(
                    request=request,
                    input_text=chunk["input_text"],
                    prompt_override=self._build_chunk_prompt(request.promptConfig, index, len(chunks)),
                )
                chunk_results.append(chunk_response)
                yield {"event": "progress", "message": f"[chunk-progress] {index}/{len(chunks)} 已完成\n"}
            merged_response = await self._merge_chunk_results(request, chunk_results)
            merged_response = self._finalize_response(
                request=request,
                response=merged_response,
                total_duration_ms=self._elapsed_millis(started_at),
            )
            yield {"event": "metrics", "metrics": self._runtime_meta(merged_response)}
            yield {"event": "done", "data": merged_response.model_dump()}
            return

        accumulated = ""
        messages = self._build_messages(request.promptConfig, prepared["input_text"], self._analysis_type(request.agentType))
        token_used = 0
        semaphore_started_at = perf_counter()
        async with self._llm_semaphore:
            queue_wait_ms = self._elapsed_millis(semaphore_started_at)
            provider_started_at = perf_counter()
            async for event in self.provider_client.stream(
                messages=messages,
                model=request.promptConfig.modelName or settings.default_model,
                temperature=request.promptConfig.temperature,
                max_tokens=request.promptConfig.maxTokens,
                require_json=self._requires_json(request.promptConfig),
            ):
                if event["event"] == "delta":
                    accumulated += event["delta"]
                    yield event
                elif event["event"] == "done":
                    token_used = int(event.get("tokenUsed") or 0)
            provider_latency_ms = self._elapsed_millis(provider_started_at)
        response = self._build_response(
            request,
            accumulated,
            token_used,
            request.promptConfig.modelName or settings.default_model,
            runtime_meta=self._build_runtime_meta(
                request,
                stream_mode=True,
                use_chunking=False,
                chunk_count=0,
                provider_call_count=1,
                queue_wait_ms=queue_wait_ms,
                provider_latency_ms=provider_latency_ms,
            ),
        )
        response = self._finalize_response(
            request=request,
            response=response,
            total_duration_ms=self._elapsed_millis(started_at),
        )
        yield {"event": "metrics", "metrics": self._runtime_meta(response)}
        yield {"event": "done", "data": response.model_dump()}

    def _build_graph(self):
        graph = StateGraph(AnalysisState)
        graph.add_node("prepare", self._prepare_node)
        graph.add_node("analyze_direct", self._analyze_direct_node)
        graph.add_node("split_chunks", self._split_chunks_node)
        graph.add_node("analyze_chunks", self._analyze_chunks_node)
        graph.add_node("merge_chunks", self._merge_chunks_node)
        graph.set_entry_point("prepare")
        graph.add_conditional_edges("prepare", self._route_after_prepare, {
            "direct": "analyze_direct",
            "chunk": "split_chunks",
        })
        graph.add_edge("analyze_direct", END)
        graph.add_edge("split_chunks", "analyze_chunks")
        graph.add_edge("analyze_chunks", "merge_chunks")
        graph.add_edge("merge_chunks", END)
        return graph.compile()

    async def _prepare_node(self, state: AnalysisState) -> AnalysisState:
        prepared = self._prepare_request(state["request"])
        return {
            "input_text": prepared["input_text"],
            "use_chunking": prepared["use_chunking"],
            "chunks": prepared.get("chunks", []),
        }

    def _route_after_prepare(self, state: AnalysisState) -> str:
        return "chunk" if state.get("use_chunking") else "direct"

    async def _analyze_direct_node(self, state: AnalysisState) -> AnalysisState:
        response = await self._invoke_once(
            request=state["request"],
            input_text=state["input_text"],
            prompt_override=None,
        )
        return {"response": response}

    async def _split_chunks_node(self, state: AnalysisState) -> AnalysisState:
        return {"chunks": state.get("chunks", [])}

    async def _analyze_chunks_node(self, state: AnalysisState) -> AnalysisState:
        request = state["request"]
        chunks = state.get("chunks", [])
        parallelism = max(1, min(int(request.limits.get("chunkParallelism", 2) or 2), 2))
        semaphore = asyncio.Semaphore(parallelism)

        async def run_chunk(index: int, chunk: dict[str, Any]) -> RunResponse:
            async with semaphore:
                return await self._invoke_once(
                    request=request,
                    input_text=chunk["input_text"],
                    prompt_override=self._build_chunk_prompt(request.promptConfig, index, len(chunks)),
                )

        results = await asyncio.gather(*(run_chunk(index, chunk) for index, chunk in enumerate(chunks, start=1)))
        return {"chunk_results": list(results)}

    async def _merge_chunks_node(self, state: AnalysisState) -> AnalysisState:
        response = await self._merge_chunk_results(state["request"], state.get("chunk_results", []))
        return {"response": response}

    def _prepare_request(self, request: RunRequest) -> dict[str, Any]:
        source_payload = request.sourcePayload or {}
        input_text = str(source_payload.get("inputText") or "").strip()
        chapters = source_payload.get("chapters") or []
        max_input_tokens = int(request.limits.get("chunkMaxInputTokens") or 6000)
        target_input_tokens = min(
            max_input_tokens,
            int(request.limits.get("chunkTargetInputTokens") or 3500),
        )
        estimated_tokens = self._estimate_tokens(input_text)
        if chapters and estimated_tokens > max_input_tokens:
            return {
                "input_text": input_text,
                "use_chunking": True,
                "chunks": self._split_book_chunks(source_payload, chapters, target_input_tokens),
            }
        return {"input_text": input_text, "use_chunking": False, "chunks": []}

    async def _invoke_once(self, *, request: RunRequest, input_text: str, prompt_override: str | None) -> RunResponse:
        prompt_config = request.promptConfig.model_copy()
        if prompt_override is not None:
            prompt_config.promptContent = prompt_override
        messages = self._build_messages(prompt_config, input_text, self._analysis_type(request.agentType))
        semaphore_started_at = perf_counter()
        async with self._llm_semaphore:
            queue_wait_ms = self._elapsed_millis(semaphore_started_at)
            provider_started_at = perf_counter()
            result = await self.provider_client.invoke(
                messages=messages,
                model=prompt_config.modelName or settings.default_model,
                temperature=prompt_config.temperature,
                max_tokens=prompt_config.maxTokens,
                require_json=self._requires_json(prompt_config),
            )
            provider_latency_ms = self._elapsed_millis(provider_started_at)
        return self._build_response(
            request,
            result["content"],
            int(result["token_used"]),
            result["model_name"],
            runtime_meta=self._build_runtime_meta(
                request,
                stream_mode=False,
                use_chunking=False,
                chunk_count=0,
                provider_call_count=1,
                queue_wait_ms=queue_wait_ms,
                provider_latency_ms=provider_latency_ms,
            ),
        )

    async def _merge_chunk_results(self, request: RunRequest, chunk_results: list[RunResponse]) -> RunResponse:
        if len(chunk_results) == 1:
            response = chunk_results[0].model_copy(deep=True)
            return self._apply_runtime_meta(
                response,
                self._build_chunk_runtime_meta(request, chunk_results, None),
            )
        merged_input = "\n\n".join(
            f"## 分段 {index}\n{result.content}"
            for index, result in enumerate(chunk_results, start=1)
        )
        merged_prompt = self._build_merge_prompt(request.promptConfig)
        merged = await self._invoke_once(
            request=request,
            input_text=merged_input,
            prompt_override=merged_prompt,
        )
        merged.tokenUsed += sum(result.tokenUsed for result in chunk_results)
        merged.resultJson.setdefault("analysisMode", "chunk_merge")
        merged.resultJson.setdefault("segmentCount", len(chunk_results))
        return self._apply_runtime_meta(
            merged,
            self._build_chunk_runtime_meta(request, chunk_results, merged),
        )

    def _build_messages(self, prompt_config: PromptConfigPayload, input_text: str, analysis_type: str) -> list[dict[str, str]]:
        template = prompt_config.promptContent or "{{content}}"
        if "{{content}}" in template:
            system_prompt = template.replace("{{content}}", "正文内容会在下一条 user message 中提供，请只基于该正文完成分析。")
            system_prompt = self._augment_prompt_for_json(prompt_config, system_prompt)
            return [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": input_text},
            ]
        prompt = template + "\n\n" + input_text
        return [{"role": "user", "content": self._augment_prompt_for_json(prompt_config, prompt)}]

    def _augment_prompt_for_json(self, prompt_config: PromptConfigPayload, prompt: str) -> str:
        if not self._requires_json(prompt_config):
            return prompt
        sections = [prompt, "", "Please output valid JSON only."]
        if prompt_config.outputJsonSchema:
            sections.extend(["output schema:", prompt_config.outputJsonSchema])
        if prompt_config.outputExampleJson:
            sections.extend(["output example:", prompt_config.outputExampleJson])
        return "\n".join(sections)

    def _requires_json(self, prompt_config: PromptConfigPayload) -> bool:
        if prompt_config.outputJsonSchema:
            return True
        if (prompt_config.postProcessType or "").lower() == "json_extract":
            return True
        parse_config = (prompt_config.parseConfigJson or "").lower()
        return '"parser":"json"' in parse_config or '"parser": "json"' in parse_config

    def _build_response(
        self,
        request: RunRequest,
        content: str,
        token_used: int,
        model_name: str,
        runtime_meta: dict[str, Any] | None = None,
    ) -> RunResponse:
        result_json = self._parse_result_json(content)
        analysis_type = self._analysis_type(request.agentType)
        result_json.setdefault("analysisType", analysis_type)
        result_json.setdefault("summary", self._short_text(content, 240))
        result_json.setdefault("detailContent", content)
        if analysis_type == "theme":
            result_json.setdefault("historicalWordCloud", [])
            result_json.setdefault("themeTable", [])
            result_json.setdefault("hotBooks", [])
            result_json.setdefault("insightCards", [])
            result_json.setdefault("snapshotComparisons", [])
            result_json.setdefault("trendPreview", self._short_text(content, 300))
            result_json.setdefault("historyAnalysisCount", len(request.sourcePayload.get("snapshots") or []))
        response = RunResponse(
            taskId=request.taskId,
            modelName=model_name,
            content=content,
            tokenUsed=max(0, token_used),
            resultJson=result_json,
        )
        if runtime_meta:
            response = self._apply_runtime_meta(response, runtime_meta)
        return response

    def _parse_result_json(self, content: str) -> dict[str, Any]:
        cleaned = (content or "").strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.strip("`")
            cleaned = cleaned.replace("json\n", "", 1).strip()
        try:
            parsed = json.loads(cleaned)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass
        return {}

    def _split_book_chunks(self, source_payload: dict[str, Any], chapters: list[dict[str, Any]], target_tokens: int) -> list[dict[str, Any]]:
        book = source_payload.get("book") or {}
        chunks: list[list[dict[str, Any]]] = []
        current: list[dict[str, Any]] = []
        for chapter in chapters:
            candidate = current + [chapter]
            candidate_text = self._build_book_input_text(book, candidate)
            if current and self._estimate_tokens(candidate_text) > target_tokens:
                chunks.append(current)
                current = []
            current.append(chapter)
        if current:
            chunks.append(current)
        return [
            {
                "input_text": self._build_book_input_text(book, chunk),
                "chapters": chunk,
            }
            for chunk in chunks
        ]

    def _build_book_input_text(self, book: dict[str, Any], chapters: list[dict[str, Any]]) -> str:
        parts = [
            f"Book: {book.get('bookName', '')}",
            f"Author: {book.get('author', '')}",
            f"Intro: {book.get('intro', '')}",
        ]
        for chapter in chapters:
            parts.append(f"[{chapter.get('chapterTitle', '')}] {chapter.get('content', '')}")
        return "\n".join(parts)

    def _build_chunk_prompt(self, prompt_config: PromptConfigPayload, chunk_index: int, chunk_count: int) -> str:
        original = (prompt_config.promptContent or "{{content}}").replace("{{content}}", "").strip()
        return (
            f"你正在进行长篇小说分段分析。\n当前是第 {chunk_index}/{chunk_count} 段，请只基于当前分段正文输出局部分析。\n"
            f"原始分析要求：\n{original}\n\n{{content}}"
        )

    def _build_merge_prompt(self, prompt_config: PromptConfigPayload) -> str:
        original = (prompt_config.promptContent or "{{content}}").replace("{{content}}", "").strip()
        return (
            "你正在整合同一本小说的多段局部分析结果。\n请去重、补全跨章节关系，输出一份最终结论。\n"
            f"原始分析要求：\n{original}\n\n{{content}}"
        )

    def _estimate_tokens(self, text: str) -> int:
        count = 0
        for char in text or "":
            count += 2 if ord(char) > 0x2E7F else 1
        return max(1, count // 3)

    def _analysis_type(self, agent_type: str) -> str:
        return "theme" if agent_type == "trend_theme" else agent_type

    def _build_runtime_meta(
        self,
        request: RunRequest,
        *,
        stream_mode: bool,
        use_chunking: bool,
        chunk_count: int,
        provider_call_count: int,
        queue_wait_ms: int,
        provider_latency_ms: int,
    ) -> dict[str, Any]:
        runtime_meta: dict[str, Any] = {
            "framework": "langgraph",
            "runtimeMode": "langgraph",
            "agentType": request.agentType,
            "analysisType": self._analysis_type(request.agentType),
            "streamMode": stream_mode,
            "useChunking": use_chunking,
            "chunkCount": max(0, chunk_count),
            "providerCallCount": max(1, provider_call_count),
            "queueWaitMillis": max(0, int(queue_wait_ms)),
            "providerLatencyMillis": max(0, int(provider_latency_ms)),
        }
        if request.traceId:
            runtime_meta["traceId"] = request.traceId
        return runtime_meta

    def _build_chunk_runtime_meta(
        self,
        request: RunRequest,
        chunk_results: list[RunResponse],
        merged_response: RunResponse | None,
    ) -> dict[str, Any]:
        runtime_metas = [self._runtime_meta(result) for result in chunk_results]
        if merged_response is not None:
            runtime_metas.append(self._runtime_meta(merged_response))
        return self._build_runtime_meta(
            request,
            stream_mode=False,
            use_chunking=True,
            chunk_count=len(chunk_results),
            provider_call_count=len(runtime_metas),
            queue_wait_ms=sum(int(meta.get("queueWaitMillis") or 0) for meta in runtime_metas),
            provider_latency_ms=sum(int(meta.get("providerLatencyMillis") or 0) for meta in runtime_metas),
        )

    def _finalize_response(self, request: RunRequest, response: RunResponse, total_duration_ms: int) -> RunResponse:
        runtime_meta = self._runtime_meta(response)
        if not runtime_meta:
            runtime_meta = self._build_runtime_meta(
                request,
                stream_mode=False,
                use_chunking=False,
                chunk_count=0,
                provider_call_count=1,
                queue_wait_ms=0,
                provider_latency_ms=0,
            )
        runtime_meta["totalDurationMillis"] = max(0, int(total_duration_ms))
        return self._apply_runtime_meta(response, runtime_meta)

    def _apply_runtime_meta(self, response: RunResponse, runtime_meta: dict[str, Any]) -> RunResponse:
        result_json = dict(response.resultJson or {})
        meta = result_json.get("meta")
        if not isinstance(meta, dict):
            meta = {}
        runtime = meta.get("runtime")
        if not isinstance(runtime, dict):
            runtime = {}
        runtime.update(runtime_meta)
        meta["runtime"] = runtime
        result_json["meta"] = meta
        response.resultJson = result_json
        return response

    def _runtime_meta(self, response: RunResponse) -> dict[str, Any]:
        meta = response.resultJson.get("meta")
        if not isinstance(meta, dict):
            return {}
        runtime = meta.get("runtime")
        return dict(runtime) if isinstance(runtime, dict) else {}

    def _elapsed_millis(self, started_at: float) -> int:
        return max(0, int((perf_counter() - started_at) * 1000))

    def _short_text(self, text: str, max_length: int) -> str:
        compact = " ".join((text or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."
