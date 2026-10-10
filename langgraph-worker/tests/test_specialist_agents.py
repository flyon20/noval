from __future__ import annotations

import asyncio
import unittest

from app.config import settings
from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.harness.agent_kernel import AgentKernel
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
    ExpertProfile,
    ExpertRegistry,
    route_agents,
    run_specialists_parallel,
    run_specialists,
    select_agents,
)
from app.services.agents.expert_registry import ExpertCategory
from app.models.agent_task import RunToolIdentity
from app.services.intents.domain_intents import Intent, IntentDecision
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.harness.capability_authorizer import CapabilityAuthorizer
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.admission import get_delegation_semaphore
from app.services.harness.cancellation import CancellationToken, RunCancelledError, cancellation_scope
from app.services.harness.contracts import (
    CapabilityPlan,
    CapabilityRequest,
    DomainStatus,
    IntentEnvelope,
)
from app.services.harness.tool_ledger import run_tool_ledger_scope


def _plan_for_registry(registry: ExpertRegistry) -> CapabilityPlan:
    capability_ids = sorted({
        capability_id
        for profile in registry.profiles
        for capability_id in profile.capabilityIds
    })
    return CapabilityPlan(
        intentEnvelopeHash="sha256:test",
        capabilityRequests=tuple(
            CapabilityRequest(capabilityId=capability_id)
            for capability_id in capability_ids
        ),
        expertCandidateIds=tuple(profile.name for profile in registry.profiles),
    )


