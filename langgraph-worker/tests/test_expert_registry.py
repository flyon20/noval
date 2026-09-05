from __future__ import annotations

from dataclasses import replace
import unittest

from app.services.agents import MarketScanAgent, OpeningStrategyAgent, OutlineAgent
from app.services.agents.expert_registry import (
    ExpertCategory,
    ExpertProfile,
    ExpertRegistry,
    ExpertRouter,
)
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import (
    CapabilityPlan,
    CapabilityRequest,
    DomainStatus,
    ExpertExecutionKind,
    IntentEnvelope,
)
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.intents.domain_intents import Intent, IntentDecision


class ExpertRegistryTests(unittest.TestCase):
    def _decision(self, intent: Intent, sub_intents: list[Intent] | None = None) -> IntentDecision:
        return IntentDecision(primaryIntent=intent, subIntents=sub_intents or [])

    def _delegated_profile(
        self,
        *,
        name: str,
        agent_class: type,
        priority: int,
        expected_quality_gain: float = 0.0,
        latency_cost: float = 0.0,
        token_cost: float = 0.0,
        resource_cost: float = 0.0,
    ) -> ExpertProfile:
        return ExpertProfile(
            name=name,
            displayName=name.replace("_", " ").title(),
            agentClass=agent_class,
            category=ExpertCategory.DELEGATED,
            triggerIntents=(Intent.market_scan,),
            priority=priority,
            expectedQualityGain=expected_quality_gain,
            qualityGainVerified=True,
            qualityGainSource="admin_configured_eval",
            qualityGainEvalRunId=42,
            latencyCost=latency_cost,
            tokenCost=token_cost,
            resourceCost=resource_cost,
        )

    def _fully_bound_profile(self) -> ExpertProfile:
        return ExpertProfile(
            name="market_scan",
            displayName="\u5e02\u573a\u5206\u6790",
            agentClass=MarketScanAgent,
            category=ExpertCategory.DELEGATED,
            enabled=True,
            defaultMode="deep",
            costClass="high",
            maxTokens=1200,
            maxToolCalls=4,
            capabilityIds=("market.read", "market.analyze"),
            requestedToolCapabilities=("skill.activate", "market.read"),
            defaultSkillIds=("webnovel-market-scan", "webnovel-evidence"),
            outputContract="market-analysis-v2",
            executionKind=ExpertExecutionKind.DELEGATED,
            triggerIntents=(Intent.mixed_creation_research, Intent.market_scan),
            triggerTaskTypes=("topic_strategy", "market_scan"),
            priority=10,
            promptVersion="\u7248\u672c\u4e8c",
            evalSuite="\u5e02\u573a\u5957\u4ef6",
            guardrail=True,
            expectedQualityGain=0.45,
            qualityGainVerified=True,
            qualityGainSource="admin_configured_eval",
            qualityGainEvalRunId=42,
            latencyCost=0.10,
            tokenCost=0.05,
            resourceCost=0.02,
        )

    def _plan_for(self, registry: ExpertRegistry) -> CapabilityPlan:
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

    def test_default_registry_classifies_capabilities_and_delegates_none(self) -> None:
        registry = ExpertRegistry.default()

        self.assertEqual(ExpertCategory.SKILL, registry.get("market_scan").category)
        self.assertEqual(ExpertCategory.DETERMINISTIC, registry.get("reader_risk").category)
        self.assertEqual(ExpertCategory.DELEGATED, registry.get("supervisor").category)
        self.assertFalse(hasattr(registry.get("market_scan"), "allowedTools"))

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(
                Intent.mixed_creation_research,
                [Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
            ),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual([], result.agentClasses)
        self.assertEqual(0, result.delegatedCount)
        self.assertEqual(1, result.maxParallel)
        self.assertEqual(
            ["market_scan", "opening_strategy", "outline"],
            [capability.name for capability in result.selectedCapabilities],
        )
        self.assertTrue(
            all(capability.category == ExpertCategory.SKILL for capability in result.selectedCapabilities)
        )
        self.assertTrue(all("allowedTools" not in capability.to_dict() for capability in result.selectedCapabilities))

    def test_delegation_defaults_to_zero_without_expected_quality_gain(self) -> None:
        registry = ExpertRegistry([
            self._delegated_profile(
                name="market_scan",
                agent_class=MarketScanAgent,
                priority=10,
            )
        ])

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual([], result.agentClasses)
        self.assertEqual([], result.selectedCapabilities)
        self.assertEqual(0, result.delegatedCount)
        self.assertIn("quality_gain", result.skippedExperts["market_scan"])

    def test_eval_candidate_can_run_unverified_profile_while_control_stays_zero(self) -> None:
        profile = ExpertProfile(
            name="market_scan",
            displayName="Market Scan",
            agentClass=MarketScanAgent,
            category=ExpertCategory.DELEGATED,
            triggerIntents=(Intent.market_scan,),
            priority=10,
            evalSuite="market",
        )
        registry = ExpertRegistry([profile])
        router = ExpertRouter(registry)
        plan = self._plan_for(registry)

        control = router.route(
            intent_decision=self._decision(Intent.market_scan),
            eval_delegation_mode="control",
            capability_plan=plan,
        )
        candidate = router.route(
            intent_decision=self._decision(Intent.market_scan),
            eval_delegation_mode="candidate",
            eval_candidate_config_fingerprint=profile.eval_config_fingerprint(),
            capability_plan=plan,
        )

        self.assertEqual([], control.selectedExperts)
        self.assertEqual(["market_scan"], [item.name for item in candidate.selectedExperts])
        self.assertFalse(candidate.selectedExperts[0].qualityGainVerified)

    def test_eval_candidate_cannot_select_a_different_profile_hash(self) -> None:
        profile = ExpertProfile(
            name="market_scan",
            displayName="Market Scan",
            agentClass=MarketScanAgent,
            category=ExpertCategory.DELEGATED,
            triggerIntents=(Intent.market_scan,),
            priority=10,
            evalSuite="market",
        )

        registry = ExpertRegistry([profile])
        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            eval_delegation_mode="candidate",
            eval_candidate_config_fingerprint="0" * 64,
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual("eval_other_candidate", result.skippedExperts["market_scan"])

    def test_eval_config_fingerprint_covers_routing_and_eval_authorization_fields(self) -> None:
        profile = ExpertProfile(
            name="market_scan",
            displayName="Market Scan",
            agentClass=MarketScanAgent,
            category=ExpertCategory.DELEGATED,
            defaultMode="both",
            triggerIntents=(Intent.market_scan,),
            triggerTaskTypes=("market_scan",),
            priority=10,
            evalSuite="market",
        )

        variants = (
            replace(profile, triggerIntents=(Intent.outline_building,)),
            replace(profile, triggerTaskTypes=("outline_building",)),
            replace(profile, evalSuite="mixed-creation"),
            replace(profile, defaultMode="deep"),
            replace(profile, priority=20),
        )

        self.assertTrue(
            all(profile.eval_config_fingerprint() != item.eval_config_fingerprint() for item in variants)
        )

    def test_eval_config_fingerprint_uses_utf8_canonical_json_for_chinese_admin_values(self) -> None:
        profile = ExpertProfile(
            name="market_scan",
            displayName="Market Scan",
            agentClass=MarketScanAgent,
            category=ExpertCategory.DELEGATED,
            defaultMode="deep",
            maxTokens=1200,
            maxToolCalls=4,
            requestedToolCapabilities=("skill.activate", "market.read"),
            triggerIntents=(Intent.mixed_creation_research, Intent.market_scan),
            triggerTaskTypes=("topic_strategy", "market_scan"),
            priority=10,
            promptVersion="版本二",
            evalSuite="市场套件",
        )

        clone = replace(profile)
        self.assertEqual(profile.eval_config_fingerprint(), clone.eval_config_fingerprint())
        self.assertEqual(64, len(profile.eval_config_fingerprint()))
        self.assertNotEqual(
            profile.eval_config_fingerprint(),
            replace(profile, promptVersion="v2").eval_config_fingerprint(),
        )

    def test_unverified_admin_quality_gain_cannot_enable_delegation(self) -> None:
        profile = ExpertProfile(
            name="market_scan",
            displayName="Market Scan",
            agentClass=MarketScanAgent,
            category=ExpertCategory.DELEGATED,
            triggerIntents=(Intent.market_scan,),
            expectedQualityGain=1.0,
            qualityGainVerified=False,
            qualityGainSource="admin_declared",
        )

        registry = ExpertRegistry([profile])
        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual("quality_gain_unverified", result.skippedExperts["market_scan"])

    def test_quality_gain_uses_expected_gain_less_all_costs_and_mode_threshold(self) -> None:
        registry = ExpertRegistry([
            self._delegated_profile(
                name="below_fast_threshold",
                agent_class=MarketScanAgent,
                priority=10,
                expected_quality_gain=0.55,
                latency_cost=0.10,
                token_cost=0.10,
                resource_cost=0.11,
            ),
            self._delegated_profile(
                name="at_fast_threshold",
                agent_class=OpeningStrategyAgent,
                priority=20,
                expected_quality_gain=0.55,
                latency_cost=0.10,
                token_cost=0.10,
                resource_cost=0.10,
            ),
        ])

        fast = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="fast",
            capability_plan=self._plan_for(registry),
        )
        deep = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual(["at_fast_threshold"], [expert.name for expert in fast.selectedExperts])
        self.assertEqual(["at_fast_threshold"], [expert.name for expert in fast.selectedCapabilities])
        self.assertEqual(0.25, fast.selectedExperts[0].qualityGain)
        self.assertIn("quality_gain", fast.skippedExperts["below_fast_threshold"])
        self.assertEqual(
            ["below_fast_threshold", "at_fast_threshold"],
            [expert.name for expert in deep.selectedExperts],
        )

    def test_fast_caps_one_and_deep_caps_two_delegated_agents(self) -> None:
        registry = ExpertRegistry([
            self._delegated_profile(
                name="first",
                agent_class=MarketScanAgent,
                priority=10,
                expected_quality_gain=0.60,
            ),
            self._delegated_profile(
                name="second",
                agent_class=OpeningStrategyAgent,
                priority=20,
                expected_quality_gain=0.50,
            ),
            self._delegated_profile(
                name="third",
                agent_class=OutlineAgent,
                priority=30,
                expected_quality_gain=0.40,
            ),
        ])

        fast = ExpertRouter(registry, max_experts_fast=99).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="fast",
            capability_plan=self._plan_for(registry),
        )
        deep = ExpertRouter(registry, max_experts_deep=99).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual(["first"], [expert.name for expert in fast.selectedExperts])
        self.assertEqual(["first", "second"], [expert.name for expert in deep.selectedExperts])
        self.assertEqual("top_k:fast", fast.skippedExperts["second"])
        self.assertEqual("top_k:deep", deep.skippedExperts["third"])

    def test_parallel_delegation_is_hard_capped_at_one(self) -> None:
        registry = ExpertRegistry([
            self._delegated_profile(
                name="market_scan",
                agent_class=MarketScanAgent,
                priority=10,
                expected_quality_gain=0.50,
            )
        ])

        result = ExpertRouter(registry, max_parallel=8).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )

        self.assertEqual(1, result.maxParallel)

    def test_selected_trace_respects_remaining_run_delegation_budget(self) -> None:
        registry = ExpertRegistry([
            self._delegated_profile(
                name="first",
                agent_class=MarketScanAgent,
                priority=10,
                expected_quality_gain=0.60,
            ),
            self._delegated_profile(
                name="second",
                agent_class=OpeningStrategyAgent,
                priority=20,
                expected_quality_gain=0.50,
            ),
        ])
        budget = RunBudget(
            mode="deep",
            max_total_tokens=10_000,
            max_tool_calls=10,
            max_delegations=1,
        )

        with run_budget_scope(budget):
            result = ExpertRouter(registry).route(
                intent_decision=self._decision(Intent.market_scan),
                reasoning_mode="deep",
                capability_plan=self._plan_for(registry),
            )

        self.assertEqual(["first"], [expert.name for expert in result.selectedExperts])
        self.assertEqual(1, result.delegatedCount)
        self.assertEqual("delegation_budget", result.skippedExperts["second"])

    def test_selected_trace_matches_actual_delegated_agents(self) -> None:
        registry = ExpertRegistry([
            ExpertProfile(
                name="market_skill",
                displayName="Market Skill",
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.market_scan,),
                priority=10,
            ),
            ExpertProfile(
                name="rank_lookup",
                displayName="Rank Lookup",
                category=ExpertCategory.DETERMINISTIC,
                triggerIntents=(Intent.market_scan,),
                priority=20,
            ),
            self._delegated_profile(
                name="market_delegate",
                agent_class=MarketScanAgent,
                priority=30,
                expected_quality_gain=0.50,
            ),
        ])

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )
        trace = result.to_dict()

        self.assertEqual(["market_delegate"], [expert.name for expert in result.selectedExperts])
        self.assertEqual([MarketScanAgent], result.agentClasses)
        self.assertEqual(len(result.selectedExperts), result.delegatedCount)
        self.assertEqual(len(result.agentClasses), result.delegatedCount)
        self.assertEqual(result.delegatedCount, trace["delegatedCount"])
        self.assertEqual(result.delegatedCount, len(trace["selectedExperts"]))
        self.assertEqual(
            ["market_skill", "rank_lookup", "market_delegate"],
            [capability["name"] for capability in trace["selectedCapabilities"]],
        )

    def test_admin_overlay_accepts_category_and_quality_gain_policy(self) -> None:
        registry = ExpertRegistry([
            self._delegated_profile(
                name="market_scan",
                agent_class=MarketScanAgent,
                priority=10,
            )
        ]).with_admin_profiles([
            {
                "expertName": "market_scan",
                "category": "Skill",
                "expectedQualityGain": 0.60,
                "latencyCost": 0.10,
                "tokenCost": 0.20,
                "resourceCost": 0.05,
                "qualityGainVerified": True,
                "qualityGainSource": "admin_configured_eval",
                "qualityGainEvalRunId": 42,
            }
        ])

        profile = registry.get("market_scan")

        self.assertIsNotNone(profile)
        assert profile is not None
        self.assertEqual(ExpertCategory.SKILL, profile.category)
        self.assertEqual(0.60, profile.expectedQualityGain)
        self.assertEqual(0.10, profile.latencyCost)
        self.assertEqual(0.20, profile.tokenCost)
        self.assertEqual(0.05, profile.resourceCost)
        self.assertTrue(profile.qualityGainVerified)
        self.assertEqual("admin_configured_eval", profile.qualityGainSource)
        self.assertEqual(42, profile.qualityGainEvalRunId)


    def test_skill_category_maps_to_inline_execution_kind(self) -> None:
        registry = ExpertRegistry.default()
        market = registry.get("market_scan")
        reader = registry.get("reader_risk")
        supervisor = registry.get("supervisor")
        self.assertEqual(ExpertExecutionKind.INLINE, market.executionKind)
        self.assertEqual(ExpertExecutionKind.DETERMINISTIC, reader.executionKind)
        self.assertEqual(ExpertExecutionKind.DELEGATED, supervisor.executionKind)

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="fast",
            capability_plan=self._plan_for(registry),
        )
        self.assertTrue(result.selectedCapabilities)
        self.assertTrue(all(item.executionKind is ExpertExecutionKind.INLINE for item in result.selectedCapabilities))
        self.assertTrue(all(item.executionKind is ExpertExecutionKind.INLINE for item in result.selectedCapabilities))

    def test_capability_plan_prevents_raw_intent_from_expanding_experts(self) -> None:
        registry = ExpertRegistry.default()
        plan = CapabilityCompiler().compile(
            IntentEnvelope(
                domainStatus=DomainStatus.IN_SCOPE,
                goal="market_scan",
                operations=("market_scan",),
                confidence=0.9,
                classificationSource="rules",
            )
        )
        # Plan only nominates market_scan; opening_strategy must not appear from raw task graph.
        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            task_graph={"tasks": [{"type": "opening_strategy"}, {"type": "market_scan"}]},
            capability_plan=plan,
        )
        names = [item.name for item in result.selectedCapabilities]
        self.assertIn("market_scan", names)
        self.assertNotIn("opening_strategy", names)
        self.assertIn("outside_capability_plan", result.skippedExperts.get("opening_strategy", "outside_capability_plan"))

    def test_missing_capability_plan_selects_no_experts(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(Intent.market_scan),
            capability_plan=None,
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual([], result.selectedCapabilities)
        self.assertEqual("missing", result.capabilityPlanStatus)
        self.assertEqual(("missing_capability_plan",), result.reasonCodes)
        self.assertEqual("missing_capability_plan", result.skippedExperts["market_scan"])

    def test_invalid_capability_plan_type_selects_no_experts(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(Intent.market_scan),
            capability_plan="invalid",  # type: ignore[arg-type]
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual([], result.selectedCapabilities)
        self.assertEqual("invalid", result.capabilityPlanStatus)
        self.assertEqual(("invalid_capability_plan",), result.reasonCodes)
        self.assertEqual("invalid_capability_plan", result.skippedExperts["market_scan"])

    def test_malformed_capability_plan_dict_selects_no_experts(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(Intent.market_scan),
            capability_plan={"intentEnvelopeHash": "sha256:test", "unexpected": True},
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual([], result.selectedCapabilities)
        self.assertEqual("invalid", result.capabilityPlanStatus)
        self.assertEqual(("invalid_capability_plan",), result.reasonCodes)

    def test_valid_empty_capability_plan_selects_no_experts(self) -> None:
        result = ExpertRouter(ExpertRegistry.default()).route(
            intent_decision=self._decision(Intent.market_scan),
            capability_plan=CapabilityPlan(intentEnvelopeHash="sha256:test"),
        )

        self.assertEqual([], result.selectedExperts)
        self.assertEqual([], result.selectedCapabilities)
        self.assertEqual("valid", result.capabilityPlanStatus)
        self.assertEqual((), result.reasonCodes)
        self.assertEqual("outside_capability_plan", result.skippedExperts["market_scan"])

    def test_eval_config_fingerprint_covers_all_pre_eval_behavior_and_excludes_attestation(self) -> None:
        profile = self._fully_bound_profile()
        config_variants = (
            replace(profile, name="market_scan_v2"),
            replace(profile, displayName="Market Scan"),
            replace(profile, category=ExpertCategory.DETERMINISTIC),
            replace(profile, enabled=False),
            replace(profile, defaultMode="fast"),
            replace(profile, costClass="low"),
            replace(profile, maxTokens=1199),
            replace(profile, maxToolCalls=3),
            replace(profile, capabilityIds=("market.read",)),
            replace(profile, requestedToolCapabilities=("market.read",)),
            replace(profile, defaultSkillIds=("webnovel-market-scan",)),
            replace(profile, outputContract="market-analysis-v3"),
            replace(profile, executionKind=ExpertExecutionKind.INLINE),
            replace(profile, triggerIntents=(Intent.market_scan,)),
            replace(profile, triggerTaskTypes=("market_scan",)),
            replace(profile, priority=20),
            replace(profile, promptVersion="v3"),
            replace(profile, evalSuite="market-v3"),
            replace(profile, guardrail=False),
            replace(profile, latencyCost=0.11),
            replace(profile, tokenCost=0.06),
            replace(profile, resourceCost=0.03),
        )
        attestation_variants = (
            replace(profile, expectedQualityGain=0.55),
            replace(profile, qualityGainVerified=False),
            replace(profile, qualityGainSource="unverified"),
            replace(profile, qualityGainEvalRunId=43),
        )

        fingerprint = profile.eval_config_fingerprint()

        self.assertTrue(all(fingerprint != item.eval_config_fingerprint() for item in config_variants))
        self.assertTrue(all(fingerprint == item.eval_config_fingerprint() for item in attestation_variants))
        self.assertFalse(hasattr(profile, "fingerprint"))

    def test_explicit_fingerprints_use_cross_language_utf8_canonical_json(self) -> None:
        profile = self._fully_bound_profile()

        self.assertEqual(
            "4a3f99253e8491662cfaf0b7b1f23d1bc4a3dc712068b3cc409632746d731002",
            profile.eval_config_fingerprint(),
        )
        self.assertEqual(
            "943de925751db23fa4807e2011a6ebdd15bdf83d33792638d09c390e1c50f9bc",
            profile.runtime_binding_fingerprint(),
        )
        self.assertNotEqual(
            profile.runtime_binding_fingerprint(),
            replace(profile, expectedQualityGain=0.46).runtime_binding_fingerprint(),
        )
        self.assertNotEqual(
            profile.runtime_binding_fingerprint(),
            replace(profile, qualityGainEvalRunId=43).runtime_binding_fingerprint(),
        )

    def test_route_payload_uses_explicit_fingerprints_and_stable_binding_aggregate(self) -> None:
        profile = self._fully_bound_profile()
        registry = ExpertRegistry([profile])

        result = ExpertRouter(registry).route(
            intent_decision=self._decision(Intent.market_scan),
            reasoning_mode="deep",
            capability_plan=self._plan_for(registry),
        )
        trace = result.to_dict()
        selected = trace["selectedExperts"][0]

        self.assertEqual(profile.eval_config_fingerprint(), selected["evalConfigFingerprint"])
        self.assertEqual(profile.runtime_binding_fingerprint(), selected["runtimeBindingFingerprint"])
        self.assertNotIn("profileFingerprint", selected)
        self.assertTrue(trace["expertBindingsHash"].startswith("sha256:"))


if __name__ == "__main__":
    unittest.main()
