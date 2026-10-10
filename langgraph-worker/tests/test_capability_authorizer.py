from __future__ import annotations

import inspect
import unittest

from app.services.harness.capability_authorizer import CapabilityAuthorizer
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import (
    CapabilityLimits,
    CapabilityScope,
    DomainStatus,
    IntentEnvelope,
    SideEffectPolicy,
)
from app.services.harness.execution_path import ExecutionPath


class CapabilityAuthorizerTests(unittest.TestCase):
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

    def test_market_read_grants_lookup_but_not_refresh(self) -> None:
        plan = self.compiler.compile(self._envelope("market_scan"))
        decision = self.authorizer.authorize(plan)
        tools = self.authorizer.allowed_tool_names(decision)
        self.assertEqual({"rank.lookup"}, tools)
        self.assertNotIn("rank.research_pack", tools)
        self.assertNotIn("knowledge.vector_search", tools)
        self.assertNotIn("rank.refresh", tools)

    def test_mixed_creation_can_request_bounded_market_research_tools(self) -> None:
        plan = self.compiler.compile(self._envelope("mixed_creation_research"))
        decision = self.authorizer.authorize(plan)
        tools = self.authorizer.allowed_tool_names(decision)

        self.assertIn("rank.lookup", tools)
        self.assertIn("rank.research_pack", tools)
        self.assertIn("knowledge.vector_search", tools)

    def test_pure_creation_without_tool_caps_can_still_grant_creation_tools(self) -> None:
        plan = self.compiler.compile(self._envelope("opening_strategy"))
        decision = self.authorizer.authorize(plan)
        tools = self.authorizer.allowed_tool_names(decision)
        self.assertIn("skill.lookup", tools)
        self.assertNotIn("rank.lookup", tools)

    def test_project_continuity_grants_exact_foreshadowing_aggregate(self) -> None:
        plan = self.compiler.compile(self._envelope("project_knowledge"))
        decision = self.authorizer.authorize(plan)

        self.assertIn("project.foreshadowing.aggregate", self.authorizer.allowed_tool_names(decision))

    def test_out_of_scope_has_empty_grants(self) -> None:
        plan = self.compiler.compile(self._envelope("out_of_scope", status=DomainStatus.OUT_OF_SCOPE))
        decision = self.authorizer.authorize(plan)
        self.assertEqual((), decision.grants)
        self.assertEqual(set(), self.authorizer.allowed_tool_names(decision))

    def test_manifest_intersection_can_deny_tools(self) -> None:
        plan = self.compiler.compile(self._envelope("market_scan"))
        decision = self.authorizer.authorize(plan)
        tools = self.authorizer.effective_tool_names(
            decision,
            manifest_tools={"rank.lookup"},
        )
        self.assertEqual({"rank.lookup"}, tools)

    def test_authorize_does_not_advertise_unused_runtime_policy(self) -> None:
        parameters = inspect.signature(self.authorizer.authorize).parameters

        self.assertNotIn("runtime_policy", parameters)

    def test_unmapped_capability_is_denied_not_granted(self) -> None:
        plan = self.compiler.compile(self._envelope("market_scan"))
        # inject unknown capability id
        dirty = plan.model_copy(
            update={
                "requestedToolCapabilities": plan.requestedToolCapabilities + ("unknown.capability",),
            }
        )
        decision = self.authorizer.authorize(dirty)
        self.assertIn("unknown.capability", decision.deniedCapabilityIds)
        self.assertNotIn(
            "unknown.capability",
            {grant.capabilityId for grant in decision.grants},
        )


if __name__ == "__main__":
    unittest.main()
