from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from app.services.harness.capability_authorizer import CapabilityAuthorizer
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import (
    CapabilityScope,
    DataAccessFilter,
    DataAccessPlan,
    DataAccessRequest,
    DataTemporalScope,
    DomainStatus,
    IntentEnvelope,
)
from app.services.harness.data_access_planner import DataAccessPlanner
from app.services.intents.domain_intents import IntentDataAccessProposal
from app.services.skills.registry import SkillRegistry


def _envelope(*operations: str, entities: dict | None = None) -> IntentEnvelope:
    return IntentEnvelope(
        domainStatus=DomainStatus.IN_SCOPE,
        goal=operations[0] if operations else "market_scan",
        operations=operations or ("market_scan",),
        entities=entities or {},
        confidence=0.92,
        classificationSource="llm_fallback",
        notes=("llm:data_access",),
    )


def test_llm_semantic_proposal_is_bounded_and_compiles_to_existing_tools() -> None:
    question = "Why is this topic absent from the recent male-new list; compare the latest six snapshots."
    envelope = _envelope(
        "market_scan",
        entities={
            "marketQuestionType": "taxonomy_absence",
            "dataAccess": [
                {
                    "datasetCapability": "market.history",
                    "purpose": "market_taxonomy",
                    "temporalScope": {
                        "mode": "LATEST_N_SNAPSHOTS",
                        "latestNSnapshots": 6,
                    },
                    "retrievalChannels": ["structured", "fulltext", "vector"],
                    "evidenceTypes": ["current_rank", "historical_snapshot"],
                    "filters": [{"field": "board", "value": "male-new"}],
                    "limit": 500,
                    "required": True,
                    "reasonCodes": ["llm:taxonomy_absence"],
                }
            ],
        },
    )

    data_plan = DataAccessPlanner().plan(
        envelope,
        semantic_query=question,
        request_scope=CapabilityScope(userId=7),
    )
    capability_plan = CapabilityCompiler().compile(
        envelope,
        request_scope=CapabilityScope(userId=7),
        data_access_plan=data_plan,
    )
    authorization = CapabilityAuthorizer().authorize(capability_plan)

    assert data_plan.proposalSource == "intent_entities"
    assert data_plan.rejectedProposalCount == 0
    assert len(data_plan.requests) == 1
    request = data_plan.requests[0]
    assert request.semanticQuery == question
    assert request.datasetCapability.value == "market.history"
    assert request.temporalScope.mode.value == "LATEST_N_SNAPSHOTS"
    assert request.temporalScope.latestNSnapshots == 6
    assert request.limit == 60
    assert request.reasonCodes == (
        "planner:intent_entities",
        "dataset:market.history",
        "purpose:market_taxonomy",
    )
    assert "llm:taxonomy_absence" not in json.dumps(
        data_plan.trace_summary(),
        ensure_ascii=False,
    )
    assert capability_plan.dataAccessPlanHash == data_plan.fingerprint
    assert capability_plan.dataAccessRequestIds == (request.requestId,)
    assert "governed-data-access" in capability_plan.skillCandidateIds
    assert "market.research" in {
        item.capabilityId for item in capability_plan.capabilityRequests
    }
    assert "market.historical_rank" in capability_plan.evidenceRequirements
    assert {
        "rank.lookup",
        "rank.research_pack",
        "knowledge.vector_search",
    }.issubset(CapabilityAuthorizer().allowed_tool_names(authorization))


@pytest.mark.parametrize(
    "payload",
    [
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "latest market",
            "sql": "select * from users",
        },
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "latest market",
            "table": "knowledge_rank_snapshot",
        },
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "latest market",
            "userId": 99,
        },
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "latest market",
            "permissions": ["admin"],
        },
        {
            "datasetCapability": "identity.user",
            "purpose": "market_current_state",
            "semanticQuery": "latest market",
        },
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "SELECT password FROM users",
        },
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "load https://example.com/private",
        },
        {
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "semanticQuery": "read ../../etc/passwd",
        },
    ],
)
def test_contract_rejects_sensitive_or_executable_data_requests(payload: dict) -> None:
    with pytest.raises(ValidationError):
        DataAccessRequest.model_validate(payload)


def test_contract_rejects_tenant_filters_and_invalid_temporal_ranges() -> None:
    with pytest.raises(ValidationError):
        DataAccessFilter(field="userId", value="7")
    with pytest.raises(ValidationError):
        DataTemporalScope(mode="AS_OF")
    with pytest.raises(ValidationError):
        DataTemporalScope(mode="RANGE", startDate="2026-08-08", endDate="2026-08-01")
    with pytest.raises(ValidationError):
        DataTemporalScope(mode="LATEST_N_SNAPSHOTS", latestNSnapshots=0)


def test_exact_historical_range_projects_calendar_dates_to_rank_source_policy() -> None:
    planner = DataAccessPlanner()
    envelope = _envelope(
        "market_scan",
        entities={
            "dataAccess": [
                {
                    "datasetCapability": "market.history",
                    "purpose": "market_taxonomy",
                    "temporalScope": {
                        "mode": "RANGE",
                        "startDate": "2026-08-03",
                        "endDate": "2026-08-09",
                    },
                    "retrievalChannels": ["structured"],
                    "evidenceTypes": ["historical_snapshot"],
                }
            ]
        },
    )
    data_plan = planner.plan(
        envelope,
        semantic_query="compare last week's genres",
        request_scope=CapabilityScope(userId=7),
    )
    capability_plan = CapabilityCompiler().compile(
        envelope,
        request_scope=CapabilityScope(userId=7),
        data_access_plan=data_plan,
    )

    constraints = planner.market_tool_constraints(data_plan, capability_plan)

    assert constraints["sourcePolicy"] == {
        "freshness": "time_window",
        "allowHistorical": True,
        "requireSnapshotTime": True,
        "snapshotStartDate": "2026-08-03",
        "snapshotEndDate": "2026-08-09",
        "timeWindowDays": 7,
    }