class SpecialistAgentTests(unittest.TestCase):
    def _decision(self, intent: Intent, sub_intents: list[Intent] | None = None) -> IntentDecision:
        return IntentDecision(primaryIntent=intent, subIntents=sub_intents or [])

    def _request(self) -> KnowledgeChatRequest:
        return KnowledgeChatRequest(question="先扫榜单趋势，再给我开书和大纲方向")

    def test_market_scan_selects_market_scan_agent(self) -> None:
        registry = ExpertRegistry.default()
        agents = select_agents(
            self._decision(Intent.market_scan),
            capability_plan=_plan_for_registry(registry),
        )

        self.assertEqual([MarketScanAgent], agents)

    def test_opening_strategy_selects_opening_strategy_agent(self) -> None:
        registry = ExpertRegistry.default()
        agents = select_agents(
            self._decision(Intent.opening_strategy),
            capability_plan=_plan_for_registry(registry),
        )

        self.assertEqual([OpeningStrategyAgent], agents)

    def test_outline_building_selects_outline_agent(self) -> None:
        registry = ExpertRegistry.default()
        agents = select_agents(
            self._decision(Intent.outline_building),
            capability_plan=_plan_for_registry(registry),
        )

        self.assertEqual([OutlineAgent], agents)

    def test_specialist_prompt_budget_is_applied_to_full_message_set(self) -> None:
        context = create_context(
            request=KnowledgeChatRequest(question="根据已有设定输出完整大纲"),
            intent_decision=self._decision(Intent.outline_building),
            diagnostics={"material": "机甲群像设定" * 20_000},
        )
        agent = OutlineAgent()

        bounded = agent._llm_messages(context, max_prompt_chars=20_000)
        expanded = agent._llm_messages(context, max_prompt_chars=100_000)
        mandatory_chars = len(expanded[0]["content"]) + len(expanded[2]["content"])
        envelope_edge = agent._llm_messages(
            context,
            max_prompt_chars=mandatory_chars + 1,
        )
        tiny = agent._llm_messages(context, max_prompt_chars=128)

        self.assertLessEqual(sum(len(item["content"]) for item in bounded), 20_000)
        self.assertGreater(
            sum(len(item["content"]) for item in expanded),
            sum(len(item["content"]) for item in bounded),
        )
        self.assertLessEqual(
            sum(len(item["content"]) for item in envelope_edge),
            mandatory_chars + 1,
        )
        self.assertLessEqual(sum(len(item["content"]) for item in tiny), 128)
        self.assertTrue(tiny[0]["content"])
        self.assertTrue(tiny[2]["content"])

    def test_mixed_creation_research_runs_stable_market_opening_outline_order(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.outline_building, Intent.opening_strategy, Intent.market_scan],
        )
        context = create_context(request=self._request(), intent_decision=decision)

        results = run_specialists(
            context,
            capability_plan=_plan_for_registry(ExpertRegistry.default()),
        )

        self.assertEqual(
            ["market_scan", "opening_strategy", "outline"],
            [result.agentName for result in results],
        )
        self.assertEqual(
            ["trend", "opening_strategy", "outline"],
            [result.answerMode for result in results],
        )

    def test_parallel_specialist_runner_preserves_stable_order_and_reports_diagnostics(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.outline_building, Intent.opening_strategy, Intent.market_scan],
        )
        request = self._request()
        request.reasoningMode = "deep"
        context = create_context(request=request, intent_decision=decision)

        results = self._run(run_specialists_parallel(
            context,
            max_parallel=2,
            capability_plan=_plan_for_registry(ExpertRegistry.default()),
        ))

        self.assertEqual(
            ["market_scan", "opening_strategy", "outline"],
            [result.agentName for result in results],
        )
        self.assertTrue(all(result.diagnostics["runner"] == "controlled_moe" for result in results))
        self.assertTrue(all(result.diagnostics["parallelLimit"] == 1 for result in results))
        self.assertEqual(list(range(3)), [result.diagnostics["parallelIndex"] for result in results])

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
            self._decision(Intent.mixed_creation_research, [Intent.market_scan, Intent.outline_building]),
            capability_plan=_plan_for_registry(ExpertRegistry.default()),
        )

        self.assertEqual(
            [MarketScanAgent, OutlineAgent],
            agents,
        )

    def test_route_agents_reports_selected_experts_and_reasons(self) -> None:
        route = route_agents(
            self._decision(Intent.mixed_creation_research, [Intent.market_scan, Intent.outline_building]),
            reasoning_mode="fast",
            capability_plan=_plan_for_registry(ExpertRegistry.default()),
        )

        self.assertEqual("fast", route.reasoningMode)
        self.assertEqual([], route.selectedExperts)
        self.assertEqual(["market_scan", "outline"], [item.name for item in route.selectedCapabilities])
        self.assertEqual(1, route.maxParallel)

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
        route = route_agents(
            decision,
            reasoning_mode="fast",
            capability_plan=_plan_for_registry(ExpertRegistry.default()),
        )
        context = create_context(request=self._request(), intent_decision=decision)

        results = self._run(run_specialists_parallel(context, max_parallel=2, expert_route=route))

        self.assertEqual(
            [expert.name for expert in route.selectedCapabilities],
            [result.agentName for result in results],
        )
        self.assertTrue(all(result.diagnostics["expertRouterReason"] for result in results))
        self.assertTrue(all(result.diagnostics["expertRouterMode"] == "fast" for result in results))

    def test_parallel_specialist_runner_enforces_run_delegation_budget_and_cancellation(self) -> None:
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
        )
        fast_request = self._request()
        fast_request.reasoningMode = "fast"
        deep_request = self._request()
        deep_request.reasoningMode = "deep"
        fast_context = create_context(request=fast_request, intent_decision=decision)
        deep_context = create_context(request=deep_request, intent_decision=decision)

        with run_budget_scope("fast") as fast_budget:
            fast_results = self._run(run_specialists_parallel(
                fast_context,
                max_parallel=3,
                capability_plan=_plan_for_registry(ExpertRegistry.default()),
            ))
        with run_budget_scope("deep") as deep_budget:
            deep_results = self._run(run_specialists_parallel(
                deep_context,
                max_parallel=3,
                capability_plan=_plan_for_registry(ExpertRegistry.default()),
            ))

        # Governed sparse MoE: fast INLINE <= 2, deep INLINE <= 4; no default delegations.
        self.assertEqual(2, len(fast_results))
        self.assertEqual(["market_scan", "opening_strategy"], [item.agentName for item in fast_results])
        self.assertEqual(0, fast_budget.used_delegations)
        self.assertEqual(3, len(deep_results))
        self.assertEqual(0, deep_budget.used_delegations)

        token = CancellationToken()
        token.cancel("delegation_cancelled")
        with cancellation_scope(token):
            with self.assertRaisesRegex(RunCancelledError, "delegation_cancelled"):
                self._run(run_specialists_parallel(
                    deep_context,
                    max_parallel=3,
                    capability_plan=_plan_for_registry(ExpertRegistry.default()),
                ))

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
    def _delegated_route(
        self,
        *,
        name: str,
        agent_class: type,
        intent: Intent,
        requested_tool_capabilities: tuple[str, ...] = (),
        max_tokens: int = 900,
    ):
        registry = ExpertRegistry([
            ExpertProfile(
                name=name,
                displayName=name,
                agentClass=agent_class,
                category=ExpertCategory.DELEGATED,
                triggerIntents=(intent,),
                expectedQualityGain=0.50,
                qualityGainVerified=True,
                qualityGainSource="admin_configured_eval",
                qualityGainEvalRunId=42,
                requestedToolCapabilities=requested_tool_capabilities,
                maxTokens=max_tokens,
                maxToolCalls=1,
            )
        ])
        return route_agents(
            self._decision(intent),
            reasoning_mode="deep",
            registry=registry,
            capability_plan=_plan_for_registry(registry),
        )

    def _market_authorization(self):
        plan = CapabilityCompiler().compile(IntentEnvelope(
            domainStatus=DomainStatus.IN_SCOPE,
            goal="market_scan",
            operations=("market_scan",),
            confidence=0.9,
            classificationSource="rules",
        ))
        return CapabilityAuthorizer().authorize(plan)

    async def test_cancelled_delegation_wait_does_not_consume_run_budget(self) -> None:
        decision = IntentDecision(
            primaryIntent=Intent.mixed_creation_research,
            subIntents=[Intent.market_scan, Intent.outline_building],
        )
        context = create_context(
            request=KnowledgeChatRequest(question="outline after market scan"),
            intent_decision=decision,
        )
        semaphore = get_delegation_semaphore()
        held_slots = settings.max_delegated_agent_concurrency
        for _ in range(held_slots):
            await semaphore.acquire()
        token = CancellationToken()
        expert_route = self._delegated_route(
            name="market_scan",
            agent_class=MarketScanAgent,
            intent=Intent.market_scan,
        )
        try:
            with run_budget_scope("fast") as budget, cancellation_scope(token):
                task = asyncio.create_task(run_specialists_parallel(
                    context,
                    max_parallel=1,
                    expert_route=expert_route,
                ))
                await asyncio.sleep(0.02)
                token.cancel("cancelled_while_waiting_for_delegation")
                with self.assertRaisesRegex(RunCancelledError, "cancelled_while_waiting_for_delegation"):
                    await task
            self.assertEqual(0, budget.used_delegations)
        finally:
            for _ in range(held_slots):
                semaphore.release()

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
            agent_kernel=AgentKernel(provider),
            model="deepseek-v4-pro",
            expert_route=self._delegated_route(
                name="market_scan",
                agent_class=MarketScanAgent,
                intent=Intent.market_scan,
                max_tokens=1234,
            ),
        )

        llm_results = [result for result in results if result.diagnostics.get("llmBacked")]
        self.assertEqual(1, len(llm_results))
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(1234, provider.calls[0]["max_tokens"])
        self.assertTrue(all(call["reasoning_mode"] == "deep" for call in provider.calls))
        self.assertTrue(all(result.summary == "LLM specialist summary" for result in llm_results))

    async def test_failed_delegated_provider_call_is_not_reported_completed(self) -> None:
        class Provider:
            async def invoke(self, **_kwargs) -> dict:
                raise TimeoutError("delegated provider timeout")

        context = create_context(
            request=KnowledgeChatRequest(question="market", reasoningMode="deep"),
            intent_decision=self._decision(Intent.market_scan),
        )

        results = await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(Provider()),
            expert_route=self._delegated_route(
                name="market_scan",
                agent_class=MarketScanAgent,
                intent=Intent.market_scan,
            ),
        )

        self.assertEqual("failed", results[0].status)
        self.assertFalse(results[0].diagnostics["llmBacked"])
        self.assertEqual("failed", results[0].diagnostics["executionStatus"])

    async def test_governed_domain_model_pass_can_model_back_skill_without_eval_delegation(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {
                    "model_name": "deepseek-chat",
                    "content": "Model-backed market analysis.",
                    "token_used": 19,
                    "usage": {"totalTokens": 19},
                }

        provider = Provider()
        decision = self._decision(
            Intent.mixed_creation_research,
            [Intent.market_scan, Intent.outline_building],
        )
        request = KnowledgeChatRequest(question="先看榜单再构思大纲", reasoningMode="fast")
        context = create_context(request=request, intent_decision=decision)
        route = route_agents(
            decision,
            reasoning_mode="fast",
            capability_plan=_plan_for_registry(ExpertRegistry.default()),
        )

        with run_budget_scope("fast") as budget:
            results = await run_specialists_parallel(
                context,
                agent_kernel=AgentKernel(provider),
                model="deepseek-chat",
                expert_route=route,
                model_specialist_names={"market_scan"},
            )

        by_name = {result.agentName: result for result in results}
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(1, budget.used_delegations)
        self.assertTrue(by_name["market_scan"].diagnostics["llmBacked"])
        self.assertEqual(
            "governed_domain_pass",
            by_name["market_scan"].diagnostics["modelExecutionKind"],
        )
        self.assertFalse(by_name["outline"].diagnostics.get("llmBacked", False))
        self.assertFalse(any(call.get("name", "").startswith("llm.") for call in by_name["market_scan"].toolCalls))

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
            agent_kernel=AgentKernel(provider),
            model="deepseek-v4-pro",
            expert_route=self._delegated_route(
                name="chapter_outline",
                agent_class=ChapterOutlineAgent,
                intent=Intent.chapter_outline,
            ),
        )

        self.assertEqual("high", provider.calls[0]["reasoning_effort"])
        self.assertEqual("chapter_outline", results[0].agentName)
        self.assertEqual("Chapter outline drafted.", results[0].summary)

    async def test_requested_reasoning_effort_overrides_agent_deep_default(self) -> None:
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
        # 用户在选择器里挑了「低」：即使 agent 自己的 deep 默认是 high，也要按用户选的走。
        context = create_context(
            request=KnowledgeChatRequest(
                question="给我一个前三章大纲",
                reasoningMode="deep",
                reasoningEffort="low",
            ),
            intent_decision=self._decision(Intent.chapter_outline),
        )

        results = await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(provider),
            model="deepseek-v4-pro",
            expert_route=self._delegated_route(
                name="chapter_outline",
                agent_class=ChapterOutlineAgent,
                intent=Intent.chapter_outline,
            ),
        )

        self.assertEqual("low", provider.calls[0]["reasoning_effort"])
        self.assertEqual("low", results[0].diagnostics.get("llmReasoningEffort"))

    async def test_requested_reasoning_effort_applies_in_fast_mode(self) -> None:
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
        # fast 模式本来不发推理参数，显式档位要能把它打开，否则选择器点了没反应。
        context = create_context(
            request=KnowledgeChatRequest(
                question="给我一个前三章大纲",
                reasoningMode="fast",
                reasoningEffort="medium",
            ),
            intent_decision=self._decision(Intent.chapter_outline),
        )

        await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(provider),
            model="deepseek-v4-pro",
            expert_route=self._delegated_route(
                name="chapter_outline",
                agent_class=ChapterOutlineAgent,
                intent=Intent.chapter_outline,
            ),
        )

        self.assertEqual("medium", provider.calls[0]["reasoning_effort"])

    async def test_unknown_reasoning_effort_falls_back_to_mode_default(self) -> None:
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
            request=KnowledgeChatRequest(
                question="给我一个前三章大纲",
                reasoningMode="deep",
                reasoningEffort="turbo",
            ),
            intent_decision=self._decision(Intent.chapter_outline),
        )

        await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(provider),
            model="deepseek-v4-pro",
            expert_route=self._delegated_route(
                name="chapter_outline",
                agent_class=ChapterOutlineAgent,
                intent=Intent.chapter_outline,
            ),
        )

        self.assertEqual("high", provider.calls[0]["reasoning_effort"])

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
                user_id: str | None = None,
                project_id: str | None = None,
                supervisor_permissions: set[str] | None = None,
            ) -> dict:
                self.calls.append({
                    "name": name,
                    "arguments": arguments,
                    "timeout": timeout,
                    "route": route,
                    "userId": user_id,
                    "projectId": project_id,
                    "supervisorPermissions": sorted(supervisor_permissions or set()),
                })
                return {"items": [{"rankNo": 1, "bookName": "榜一样本"}]}

        provider = Provider()
        mcp_client = McpClient()
        registry = McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "required": ["platform"]},
                "routes": ["market_scan"],
                "sideEffectType": "read",
                "scopeRequirement": "project",
                "timeoutMs": 30000,
                "identityKeys": ["userId", "projectId"],
                "secretInputKeys": [],
                "secretOutputKeys": [],
            }
        ])
        context = create_context(
            request=KnowledgeChatRequest(question="先扫榜单趋势，再给我开书方向", reasoningMode="deep"),
            intent_decision=self._decision(
                Intent.mixed_creation_research,
                [Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
            ),
        )

        context.request.userId = 7
        context.request.projectId = 91
        expert_route = self._delegated_route(
            name="market_scan",
            agent_class=MarketScanAgent,
            intent=Intent.market_scan,
            requested_tool_capabilities=("market.read",),
        )
        with run_tool_ledger_scope(RunToolIdentity(
            runId="specialist-run",
            userId=7,
            projectId=91,
            route="market_scan",
        )):
            results = await run_specialists_parallel(
                context,
                max_parallel=3,
                agent_kernel=AgentKernel(provider),
                model="deepseek-v4-pro",
                mcp_client=mcp_client,
                mcp_tool_registry=registry,
                expert_route=expert_route,
                allow_specialist_tools=True,
                authorization_decision=self._market_authorization(),
            )

        self.assertEqual("rank.lookup", mcp_client.calls[0]["name"])
        self.assertEqual("market_scan", mcp_client.calls[0]["route"])
        market_result = next(result for result in results if result.agentName == "market_scan")
        self.assertTrue(market_result.diagnostics["llmBacked"])
        self.assertTrue(market_result.diagnostics["mcpToolLoop"])
        self.assertTrue(any(call.get("name") == "rank.lookup" for call in market_result.toolCalls))
        tool_enabled_calls = [call for call in provider.calls if call.get("tools")]
        self.assertEqual(1, len(tool_enabled_calls))
        self.assertEqual(2, len(provider.calls))
        self.assertTrue(all(call["reasoning_mode"] == "deep" for call in tool_enabled_calls))

    async def test_specialist_mcp_loop_is_disabled_without_explicit_grant(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {"content": "delegated answer", "token_used": 5}

        class McpClient:
            def __init__(self) -> None:
                self.calls: list[dict] = []

        provider = Provider()
        mcp_client = McpClient()
        context = create_context(
            request=KnowledgeChatRequest(question="market", reasoningMode="deep", userId=7, projectId=91),
            intent_decision=self._decision(Intent.market_scan),
        )
        registry = McpToolRegistry([{
            "name": "rank.lookup",
            "description": "rank lookup",
            "inputSchema": {"type": "object"},
            "routes": ["market_scan"],
            "sideEffectType": "read",
            "scopeRequirement": "project",
            "timeoutMs": 30000,
            "identityKeys": ["userId", "projectId"],
            "secretInputKeys": [],
            "secretOutputKeys": [],
        }])

        results = await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(provider),
            model="deepseek-v4-pro",
            mcp_client=mcp_client,
            mcp_tool_registry=registry,
            expert_route=self._delegated_route(
                name="market_scan",
                agent_class=MarketScanAgent,
                intent=Intent.market_scan,
                requested_tool_capabilities=("market.read",),
            ),
        )

        self.assertEqual(1, len(results))
        self.assertEqual([], mcp_client.calls)
        self.assertTrue(all(not call.get("tools") for call in provider.calls))

    async def test_zero_run_tool_budget_hides_delegated_provider_schema(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {"content": "delegated answer", "token_used": 5}

        class McpClient:
            def __init__(self) -> None:
                self.calls: list[dict] = []

        provider = Provider()
        mcp_client = McpClient()
        context = create_context(
            request=KnowledgeChatRequest(
                question="market",
                reasoningMode="deep",
                userId=7,
                projectId=91,
            ),
            intent_decision=self._decision(Intent.market_scan),
        )
        registry = McpToolRegistry([{
            "name": "rank.lookup",
            "description": "rank lookup",
            "inputSchema": {"type": "object"},
            "routes": ["market_scan"],
            "sideEffectType": "read",
            "scopeRequirement": "project",
            "timeoutMs": 30000,
            "identityKeys": ["userId", "projectId"],
            "secretInputKeys": [],
            "secretOutputKeys": [],
        }])
        expert_route = self._delegated_route(
            name="market_scan",
            agent_class=MarketScanAgent,
            intent=Intent.market_scan,
            requested_tool_capabilities=("market.read",),
        )
        budget = RunBudget(
            mode="deep",
            max_total_tokens=512_000,
            max_tool_calls=0,
            max_delegations=1,
        )

        with run_budget_scope(budget), run_tool_ledger_scope(RunToolIdentity(
            runId="specialist-zero-tool-budget",
            userId=7,
            projectId=91,
            route="market_scan",
        )):
            results = await run_specialists_parallel(
                context,
                agent_kernel=AgentKernel(provider),
                model="deepseek-v4-pro",
                mcp_client=mcp_client,
                mcp_tool_registry=registry,
                expert_route=expert_route,
                allow_specialist_tools=True,
                authorization_decision=self._market_authorization(),
            )

        self.assertEqual("completed", results[0].status)
        self.assertEqual([], mcp_client.calls)
        self.assertTrue(provider.calls)
        self.assertTrue(all(not call.get("tools") for call in provider.calls))

    async def test_delegated_tools_require_top_level_authorization_and_route_manifest(self) -> None:
        class RecordingRegistry(McpToolRegistry):
            def __init__(self, tools: list[dict]) -> None:
                super().__init__(tools)
                self.project_scopes: list[object] = []

            def manifest_summary(self, **kwargs):
                self.project_scopes.append(kwargs.get("project_id", "missing"))
                return super().manifest_summary(**kwargs)

        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {"content": "delegated answer", "token_used": 5}

        provider = Provider()
        context = create_context(
            request=KnowledgeChatRequest(question="market", reasoningMode="deep", userId=7),
            intent_decision=self._decision(Intent.market_scan),
        )
        registry = RecordingRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object"},
                "routes": ["market_scan"],
                "sideEffectType": "read",
                "scopeRequirement": "user",
                "timeoutMs": 30000,
                "identityKeys": ["userId"],
                "secretInputKeys": [],
                "secretOutputKeys": [],
            },
            {
                "name": "rank.research_pack",
                "description": "wrong route",
                "inputSchema": {"type": "object"},
                "routes": ["book_breakdown"],
                "sideEffectType": "read",
                "scopeRequirement": "user",
                "timeoutMs": 30000,
                "identityKeys": ["userId"],
                "secretInputKeys": [],
                "secretOutputKeys": [],
            },
        ])

        await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(provider),
            mcp_client=object(),
            mcp_tool_registry=registry,
            expert_route=self._delegated_route(
                name="market_scan",
                agent_class=MarketScanAgent,
                intent=Intent.market_scan,
                requested_tool_capabilities=("market.read",),
            ),
            allow_specialist_tools=True,
            authorization_decision=self._market_authorization(),
        )

        names = {
            item["function"]["name"]
            for item in provider.calls[0].get("tools") or []
        }
        self.assertEqual({"rank.lookup"}, names)
        self.assertEqual([None], registry.project_scopes)

    async def test_expert_requested_capabilities_cannot_grant_without_authorization(self) -> None:
        class Provider:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def invoke(self, **kwargs) -> dict:
                self.calls.append(kwargs)
                return {"content": "delegated answer", "token_used": 5}

        provider = Provider()
        context = create_context(
            request=KnowledgeChatRequest(question="market", reasoningMode="deep", userId=7, projectId=91),
            intent_decision=self._decision(Intent.market_scan),
        )
        registry = McpToolRegistry([{
            "name": "rank.lookup",
            "description": "rank lookup",
            "inputSchema": {"type": "object"},
            "routes": ["market_scan"],
            "sideEffectType": "read",
            "scopeRequirement": "project",
            "timeoutMs": 30000,
            "identityKeys": ["userId", "projectId"],
            "secretInputKeys": [],
            "secretOutputKeys": [],
        }])

        await run_specialists_parallel(
            context,
            agent_kernel=AgentKernel(provider),
            mcp_client=object(),
            mcp_tool_registry=registry,
            expert_route=self._delegated_route(
                name="market_scan",
                agent_class=MarketScanAgent,
                intent=Intent.market_scan,
                requested_tool_capabilities=("market.read",),
            ),
            allow_specialist_tools=True,
        )

        self.assertTrue(all(not call.get("tools") for call in provider.calls))

    def _decision(self, intent: Intent, sub_intents: list[Intent] | None = None) -> IntentDecision:
        return IntentDecision(primaryIntent=intent, subIntents=sub_intents or [])


if __name__ == "__main__":
    unittest.main()
