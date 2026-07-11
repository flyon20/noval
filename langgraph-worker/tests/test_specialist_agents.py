from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.mcp.tool_registry import McpToolRegistry
from app.services.agents import (
    AuthorStrategyAgent,
    BookBreakdownAgent,
    ChapterOutlineAgent,
    CharacterAgent,
    EditorAgent,
    InspirationAgent,
    MarketScanAgent,
    OpeningStrategyAgent,
    OutlineAgent,
    ReaderRiskAgent,
    RevisionAgent,
    SupervisorAgent,
    WorldbuildingAgent,
    create_context,
    route_agents,
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
            ["market_scan", "author_strategy", "opening_strategy", "outline", "reader_risk", "editor", "supervisor"],
            [result.agentName for result in results],
        )
        self.assertEqual(
            ["trend", "author_strategy", "opening_strategy", "outline", "reader_risk", "editor_review", "supervisor"],
            [result.answerMode for result in results],
        )

    def test_parallel_specialist_runner_preserves_stable_order_and_reports_diagnostics(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.outline_building, Intent.opening_strategy, Intent.market_scan],
        )
        context = create_context(request=self._request(), intent_decision=decision)

        results = self._run(run_specialists_parallel(context, max_parallel=2))

        self.assertEqual(
            ["market_scan", "author_strategy", "opening_strategy", "outline", "reader_risk", "editor", "supervisor"],
            [result.agentName for result in results],
        )
        self.assertTrue(all(result.diagnostics["runner"] == "parallel" for result in results))
        self.assertTrue(all(result.diagnostics["parallelLimit"] == 2 for result in results))
        self.assertEqual(list(range(7)), [result.diagnostics["parallelIndex"] for result in results])

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
        self.assertEqual("completed", result.status)
        self.assertTrue(result.summary)
        self.assertTrue(result.evidenceRefs)
        self.assertIn("rank", result.evidenceRefs)
        self.assertIsInstance(result.toolCalls, list)
        self.assertIn("skillFragmentCount", result.diagnostics)
        self.assertEqual(2, result.diagnostics["skillFragmentCount"])
        self.assertEqual(2, result.diagnostics["sourceCount"])
        self.assertEqual(1, result.diagnostics["materialSourceCount"])

    def test_mixed_creation_selects_phase8_handoff_agents(self) -> None:
        agents = select_agents(
            self._decision(Intent.mixed_creation_research, [Intent.market_scan, Intent.outline_building])
        )

        self.assertEqual(
            [MarketScanAgent, AuthorStrategyAgent, OpeningStrategyAgent, OutlineAgent, ReaderRiskAgent, EditorAgent, SupervisorAgent],
            agents,
        )

    def test_route_agents_reports_selected_experts_and_reasons(self) -> None:
        route = route_agents(
            self._decision(Intent.mixed_creation_research, [Intent.market_scan, Intent.outline_building]),
            reasoning_mode="fast",
        )

        self.assertEqual("fast", route.reasoningMode)
        self.assertEqual("market_scan", route.selectedExperts[0].name)
        self.assertTrue(route.selectedExperts[0].reason)
        self.assertGreaterEqual(route.maxParallel, 1)

    def test_parallel_specialist_runner_accepts_precomputed_route(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [
                Intent.market_scan,
                Intent.opening_strategy,
                Intent.outline_building,
                Intent.chapter_outline,
            ],
        )
        route = route_agents(decision, reasoning_mode="fast")
        context = create_context(request=self._request(), intent_decision=decision)

        results = self._run(run_specialists_parallel(context, max_parallel=2, expert_route=route))

        self.assertEqual(
            [expert.name for expert in route.selectedExperts],
            [result.agentName for result in results],
        )
        self.assertTrue(all(result.diagnostics["expertRouterReason"] for result in results))
        self.assertTrue(all(result.diagnostics["expertRouterMode"] == "fast" for result in results))

    def test_reader_risk_agent_returns_prioritized_risk_contract(self) -> None:
        context = create_context(
            request=self._request(),
            intent_decision=self._decision(Intent.mixed_creation_research, [Intent.market_scan]),
        )

        result = ReaderRiskAgent().run(context)

        self.assertEqual("reader_risk", result.agentName)
        self.assertIn("Risk", " ".join(result.generationInstructions))
        self.assertIn("reader", result.evidenceRefs)

    def test_supervisor_agent_rejects_claims_outside_evidence_contract(self) -> None:
        context = create_context(
            request=self._request(),
            intent_decision=self._decision(Intent.mixed_creation_research, [Intent.market_scan]),
            diagnostics={"evidenceContractStatus": "degraded_directional"},
        )

        result = SupervisorAgent().run(context)

        self.assertEqual("supervisor", result.agentName)
        self.assertIn("EvidenceContract", " ".join(result.evidencePolicy))
        self.assertIn("degraded_directional", result.warnings)

    def test_all_selectable_specialists_are_enabled_for_independent_llm_calls(self) -> None:
        agent_classes = {
            MarketScanAgent,
            AuthorStrategyAgent,
            BookBreakdownAgent,
            OpeningStrategyAgent,
            OutlineAgent,
            ChapterOutlineAgent,
            InspirationAgent,
            CharacterAgent,
            WorldbuildingAgent,
            RevisionAgent,
            ReaderRiskAgent,
            EditorAgent,
            SupervisorAgent,
        }

        disabled = [agent_class.__name__ for agent_class in agent_classes if not agent_class.llm_enabled]

        self.assertEqual([], disabled)

    def _run(self, awaitable):
        import asyncio

        return asyncio.run(awaitable)


