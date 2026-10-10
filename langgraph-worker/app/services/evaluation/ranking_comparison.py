"""Controlled historical-ranking tasks with a faithful, fixed snapshot backend."""
from __future__ import annotations

import asyncio
from collections import Counter
from copy import deepcopy
from datetime import date, datetime, timedelta
from pathlib import Path
import re
import time
from typing import Any, Callable

from app.models.knowledge import KnowledgeChatRequest, RankLookupResult, RankResearchPack
from app.services.harness.autonomous_context import AutonomousConversationContext
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.novel_research_agent import NovelResearchAgent


class RankingFixtureBackend:
    """Mirror newest-first window lookup and balanced date-range boundaries.

    These are the repository's Backend query semantics. They intentionally do
    not hand the model the correct comparison or silently enlarge row limits.
    """

    def __init__(self, fixture: dict[str, Any], *, output_tokens: int = 3072) -> None:
        self.fixture = deepcopy(fixture)
        self.output_tokens = output_tokens
        self.calls: list[dict[str, Any]] = []

    async def get_agent_runtime_config(self):
        return {"maxFinalOutputTokensFast": self.output_tokens, "maxFinalOutputTokensDeep": self.output_tokens,
                "specialistMcpEnabled": False}

    async def get_project_memory(self, **kwargs):
        return {"summary": "", "items": []}

    async def search_evidence(self, **kwargs):
        self.calls.append({"tool": "knowledge.vector_search", "arguments": {"query": kwargs.get("query")}, "returnedRows": 0})
        return []

    async def get_rank_research_pack(self, **kwargs):
        rows = await self.lookup_rank(**kwargs)
        self.calls[-1]["tool"] = "rank.research_pack"
        return RankResearchPack(ranks=rows)

    def rows(self, snapshot: dict[str, Any], *, historical: bool) -> list[RankLookupResult]:
        scope = self.fixture["scope"]
        stamp = datetime.fromisoformat(snapshot["time"])
        now = datetime.fromisoformat(self.fixture["asOfDate"] + "T12:00:00")
        return [RankLookupResult(
            rankId=snapshot["id"] * 100 + rank, snapshotId=snapshot["id"], snapshotTime=snapshot["time"],
            **scope, channelName="男频新书榜", boardName=scope["category"], rankNo=rank, bookId=book_id,
            bookName=snapshot.get("names", {}).get(str(book_id), self.fixture["books"][str(book_id)]["name"]),
            author=f"测试作者{book_id}", intro=self.fixture["books"][str(book_id)]["intro"],
            sourceLabel=f"男频新书榜 / {scope['category']} #{rank}",
            freshness="FRESH" if (now - stamp).days < 3 else "STALE",
            ageHours=max(0, (now - stamp).total_seconds() / 3600), historicalReference=historical,
        ) for rank, book_id in enumerate(snapshot["bookIds"], 1)]

    async def lookup_rank(self, **kwargs):
        start, end = kwargs.get("snapshot_start_date"), kwargs.get("snapshot_end_date")
        if (start is None) != (end is None):
            raise ValueError("both snapshot dates are required")
        if start is not None:
            start, end = date.fromisoformat(str(start)), date.fromisoformat(str(end))
            if start > end or not kwargs.get("allow_historical"):
                raise ValueError("invalid historical query")
        scope = self.fixture["scope"]
        matches = all(kwargs.get(argument) in (None, value) for argument, value in (
            ("platform", scope["platform"]), ("channel_code", scope["channelCode"]),
            ("board_code", scope["boardCode"]), ("category", scope["category"]),
        ))
        snapshots = sorted(self.fixture["snapshots"], key=lambda item: item["time"], reverse=True) if matches else []
        historical = bool(kwargs.get("allow_historical"))
        if start is not None:
            snapshots = [item for item in snapshots if start <= date.fromisoformat(item["time"][:10]) <= end]
            snapshots = [snapshots[0], snapshots[-1]] if len(snapshots) > 1 else snapshots
        elif snapshots and historical and kwargs.get("freshness") == "time_window":
            cutoff = datetime.fromisoformat(snapshots[0]["time"]) - timedelta(days=max(1, min(365, int(kwargs.get("time_window_days") or 30))))
            snapshots = [item for item in snapshots if datetime.fromisoformat(item["time"]) >= cutoff]
        else:
            snapshots = snapshots[:1]
        groups = [[row for row in self.rows(snapshot, historical=historical)
                   if kwargs.get("rank_no") in (None, row.rankNo)] for snapshot in snapshots]
        if start is not None:
            rows = [group[index] for index in range(max(map(len, groups), default=0)) for group in groups if index < len(group)]
        else:
            rows = [row for group in groups for row in group]
        rows = rows[:max(1, min(100, int(kwargs.get("limit") or 10)))]
        self.calls.append({"tool": "rank.lookup", "arguments": deepcopy(kwargs), "returnedRows": len(rows),
                           "snapshots": dict(Counter(str(row.snapshotId) for row in rows))})
        return rows


