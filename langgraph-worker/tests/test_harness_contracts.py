from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.services.harness import contracts as harness_contracts
from app.services.harness.contracts import (
    AuthorizationDecision,
    CapabilityPlan,
    CapabilityRequest,
    DomainStatus,
    EvidenceCommit,
    EvidenceDecision,
    ExpertBinding,
    ExpertExecutionKind,
    ExpertUseRecord,
    HarnessRunFingerprint,
    IntentEnvelope,
    SideEffectPolicy,
    SkillUseRecord,
    ToolGrant,
)
from app.services.harness.execution_path import ExecutionPath


def _intent_envelope(**updates: object) -> IntentEnvelope:
    payload: dict[str, object] = {
        "domainStatus": DomainStatus.IN_SCOPE,
        "goal": "market_scan",
        "operations": ("market_scan",),
        "confidence": 0.91,
        "classificationSource": "rules",
        "notes": ("rule:market",),
    }
    payload.update(updates)
    return IntentEnvelope(**payload)


@pytest.mark.parametrize(
    ("field_name", "value"),
    [
        ("allowedTools", ["rank.lookup"]),
        ("requiredSkills", ["webnovel-market-scan"]),
        ("permissions", ["admin"]),
    ],
)
def test_intent_envelope_rejects_execution_and_permission_fields(field_name: str, value: object) -> None:
    with pytest.raises(ValidationError):
        _intent_envelope(**{field_name: value})


def test_contracts_are_frozen_and_use_stable_canonical_fingerprints() -> None:
    envelope = _intent_envelope(
        operations=("market_scan", "opening_strategy", "market_scan"),
        notes=("rule:market", "rule:creative", "rule:market"),
    )
    plan = CapabilityPlan(
        compilerVersion="capability-compiler-v1",
        intentEnvelopeHash=envelope.fingerprint,
        capabilityRequests=(
            CapabilityRequest(capabilityId="market.read", reasonCodes=("intent:market_scan",)),
            CapabilityRequest(capabilityId="creation.opening", reasonCodes=("intent:opening_strategy",)),
            CapabilityRequest(capabilityId="market.read", reasonCodes=("duplicate",)),
        ),
        executionPath=ExecutionPath.COMPLEX,
        sideEffectPolicy=SideEffectPolicy.READ_ONLY,
        skillCandidateIds=("webnovel-market-scan", "webnovel-opening-hook", "webnovel-market-scan"),
        expertCandidateIds=("market_scan", "opening_strategy", "market_scan"),
        requestedToolCapabilities=("market.read", "market.read"),
        reasonCodes=("mixed_plan", "mixed_plan"),
    )
    restored = CapabilityPlan.model_validate(plan.model_dump(mode="json"))

    assert envelope.operations == ("market_scan", "opening_strategy")
    assert envelope.notes == ("rule:market", "rule:creative")
    assert tuple(request.capabilityId for request in plan.capabilityRequests) == (
        "market.read",
        "creation.opening",
    )
    assert plan.skillCandidateIds == ("webnovel-market-scan", "webnovel-opening-hook")
    assert plan.expertCandidateIds == ("market_scan", "opening_strategy")
    assert plan.requestedToolCapabilities == ("market.read",)
    assert plan.reasonCodes == ("mixed_plan",)
    assert restored.fingerprint == plan.fingerprint
    assert restored.planId == plan.planId
    assert plan.fingerprint.startswith("sha256:")
    assert len(plan.fingerprint.removeprefix("sha256:")) == 64

    with pytest.raises(ValidationError):
        envelope.goal = "different"  # type: ignore[misc]


def test_intent_trace_summary_exposes_safe_classification_metadata() -> None:
    summary = _intent_envelope(
        classificationSource="llm_fallback",
        confidence=0.73,
        conversationMode="context_followup",
    ).trace_summary()

    assert summary["classificationSource"] == "llm_fallback"
    assert summary["confidence"] == 0.73
    assert summary["conversationMode"] == "context_followup"


