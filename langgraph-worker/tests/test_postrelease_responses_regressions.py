import json
from datetime import datetime, timezone
from unittest.mock import AsyncMock

import pytest

import app.services.harness
from app.config import settings
from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource, RankLookupResult
from app.services.intents.domain_intents import Intent, IntentDecision
from app.services.novel_research_agent import NovelResearchAgent
from app.services.provider_client import OpenAICompatibleProviderClient, ProviderProfile


CATEGORIES = ("\u90fd\u5e02\u8111\u6d1e", "\u90fd\u5e02\u65e5\u5e38")


@pytest.mark.parametrize("model", ["gpt-5.6-sol", "deepseek-v4-pro"])
@pytest.mark.parametrize("mode", ["fast", "deep"])
def test_selected_model_applies_to_intent_review_and_answer(monkeypatch, model, mode):
    monkeypatch.setattr(settings, "intent_model", "deepseek-v4-flash")
    monkeypatch.setattr(settings, "review_model", "deepseek-v4-flash")
    agent = NovelResearchAgent()
    request = KnowledgeChatRequest(
        question="synthetic model routing", reasoningMode=mode,
        limits={"modelKey": "selected-profile", "modelName": model},
    )
    profiles = [
        {"profileKey": key, "profileVersion": "v1", "model": model,
         "protocol": "responses", "providerType": "openai-compatible",
         "endpoint": f"https://{key}.example/v1", "isDefault": key == "default-profile"}
        for key in ("default-profile", "selected-profile")
    ]
    state = {"request": request, "runtime_config": {"providerProfiles": profiles}}
    for resolve in (agent._model_name, agent._intent_model_name, agent._review_model_name):
        assert resolve(request) == model
        assert agent._provider_profile_for_state(state, resolve(request))["profileKey"] == "selected-profile"


def test_stage_defaults_remain_available_without_a_selected_model(monkeypatch):
    monkeypatch.setattr(settings, "intent_model", "legacy-intent")
    monkeypatch.setattr(settings, "review_model", "legacy-review")
    agent = NovelResearchAgent()
    request = KnowledgeChatRequest(question="synthetic default routing")
    assert agent._intent_model_name(request) == "legacy-intent"
    assert agent._review_model_name(request) == "legacy-review"


@pytest.mark.parametrize("stream", [False, True])
@pytest.mark.parametrize("host", ["gateway.example", "api.openai.com"])
def test_responses_json_mode_has_an_input_instruction(stream, host):
    provider = OpenAICompatibleProviderClient()
    profile = ProviderProfile(
        profile_key="selected-profile", profile_version="v1", model="gpt-5.6-sol",
        protocol="responses", endpoint=f"https://{host}/v1", api_key="synthetic-test-key",
    )
    messages = [
        {"role": "system", "content": "Return JSON only."},
        {"role": "user", "content": "Return an object containing ok set to true."},
    ]
    payload = provider._build_payload(
        messages=messages, model=profile.model, temperature=0, max_tokens=256,
        require_json=True, stream=stream, reasoning_mode="fast",
        cache_affinity="synthetic-session", wire_api="responses", provider_profile=profile,
    )
    assert payload["text"]["format"]["type"] == "json_object"
    assert any(item.get("role") == "developer" and "json" in str(item.get("content")).lower()
               for item in payload["input"])
    assert payload["model"] == profile.model
    assert payload["prompt_cache_key"]
    assert "messages" not in payload
    assert len(messages) == 2
    if host == "gateway.example":
        assert "prompt_cache_options" not in payload
        assert payload["instructions"] == messages[0]["content"]
        assert payload["input"][0] == messages[1]


def rank_sources(category, count):
    board_index = CATEGORIES.index(category) + 1
    return [KnowledgeSource(
        chunkId=board_index * 1000 + rank, documentId=board_index * 1000 + rank,
        score=1, sourceType="RANK", platform="fanqie", channelCode="male-new",
        boardCode="262" if board_index == 1 else "261", category=category,
        rankNo=rank, bookId=board_index * 1000 + rank, bookName=f"Synthetic book {board_index}-{rank}",
        title=category, preview="Synthetic rank record.", snapshotId=board_index,
        snapshotTime=datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    ) for rank in range(1, count + 1)]


def market_state():
    return {
        "request": KnowledgeChatRequest(
            question="\u5f53\u524d\u7537\u9891\u90fd\u5e02\u8111\u6d1e\u548c\u90fd\u5e02\u65e5\u5e38\u7684\u699c\u5355\u8d8b\u52bf\u5982\u4f55\uff1f",
            reasoningMode="deep", limits={"rankLimit": 30},
        ),
        "domain_intent": "market_scan", "intent": "trend_research",
        "intent_decision": IntentDecision(primaryIntent=Intent.market_scan, confidence=0.9).model_dump(mode="json"),
        "source_policy": {"currentRankLimit": 30, "requiredEvidence": "current_structured_rank_topn"},
        "runtime_config": {"maxEvidenceItems": 30},
        "tool_runs": [],
    }