def comparison_truth(fixture: dict[str, Any], previous_id: int = 1004, current_id: int = 1008) -> dict[str, Any]:
    snapshots = {item["id"]: item for item in fixture["snapshots"]}
    previous, current = snapshots[previous_id], snapshots[current_id]
    before = {book_id: rank for rank, book_id in enumerate(previous["bookIds"], 1)}
    after = {book_id: rank for rank, book_id in enumerate(current["bookIds"], 1)}
    common = before.keys() & after.keys()
    return {"previousSnapshot": previous_id, "currentSnapshot": current_id,
            "previousDate": previous["time"], "currentDate": current["time"],
            "changes": [{"bookId": book_id, "previousRank": before[book_id], "currentRank": after[book_id],
                         "rise": before[book_id] - after[book_id]} for book_id in sorted(common, key=after.get)],
            "enteredTop10": [book_id for book_id in after if book_id not in before],
            "leftTop10": [book_id for book_id in before if book_id not in after],
            "retentionRate": len(common) / len(before), "denominator": len(before)}


def retrieval_checks(turn: dict[str, Any], sources: list[dict[str, Any]], calls: list[dict[str, Any]], answer: str,
                     fixture: dict[str, Any]) -> dict[str, Any]:
    expected = {item["id"]: set(item["bookIds"]) for item in fixture["snapshots"]}
    coverage = {}
    for snapshot in turn["expectedSnapshots"]:
        observed = {source.get("bookId") for source in sources if source.get("snapshotId") == snapshot
                    and all(source.get(field) == value for field, value in fixture["scope"].items())}
        coverage[str(snapshot)] = len(expected[snapshot] & observed)
    citations = [int(number) for number in re.findall(r"\[(\d+)\]", answer)]
    result = {"snapshotCoverage": coverage, "completeRequiredSnapshots": all(count == 10 for count in coverage.values()),
              "hasCitations": bool(citations), "citationsInRange": all(1 <= number <= len(sources) for number in citations)}
    if turn["kind"] == "missing":
        start, end = turn["missingRange"]
        result["queriedMissingPeriod"] = any(
            call["arguments"].get("snapshot_start_date") == start and call["arguments"].get("snapshot_end_date") == end
            and call.get("returnedRows") == 0 for call in calls if call["tool"].startswith("rank.")
        )
    return result


