"""
Golden Eval Suite: Mixed Creation Research

Tests complex multi-intent tasks combining market scan + book breakdown + creative generation.
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.models.knowledge import KnowledgeChatRequest
from app.services.evaluation import GoldenEvalCase, RetrievalEvalThresholds


class GoldenEvalMixedCreationTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cases_file = Path(__file__).parent / "golden_cases" / "mixed_creation_cases.json"
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
            expected_sub_intents=set(raw.get("expected_sub_intents", [])),
            relevant_source_ids=set(raw.get("relevant_source_ids", [])),
            forbidden_claims=raw.get("forbidden_claims", []),
            retrieval_thresholds=RetrievalEvalThresholds(**raw.get("retrieval_thresholds", {})),
        )

    async def test_mixed_creation_001_rank_imitation_and_chapter_outline(self) -> None:
        """根据当前男频都市脑洞新书榜第一的书，模仿题材并给前三章细纲。"""
        case = self._find_case("mixed-creation-001")

        # TODO: Integrate with actual NovelResearchAgent
        # For now, validate case structure
        self.assertEqual("mixed_creation_research", case.expected_intent)
        self.assertEqual("mixed_creation", case.expected_answer_mode)
        self.assertIn("market_scan", case.expected_sub_intents)
        self.assertIn("chapter_outline", case.expected_sub_intents)
        self.assertIn("rank:101", case.relevant_source_ids)

    async def test_mixed_creation_002_trend_scan_and_topic_strategy(self) -> None:
        """看一下最近男频都市脑洞风向，参考榜一卖点，给我一个不撞车的新题材。"""
        case = self._find_case("mixed-creation-002")

        self.assertEqual("mixed_creation_research", case.expected_intent)
        self.assertIn("opening_strategy", case.expected_sub_intents)
        self.assertIn("绝对不撞车", case.forbidden_claims)

    async def test_mixed_creation_003_character_worldbuilding_with_risk(self) -> None:
        """参考番茄男频都市脑洞榜前三，设计一个主角、金手指和世界观，再指出可能的毒点。"""
        case = self._find_case("mixed-creation-003")

        self.assertIn("character_design", case.expected_sub_intents)
        self.assertIn("worldbuilding", case.expected_sub_intents)
        self.assertGreaterEqual(len(case.relevant_source_ids), 3)

    def _find_case(self, case_id: str) -> GoldenEvalCase:
        for case in self.cases:
            if case.case_id == case_id:
                return case
        raise ValueError(f"Case {case_id} not found")


if __name__ == "__main__":
    unittest.main()
