from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, AsyncGenerator, AsyncIterator, TYPE_CHECKING

from langgraph.graph import END, StateGraph

from app.config import settings
from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.checkpointing import build_langgraph_checkpointer
from app.services.harness.admission import run_slot
from app.services.harness.agent_kernel import AgentKernel
from app.services.harness.budget import run_budget_scope
from app.services.harness.capability_authorizer import CapabilityAuthorizer
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.cancellation import (
    CancellationToken,
    cancellation_checkpoint,
    cancellation_scope,
    current_cancellation_token,
)
from app.services.harness.contracts import EvidenceCommit
from app.services.harness.context_compaction import (
    ContextCompactionResult,
    ContextCompactor,
    ModelContextCapabilityRegistry,
)
from app.services.harness.context_policy import context_policy_scope
from app.services.harness.data_access_planner import DataAccessPlanner
from app.services.harness.execution_path import ExecutionPathRouter
from app.services.harness.provider_dispatch_scope import ProviderCapabilities, provider_dispatch_scope
from app.services.harness.retrieval_planner import ProjectRetrievalPlanner
from app.services.harness.tool_ledger import run_tool_ledger_scope
from app.services.harness.validators import PromptInjectionValidator
from app.services.intents import IntentRouter
from app.services.runtime import AgentSupervisor, ContextAssembler, IntentAgent, MemoryAgent, MemoryExtractor
from app.services.runtime.evidence_arbiter import EvidenceArbiter
from app.services.skills import SkillRegistry
from app.services.skills.mediation import SkillMediator
from app.services.task_graph import DomainTaskToolExecutor, DomainToolPlanner, EvidencePackBuilder, TaskGraphDecomposer

if TYPE_CHECKING:
    from app.services.novel_research_agent import NovelResearchAgent