async def compare_ranking_agents(
    fixture: dict[str, Any], providers: dict[str, Any], *, node: str, output_dir: Path,
    on_result: Callable[[dict[str, Any]], None] | None = None,
) -> dict[str, Any]:
    runtimes = tuple(providers)
    if not runtimes or set(runtimes) - {"workflow", "autonomous", "pi_full", "dsh_full"}:
        raise ValueError("unsupported ranking comparison runtime")
    as_of = date.fromisoformat(fixture["asOfDate"])
    rows = []
    for case_index, case in enumerate(fixture["cases"]):
        order = runtimes[case_index % len(runtimes):] + runtimes[:case_index % len(runtimes)]
        for mode in order:
            provider = providers[mode]
            backend = RankingFixtureBackend(fixture, output_tokens=provider.output_tokens)
            history = []
            context = AutonomousConversationContext()
            kernel = None
            try:
                for turn_index, turn in enumerate(case["turns"]):
                    agent = NovelResearchAgent(knowledge_client=backend, provider_client=provider,
                        runtime_mode="workflow" if mode == "workflow" else "autonomous", autonomous_context=context)
                    agent.intent_router.today_provider = lambda: as_of
                    if mode in {"pi_full", "dsh_full"}:
                        if kernel is None:
                            if mode == "pi_full":
                                from app.services.evaluation.pi_session import PiSessionEvaluationKernel as SessionKernel
                            else:
                                from app.services.evaluation.dsh_session import DshSessionEvaluationKernel as SessionKernel
                            kernel = SessionKernel(agent.agent_kernel, node=node, session_dir=output_dir / "sessions" / case["id"] / mode)
                        kernel.host = agent.agent_kernel
                        agent.agent_kernel = kernel
                        agent.harness.agent_kernel = kernel
                    first_call, first_tool = len(provider.calls), len(backend.calls)
                    first_native = len(kernel.runs) if kernel else 0
                    row = {"caseId": case["id"], "turn": turn_index + 1, "runtime": mode, "kind": turn["kind"], "question": turn["question"]}
                    budget = RunBudget(mode="fast", max_total_tokens=80_000, max_tool_calls=8, max_delegations=2)
                    started = time.perf_counter()
                    try:
                        request = KnowledgeChatRequest(question=turn["question"], userId=7, history=deepcopy(history),
                            conversationId=f"ranking-{case['id']}-{mode}", reasoningMode="fast", mode="research",
                            contextSummary=f"当前日期为 {fixture['asOfDate']}。",
                            limits={"modelName": provider.model, "intentModelName": provider.model,
                                    "reviewModelName": provider.model, "timeoutMillis": 60_000})
                        with run_budget_scope(budget):
                            async with asyncio.timeout(180):
                                response = await agent.run(request)
                        sources = [source.model_dump(mode="json", exclude_none=True) for source in response.sources]
                        row.update({"status": response.status, "answer": response.answer, "sources": sources,
                                    "resultJson": response.resultJson,
                                    "retrievalChecks": retrieval_checks(turn, sources, backend.calls[first_tool:], response.answer, fixture)})
                        history.extend(({"role": "user", "content": turn["question"]}, {"role": "assistant", "content": response.answer}))
                    except Exception as exc:
                        row.update({"status": "error", "errorType": type(exc).__name__})
                    finally:
                        await agent.aclose()
                    calls = deepcopy(provider.calls[first_call:])
                    row.update({"providerCalls": calls, "backendCalls": deepcopy(backend.calls[first_tool:]),
                        "providerCallCount": len(calls), "elapsedMs": round((time.perf_counter() - started) * 1000),
                        "providerElapsedMs": sum(call["durationMs"] for call in calls),
                        "reportedTokens": sum(call.get("tokens") or 0 for call in calls),
                        "reportedInputTokens": sum(call.get("usage", {}).get("inputTokens", 0) for call in calls),
                        "reportedOutputTokens": sum(call.get("usage", {}).get("outputTokens", 0) for call in calls),
                        "usageComplete": all(call.get("status") == "succeeded" and call.get("tokens") is not None for call in calls),
                        "modelCalled": bool(calls), "budget": budget.snapshot()})
                    if kernel:
                        row["nativeRuns"] = deepcopy(kernel.runs[first_native:])
                    rows.append(row)
                    if on_result:
                        on_result(row)
                    if row["status"] == "error":
                        break
            finally:
                if kernel:
                    await kernel.aclose()
    return {"schemaVersion": 1, "fixtureId": fixture["id"], "measurement": "real_provider_with_synthetic_rank_snapshots",
            "runtimes": list(runtimes), "truth": comparison_truth(fixture), "qualityVerdict": "manual_review_required", "rows": rows}
