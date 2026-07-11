from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Any, TypeAlias

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.intents.domain_intents import Intent, IntentDecision
from app.services.runtime.tool_call_loop import ToolCallLoop


@dataclass(slots=True)
class AgentRunContext:
    request: KnowledgeChatRequest
    intent_decision: IntentDecision
    sources: list[KnowledgeSource] = field(default_factory=list)
    skill_fragments: list[Any] = field(default_factory=list)
    actions: list[str] = field(default_factory=list)
    diagnostics: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class AgentRunResult:
    agentName: str
    status: str
    answerMode: str
    summary: str
    evidenceRefs: list[str]
    warnings: list[str]
    toolCalls: list[dict[str, Any]]
    generationInstructions: list[str]
    evidencePolicy: list[str]
    actions: list[str]
    diagnostics: dict[str, Any]


class BaseSpecialistAgent:
    agent_name = "base"
    answer_mode = "creative"
    summary = "Prepare specialist guidance for the final answer."
    generation_instructions: tuple[str, ...] = ()
    evidence_policy: tuple[str, ...] = ()
    actions: tuple[str, ...] = ()
    evidence_refs: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()
    tool_calls: tuple[dict[str, Any], ...] = ()
    llm_enabled = False
    tool_route: str | None = None
    deep_reasoning_effort = "max"

    def run(self, context: AgentRunContext) -> AgentRunResult:
        diagnostics = {
            **context.diagnostics,
            "primaryIntent": context.intent_decision.primaryIntent.value,
            "subIntents": [intent.value for intent in context.intent_decision.subIntents],
            "sourceCount": len(context.sources),
            "materialSourceCount": sum(1 for source in context.sources if bool(source.material)),
            "skillFragmentCount": len(context.skill_fragments),
        }
        if context.sources:
            diagnostics["sourceKinds"] = sorted(
                {
                    source.sourceType
                    or source.analysisType
                    or ("rank" if source.rankNo is not None else "unknown")
                    for source in context.sources
                }
            )

        return AgentRunResult(
            agentName=self.agent_name,
            status="completed",
            answerMode=self.answer_mode,
            summary=self.summary,
            evidenceRefs=list(self.evidence_refs or self._default_evidence_refs(context)),
            warnings=list(self._warnings(context)),
            toolCalls=[dict(call) for call in self.tool_calls],
            generationInstructions=list(self.generation_instructions),
            evidencePolicy=list(self.evidence_policy),
            actions=[*context.actions, *self.actions],
            diagnostics=diagnostics,
        )

    def _default_evidence_refs(self, context: AgentRunContext) -> tuple[str, ...]:
        if not context.sources:
            return ("user_request",)
        refs: list[str] = []
        if any(source.rankNo is not None or source.sourceType == "rank" for source in context.sources):
            refs.append("rank")
        if any(bool(source.material) for source in context.sources):
            refs.append("chapter_material")
        if any(source.sourceType == "memory" for source in context.sources):
            refs.append("memory")
        return tuple(refs or ("retrieved_sources",))

    def _warnings(self, context: AgentRunContext) -> tuple[str, ...]:
        status = str(context.diagnostics.get("evidenceContractStatus") or "").strip()
        if status and status not in {"verified_latest", "answerable"}:
            return (*self.warnings, status)
        return self.warnings

    async def run_llm(
        self,
        context: AgentRunContext,
        *,
        provider_client: Any,
        model: str,
        reasoning_mode: str,
        mcp_client: Any | None = None,
        mcp_tool_registry: Any | None = None,
    ) -> AgentRunResult:
        result = self.run(context)
        if not self.llm_enabled or provider_client is None:
            return result
        try:
            response = await self._invoke_specialist_model(
                context,
                provider_client=provider_client,
                model=model,
                reasoning_mode=reasoning_mode,
                mcp_client=mcp_client,
                mcp_tool_registry=mcp_tool_registry,
            )
            summary = str(response.get("content") or "").strip()
            if summary:
                result.summary = summary
                result.generationInstructions = [summary, *result.generationInstructions]
            result.diagnostics["llmBacked"] = True
            result.diagnostics["llmModel"] = response.get("model_name") or model
            result.diagnostics["llmTokenUsed"] = int(response.get("token_used") or 0)
            result.diagnostics["llmReasoningMode"] = reasoning_mode
            if reasoning_mode == "deep":
                result.diagnostics["llmReasoningEffort"] = self._reasoning_effort(reasoning_mode)
            result.toolCalls.append({
                "name": f"llm.{self.agent_name}",
                "status": "succeeded",
                "model": result.diagnostics["llmModel"],
                "tokenUsed": result.diagnostics["llmTokenUsed"],
            })
            tool_runs = [run for run in response.get("toolRuns", []) if isinstance(run, dict)]
            if tool_runs:
                result.diagnostics["mcpToolLoop"] = True
                result.diagnostics["mcpToolRunCount"] = len(tool_runs)
                for run in tool_runs:
                    result.toolCalls.append({
                        **run,
                        "agentName": self.agent_name,
                        "scope": "specialist",
                    })
        except Exception as exc:
            result.diagnostics["llmBacked"] = False
            result.diagnostics["llmError"] = str(exc) or exc.__class__.__name__
            result.warnings.append("llm_specialist_fallback")
        return result

    async def _invoke_specialist_model(
        self,
        context: AgentRunContext,
        *,
        provider_client: Any,
        model: str,
        reasoning_mode: str,
        mcp_client: Any | None,
        mcp_tool_registry: Any | None,
    ) -> dict[str, Any]:
        messages = self._llm_messages(context)
        route = self._tool_route(context)
        if route and mcp_client is not None and mcp_tool_registry is not None:
            return await ToolCallLoop(
                provider_client=provider_client,
                mcp_client=mcp_client,
                registry=mcp_tool_registry,
            ).run(
                messages=messages,
                route=route,
                model=model,
                temperature=0.2,
                max_tokens=900,
                reasoning_mode=reasoning_mode,
                reasoning_effort=self._reasoning_effort(reasoning_mode),
            )
        return await provider_client.invoke(
            messages=messages,
            model=model,
            temperature=0.2,
            max_tokens=900,
            require_json=False,
            reasoning_mode=reasoning_mode,
            reasoning_effort=self._reasoning_effort(reasoning_mode),
        )

    def _tool_route(self, context: AgentRunContext) -> str | None:
        return self.tool_route

    def _reasoning_effort(self, reasoning_mode: str) -> str | None:
        if reasoning_mode != "deep":
            return None
        effort = str(self.deep_reasoning_effort or "").strip().lower()
        return effort if effort in {"high", "max"} else "max"

    def _llm_messages(self, context: AgentRunContext) -> list[dict[str, str]]:
        evidence = []
        for index, source in enumerate(context.sources[:6], start=1):
            evidence.append(
                f"[{index}] {source.bookName or source.title or 'source'} "
                f"rank={source.rankNo or ''} type={source.sourceType or source.analysisType or ''} "
                f"preview={source.preview or ''}"
            )
        return [
            {
                "role": "system",
                "content": (
                    "You are a focused specialist subagent in a web-novel writing system. "
                    "Return concise specialist guidance only. Respect the evidence policy. "
                    "Do not invent latest ranking or market facts."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"agent: {self.agent_name}\n"
                    f"task summary: {self.summary}\n"
                    f"question: {context.request.question}\n"
                    f"instructions: {list(self.generation_instructions)}\n"
                    f"evidence policy: {list(self.evidence_policy)}\n"
                    f"diagnostics: {context.diagnostics}\n"
                    f"evidence:\n" + ("\n".join(evidence) if evidence else "(none)")
                ),
            },
        ]


