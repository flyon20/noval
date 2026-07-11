from __future__ import annotations

import unittest

from app.services.agents import (
    AuthorStrategyAgent,
    ChapterOutlineAgent,
    EditorAgent,
    ExpertProfile,
    ExpertRegistry,
    ExpertRouter,
    MarketScanAgent,
    OpeningStrategyAgent,
    OutlineAgent,
    ReaderRiskAgent,
    SupervisorAgent,
)
from app.services.intents.domain_intents import Intent, IntentDecision


class ExpertRegistryTests(unittest.TestCase):
    def _decision(self, intent: Intent, sub_intents: list[Intent] | None = None) -> IntentDecision:
        return IntentDecision(primaryIntent=intent, subIntents=sub_intents or [])

    def test_default_mixed_creation_route_keeps_existing_handoff_order(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(
                Intent.mixed_creation_research,
                [Intent.market_scan, Intent.outline_building],
            ),
            reasoning_mode="deep",
        )

        self.assertEqual(
            [
                "market_scan",
                "author_strategy",
                "opening_strategy",
                "outline",
                "reader_risk",
                "editor",
                "supervisor",
            ],
            [expert.name for expert in result.selectedExperts],
        )
        self.assertEqual(
            [
                MarketScanAgent,
                AuthorStrategyAgent,
                OpeningStrategyAgent,
                OutlineAgent,
                ReaderRiskAgent,
                EditorAgent,
                SupervisorAgent,
            ],
            result.agentClasses,
        )
        self.assertTrue(all(expert.reason for expert in result.selectedExperts))
        self.assertEqual("deep", result.reasoningMode)

    def test_fast_mode_caps_non_guard_experts_but_keeps_guardrails(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(
                Intent.mixed_creation_research,
                [
                    Intent.market_scan,
                    Intent.book_breakdown,
                    Intent.opening_strategy,
                    Intent.outline_building,
                    Intent.chapter_outline,
                    Intent.inspiration_expand,
                    Intent.character_design,
                    Intent.worldbuilding,
                    Intent.revision_advice,
                ],
            ),
            reasoning_mode="fast",
        )

        routed_names = [expert.name for expert in result.selectedExperts]
        non_guard_names = [
            expert.name
            for expert in result.selectedExperts
            if "guardrail" not in expert.reasonTags
        ]

        self.assertLessEqual(len(non_guard_names), 4)
        self.assertIn("market_scan", routed_names)
        self.assertIn("reader_risk", routed_names)
        self.assertIn("editor", routed_names)
        self.assertIn("supervisor", routed_names)
        self.assertEqual(3, result.maxParallel)

    def test_disabled_profile_is_skipped_with_route_reason(self) -> None:
        registry = ExpertRegistry([
            ExpertProfile(
                name="market_scan",
                displayName="Market",
                agentClass=MarketScanAgent,
                enabled=False,
                triggerIntents=(Intent.market_scan,),
            ),
            ExpertProfile(
                name="opening_strategy",
                displayName="Opening",
                agentClass=OpeningStrategyAgent,
                triggerIntents=(Intent.market_scan,),
            ),
        ])

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
        )

        self.assertEqual(["opening_strategy"], [expert.name for expert in result.selectedExperts])
        self.assertIn("market_scan", result.skippedExperts)
        self.assertEqual("disabled", result.skippedExperts["market_scan"])

    def test_task_graph_task_type_can_trigger_chapter_outline_expert(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(Intent.opening_strategy),
            reasoning_mode="deep",
            task_graph={
                "tasks": [
                    {
                        "id": "t1",
                        "type": "chapter_outline",
                        "goal": "Design the first three chapters.",
                    }
                ]
            },
        )

        self.assertIn("chapter_outline", [expert.name for expert in result.selectedExperts])
        chapter_route = next(expert for expert in result.selectedExperts if expert.name == "chapter_outline")
        self.assertIn("task:chapter_outline", chapter_route.reasonTags)

    def test_admin_profiles_overlay_default_registry_without_losing_agent_classes(self) -> None:
        registry = ExpertRegistry.default().with_admin_profiles([
            {
                "expertName": "market_scan",
                "enabled": False,
                "priority": 5,
                "maxTokens": 1500,
                "maxToolCalls": 1,
                "allowedTools": ["rank.lookup"],
                "triggerIntents": ["market_scan"],
                "triggerTasks": ["market_scan"],
                "promptVersion": "v2",
                "evalSuiteId": "market-v2",
            }
        ])

        profile = registry.get("market_scan")

        self.assertIsNotNone(profile)
        assert profile is not None
        self.assertIs(profile.agentClass, MarketScanAgent)
        self.assertFalse(profile.enabled)
        self.assertEqual(5, profile.priority)
        self.assertEqual(1500, profile.maxTokens)
        self.assertEqual(1, profile.maxToolCalls)
        self.assertEqual(("rank.lookup",), profile.allowedTools)
        self.assertEqual((Intent.market_scan,), profile.triggerIntents)
        self.assertEqual(("market_scan",), profile.triggerTaskTypes)
        self.assertEqual("v2", profile.promptVersion)
        self.assertEqual("market-v2", profile.evalSuite)

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
        )

        self.assertEqual([], [expert.name for expert in result.selectedExperts])
        self.assertEqual("disabled", result.skippedExperts["market_scan"])


if __name__ == "__main__":
    unittest.main()
