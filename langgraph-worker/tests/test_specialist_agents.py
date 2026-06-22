from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.agents import (
    MarketScanAgent,
    OpeningStrategyAgent,
    OutlineAgent,
    create_context,
    run_specialists_parallel,
    run_specialists,
    select_agents,
)
from app.services.intents.domain_intents import Intent, IntentDecision


class SpecialistAgentTests(unittest.TestCase):
    def _decision(self, intent: Intent, sub_intents: list[Intent] | None = None) -> IntentDecision:
        return IntentDecision(primaryIntent=intent, subIntents=sub_intents or [])

    def _request(self) -> KnowledgeChatRequest:
        return KnowledgeChatRequest(question="先扫榜单趋势，再给我开书和大纲方向")

    def test_market_scan_selects_market_scan_agent(self) -> None:
        agents = select_agents(self._decision(Intent.market_scan))

        self.assertEqual([MarketScanAgent], agents)

    def test_opening_strategy_selects_opening_strategy_agent(self) -> None:
        agents = select_agents(self._decision(Intent.opening_strategy))

        self.assertEqual([OpeningStrategyAgent], agents)

    def test_outline_building_selects_outline_agent(self) -> None:
        agents = select_agents(self._decision(Intent.outline_building))

        self.assertEqual([OutlineAgent], agents)

    def test_mixed_creation_research_runs_stable_market_opening_outline_order(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.outline_building, Intent.opening_strategy, Intent.market_scan],
        )
        context = create_context(request=self._request(), intent_decision=decision)

        results = run_specialists(context)

        self.assertEqual(
            ["market_scan", "opening_strategy", "outline"],
            [result.agentName for result in results],
        )
        self.assertEqual(["trend", "opening_strategy", "outline"], [result.answerMode for result in results])

    def test_parallel_specialist_runner_preserves_stable_order_and_reports_diagnostics(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.outline_building, Intent.opening_strategy, Intent.market_scan],
        )
        context = create_context(request=self._request(), intent_decision=decision)

        results = self._run(run_specialists_parallel(context, max_parallel=2))

        self.assertEqual(
            ["market_scan", "opening_strategy", "outline"],
            [result.agentName for result in results],
        )
        self.assertTrue(all(result.diagnostics["runner"] == "parallel" for result in results))
        self.assertTrue(all(result.diagnostics["parallelLimit"] == 2 for result in results))
        self.assertEqual([0, 1, 2], [result.diagnostics["parallelIndex"] for result in results])

    def test_agent_result_reports_source_material_and_skill_fragment_diagnostics(self) -> None:
        context = create_context(
            request=self._request(),
            intent_decision=self._decision(Intent.market_scan),
            sources=[
                KnowledgeSource(bookName="榜一案例", material="完整章节素材", preview="趋势样例"),
                KnowledgeSource(bookName="榜二案例", preview="无正文预览"),
            ],
            skill_fragments=["开篇钩子检查表", {"name": "爽点节奏模板"}],
        )

        result = MarketScanAgent().run(context)

        self.assertEqual("market_scan", result.agentName)
        self.assertEqual("trend", result.answerMode)
        self.assertIn("rank", " ".join(result.evidencePolicy).lower())
        self.assertIn("skillFragmentCount", result.diagnostics)
        self.assertEqual(2, result.diagnostics["skillFragmentCount"])
        self.assertEqual(2, result.diagnostics["sourceCount"])
        self.assertEqual(1, result.diagnostics["materialSourceCount"])

    def _run(self, awaitable):
        import asyncio

        return asyncio.run(awaitable)


if __name__ == "__main__":
    unittest.main()