SpecialistAgentClass: TypeAlias = type[BaseSpecialistAgent]


def create_context(
    *,
    request: KnowledgeChatRequest,
    intent_decision: IntentDecision,
    sources: list[KnowledgeSource] | None = None,
    skill_fragments: list[Any] | None = None,
    actions: list[str] | None = None,
    diagnostics: dict[str, Any] | None = None,
) -> AgentRunContext:
    return AgentRunContext(
        request=request,
        intent_decision=intent_decision,
        sources=list(sources or []),
        skill_fragments=list(skill_fragments or []),
        actions=list(actions or []),
        diagnostics=dict(diagnostics or {}),
    )


def select_agents(decision: IntentDecision) -> list[SpecialistAgentClass]:
    return list(route_agents(decision, reasoning_mode="deep").agentClasses)


def route_agents(
    decision: IntentDecision,
    *,
    reasoning_mode: str | None = "deep",
    task_graph: dict[str, Any] | Any | None = None,
    registry: Any | None = None,
    max_parallel: int | None = None,
) -> Any:
    from app.services.agents.expert_registry import ExpertRegistry, ExpertRouter

    return ExpertRouter(
        registry or ExpertRegistry.default(),
        max_parallel=max_parallel or 3,
    ).route(
        intent_decision=decision,
        reasoning_mode=reasoning_mode,
        task_graph=task_graph,
    )


