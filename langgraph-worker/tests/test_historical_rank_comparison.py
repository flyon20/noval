from __future__ import annotations

from datetime import date
import json
from pathlib import Path

import pytest

from app.models.knowledge import KnowledgeChatRequest
from app.services.evaluation.ranking_comparison import RankingFixtureBackend
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.intents import IntentRouter
from app.services.novel_research_agent import NovelResearchAgent
from app.services.rank_comparison import build_rank_comparison, requested_rank_periods


def fixture():
    return json.loads((Path(__file__).parent / "fixtures/rank_comparison_v1.json").read_text(encoding="utf-8"))


class OfflineProvider:
    def __init__(self):
        self.calls = []

    def resolve_provider_profile(self, *args, **kwargs):
        return None

    async def invoke(self, **kwargs):
        self.calls.append(kwargs)
        return {"content": "离线占位回答，不提供榜单比较结论。", "model_name": "test-model", "token_used": 10}


async def run_question(question, *, history=None, backend=None):
    backend = backend or RankingFixtureBackend(fixture())
    provider = OfflineProvider()
    agent = NovelResearchAgent(knowledge_client=backend, provider_client=provider, runtime_mode="workflow")
    agent.intent_router.today_provider = lambda: date(2026, 10, 8)
    try:
        with run_budget_scope(RunBudget(mode="fast", max_total_tokens=80_000, max_tool_calls=8, max_delegations=2)):
            response = await agent.run(KnowledgeChatRequest(question=question, userId=7,
                history=history or [], conversationId="historical-regression", reasoningMode="fast",
                mode="research", limits={"modelName": "test-model", "intentModelName": "test-model"}))
        return response, backend.calls, provider.calls
    finally:
        await agent.aclose()


def test_historical_followup_keeps_rank_capability_and_both_periods():
    decision = IntentRouter(today_provider=lambda: date(2026, 10, 8)).classify(
        "再拿往期历史榜单比较一下", history=["用户：番茄男频都市脑洞新书榜最新 Top10 有哪些书？"])
    assert decision.primaryIntent == "market_scan"
    assert decision.toolNeeds.needsRankData
    periods = decision.entities["rankComparisonPeriods"]
    assert [(item["startDate"], item["endDate"]) for item in periods] == [
        ("2026-09-28", "2026-10-04"), ("2026-10-05", "2026-10-08")]
    assert decision.entities["rankLimit"] == 10


def test_generic_ranking_word_does_not_trigger_exact_rank_one():
    agent = NovelResearchAgent.__new__(NovelResearchAgent)
    assert agent._parse_exact_rank_lookup(fixture()["cases"][1]["turns"][0]["question"]) is None
    assert agent._parse_exact_rank_lookup("番茄男频新书榜都市脑洞排名第一的书是什么")["rank_no"] == 1
    assert agent._parse_exact_rank_lookup("番茄男频新书榜都市脑洞排名第5名是哪本")["rank_no"] == 5


@pytest.mark.asyncio
@pytest.mark.parametrize("followup", [False, True])
async def test_workflow_compares_calendar_periods_and_renamed_book_without_generation(followup):
    data = fixture()
    question = data["cases"][1]["turns"][1]["question"] if followup else data["cases"][0]["turns"][0]["question"]
    history = [{"role": "user", "content": data["cases"][1]["turns"][0]["question"]},
               {"role": "assistant", "content": "已查询 2026-10-08 的番茄男频都市脑洞新书榜 Top10。"}] if followup else []
    response, calls, model_calls = await run_question(question, history=history)
    result = response.resultJson["rankComparison"]
    assert response.status == "answered"
    assert result["comparisonSupported"]
    assert [period["snapshotId"] for period in result["periods"]] == [1004, 1008]
    assert result["retentionRate"] == 0.7
    renamed = next(item for item in result["rankChanges"] if item["bookId"] == 1003)
    assert (renamed["previousRank"], renamed["currentRank"], renamed["delta"]) == (3, 1, 2)
    assert renamed["previousBookName"] == "离职后接管旧书店"
    assert [item["bookId"] for item in result["enteredTopN"]] == [1011, 1012, 1013]
    assert [item["bookId"] for item in result["leftTopN"]] == [1006, 1009, 1010]
    assert len(response.sources) == 20
    assert "70%" in response.answer and "2026-10-04" in response.answer and "2026-10-08" in response.answer
    assert "离职后接管旧书店" in response.answer and "失业后接管旧书店" in response.answer
    assert len(calls) == 2 and all(call["arguments"]["limit"] == 20 for call in calls)
    assert not model_calls


