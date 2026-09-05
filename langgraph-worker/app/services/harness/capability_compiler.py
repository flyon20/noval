from __future__ import annotations

from dataclasses import dataclass

from .contracts import (
    CapabilityLimits,
    CapabilityPlan,
    CapabilityRequest,
    CapabilityScope,
    DataAccessPlan,
    DataProposalSource,
    DatasetCapability,
    DomainStatus,
    IntentEnvelope,
    SideEffectPolicy,
)
from .execution_path import ExecutionPath


@dataclass(frozen=True, slots=True)
class _OperationPolicy:
    capabilities: tuple[str, ...] = ()
    evidence: tuple[str, ...] = ()
    retrieval: tuple[str, ...] = ()
    memory: tuple[str, ...] = ()
    skills: tuple[str, ...] = ()
    experts: tuple[str, ...] = ()
    tool_capabilities: tuple[str, ...] = ()
    degradation: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class _DatasetPolicy:
    allowed_operations: tuple[str, ...]
    capabilities: tuple[str, ...] = ()
    evidence: tuple[str, ...] = ()
    retrieval: tuple[str, ...] = ()
    memory: tuple[str, ...] = ()
    tool_capabilities: tuple[str, ...] = ()


_POLICIES = {
    "market_scan": _OperationPolicy(
        capabilities=("market.read",),
        evidence=("market.current_rank",),
        retrieval=("market",),
        skills=("webnovel-market-scan",),
        experts=("market_scan",),
        tool_capabilities=("market.read",),
        degradation=("degrade_to_directional_market_evidence",),
    ),
    "market_research": _OperationPolicy(
        capabilities=("market.read", "market.research"),
        evidence=("market.current_rank",),
        retrieval=("market",),
        skills=("webnovel-market-scan",),
        experts=("market_scan",),
        tool_capabilities=("market.read", "market.research"),
        degradation=("degrade_to_directional_market_evidence",),
    ),
    "book_breakdown": _OperationPolicy(
        capabilities=("book.read",),
        evidence=("book.source_material",),
        retrieval=("book",),
        skills=("webnovel-book-breakdown",),
        experts=("book_breakdown",),
        tool_capabilities=("book.read",),
        degradation=("request_book_selection_or_more_evidence",),
    ),
    "opening_strategy": _OperationPolicy(
        capabilities=("creation.opening",),
        skills=("webnovel-opening-strategy",),
        experts=("opening_strategy",),
    ),
    "outline_building": _OperationPolicy(
        capabilities=("creation.outline",),
        skills=("webnovel-outline-building",),
        experts=("outline",),
    ),
    "chapter_outline": _OperationPolicy(
        capabilities=("creation.chapter_outline",),
        skills=("webnovel-chapter-outline",),
        experts=("chapter_outline",),
    ),
    "inspiration_expand": _OperationPolicy(
        capabilities=("creation.ideation",),
        skills=("webnovel-topic-strategy",),
        experts=("inspiration",),
    ),
    "character_design": _OperationPolicy(
        capabilities=("creation.character",),
        experts=("character",),
    ),
    "worldbuilding": _OperationPolicy(
        capabilities=("creation.worldbuilding",),
        experts=("worldbuilding",),
    ),
    "revision_advice": _OperationPolicy(
        capabilities=("creation.revision",),
        experts=("revision", "reader_risk", "editor"),
    ),
    "followup_context": _OperationPolicy(
        capabilities=("creation.followup",),
        memory=("thread",),
    ),
    "mixed_creation_research": _OperationPolicy(
        capabilities=("market.read", "market.research"),
        evidence=("market.current_rank",),
        retrieval=("market",),
        skills=("webnovel-market-scan",),
        experts=("market_scan",),
        tool_capabilities=("market.read", "market.research"),
        degradation=("degrade_to_directional_market_evidence",),
    ),
    "project_knowledge": _OperationPolicy(
        capabilities=("project.retrieve", "memory.project.read"),
        evidence=("project.canonical_knowledge",),
        retrieval=("project",),
        memory=("project", "thread"),
        skills=("webnovel-project-knowledge-qa",),
        experts=("editor",),
        tool_capabilities=("project.retrieve", "memory.project.read"),
        degradation=("request_project_scope_or_more_evidence",),
    ),
}


_DATASET_POLICIES = {
    DatasetCapability.MARKET_RANK: _DatasetPolicy(
        allowed_operations=("market_scan", "market_research", "mixed_creation_research"),
        capabilities=("market.read",),
        evidence=("market.current_rank",),
        retrieval=("market",),
        tool_capabilities=("market.read",),
    ),
    DatasetCapability.MARKET_HISTORY: _DatasetPolicy(
        allowed_operations=("market_scan", "market_research", "mixed_creation_research"),
        capabilities=("market.research",),
        evidence=("market.historical_rank",),
        retrieval=("market",),
        tool_capabilities=("market.research",),
    ),
    DatasetCapability.BOOK_SOURCE: _DatasetPolicy(
        allowed_operations=("book_breakdown",),
        capabilities=("book.read",),
        evidence=("book.source_material",),
        retrieval=("book",),
        tool_capabilities=("book.read",),
    ),
    DatasetCapability.PROJECT_KNOWLEDGE: _DatasetPolicy(
        allowed_operations=("project_knowledge",),
        capabilities=("project.retrieve", "memory.project.read"),
        evidence=("project.canonical_knowledge",),
        retrieval=("project",),
        memory=("project", "thread"),
        tool_capabilities=("project.retrieve", "memory.project.read"),
    ),
    DatasetCapability.PROJECT_CONTINUITY: _DatasetPolicy(
        allowed_operations=("project_knowledge",),
        capabilities=("project.continuity.read", "memory.project.read"),
        evidence=("project.continuity_evidence",),
        retrieval=("project",),
        memory=("project", "thread"),
        tool_capabilities=("project.continuity.read", "memory.project.read"),
    ),
    DatasetCapability.CONVERSATION_THREAD: _DatasetPolicy(
        allowed_operations=("followup_context",),
        memory=("thread",),
    ),
}