class LlmSpecialistAgentTests(unittest.IsolatedAsyncioTestCase):
    async def test_llm_backed_specialist_runner_calls_provider_for_selected_handoff_agents(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {
                    "model_name": "deepseek-v4-pro",
                    "content": "LLM specialist summary",
                    "token_used": 17,
                }

        provider = Provider()
        context = create_context(
            request=KnowledgeChatRequest(question="先扫榜单趋势，再给我开书和大纲方向", reasoningMode="deep"),
            intent_decision=self._decision(
                Intent.mixed_creation_research,
                [Intent.outline_building, Intent.opening_strategy, Intent.market_scan],
            ),
        )

        results = await run_specialists_parallel(
            context,
            max_parallel=3,
            provider_client=provider,
            model="deepseek-v4-pro",
        )

        llm_results = [result for result in results if result.diagnostics.get("llmBacked")]
        self.assertGreaterEqual(len(llm_results), 5)
        self.assertGreaterEqual(len(provider.calls), 5)
        self.assertTrue(all(call["reasoning_mode"] == "deep" for call in provider.calls))
        self.assertTrue(all(result.summary == "LLM specialist summary" for result in llm_results))

    async def test_execution_specialist_can_request_high_reasoning_effort(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {
                    "model_name": "deepseek-v4-pro",
                    "content": "Chapter outline drafted.",
                    "token_used": 13,
                }

        provider = Provider()
        context = create_context(
            request=KnowledgeChatRequest(question="给我一个前三章大纲", reasoningMode="deep"),
            intent_decision=self._decision(Intent.chapter_outline),
        )

        results = await run_specialists_parallel(
            context,
            provider_client=provider,
            model="deepseek-v4-pro",
        )

        self.assertEqual("high", provider.calls[0]["reasoning_effort"])
        self.assertEqual("chapter_outline", results[0].agentName)
        self.assertEqual("Chapter outline drafted.", results[0].summary)

    async def test_llm_backed_specialist_can_execute_own_mcp_tool_loop(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []
                self.emitted_tool_call = False

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                if kwargs.get("tools") and not self.emitted_tool_call:
                    self.emitted_tool_call = True
                    return {
                        "model_name": "deepseek-v4-pro",
                        "content": "",
                        "reasoning_content": "Need current market data.",
                        "tool_calls": [
                            {"id": "call-rank", "name": "rank.lookup", "arguments": {"platform": "fanqie"}},
                        ],
                        "token_used": 11,
                    }
                return {
                    "model_name": "deepseek-v4-pro",
                    "content": "MarketScanAgent used rank evidence before strategy.",
                    "token_used": 19,
                }

        class McpClient:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def call_tool(
                self,
                name: str,
                arguments: dict,
                timeout: float | None = None,
                route: str | None = None,
            ) -> dict:
                self.calls.append({"name": name, "arguments": arguments, "timeout": timeout, "route": route})
                return {"items": [{"rankNo": 1, "bookName": "榜一样本"}]}

        provider = Provider()
        mcp_client = McpClient()
        registry = McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "required": ["platform"]},
            }
        ])
        context = create_context(
            request=KnowledgeChatRequest(question="先扫榜单趋势，再给我开书方向", reasoningMode="deep"),
            intent_decision=self._decision(
                Intent.mixed_creation_research,
                [Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
            ),
        )

        results = await run_specialists_parallel(
            context,
            max_parallel=3,
            provider_client=provider,
            model="deepseek-v4-pro",
            mcp_client=mcp_client,
            mcp_tool_registry=registry,
        )

        self.assertEqual("rank.lookup", mcp_client.calls[0]["name"])
        self.assertEqual("market_scan", mcp_client.calls[0]["route"])
        market_result = next(result for result in results if result.agentName == "market_scan")
        self.assertTrue(market_result.diagnostics["llmBacked"])
        self.assertTrue(market_result.diagnostics["mcpToolLoop"])
        self.assertTrue(any(call.get("name") == "rank.lookup" for call in market_result.toolCalls))
        tool_enabled_calls = [call for call in provider.calls if call.get("tools")]
        self.assertGreaterEqual(len(tool_enabled_calls), 2)
        self.assertTrue(all(call["reasoning_mode"] == "deep" for call in tool_enabled_calls))

    def _decision(self, intent: Intent, sub_intents: list[Intent] | None = None) -> IntentDecision:
        return IntentDecision(primaryIntent=intent, subIntents=sub_intents or [])


if __name__ == "__main__":
    unittest.main()
