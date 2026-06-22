from __future__ import annotations

import unittest

from app.models.agent_runtime import ContextBundle, ContextLayer, MemoryCandidate, SourcePolicy, SupervisorDecision


class AgentRuntimeModelsTest(unittest.TestCase):
    def test_source_policy_serializes_latest_rank_defaults(self) -> None:
        policy = SourcePolicy(freshness="latest")

        payload = policy.model_dump(mode="json")

        self.assertEqual("latest", payload["freshness"])
        self.assertFalse(payload["allowHistorical"])
        self.assertTrue(payload["requireSnapshotTime"])

    def test_context_bundle_keeps_layer_scopes(self) -> None:
        bundle = ContextBundle(
            systemBaseline=ContextLayer(scope="system", content={"domain": "webnovel"}),
            projectProfile=ContextLayer(scope="project", content={"genre": "都市脑洞"}),
            threadSummary=ContextLayer(scope="thread", content={"summary": "讨论前三章"}),
            currentTurn=ContextLayer(scope="turn", content={"question": "最近还能不能写"}),
        )

        payload = bundle.model_dump(mode="json", exclude_none=True)

        self.assertEqual("system", payload["systemBaseline"]["scope"])
        self.assertEqual("project", payload["projectProfile"]["scope"])
        self.assertEqual("thread", payload["threadSummary"]["scope"])
        self.assertEqual("turn", payload["currentTurn"]["scope"])

    def test_supervisor_decision_preserves_next_route_and_reason(self) -> None:
        decision = SupervisorDecision(
            status="needs_fresh_rank",
            freshnessSatisfied=False,
            evidenceEnough=False,
            reason="latest rank snapshot missing",
            nextRoute="market_research_subgraph",
        )

        payload = decision.model_dump(mode="json")

        self.assertEqual("needs_fresh_rank", payload["status"])
        self.assertFalse(payload["freshnessSatisfied"])
        self.assertEqual("market_research_subgraph", payload["nextRoute"])
        self.assertEqual("latest rank snapshot missing", payload["reason"])

    def test_memory_candidate_serializes_project_scope(self) -> None:
        candidate = MemoryCandidate(
            scope="project",
            type="constraint",
            content="不后宫，前三章快节奏",
            confidence=0.82,
            sourceTraceId="trace-1",
            reason="explicit project writing constraint",
        )

        payload = candidate.model_dump(mode="json")

        self.assertEqual("project", payload["scope"])
        self.assertEqual("constraint", payload["type"])
        self.assertEqual("trace-1", payload["sourceTraceId"])
        self.assertAlmostEqual(0.82, payload["confidence"])


if __name__ == "__main__":
    unittest.main()