class CapabilityCompiler:
    VERSION = "capability-compiler-v1"

    def compile(
        self,
        intent: IntentEnvelope,
        *,
        request_scope: CapabilityScope | None = None,
        runtime_limits: CapabilityLimits | None = None,
        data_access_plan: DataAccessPlan | None = None,
    ) -> CapabilityPlan:
        scope = request_scope or CapabilityScope()
        limits = runtime_limits or CapabilityLimits()
        if (
            data_access_plan is not None
            and data_access_plan.intentEnvelopeHash != intent.fingerprint
        ):
            raise ValueError("data access plan does not match intent envelope")
        if intent.domainStatus is DomainStatus.OUT_OF_SCOPE:
            return CapabilityPlan(
                compilerVersion=self.VERSION,
                intentEnvelopeHash=intent.fingerprint,
                executionPath=ExecutionPath.DIRECT,
                limits=limits,
                dataAccessPlanHash=(
                    data_access_plan.fingerprint if data_access_plan is not None else None
                ),
                reasonCodes=("domain_out_of_scope",),
            )

        capabilities: list[CapabilityRequest] = []
        evidence: list[str] = []
        retrieval: list[str] = []
        memory: list[str] = []
        skills: list[str] = []
        experts: list[str] = []
        tool_capabilities: list[str] = []
        degradation: list[str] = []
        reasons: list[str] = []
        accepted_data_request_ids: list[str] = []

        for operation in intent.operations:
            policy = _POLICIES.get(operation)
            if policy is None:
                reasons.append(f"unmapped_operation:{operation}")
                continue
            reasons.append(f"operation:{operation}")
            capabilities.extend(
                CapabilityRequest(
                    capabilityId=capability_id,
                    reasonCodes=(f"intent:{operation}",),
                )
                for capability_id in policy.capabilities
            )
            evidence.extend(policy.evidence)
            retrieval.extend(policy.retrieval)
            memory.extend(policy.memory)
            skills.extend(policy.skills)
            experts.extend(policy.experts)
            tool_capabilities.extend(policy.tool_capabilities)
            degradation.extend(policy.degradation)

        if (
            data_access_plan is not None
            and data_access_plan.proposalSource is DataProposalSource.INTENT_ENTITIES
        ):
            operation_ids = set(intent.operations)
            for request in data_access_plan.requests:
                policy = _DATASET_POLICIES.get(request.datasetCapability)
                if policy is None or not operation_ids.intersection(policy.allowed_operations):
                    reasons.append(f"data_access_denied:{request.datasetCapability.value}")
                    continue
                accepted_data_request_ids.append(request.requestId)
                reasons.append(f"data_access:{request.datasetCapability.value}")
                capabilities.extend(
                    CapabilityRequest(
                        capabilityId=capability_id,
                        required=request.required,
                        reasonCodes=(f"data_access:{request.datasetCapability.value}",),
                    )
                    for capability_id in policy.capabilities
                )
                evidence.extend(policy.evidence)
                retrieval.extend(policy.retrieval)
                memory.extend(policy.memory)
                tool_capabilities.extend(policy.tool_capabilities)
            if accepted_data_request_ids:
                skills.append("governed-data-access")
        elif data_access_plan is not None:
            reasons.append(f"data_access_observed:{data_access_plan.proposalSource.value}")

        execution_path = self._execution_path(intent.operations, capabilities, retrieval)
        requires_project_scope = bool(
            "project" in retrieval
            or (
                data_access_plan is not None
                and data_access_plan.requiresTrustedProjectScope
            )
        )
        if requires_project_scope and scope.projectId is None:
            reasons.append("missing_project_scope")
        reasons.append(f"execution:{execution_path.value.lower()}")
        if intent.domainStatus is DomainStatus.NEEDS_CLARIFICATION:
            reasons.append("intent_needs_clarification")

        return CapabilityPlan(
            compilerVersion=self.VERSION,
            intentEnvelopeHash=intent.fingerprint,
            capabilityRequests=tuple(capabilities),
            evidenceRequirements=tuple(evidence),
            retrievalScopes=tuple(retrieval),
            memoryScopes=tuple(memory),
            sideEffectPolicy=SideEffectPolicy.READ_ONLY,
            executionPath=execution_path,
            limits=limits,
            skillCandidateIds=tuple(skills),
            expertCandidateIds=tuple(experts),
            requestedToolCapabilities=tuple(tool_capabilities),
            dataAccessPlanHash=(
                data_access_plan.fingerprint if data_access_plan is not None else None
            ),
            dataAccessRequestIds=tuple(accepted_data_request_ids),
            degradationPolicy=tuple(degradation),
            requiresProjectScope=requires_project_scope,
            delegationAllowed=False,
            reasonCodes=tuple(reasons),
        )

    @staticmethod
    def _execution_path(
        operations: tuple[str, ...],
        capabilities: list[CapabilityRequest],
        retrieval: list[str],
    ) -> ExecutionPath:
        operation_set = set(operations)
        if operation_set <= {"followup_context", "project_knowledge"} and "project" in retrieval:
            return ExecutionPath.RETRIEVE
        has_creation = any(request.capabilityId.startswith("creation.") for request in capabilities)
        if "mixed_creation_research" in operation_set or retrieval and has_creation:
            return ExecutionPath.COMPLEX
        if retrieval:
            return ExecutionPath.RETRIEVE
        return ExecutionPath.DIRECT


__all__ = ["CapabilityCompiler"]
