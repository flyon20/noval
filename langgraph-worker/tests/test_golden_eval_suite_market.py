"""
Golden Eval Suite: Market Scan

Tests pure market trend queries and rank fact lookups.
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.services.evaluation import GoldenEvalCase, RetrievalEvalThresholds


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

    def _find_case(self, case_id: str) -> GoldenEvalCase:
        for case in self.cases:
            if case.case_id == case_id:
                return case
        raise ValueError(f"Case {case_id} not found")


if __name__ == "__main__":
    unittest.main()
