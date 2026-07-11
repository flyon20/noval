"""
Golden Eval Suite: Market Scan

Tests pure market trend queries and rank fact lookups.
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.models.knowledge import (
    BookProfile,
    KnowledgeChatRequest,
    KnowledgeSource,
    RankLookupResult,
    RankResearchPack,
)
from app.services.evaluation import GoldenEvalCase, RetrievalEvalThresholds
from app.services.novel_research_agent import NovelResearchAgent


class GoldenEvalMarketScanTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cases_file = Path(__file__).parent / "golden_cases" / "market_scan_cases.json"
        with cases_file.open("r", encoding="utf-8") as f:
            raw_cases = json.load(f)
        cls.cases = [cls._parse_case(raw) for raw in raw_cases]

    @classmethod
    def _parse_case(cls, raw: dict) -> GoldenEvalCase:
        return GoldenEvalCase(
            case_id=raw["case_id"],
            question=raw["question"],
            request_payload=raw.get("request_payload", {}),
            expected_intent=raw.get("expected_intent"),
            expected_answer_mode=raw.get("expected_answer_mode"),
            relevant_source_ids=set(raw.get("relevant_source_ids", [])),
            forbidden_claims=raw.get("forbidden_claims", []),
            retrieval_thresholds=RetrievalEvalThresholds(**raw.get("retrieval_thresholds", {})),
        )

    async def test_market_scan_001_male_urban_trend(self) -> None:
        """最近番茄男频都市脑洞新书榜风向是什么？"""
        case = self._find_case("market-scan-001")

        self.assertEqual("market_scan", case.expected_intent)
        self.assertEqual("trend", case.expected_answer_mode)
        self.assertIn("世界首富", case.forbidden_claims)
        self.assertGreaterEqual(len(case.relevant_source_ids), 3)

    async def test_market_scan_002_rank_one_fact(self) -> None:
        """番茄男频都市脑洞新书榜第一名是什么书？"""
        case = self._find_case("market-scan-002")

        self.assertEqual("market_scan", case.expected_intent)
        self.assertEqual("rank_fact", case.expected_answer_mode)
        self.assertEqual(1.0, case.retrieval_thresholds.min_hit_rate_at_k)
        self.assertIn("rank:101", case.relevant_source_ids)

    async def test_market_scan_003_hot_topics(self) -> None:
        """最近男频都市脑洞热门题材有哪些？"""
        case = self._find_case("market-scan-003")

        self.assertEqual("trend", case.expected_answer_mode)
        self.assertGreaterEqual(len(case.relevant_source_ids), 5)

    async def test_market_scan_004_female_romance_top5(self) -> None:
        """番茄女频现言榜前五名都是什么类型的书？"""
        case = self._find_case("market-scan-004")

        self.assertEqual("trend", case.expected_answer_mode)
        self.assertIn("rank:201", case.relevant_source_ids)

    async def test_pure_market_mixed_snapshot_blocks_or_refreshes_without_creative_inference(self) -> None:
        client = MixedSnapshotMarketKnowledgeClient()
        provider = MarketGoldenAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status, response.resultJson)
        self.assertEqual("market_scan", response.resultJson["domainIntent"])
        self.assertNotEqual("mixed_creation", response.resultJson.get("answerMode"))
        self.assertIn("refresh_rank_board", response.actions)
        self.assertEqual([], provider.invoke_calls)
        source_policy = response.resultJson["sourcePolicy"]
        self.assertTrue(source_policy["trendGateFailed"])
        self.assertEqual("mixed_structured_rank_snapshot", source_policy["trendGateReason"])
        self.assertNotIn("evidenceContract", source_policy)

    def _find_case(self, case_id: str) -> GoldenEvalCase:
        for case in self.cases:
            if case.case_id == case_id:
                return case
        raise ValueError(f"Case {case_id} not found")


class MixedSnapshotMarketKnowledgeClient:
    def __init__(self) -> None:
        self.lookup_rank_calls: list[dict] = []
        self.rank_pack_calls: list[dict] = []
        self.search_evidence_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        return [
            self._rank_item(index=index, snapshot_id=7101 if index % 2 else 7201, prefix="Lookup")
            for index in range(1, 11)
        ]

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> RankResearchPack:
        self.rank_pack_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "chapter_limit_per_book": chapter_limit_per_book,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        ranks = [self._rank_item(index=index, snapshot_id=7201, prefix="Pack") for index in range(1, 11)]
        return RankResearchPack(
            ranks=ranks,
            books=[
                BookProfile(
                    bookId=rank.bookId,
                    platform=rank.platform,
                    bookName=rank.bookName,
                    author=rank.author,
                    intro=rank.intro,
                    category=rank.category,
                )
                for rank in ranks
            ],
        )

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return []

    def _rank_item(self, *, index: int, snapshot_id: int, prefix: str) -> RankLookupResult:
        return RankLookupResult(
            rankId=snapshot_id * 100 + index,
            snapshotId=snapshot_id,
            snapshotTime="2026-06-27T00:00:00+00:00",
            platform="fanqie",
            channelCode="male-new",
            boardCode="urban-brain",
            channelName="男频新书榜",
            boardName="都市脑洞",
            category="都市脑洞",
            rankNo=index,
            bookId=snapshot_id + index,
            bookName=f"{prefix} Market Snapshot Book {index}",
            author=f"{prefix} Author",
            intro=f"{prefix} market rank sample {index}",
            sourceLabel=f"男频新书榜 / 都市脑洞 #{index}",
        )


class MarketGoldenAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "不应在混合快照纯市场问题中生成创作推断。[1]",
            "token_used": 64,
        }


if __name__ == "__main__":
    unittest.main()