@pytest.mark.parametrize(
    "filter_value",
    [
        "SELECT password FROM users",
        "https://example.com/private",
        "../../etc/passwd",
    ],
)
def test_intent_proposal_rejects_sensitive_filter_values(filter_value: str) -> None:
    with pytest.raises(ValidationError):
        IntentDataAccessProposal.model_validate({
            "datasetCapability": "market.rank",
            "purpose": "market_current_state",
            "filters": [{"field": "board", "value": filter_value}],
        })


@pytest.mark.parametrize(
    "extra_field,extra_value",
    [
        ("sql", "select * from rank_snapshot"),
        ("table", "knowledge_rank_snapshot"),
        ("userId", 99),
        ("projectId", 88),
        ("permissions", ["admin"]),
    ],
)
def test_intent_proposal_rejects_sensitive_or_tenant_fields(
    extra_field: str,
    extra_value: object,
) -> None:
    payload = {
        "datasetCapability": "market.rank",
        "purpose": "market_current_state",
        extra_field: extra_value,
    }
    with pytest.raises(ValidationError):
        IntentDataAccessProposal.model_validate(payload)


def test_board_code_entity_becomes_a_bounded_board_filter() -> None:
    plan = DataAccessPlanner().plan(
        _envelope("market_scan", entities={"boardCode": "urban-brain"}),
        semantic_query="latest urban brain ranking",
        request_scope=CapabilityScope(userId=7),
    )

    filters = {
        item.field.value: item.value
        for item in plan.requests[0].filters
    }
    assert filters["board"] == "urban-brain"


def test_invalid_model_proposal_fails_closed_to_minimum_deterministic_plan() -> None:
    envelope = _envelope(
        "market_scan",
        entities={
            "dataAccess": [
                {
                    "datasetCapability": "market.history",
                    "purpose": "market_history",
                    "sql": "select * from rank_snapshots",
                }
            ],
        },
    )

    plan = DataAccessPlanner().plan(
        envelope,
        semantic_query="compare recent rank snapshots",
        request_scope=CapabilityScope(userId=7),
    )

    assert plan.proposalSource == "deterministic_fallback"
    assert plan.rejectedProposalCount == 1
    assert [item.datasetCapability.value for item in plan.requests] == ["market.rank"]
    assert "invalid_intent_data_access_proposal" in plan.reasonCodes


def test_deterministic_plan_is_observational_and_does_not_expand_runtime_requirements() -> None:
    envelope = _envelope("market_scan", "market_research")
    data_plan = DataAccessPlanner().plan(
        envelope,
        semantic_query="recent market analysis",
        request_scope=CapabilityScope(userId=7),
    )

    capability_plan = CapabilityCompiler().compile(
        envelope,
        request_scope=CapabilityScope(userId=7),
        data_access_plan=data_plan,
    )

    assert data_plan.proposalSource == "deterministic_default"
    assert capability_plan.dataAccessPlanHash == data_plan.fingerprint
    assert capability_plan.dataAccessRequestIds == ()
    assert "market.historical_rank" not in capability_plan.evidenceRequirements
    assert "governed-data-access" not in capability_plan.skillCandidateIds


def test_cross_operation_forgery_cannot_expand_authorized_tools() -> None:
    envelope = _envelope("market_scan")
    forged_request = DataAccessRequest(
        datasetCapability="book.source",
        purpose="book_analysis",
        semanticQuery="read another user's draft",
        retrievalChannels=("fulltext", "vector"),
        evidenceTypes=("book_source",),
    )
    forged_plan = DataAccessPlan(
        intentEnvelopeHash=envelope.fingerprint,
        proposalSource="intent_entities",
        requests=(forged_request,),
    )

    capability_plan = CapabilityCompiler().compile(
        envelope,
        data_access_plan=forged_plan,
    )
    authorization = CapabilityAuthorizer().authorize(capability_plan)
    tools = CapabilityAuthorizer().allowed_tool_names(authorization)

    assert "book.read" not in {
        item.capabilityId for item in capability_plan.capabilityRequests
    }
    assert "book.search" not in tools
    assert "book.research_pack" not in tools
    assert "data_access_denied:book.source" in capability_plan.reasonCodes


def test_plan_trace_is_sanitized_and_skill_grants_no_capabilities() -> None:
    query = "compare the private-looking title with the male-new board"
    plan = DataAccessPlanner().plan(
        _envelope("market_scan", entities={"board": "male-new"}),
        semantic_query=query,
        request_scope=CapabilityScope(userId=7),
    )

    rendered = json.dumps(plan.trace_summary(), ensure_ascii=False)
    assert query not in rendered
    assert "male-new" not in rendered
    assert "semanticQueryFingerprint" in rendered
    assert "filterFields" in rendered

    skill = next(
        item for item in SkillRegistry().load_all()
        if item.skillId == "governed-data-access"
    )
    assert skill.requestedCapabilities == ()
    assert "SQL" in skill.activation_prompt()


def test_data_access_plan_must_match_the_intent_envelope() -> None:
    first = _envelope("market_scan")
    second = _envelope("book_breakdown")
    plan = DataAccessPlanner().plan(
        first,
        semantic_query="latest market",
        request_scope=CapabilityScope(userId=7),
    )

    with pytest.raises(ValueError, match="intent envelope"):
        CapabilityCompiler().compile(second, data_access_plan=plan)
