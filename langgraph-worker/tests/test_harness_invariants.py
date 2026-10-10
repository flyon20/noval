from __future__ import annotations

import unittest

from app.services.harness.capability_authorizer import CapabilityAuthorizer
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import DomainStatus, IntentEnvelope
from app.services.novel_research_agent import NovelResearchAgent
from tests.test_novel_research_agent import FakeAnswerProvider, FakeKnowledgeClient, KnowledgeChatRequest


class HarnessInvariantTests(unittest.TestCase):
    def setUp(self) -> None:
        self.compiler = CapabilityCompiler()
        self.authorizer = CapabilityAuthorizer()

    def _envelope(self, *operations: str, status: DomainStatus = DomainStatus.IN_SCOPE) -> IntentEnvelope:
        return IntentEnvelope(
            domainStatus=status,
            goal=operations[0] if operations else "out_of_scope",
            operations=operations,
            confidence=0.9,
            classificationSource="rules",
        )

    def test_out_of_scope_plan_has_no_tools_skills_or_experts(self) -> None:
        plan = self.compiler.compile(self._envelope("out_of_scope", status=DomainStatus.OUT_OF_SCOPE))
        decision = self.authorizer.authorize(plan)
        self.assertEqual((), plan.capabilityRequests)
        self.assertEqual((), plan.skillCandidateIds)
        self.assertEqual((), plan.expertCandidateIds)
        self.assertEqual((), decision.grants)

    def test_capability_plan_fingerprint_is_stable(self) -> None:
        first = self.compiler.compile(self._envelope("market_scan"))
        second = self.compiler.compile(self._envelope("market_scan"))
        self.assertEqual(first.fingerprint, second.fingerprint)

    def test_authorization_grants_and_manifest_are_sole_tool_visibility(self) -> None:
        plan = self.compiler.compile(self._envelope("market_scan"))
        decision = self.authorizer.authorize(plan)
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        state = {
            "authorization_decision": decision.model_dump(mode="json"),
            "expert_profiles": [{
                "expertName": "market_scan",
                "enabled": True,
                "allowedTools": ["rank.refresh", "admin.backdoor"],
            }],
            "selected_skills": ["webnovel-market-scan"],
        }
        allowed = agent._allowed_tools_for_state(state)
        manifest_tools = set(agent._tool_registry.manifest_summary()["toolNames"])
        expected = self.authorizer.effective_tool_names(
            decision,
            manifest_tools=manifest_tools,
        )

        self.assertEqual(expected, allowed)
        self.assertNotIn("rank.refresh", allowed)
        self.assertNotIn("admin.backdoor", allowed)

    def test_expert_and_skill_metadata_cannot_expand_empty_decision(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        state = {
            "authorization_decision": {
                "decisionId": "empty",
                "grants": [],
                "deniedCapabilityIds": [],
                "reasonCodes": ["no_requested_capabilities"],
            },
            "expert_profiles": [{"expertName": "x", "enabled": True, "allowedTools": ["rank.lookup"]}],
            "selected_skills": ["webnovel-market-scan"],
        }
        self.assertEqual(set(), agent._allowed_tools_for_state(state))

    def test_missing_authorization_decision_is_fail_closed(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        self.assertEqual(set(), agent._allowed_tools_for_state({}))


class HarnessInvariantAsyncTests(unittest.IsolatedAsyncioTestCase):
    async def test_single_intent_envelope_per_run(self) -> None:
        from tests.test_novel_research_agent import CurrentStructuredRankTrendKnowledgeClient

        agent = NovelResearchAgent(
            knowledge_client=CurrentStructuredRankTrendKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        response = await agent.run(KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            traceId="invariant-single-envelope",
        ))
        envelope = response.resultJson.get("intentEnvelope") or response.resultJson.get("trace", {}).get("intentEnvelope")
        self.assertIsInstance(envelope, dict)
        self.assertTrue(str(envelope.get("envelopeId") or envelope.get("fingerprint") or "").strip())
        auth = response.resultJson.get("authorizationDecision")
        self.assertIsInstance(auth, dict)
        grants = {item.get("toolName") for item in auth.get("grants") or []}
        self.assertIn("rank.lookup", grants)
        self.assertNotIn("rank.refresh", grants)


if __name__ == "__main__":
    unittest.main()
