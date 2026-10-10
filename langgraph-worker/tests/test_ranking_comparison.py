from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from scripts.agent_comparison import MeasuredProvider
from app.services.evaluation.ranking_comparison import RankingFixtureBackend, compare_ranking_agents, comparison_truth, retrieval_checks


def fixture():
    return json.loads((Path(__file__).parent / "fixtures/rank_comparison_v1.json").read_text(encoding="utf-8"))


@pytest.mark.asyncio
async def test_range_lookup_keeps_actual_boundaries_and_global_row_limit():
    backend = RankingFixtureBackend(fixture())
    rows = await backend.lookup_rank(platform="fanqie", channel_code="male-new", board_code="262", limit=10,
        freshness="time_window", allow_historical=True, snapshot_start_date="2026-09-28", snapshot_end_date="2026-10-04")
    assert len(rows) == 10
    assert {row.snapshotId for row in rows} == {928, 1004}
    assert len([row for row in rows if row.snapshotId == 1004]) == 5
    assert {row.rankNo for row in rows} == {1, 2, 3, 4, 5}


@pytest.mark.asyncio
async def test_latest_window_and_absent_calendar_period_are_distinct():
    backend = RankingFixtureBackend(fixture())
    latest = await backend.lookup_rank(platform="fanqie", limit=10)
    window = await backend.lookup_rank(platform="fanqie", freshness="time_window", allow_historical=True, time_window_days=30, limit=30)
    missing = await backend.lookup_rank(platform="fanqie", freshness="time_window", allow_historical=True,
        snapshot_start_date="2026-01-01", snapshot_end_date="2026-01-07", limit=30)
    assert {row.snapshotId for row in latest} == {1008}
    assert {row.snapshotId for row in window} == {1008, 1005, 1004}
    assert missing == []


def test_truth_tracks_renamed_book_identity_and_top10_membership():
    truth = comparison_truth(fixture())
    assert truth["retentionRate"] == 0.7
    assert truth["enteredTop10"] == [1011, 1012, 1013]
    assert truth["leftTop10"] == [1006, 1009, 1010]
    assert truth["changes"][0] == {"bookId": 1003, "previousRank": 3, "currentRank": 1, "rise": 2}


def test_retrieval_check_cannot_pass_with_only_the_previous_week():
    data = fixture()
    backend = RankingFixtureBackend(data)
    sources = [row.model_dump(mode="json") for row in backend.rows(data["snapshots"][1], historical=True)]
    checks = retrieval_checks(data["cases"][0]["turns"][0], sources, [], "上周资料[1]", data)
    assert checks["snapshotCoverage"] == {"1004": 10, "1008": 0}
    assert not checks["completeRequiredSnapshots"]


NODE = os.getenv("NOVAL_PI_NODE")
SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"


@pytest.mark.asyncio
@pytest.mark.skipif(not NODE or not (SCRIPTS / "pi-eval/node_modules/@earendil-works/pi-coding-agent").is_dir()
                   or not (SCRIPTS / "dsh-eval/node_modules/@deepseek-ai/dsh-agent-loop").is_dir(),
                   reason="Requires both pinned native SDKs and NOVAL_PI_NODE")
async def test_comparison_completes_all_turns_with_actual_native_runtimes(tmp_path):
    class OfflineProvider:
        def __init__(self):
            self.requests = []

        def resolve_provider_profile(self, *args, **kwargs):
            return None

        async def invoke(self, **kwargs):
            self.requests.append(kwargs)
            return {"content": "离线链路检查：没有足够依据给出比较。", "model_name": "test-model",
                    "token_used": 10, "usage": {"inputTokens": 7, "outputTokens": 3, "totalTokens": 10}}

    raw = {mode: OfflineProvider() for mode in ("workflow", "autonomous", "pi_full", "dsh_full")}
    providers = {mode: MeasuredProvider(provider, model="test-model", output_tokens=3072, max_calls=24)
                 for mode, provider in raw.items()}
    report = await compare_ranking_agents(fixture(), providers, node=NODE, output_dir=tmp_path)
    assert len(report["rows"]) == 16, report
    assert all(row["status"] != "error" for row in report["rows"]), report
    assert {mode: sum(row["runtime"] == mode for row in report["rows"]) for mode in raw} == dict.fromkeys(raw, 4)
    native = [row for row in report["rows"] if row["runtime"] in {"pi_full", "dsh_full"}]
    assert all(Path(row["nativeRuns"][-1]["sessionFile"]).is_file() for row in native)
    for mode in ("autonomous", "pi_full", "dsh_full"):
        assert raw[mode].requests
        assert all('"currentDate": "2026-10-08"' in json.dumps(request["messages"], ensure_ascii=False).replace('\\"', '"')
                   for request in raw[mode].requests)