def run_specialists(context: AgentRunContext) -> list[AgentRunResult]:
    return [agent_class().run(context) for agent_class in select_agents(context.intent_decision)]


async def run_specialists_parallel(
    context: AgentRunContext,
    *,
    max_parallel: int = 3,
    provider_client: Any | None = None,
    model: str = "deepseek-chat",
    mcp_client: Any | None = None,
    mcp_tool_registry: Any | None = None,
    expert_route: Any | None = None,
) -> list[AgentRunResult]:
    agent_classes = list(expert_route.agentClasses) if expert_route is not None else select_agents(context.intent_decision)
    if not agent_classes:
        return []
    route_parallel = int(getattr(expert_route, "maxParallel", max_parallel) or max_parallel)
    parallel_limit = max(1, min(max_parallel, route_parallel))
    selected_experts = list(getattr(expert_route, "selectedExperts", []) or [])
    semaphore = asyncio.Semaphore(parallel_limit)

    async def run_one(index: int, agent_class: SpecialistAgentClass) -> tuple[int, AgentRunResult]:
        async with semaphore:
            agent = agent_class()
            if provider_client is None:
                result = await asyncio.to_thread(agent.run, context)
            else:
                result = await agent.run_llm(
                    context,
                    provider_client=provider_client,
                    model=model,
                    reasoning_mode=_reasoning_mode(context.request),
                    mcp_client=mcp_client,
                    mcp_tool_registry=mcp_tool_registry,
                )
            result.diagnostics["runner"] = "parallel"
            result.diagnostics["parallelLimit"] = parallel_limit
            result.diagnostics["parallelIndex"] = index
            if index < len(selected_experts):
                route = selected_experts[index]
                result.diagnostics["expertRouterReason"] = getattr(route, "reason", "")
                result.diagnostics["expertRouterTags"] = list(getattr(route, "reasonTags", []) or [])
                result.diagnostics["expertRouterMode"] = getattr(expert_route, "reasoningMode", None)
                result.diagnostics["expertRouterMaxParallel"] = route_parallel
            return index, result

    indexed_results = await asyncio.gather(
        *(run_one(index, agent_class) for index, agent_class in enumerate(agent_classes))
    )
    return [result for _, result in sorted(indexed_results, key=lambda item: item[0])]


def _reasoning_mode(request: KnowledgeChatRequest) -> str:
    value = (request.reasoningMode or "fast").strip().lower()
    return "deep" if value in {"deep", "reasoning", "thinking", "max"} else "fast"
