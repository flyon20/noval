"""
Golden Eval Suite: Edge Cases

Tests boundary conditions: out-of-scope, example book names, context followup, revision.
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.services.evaluation import GoldenEvalCase, RetrievalEvalThresholds


class GoldenEvalEdgeCasesTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cases_file = Path(__file__).parent / "golden_cases" / "edge_cases.json"
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
            retrieval_thresholds=RetrievalEvalThresholds(**raw.get("retrieval_thresholds", )),
        )

    async def test_edge_case_001_weather_out_of_scope(self) -> None:
        """今天天气怎么样？"""
        case = self._find_case("edge-case-001")

        self.assertEqual("out_of_scope", case.expected_intent)
        self.assertIsNone(case.expected_answer_mode)

    async def test_edge_case_002_coding_out_of_scope(self) -> None:
        """帮我写一个 Python 函数计算斐波那契数列。"""
        case = self._find_case("edge-case-002")

        self.assertEqual("out_of_scope", case.expected_intent)

    async def test_edge_case_003_example_book_name_not_trigger_selection(self) -> None:
        """比如《重生之都市修仙》这种书名，参考这个风格设计一个新题材。"""
        case = self._find_case("edge-case-003")

        self.assertEqual("opening_strategy", case.expected_intent)
        self.assertIn("必须选择这本书", case.forbidden_claims)
        self.assertEqual(0, len(case.relevant_source_ids))

    async def test_edge_case_004_context_followup(self) -> None:
        """继续上面那个大纲，扩展成30章细纲。"""
        case = self._find_case("edge-case-004")

        self.assertEqual("followup_context", case.expected_intent)
        self.assertIn("contextSummary", case.request_payload)
        self.assertIn("history", case.request_payload)

    async def test_edge_case_005_revision_advice_detect_poison(self) -> None:
        """这段开头有什么毒点？"""
        case = self._find_case("edge-case-005")

        self.assertEqual("revision_advice", case.expected_intent)
        self.assertIn("没有任何问题", case.forbidden_claims)

    def _find_case(self, case_id: str) -> GoldenEvalCase:
        for case in self.cases:
            if case.case_id == case_id:
                return case
        raise ValueError(f"Case {case_id} not found")


if __name__ == "__main__":
    unittest.main()