class WebnovelHarness:
    """Composition and lifecycle root for the webnovel agent runtime.

    Domain node methods remain on NovelResearchAgent as a compatibility runtime host,
    while this object owns component assembly, the compiled graph, AgentKernel,
    cancellation, admission, budget, ledger and stream/run entrypoints.
    """

    def __init__(self, runtime: "NovelResearchAgent", *, state_schema: Any | None = None) -> None:
        self.runtime = runtime
        self.state_schema = state_schema

    @classmethod
    def compose(
        cls,
        runtime: "NovelResearchAgent",
        *,
        state_schema: Any,
    ) -> "WebnovelHarness":
        harness = cls(runtime, state_schema=state_schema)
        harness._compose_runtime()
        return harness

    def _compose_runtime(self) -> None:
        runtime = self.runtime
        runtime.intent_router = IntentRouter()
        runtime.intent_agent = IntentAgent(
            router=runtime.intent_router,
            llm_fallback=runtime._provider_domain_intent_fallback,
            llm_fallback_enabled=settings.agent_intent_llm_fallback_enabled,
            llm_min_confidence=settings.agent_intent_llm_min_confidence,
            model_first_enabled=settings.agent_model_first_intent_enabled,
        )
        runtime.skill_registry = SkillRegistry()
        runtime.skill_mediator = SkillMediator()
        runtime.memory_agent = MemoryAgent(memory_client=runtime.knowledge_client)
        runtime.task_graph_decomposer = TaskGraphDecomposer()
        runtime.domain_tool_planner = DomainToolPlanner()
        runtime.project_retrieval_planner = ProjectRetrievalPlanner()
        runtime.evidence_pack_builder = EvidencePackBuilder()
        runtime.evidence_arbiter = EvidenceArbiter(
            max_snapshot_age_days=settings.agent_latest_rank_max_age_days
        )
        runtime.agent_supervisor = AgentSupervisor()
        runtime.context_assembler = ContextAssembler(memory_client=runtime.knowledge_client)
        runtime.context_compactor = ContextCompactor(
            registry=ModelContextCapabilityRegistry.from_json(
                settings.context_capabilities_json
            ),
            enabled=settings.context_compaction_enabled,
        )
        runtime.memory_candidate_extractor = MemoryExtractor()
        runtime.data_access_planner = DataAccessPlanner()
        runtime.capability_compiler = CapabilityCompiler()
        runtime.capability_authorizer = CapabilityAuthorizer()
        runtime.execution_path_router = ExecutionPathRouter()
        runtime.prompt_injection_validator = PromptInjectionValidator()
        runtime._checkpointer = build_langgraph_checkpointer("novel-research-agent")
        runtime._tool_registry = runtime._build_tool_registry()
        runtime._task_tool_executor = DomainTaskToolExecutor(runtime._tool_registry)
        runtime.agent_kernel = AgentKernel(
            runtime.provider_client,
            checkpoint_writer=runtime._write_current_semantic_checkpoint,
            context_compactor=runtime.context_compactor,
        )
        runtime._graph = self._build_graph()

        self.intent_agent = runtime.intent_agent
        self.skill_registry = runtime.skill_registry
        self.data_access_planner = runtime.data_access_planner
        self.context_compactor = runtime.context_compactor
        self.capability_authorizer = runtime.capability_authorizer
        self.agent_kernel = runtime.agent_kernel
        self.graph = runtime._graph

    def _build_graph(self) -> Any:
        if self.state_schema is None:
            raise RuntimeError("Harness state schema is required for graph composition")
        runtime = self.runtime
        graph = StateGraph(self.state_schema)
        graph.add_node("assemble_context", runtime._budgeted_node(runtime._assemble_context_node, "assemble_context"))
        graph.add_node("classify_intent", runtime._budgeted_node(runtime._classify_intent_node, "classify_intent"))
        graph.add_node("plan_tasks", runtime._budgeted_node(runtime._plan_tasks_node, "plan_tasks"))
        graph.add_node("validate_preconditions", runtime._budgeted_node(runtime._validate_preconditions_node, "validate_preconditions"))
        graph.add_node("execute_tools", runtime._budgeted_node(runtime._execute_tools_node, "execute_tools"))
        graph.add_node("supervise_evidence", runtime._budgeted_node(runtime._supervise_evidence_node, "supervise_evidence"))
        graph.add_node("route_experts", runtime._budgeted_node(runtime._specialist_agents_node, "route_experts"))
        graph.add_node("analyze_market_evidence", runtime._budgeted_node(runtime._market_evidence_analysis_node, "analyze_market_evidence"))
        graph.add_node("compose_answer", runtime._budgeted_node(runtime._compose_answer_node, "compose_answer"))
        graph.add_node("review_answer", runtime._budgeted_node(runtime._review_answer_node, "review_answer"))
        graph.add_node("revise_answer", runtime._budgeted_node(runtime._revise_answer_node, "revise_answer"))
        graph.add_node("extract_memory_candidates", runtime._budgeted_node(runtime._extract_memory_candidates_node, "extract_memory_candidates"))
        graph.add_node("finalize_trace", runtime._budgeted_node(runtime._finalize_trace_node, "finalize_trace"))
        graph.set_entry_point("classify_intent")
        graph.add_edge("classify_intent", "assemble_context")
        graph.add_edge("assemble_context", "plan_tasks")
        graph.add_edge("plan_tasks", "validate_preconditions")
        graph.add_edge("validate_preconditions", "execute_tools")
        graph.add_edge("execute_tools", "supervise_evidence")
        graph.add_conditional_edges(
            "supervise_evidence",
            runtime._route_after_runtime_supervisor,
            {
                "execute_tools": "execute_tools",
                "route_experts": "route_experts",
                "finalize_trace": "finalize_trace",
            },
        )
        graph.add_conditional_edges(
            "route_experts",
            runtime._route_after_experts,
            {
                "analyze_market_evidence": "analyze_market_evidence",
                "compose_answer": "compose_answer",
            },
        )
        graph.add_edge("analyze_market_evidence", "compose_answer")
        graph.add_edge("compose_answer", "review_answer")
        graph.add_conditional_edges(
            "review_answer",
            runtime._route_after_answer_review,
            {
                "revise_answer": "revise_answer",
                "extract_memory_candidates": "extract_memory_candidates",
            },
        )
        graph.add_edge("revise_answer", "extract_memory_candidates")
        graph.add_edge("extract_memory_candidates", "finalize_trace")
        graph.add_edge("finalize_trace", END)
        return graph.compile(checkpointer=runtime._checkpointer)

    async def run(self, request: KnowledgeChatRequest) -> KnowledgeChatResponse:
        token = current_cancellation_token() or CancellationToken()
        # 治理值必须在 _prepare_context 之前入 scope：请求层压缩就在那一步算阈值。
        with cancellation_scope(token), context_policy_scope(request.limits):
            cancellation_checkpoint(token)
            compaction = self._prepare_context(request)
            request = compaction.request
            config = self.runtime._graph_config(request)
            async with run_slot(
                self.runtime._reasoning_mode(request),
                run_id=self.runtime._run_admission_identity(request),
                token=token,
            ):
                checkpoint = await self.runtime._prepare_checkpoint(request, config)
                budget = self.runtime._budget_for_checkpoint(request, checkpoint)
                with run_budget_scope(budget):
                    ledger = self.runtime._run_tool_ledger(request, checkpoint)
                    await self.runtime._hydrate_semantic_checkpoints(request, ledger)
                    with run_tool_ledger_scope(ledger):
                        async with self._provider_dispatch_lifecycle(request, checkpoint) as governance:
                            response = await self.runtime._run_scoped(
                                request,
                                config=config,
                                checkpoint=checkpoint,
                                governance=governance,
                            )
                            cancellation_checkpoint(token)
                            return self._attach_context_compaction(response, compaction)

    async def stream(self, request: KnowledgeChatRequest) -> AsyncGenerator[dict[str, Any], None]:
        token = current_cancellation_token() or CancellationToken()
        with cancellation_scope(token), context_policy_scope(request.limits):
            cancellation_checkpoint(token)
            compaction = self._prepare_context(request)
            request = compaction.request
            for event in compaction.events():
                yield event
            config = self.runtime._graph_config(request)
            async with run_slot(
                self.runtime._reasoning_mode(request),
                run_id=self.runtime._run_admission_identity(request),
                token=token,
            ):
                checkpoint = await self.runtime._prepare_checkpoint(request, config)
                budget = self.runtime._budget_for_checkpoint(request, checkpoint)
                with run_budget_scope(budget):
                    ledger = self.runtime._run_tool_ledger(request, checkpoint)
                    await self.runtime._hydrate_semantic_checkpoints(request, ledger)
                    with run_tool_ledger_scope(ledger):
                        async with self._provider_dispatch_lifecycle(request, checkpoint) as governance:
                            async for event in self.runtime._stream_from_compiled_graph(
                                request,
                                config=config,
                                checkpoint=checkpoint,
                                governance=governance,
                            ):
                                cancellation_checkpoint(token)
                                if str(event.get("event") or "").lower() == "done" and isinstance(
                                    event.get("data"),
                                    dict,
                                ):
                                    response = KnowledgeChatResponse.model_validate(event["data"])
                                    event = {
                                        **event,
                                        "data": self._attach_context_compaction(
                                            response,
                                            compaction,
                                        ).model_dump(mode="json"),
                                    }
                                yield event

    @asynccontextmanager
    async def _provider_dispatch_lifecycle(
        self,
        request: KnowledgeChatRequest,
        checkpoint: tuple[bool, dict[str, Any]] | None,
    ) -> AsyncIterator[dict[str, Any] | None]:
        governance: dict[str, Any] | None = None
        if checkpoint is None:
            governance = await self.runtime._load_agent_governance()
            governance_errors = governance.get("errors")
            if isinstance(governance_errors, dict) and "config" in governance_errors:
                raise RuntimeError("agent runtime config is unavailable")
            runtime_config = self.runtime._runtime_config_for_state(
                governance,
                dict(governance.get("config") or {}),
            )
        else:
            runtime_config = dict(checkpoint[1].get("runtime_config") or {})
        raw_profiles = runtime_config.get("providerProfiles")
        if raw_profiles is None:
            raw_profiles = []
        if not isinstance(raw_profiles, list):
            raise ValueError("provider profile catalog is invalid")
        profiles: list[dict[str, Any]] = []
        for profile in raw_profiles:
            if not isinstance(profile, dict):
                raise ValueError("provider profile catalog is invalid")
            protocol = str(profile.get("protocol") or "").strip().lower().replace("-", "_")
            if protocol in {"", "unspecified"}:
                continue
            if protocol not in {"responses", "chat_completions"} or any(
                not str(profile.get(key) or "").strip()
                for key in ("profileKey", "profileVersion", "endpoint", "model")
            ):
                raise ValueError("provider profile catalog is invalid")
            try:
                ProviderCapabilities.from_payload(profile.get("providerCapabilities"))
            except ValueError as exc:
                raise ValueError("provider profile catalog is invalid") from exc
            profiles.append(dict(profile))
        resolver = getattr(self.runtime.knowledge_client, "resolve_provider_dispatch", None)
        if not profiles:
            yield governance
            return
        if not callable(resolver):
            raise RuntimeError("provider dispatch resolver is unavailable")
        routing_policy = runtime_config.get("providerRoutingPolicy")
        outcome_reporter = getattr(
            self.runtime.knowledge_client,
            "report_provider_routing_outcome",
            None,
        )
        async with provider_dispatch_scope(
            resolver,
            routes=profiles,
            preferred_model=self.runtime._model_name(request),
            preferred_profile_key=str(request.limits.get("modelKey") or "").strip() or None,
            routing_policy=routing_policy if isinstance(routing_policy, dict) else None,
            outcome_reporter=outcome_reporter if callable(outcome_reporter) else None,
        ):
            yield governance

    def _prepare_context(self, request: KnowledgeChatRequest) -> ContextCompactionResult:
        return self.context_compactor.prepare(
            request,
            model=self.runtime._model_name(request),
        )

    @staticmethod
    def _attach_context_compaction(
        response: KnowledgeChatResponse,
        compaction: ContextCompactionResult,
    ) -> KnowledgeChatResponse:
        if not isinstance(response.resultJson, dict):
            response.resultJson = {}
        if compaction.status not in {"not_needed", "disabled"}:
            response.resultJson["contextCompaction"] = compaction.trace_summary(
                include_summary=compaction.compacted,
            )
        context_budget = response.resultJson.get("contextBudget")
        if isinstance(context_budget, dict):
            context_budget["maxInputTokens"] = compaction.capability.context_window_tokens
            context_budget["preRequestEstimatedTokens"] = compaction.before_input_tokens
            context_budget["postCompactionEstimatedTokens"] = compaction.after_input_tokens
            context_budget["compactionStatus"] = compaction.status
            context_budget["compressed"] = compaction.status in {"compacted", "reused"}
        trace = response.resultJson.get("trace")
        if isinstance(trace, dict):
            if isinstance(context_budget, dict):
                trace["contextBudget"] = dict(context_budget)
            if compaction.status not in {"not_needed", "disabled"}:
                trace["contextCompaction"] = compaction.trace_summary()
        return response

    def commit_run(
        self,
        *,
        response: KnowledgeChatResponse,
        state: dict[str, Any] | None = None,
        evidence_commit: EvidenceCommit | dict[str, Any] | None = None,
    ) -> KnowledgeChatResponse:
        """CommitCoordinator: finalize answer/memory/terminal/trace consistency.

        Kept as a harness method (no separate service file) so run ownership stays here.
        """
        payload = state if isinstance(state, dict) else {}
        if not isinstance(response.resultJson, dict):
            response.resultJson = {}

        commit = self._coerce_evidence_commit(
            evidence_commit if evidence_commit is not None else payload.get("evidence_commit")
        )
        if commit is not None:
            summary = commit.trace_summary()
            response.resultJson["evidenceCommit"] = summary
            if response.status == "answered" and self._should_block_answered_commit(response, commit):
                # Only hard evidence integrity failures or required factual commits demote.
                response.status = "insufficient_evidence"
                response.resultJson["answerStatus"] = "needs_data"
                response.resultJson["answerBoundary"] = "needs_more_data"
                reasons = list(response.resultJson.get("degradationReasons") or [])
                if "evidence_commit_rejected" not in reasons:
                    reasons.append("evidence_commit_rejected")
                response.resultJson["degradationReasons"] = reasons
            response.resultJson["evidenceRepairAllowed"] = bool(commit.repairAllowed)

        # Align terminal status fields across answer / resultJson / trace.
        response.resultJson["status"] = response.status
        if not response.resultJson.get("answerStatus"):
            response.resultJson["answerStatus"] = (
                "answered_with_evidence"
                if response.status == "answered"
                else "needs_data"
            )

        memory_candidates = response.resultJson.get("memoryCandidates")
        if memory_candidates is None and isinstance(payload.get("memory_candidates"), list):
            response.resultJson["memoryCandidates"] = list(payload.get("memory_candidates") or [])

        trace = response.resultJson.get("trace")
        if isinstance(trace, dict):
            trace["terminalStatus"] = response.status
            trace["answerStatus"] = response.resultJson.get("answerStatus")
            if commit is not None:
                trace["evidenceCommit"] = commit.trace_summary()
            if "memoryCandidates" in response.resultJson:
                trace["memoryCandidates"] = list(response.resultJson.get("memoryCandidates") or [])
            response.resultJson["trace"] = trace

        return response

    _HARD_BLOCK_REASONS = frozenset({
        "forged_citation",
        "cross_project_evidence",
        "stale_market_claim",
        "malformed_evidence_commit",
    })
    def _should_block_answered_commit(
        self,
        response: KnowledgeChatResponse,
        commit: EvidenceCommit,
    ) -> bool:
        """Demote only on integrity failures; sparse/missing evidence keeps prior gates."""
        if commit.canCommit:
            return False
        reason_codes = set(commit.reasonCodes or ())
        decision_reasons = {
            code
            for decision in commit.decisions
            for code in (decision.reasonCodes or ())
        }
        return bool(reason_codes & self._HARD_BLOCK_REASONS or decision_reasons & self._HARD_BLOCK_REASONS)

    def _coerce_evidence_commit(
        self,
        value: EvidenceCommit | dict[str, Any] | None,
    ) -> EvidenceCommit | None:
        if value is None:
            return None
        if isinstance(value, EvidenceCommit):
            return value
        if isinstance(value, dict):
            try:
                return EvidenceCommit.model_validate(value)
            except Exception:
                pass
        return EvidenceCommit(
            commitId="evidence:malformed",
            canCommit=False,
            repairAllowed=False,
            reasonCodes=("malformed_evidence_commit",),
        )

    async def aclose(self) -> None:
        await self.runtime.aclose()