@pytest.mark.parametrize(
    "factory",
    [
        lambda: CapabilityPlan(intentEnvelopeHash="sha256:1", executionPath="ASYNC"),
        lambda: CapabilityPlan(intentEnvelopeHash="sha256:1", sideEffectPolicy="UNBOUNDED_WRITE"),
        lambda: SkillUseRecord(skillId="skill-1", version="1", contentHash="a" * 64, state="LOADED"),
        lambda: ExpertUseRecord(
            bindingId="binding-1",
            expertId="editor",
            executionKind=ExpertExecutionKind.INLINE,
            state="RUNNING",
        ),
    ],
)
def test_contracts_reject_unknown_enum_values(factory) -> None:
    with pytest.raises(ValidationError):
        factory()


def test_contract_string_collection_items_are_bounded() -> None:
    with pytest.raises(ValidationError):
        _intent_envelope(notes=("rule:" + "x" * 252,))


def test_authorization_evidence_and_run_fingerprints_only_expose_bounded_metadata() -> None:
    grant = ToolGrant(
        grantId="grant-market-read",
        capabilityId="market.read",
        toolName="rank.lookup",
        route="market_scan",
        scope="project",
        sideEffectPolicy=SideEffectPolicy.READ_ONLY,
        reasonCodes=("manifest_match",),
    )
    authorization = AuthorizationDecision(
        decisionId="auth-1",
        grants=(grant, grant),
        deniedCapabilityIds=("market.refresh", "market.refresh"),
        reasonCodes=("read_only",),
    )
    evidence = EvidenceCommit(
        commitId="evidence-1",
        decisions=(
            EvidenceDecision(
                evidenceId="rank:1",
                decision="ACCEPTED",
                freshness="latest",
                provenanceRef="snapshot:1",
                citationRef="source:1",
                reasonCodes=("fresh_snapshot",),
            ),
        ),
        canCommit=True,
        repairAllowed=False,
        reasonCodes=("evidence_sufficient",),
    )
    run_fingerprint = HarnessRunFingerprint(
        modelName="deepseek-chat",
        harnessVersion="webnovel-harness-v1",
        compilerVersion="capability-compiler-v1",
        skillBomHash="sha256:skill",
        expertBindingsHash="sha256:expert",
        toolManifestVersion="tool-manifest-v1",
    )

    assert tuple(item.grantId for item in authorization.grants) == ("grant-market-read",)
    assert authorization.deniedCapabilityIds == ("market.refresh",)
    assert evidence.trace_summary()["evidenceIds"] == ["rank:1"]
    assert "content" not in evidence.model_dump(mode="json")
    assert run_fingerprint.fingerprint == HarnessRunFingerprint.model_validate(
        run_fingerprint.model_dump(mode="json")
    ).fingerprint


def test_expert_bindings_use_explicit_dual_identity_and_order_independent_aggregate() -> None:
    first = ExpertBinding(
        bindingId="binding-market",
        expertId="market_scan",
        profileVersion="v2",
        evalConfigFingerprint="a" * 64,
        runtimeBindingFingerprint="b" * 64,
        executionKind=ExpertExecutionKind.DELEGATED,
        capabilityIds=("market.read",),
    )
    second = ExpertBinding(
        bindingId="binding-editor",
        expertId="editor",
        evalConfigFingerprint="c" * 64,
        runtimeBindingFingerprint="d" * 64,
        executionKind=ExpertExecutionKind.INLINE,
        capabilityIds=("creation.edit",),
    )

    first_payload = first.model_dump(mode="json")
    forward = harness_contracts.expert_bindings_hash((
        first.runtimeBindingFingerprint,
        second.runtimeBindingFingerprint,
    ))
    reverse = harness_contracts.expert_bindings_hash((
        second.runtimeBindingFingerprint,
        first.runtimeBindingFingerprint,
    ))

    assert first_payload["evalConfigFingerprint"] == "a" * 64
    assert first_payload["runtimeBindingFingerprint"] == "b" * 64
    assert "profileFingerprint" not in first_payload
    assert forward == reverse
    assert forward.startswith("sha256:")
    assert forward != harness_contracts.expert_bindings_hash((first.runtimeBindingFingerprint,))
    with pytest.raises(ValidationError):
        ExpertBinding.model_validate({**first_payload, "profileFingerprint": "e" * 64})
