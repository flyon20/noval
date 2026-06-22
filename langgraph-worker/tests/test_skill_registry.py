from __future__ import annotations

import unittest

from app.services.intents import Intent, IntentDecision, ToolNeeds
from app.services.skills import SkillRegistry


class SkillRegistryTests(unittest.TestCase):
    def setUp(self) -> None:
        SkillRegistry.clear_cache()
        self.registry = SkillRegistry()

    def skill_ids_for(self, decision: IntentDecision, max_chars: int | None = None) -> list[str]:
        return [skill.skillId for skill in self.registry.select_for_intent(decision, max_chars=max_chars).skills]

    def test_opening_strategy_loads_opening_and_rank_data_loads_market(self) -> None:
        decision = IntentDecision(
            primaryIntent=Intent.opening_strategy,
            toolNeeds=ToolNeeds(needsRankData=True),
        )

        self.assertEqual(
            ["webnovel-market-scan", "webnovel-opening-strategy"],
            self.skill_ids_for(decision),
        )

    def test_outline_building_does_not_load_chapter_outline(self) -> None:
        decision = IntentDecision(primaryIntent=Intent.outline_building)

        skill_ids = self.skill_ids_for(decision)

        self.assertIn("webnovel-outline-building", skill_ids)
        self.assertNotIn("webnovel-chapter-outline", skill_ids)

    def test_mixed_creation_research_loads_relevant_skills_and_respects_budget(self) -> None:
        decision = IntentDecision(
            primaryIntent=Intent.mixed_creation_research,
            subIntents=[Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
            toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True),
        )

        selection = self.registry.select_for_intent(decision, max_chars=700)

        self.assertEqual(
            ["webnovel-market-scan", "webnovel-opening-strategy", "webnovel-outline-building"],
            [skill.skillId for skill in selection.skills],
        )
        self.assertLessEqual(len(selection.prompt), 700)

    def test_cached_loads_are_reused_and_ordering_is_deterministic(self) -> None:
        first = self.registry.load_all()
        second = self.registry.load_all()

        self.assertIs(first, second)
        self.assertEqual(
            [skill.skillId for skill in first],
            [skill.skillId for skill in self.registry.load_all()],
        )
        self.assertEqual(sorted(skill.skillId for skill in first), [skill.skillId for skill in first])


if __name__ == "__main__":
    unittest.main()