@pytest.mark.asyncio
async def test_absent_period_is_queried_and_not_replaced():
    response, calls, model_calls = await run_question(fixture()["cases"][2]["turns"][0]["question"])
    result = response.resultJson["rankComparison"]
    assert response.status == "insufficient_evidence"
    assert not result["comparisonSupported"] and result["retentionRate"] is None
    assert result["periods"][0]["issue"] == "missing_snapshot"
    assert result["periods"][1]["snapshotId"] == 1008
    assert calls[0]["arguments"]["snapshot_start_date"] == "2026-01-01"
    assert calls[0]["arguments"]["snapshot_end_date"] == "2026-01-07"
    assert "没有存档" in response.answer
    assert len(response.sources) == 10 and not model_calls


@pytest.mark.asyncio
async def test_tool_failure_does_not_become_a_claim_of_missing_archives():
    class FailingBackend(RankingFixtureBackend):
        async def lookup_rank(self, **kwargs):
            if kwargs.get("snapshot_start_date") == "2026-01-01":
                raise RuntimeError("synthetic backend failure")
            return await super().lookup_rank(**kwargs)

    response, _, _ = await run_question(fixture()["cases"][2]["turns"][0]["question"], backend=FailingBackend(fixture()))
    result = response.resultJson["rankComparison"]
    assert not result["comparisonSupported"]
    assert result["periods"][0]["issue"] == "query_failed"
    assert "查询失败" in response.answer and "没有存档" not in response.answer


@pytest.mark.asyncio
async def test_incomplete_last_snapshot_does_not_fall_back_to_an_earlier_complete_one():
    data = fixture()
    data["snapshots"][-1]["bookIds"].pop()
    response, _, _ = await run_question(data["cases"][0]["turns"][0]["question"], backend=RankingFixtureBackend(data))
    result = response.resultJson["rankComparison"]
    assert result["periods"][1]["snapshotId"] == 1008
    assert result["periods"][1]["issue"] == "incomplete_top_n"
    assert not result["comparisonSupported"] and result["retentionRate"] is None
    assert result["rankChanges"] == []


@pytest.mark.parametrize("defect", ["different_board", "duplicate_rank"])
def test_comparison_does_not_compute_from_mismatched_or_invalid_rank_sets(defect):
    data = fixture()
    backend = RankingFixtureBackend(data)
    before = backend.rows(data["snapshots"][1], historical=True)
    after = backend.rows(data["snapshots"][-1], historical=True)
    if defect == "different_board":
        after = [row.model_copy(update={"boardCode": "other-board"}) for row in after]
    else:
        after[-1] = after[-1].model_copy(update={"rankNo": 9})
    periods = requested_rank_periods(data["cases"][0]["turns"][0]["question"], "", date(2026, 10, 8))
    _, _, result = build_rank_comparison(periods, [before, after], top_n=10)
    assert not result["comparisonSupported"] and result["retentionRate"] is None
    assert not result["rankChanges"] and not result["enteredTopN"] and not result["leftTopN"]


def test_calendar_week_boundary_can_cross_a_year():
    periods = requested_rank_periods("比较本周和上周榜单 Top10", "", date(2027, 1, 2))
    assert [(item["startDate"], item["endDate"]) for item in periods] == [
        ("2026-12-21", "2026-12-27"), ("2026-12-28", "2027-01-02")]


@pytest.mark.asyncio
async def test_latest_top10_question_delivers_all_ten_instead_of_only_rank_one():
    response, calls, _ = await run_question(fixture()["cases"][1]["turns"][0]["question"])
    assert {source.snapshotId for source in response.sources} == {1008}
    assert {source.rankNo for source in response.sources} == set(range(1, 11))
    assert "失业后接管旧书店" in response.answer and "我在街角收怪谈" in response.answer
    assert "2026-10-08" in response.answer
    assert all(call["arguments"].get("rank_no") is None for call in calls)
