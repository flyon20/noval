from __future__ import annotations

import json
import unittest
from pathlib import Path

from app.services.intents import classify
from app.services.intents.domain_intents import Intent


FIXTURE_PATH = Path(__file__).parent / "fixtures" / "intent_eval_cases.json"


class IntentEvalSuiteTest(unittest.TestCase):
    def test_fixture_distribution_matches_phase_one_plan(self) -> None:
        cases = self._load_cases()
        expected_counts = {
            "market_scan": 12,
            "opening_strategy": 12,
            "book_breakdown": 10,
            "outline_building": 10,
            "chapter_outline": 11,
            "inspiration_expand": 8,
            "character_design": 8,
            "worldbuilding": 8,
            "revision_advice": 8,
            "followup_context": 8,
            "mixed_creation_research": 11,
            "out_of_scope": 8,
        }

        actual_counts: dict[str, int] = {}
        for case in cases:
            actual_counts[case["primaryIntent"]] = actual_counts.get(case["primaryIntent"], 0) + 1

        self.assertEqual(expected_counts, actual_counts)

    def test_eval_thresholds(self) -> None:
        cases = self._load_cases()
        primary_hits = 0
        multilabel_hits = 0
        tool_hits = 0
        tool_total = 0
        oos_true_positive = 0
        oos_predicted = 0

        for case in cases:
            decision = classify(
                case["question"],
                context_summary=case.get("context_summary"),
                history=case.get("history"),
            )
            expected_primary = case["primaryIntent"]
            if decision.primaryIntent.value == expected_primary:
                primary_hits += 1

            expected_sub = set(case.get("subIntents", []))
            actual_labels = {decision.primaryIntent.value, *(intent.value for intent in decision.subIntents)}
            if expected_sub.issubset(actual_labels):
                multilabel_hits += 1

            for key, expected_value in case.get("toolNeeds", {}).items():
                tool_total += 1
                if getattr(decision.toolNeeds, key) is expected_value:
                    tool_hits += 1

            if case.get("answerBoundary"):
                self.assertEqual(
                    case["answerBoundary"],
                    decision.answerBoundary.value,
                    f"answerBoundary mismatch for: {case['question']}",
                )

            if case.get("schemaVersion"):
                self.assertEqual(
                    case["schemaVersion"],
                    getattr(decision, "schemaVersion", None),
                    f"schemaVersion mismatch for: {case['question']}",
                )

            for entity_name in case.get("entitiesAbsent", []):
                self.assertNotIn(
                    entity_name,
                    decision.entities,
                    f"unexpected entity {entity_name} for: {case['question']}",
                )

            if decision.primaryIntent is Intent.out_of_scope:
                oos_predicted += 1
                if expected_primary == "out_of_scope":
                    oos_true_positive += 1

        primary_accuracy = primary_hits / len(cases)
        multilabel_accuracy = multilabel_hits / len(cases)
        tool_accuracy = tool_hits / max(1, tool_total)
        oos_precision = oos_true_positive / max(1, oos_predicted)

        self.assertGreaterEqual(primary_accuracy, 0.95)
        self.assertGreaterEqual(multilabel_accuracy, 0.95)
        self.assertGreaterEqual(tool_accuracy, 0.95)
        self.assertGreaterEqual(oos_precision, 0.95)

    def _load_cases(self) -> list[dict]:
        with FIXTURE_PATH.open("r", encoding="utf-8") as fixture:
            return json.load(fixture)


if __name__ == "__main__":
    unittest.main()
