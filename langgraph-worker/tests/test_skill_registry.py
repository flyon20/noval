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

    def test_phase7_skill_pack_schema_is_loaded(self) -> None:
        market_scan = next(
            skill for skill in self.registry.load_all() if skill.skillId == "webnovel-market-scan"
        )

        self.assertEqual("webnovel-market-scan", market_scan.id)
        self.assertTrue(market_scan.appliesTo)
        self.assertIn("rank.lookup", market_scan.allowedTools)
        self.assertTrue(market_scan.requiredEvidence)
        self.assertTrue(market_scan.outputContract)
        self.assertTrue(market_scan.guardrails)
        self.assertTrue(market_scan.examples)

    def test_market_scan_task_selects_market_scan_skill(self) -> None:
        selection = self.registry.select_for_task(
            {
                "intent": "market_scan",
                "taskGraph": {"nodes": [{"type": "market_scan"}]},
                "evidenceContract": {"status": "verified_latest"},
            }
        )

        self.assertEqual(("webnovel-market-scan",), selection.skill_ids[:1])

    def test_mixed_snapshot_selects_rank_evidence_arbitration(self) -> None:
        selection = self.registry.select_for_task(
            {
                "intent": "mixed_creation_research",
                "taskGraph": {"nodes": [{"type": "market_scan"}, {"type": "outline_building"}]},
                "evidenceContract": {
                    "status": "degraded_directional",
                    "warnings": [{"code": "mixed_structured_rank_snapshot"}],
                    "rejectedGroups": [{"snapshotId": "old"}],
                },
            }
        )

        self.assertIn("rank-evidence-arbitration", selection.skill_ids)

    def test_outline_task_selects_outline_building(self) -> None:
        selection = self.registry.select_for_task(
            {
                "intent": "outline_building",
                "taskGraph": {"nodes": [{"type": "outline_building"}]},
            }
        )

        self.assertIn("webnovel-outline-building", selection.skill_ids)

    def test_project_knowledge_task_selects_project_knowledge_skill(self) -> None:
        selection = self.registry.select_for_task(
            {
                "intent": "followup_context",
                "taskGraph": {
                    "nodes": [
                        {"type": "project_knowledge_qa"},
                        {"type": "foreshadowing_audit"},
                    ]
                },
            }
        )

        self.assertIn("webnovel-project-knowledge-qa", selection.skill_ids)
        skill = next(
            skill for skill in selection.skills if skill.skillId == "webnovel-project-knowledge-qa"
        )
        self.assertIn("project.resolve", skill.allowedTools)
        self.assertIn("memory.project_context", skill.allowedTools)
        self.assertIn("knowledge.vector_search", skill.allowedTools)
        self.assertIn("project_bound_chapter_or_memory_evidence", skill.requiredEvidence)

    def test_book_breakdown_skill_contains_editorial_analysis_modules(self) -> None:
        skill = next(
            skill for skill in self.registry.load_all() if skill.skillId == "webnovel-book-breakdown"
        )

        prompt = skill.compact_prompt()

        self.assertIn("黄金开局", prompt)
        self.assertIn("情节复刻模版", prompt)
        self.assertIn("主编综合评语", prompt)

    def test_market_scan_skill_contains_top30_and_cache_first_policy(self) -> None:
        skill = next(
            skill for skill in self.registry.load_all() if skill.skillId == "webnovel-market-scan"
        )

        prompt = skill.compact_prompt()

        self.assertIn("Top30", prompt)
        self.assertIn("3 天", prompt)
        self.assertIn("缓存优先", prompt)

    def test_reader_risk_task_selects_reader_risk_review(self) -> None:
        selection = self.registry.select_for_task(
            {
                "intent": "reader_risk_review",
                "taskGraph": {"nodes": [{"type": "reader_risk"}]},
            }
        )

        self.assertIn("reader-risk-review", selection.skill_ids)

    def test_cached_loads_are_reused_and_ordering_is_deterministic(self) -> None:
        first = self.registry.load_all()
        second = self.registry.load_all()

        self.assertIs(first, second)
        self.assertEqual(
            [skill.skillId for skill in first],
            [skill.skillId for skill in self.registry.load_all()],
        )
        self.assertEqual(sorted(skill.skillId for skill in first), [skill.skillId for skill in first])

    def test_backend_published_skill_overrides_local_pack_and_respects_prompt_budget(self) -> None:
        registry = SkillRegistry(
            runtime_skills=[
                {
                    "skillId": "webnovel-market-scan",
                    "version": "2026.07.02",
                    "content": "BACKEND PUBLISHED PROMPT " + ("x" * 200),
                    "intents": ["market_scan"],
                    "triggers": ["rank"],
                    "allowedTools": ["rank.lookup"],
                    "requiredEvidence": ["fresh_rank"],
                    "source": "backend",
                }
            ]
        )
        decision = IntentDecision(
            primaryIntent=Intent.market_scan,
            toolNeeds=ToolNeeds(needsRankData=True),
        )

        selection = registry.select_for_intent(decision, max_chars=80)

        market_skill = next(skill for skill in selection.skills if skill.skillId == "webnovel-market-scan")
        self.assertEqual("2026.07.02", market_skill.version)
        self.assertEqual(("rank.lookup",), market_skill.allowedTools)
        self.assertEqual(("fresh_rank",), market_skill.requiredEvidence)
        self.assertIn("BACKEND PUBLISHED PROMPT", selection.prompt)
        self.assertLessEqual(len(selection.prompt), 80)


if __name__ == "__main__":
    unittest.main()
