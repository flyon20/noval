from __future__ import annotations

from app.models.knowledge import KnowledgeChatRequest
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import (
    CapabilityLimits,
    CapabilityScope,
    DomainStatus,
    IntentEnvelope,
)
from app.services.harness.execution_path import ExecutionPath
from app.services.intents import AnswerBoundary, Intent, IntentDecision, ToolNeeds
from app.services.runtime.intent_agent import IntentAgent


def _compile(envelope: IntentEnvelope, *, project_id: int | None = None):
    return CapabilityCompiler().compile(
        envelope,
        request_scope=CapabilityScope(userId=7, projectId=project_id),
        runtime_limits=CapabilityLimits(
            maxTurns=6,
            maxToolCalls=12,
            maxDelegations=2,
            maxInputTokens=512_000,
        ),
    )


def test_same_intent_decision_adapts_to_same_envelope_and_plan() -> None:
    decision = IntentDecision(
        primaryIntent=Intent.market_scan,
        subIntents=[Intent.opening_strategy, Intent.market_scan],
        confidence=0.91,
        entities={"platform": "fanqie", "constraints": ["male-new"]},
        toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True),
        answerBoundary=AnswerBoundary.market_evidence_plus_author_inference,
        routingNotes=["llm:test", "llm:test"],
    )
    request = KnowledgeChatRequest(question="market and opening", mode="research")
    adapter = IntentAgent()

    first_envelope = adapter.to_envelope(decision, request=request)
    second_envelope = adapter.to_envelope(decision.model_copy(deep=True), request=request.model_copy(deep=True))
    first_plan = _compile(first_envelope)
    second_plan = _compile(second_envelope)

    assert first_envelope == second_envelope
    assert first_envelope.fingerprint == second_envelope.fingerprint
    assert first_envelope.classificationSource == "llm_fallback"
    assert first_plan == second_plan
    assert first_plan.fingerprint == second_plan.fingerprint


def test_out_of_scope_compiles_to_an_empty_direct_plan() -> None:
    envelope = IntentEnvelope(
        domainStatus=DomainStatus.OUT_OF_SCOPE,
        goal="out_of_scope",
        operations=("out_of_scope",),
        confidence=0.96,
        classificationSource="rules",
        notes=("rule:oos-domain",),
    )

    plan = _compile(envelope)

    assert plan.executionPath is ExecutionPath.DIRECT
    assert plan.capabilityRequests == ()
    assert plan.skillCandidateIds == ()
    assert plan.expertCandidateIds == ()
    assert plan.requestedToolCapabilities == ()
    assert plan.delegationAllowed is False


def test_pure_creation_is_direct_and_requests_no_tool_capability() -> None:
    decision = IntentDecision(
        primaryIntent=Intent.opening_strategy,
        confidence=0.92,
        toolNeeds=ToolNeeds(needsCreativeGeneration=True),
        answerBoundary=AnswerBoundary.creative_inference,
        routingNotes=["rule:opening"],
    )
    envelope = IntentAgent().to_envelope(
        decision,
        request=KnowledgeChatRequest(question="design an opening"),
    )

    plan = _compile(envelope)

    assert plan.executionPath is ExecutionPath.DIRECT
    assert [request.capabilityId for request in plan.capabilityRequests] == ["creation.opening"]
    assert plan.requestedToolCapabilities == ()
    assert plan.retrievalScopes == ()


def test_project_knowledge_requires_project_scope_and_retrieval() -> None:
    decision = IntentDecision(
        primaryIntent=Intent.followup_context,
        confidence=0.88,
        answerBoundary=AnswerBoundary.creative_inference,
        routingNotes=["rule:project-followup"],
    )
    envelope = IntentAgent().to_envelope(
        decision,
        request=KnowledgeChatRequest(
            question="continue the project setting",
            userId=7,
            projectId=42,
            contextSummary="project context",
        ),
    )

    plan = _compile(envelope, project_id=42)

    assert "project_knowledge" in envelope.operations
    assert plan.executionPath is ExecutionPath.RETRIEVE
    assert plan.requiresProjectScope is True
    assert plan.retrievalScopes == ("project",)
    assert set(plan.requestedToolCapabilities) == {"project.retrieve", "memory.project.read"}


def test_mixed_market_and_creation_is_complex_without_automatic_delegation() -> None:
    decision = IntentDecision(
        primaryIntent=Intent.mixed_creation_research,
        subIntents=[Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
        confidence=0.93,
        toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True),
        answerBoundary=AnswerBoundary.market_evidence_plus_author_inference,
        sourcePolicy={},
        memoryPolicy={},
        routingNotes=["llm:mixed"],
    )
    envelope = IntentAgent().to_envelope(
        decision,
        request=KnowledgeChatRequest(question="scan the market then draft an opening and outline"),
    )

    plan = _compile(envelope)

    assert plan.executionPath is ExecutionPath.COMPLEX
    assert plan.delegationAllowed is False
    assert plan.retrievalScopes == ("market",)
    assert "market.current_rank" in plan.evidenceRequirements
    assert "market.read" in plan.requestedToolCapabilities
    assert {request.capabilityId for request in plan.capabilityRequests} >= {
        "market.read",
        "creation.opening",
        "creation.outline",
    }
    assert "creation.ideation" not in {
        request.capabilityId
        for request in plan.capabilityRequests
    }


def test_ranked_book_imitation_requires_book_source_material() -> None:
    decision = IntentDecision(
        primaryIntent=Intent.mixed_creation_research,
        subIntents=[Intent.market_scan, Intent.book_breakdown, Intent.chapter_outline],
        confidence=0.93,
        toolNeeds=ToolNeeds(
            needsRankData=True,
            needsBookResearch=True,
            needsChapterEvidence=True,
            needsCreativeGeneration=True,
        ),
        answerBoundary=AnswerBoundary.market_evidence_plus_author_inference,
        routingNotes=["rule:mixed-research-creation"],
    )
    envelope = IntentAgent().to_envelope(
        decision,
        request=KnowledgeChatRequest(question="imitate the ranked book and draft three chapters"),
    )

    plan = _compile(envelope)

    assert set(plan.evidenceRequirements) == {"market.current_rank", "book.source_material"}
    assert {request.capabilityId for request in plan.capabilityRequests} >= {
        "market.read",
        "market.research",
        "book.read",
        "creation.chapter_outline",
    }


def test_missing_legacy_source_and_memory_policy_still_produces_complete_plan() -> None:
    decision = IntentDecision(
        primaryIntent=Intent.market_scan,
        confidence=0.9,
        toolNeeds=ToolNeeds(needsRankData=True),
        answerBoundary=AnswerBoundary.market_evidence,
        sourcePolicy={},
        memoryPolicy={},
        routingNotes=["llm:fallback"],
    )
    envelope = IntentAgent().to_envelope(
        decision,
        request=KnowledgeChatRequest(question="latest market"),
    )

    plan = _compile(envelope)

    assert plan.executionPath is ExecutionPath.RETRIEVE
    assert plan.evidenceRequirements == ("market.current_rank",)
    assert plan.retrievalScopes == ("market",)
    assert plan.degradationPolicy == ("degrade_to_directional_market_evidence",)
    assert plan.limits.maxToolCalls == 12