@pytest.mark.asyncio
async def test_complete_category_snapshots_are_validated_before_citation_budget(monkeypatch):
    agent = NovelResearchAgent()
    lookup = AsyncMock(return_value=[])
    monkeypatch.setattr(agent, "_lookup_rank_sources_for_trend", lookup)
    state = market_state()
    result = await agent._multi_category_rank_evidence(
        state, list(CATEGORIES), rank_sources(CATEGORIES[0], 30) + rank_sources(CATEGORIES[1], 30),
    )
    policy = result["source_policy"]
    assert not policy["trendGateFailed"]
    assert policy["missingCategories"] == []
    assert policy["evidenceContract"]["status"] == "verified_latest"
    assert policy["evidenceCommit"]["canCommit"]
    assert policy["structuredRankCount"] == 60
    assert len(result["sources"]) == 30
    assert all(sum(source.category == category for source in result["sources"]) == 15 for category in CATEGORIES)
    assert all(p["currentRankLimit"] == 30 for p in policy["categoryPolicies"].values())
    lookup.assert_not_awaited()
    analysis = agent._market_snapshot_analysis_payload(result["sources"], requested_current_count=30)
    assert not analysis["currentCoverageComplete"]
    assert analysis["coverageGap"] == 30


@pytest.mark.asyncio
async def test_genuinely_incomplete_category_still_fails_before_citation_budget(monkeypatch):
    agent = NovelResearchAgent()
    monkeypatch.setattr(agent, "_lookup_rank_sources_for_trend", AsyncMock(return_value=rank_sources(CATEGORIES[1], 20)))
    result = await agent._multi_category_rank_evidence(
        market_state(), list(CATEGORIES), rank_sources(CATEGORIES[0], 30) + rank_sources(CATEGORIES[1], 20),
    )
    assert result["source_policy"]["missingCategories"] == [CATEGORIES[1]]
    assert not result["source_policy"]["evidenceCommit"]["canCommit"]
    assert len(result["sources"]) <= 30


@pytest.mark.asyncio
async def test_budget_without_space_for_each_category_cannot_claim_complete_comparison():
    agent = NovelResearchAgent()
    state = market_state()
    state["runtime_config"]["maxEvidenceItems"] = 1
    result = await agent._multi_category_rank_evidence(
        state, list(CATEGORIES), rank_sources(CATEGORIES[0], 30) + rank_sources(CATEGORIES[1], 30),
    )
    assert len(result["sources"]) == 1
    assert result["source_policy"]["trendGateFailed"]
    assert result["source_policy"]["missingCategories"] == [CATEGORIES[1]]
    assert not result["source_policy"]["evidenceCommit"]["canCommit"]


@pytest.mark.parametrize("gate", [
    {"trendGateFailed": True},
    {"evidenceContract": {"status": "missing"}},
])
def test_rejected_market_evidence_does_not_start_extra_model_analysis(gate):
    agent = NovelResearchAgent()
    state = market_state()
    state["source_policy"].update(gate)
    assert not agent._should_run_market_evidence_analysis(state)


@pytest.mark.asyncio
@pytest.mark.parametrize("stream", [False, True])
async def test_full_two_category_qa_returns_an_answer_with_bounded_citations(stream):
    import test_novel_research_agent as fixtures

    class KnowledgeClient(fixtures.FakeKnowledgeClient):
        async def get_agent_runtime_config(self):
            return {"maxEvidenceItems": 30}

        async def lookup_rank(self, **kwargs):
            category = kwargs.get("category")
            if category not in CATEGORIES:
                return []
            return [RankLookupResult(
                rankId=source.chunkId, snapshotId=source.snapshotId, snapshotTime=source.snapshotTime,
                platform=source.platform, channelCode=source.channelCode, boardCode=source.boardCode,
                category=source.category, rankNo=source.rankNo, bookId=source.bookId,
                bookName=source.bookName, intro=source.preview, sourceLabel=source.category,
            ) for source in rank_sources(category, 30)]

    class Provider:
        def __init__(self):
            self.calls = []

        async def invoke(self, **kwargs):
            self.calls.append(kwargs)
            content = "Synthetic category comparison grounded in the supplied rank records. [1]"
            if kwargs.get("require_json"):
                content = json.dumps({
                    "primaryIntent": "market_scan", "confidence": 0.99,
                    "toolNeeds": {"needsRankData": True}, "answerBoundary": "market_evidence",
                })
            return {"model_name": kwargs["model"], "content": content, "token_used": 12}

    provider = Provider()
    agent = NovelResearchAgent(knowledge_client=KnowledgeClient(), provider_client=provider)
    agent.intent_agent.llm_agent.model_first_enabled = True
    request = market_state()["request"].model_copy(update={
        "limits": {"modelKey": "selected-profile", "modelName": "gpt-5.6-sol", "rankLimit": 30},
    })
    if stream:
        events = [event async for event in agent.stream(request)]
        result = next(event["data"] for event in events if event["event"] == "done")
    else:
        result = (await agent.run(request)).model_dump()
    assert result["status"] == "answered", result["resultJson"].get("sourcePolicy")
    assert len(result["sources"]) == 30
    assert len(result["answer"]) > 100
    assert all(category in result["answer"] for category in CATEGORIES)
    assert all(call["model"] == "gpt-5.6-sol" for call in provider.calls)
    assert any(call.get("request_family") == "intent" for call in provider.calls)
