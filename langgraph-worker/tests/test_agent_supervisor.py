from __future__ import annotations

import unittest

from app.models.agent_runtime import SourcePolicy
from app.models.agent_task import EvidencePack
from app.services.runtime.supervisor import AgentSupervisor


class AgentSupervisorTest(unittest.TestCase):
    def test_recent_market_without_snapshot_time_needs_fresh_rank(self) -> None:
        decision = AgentSupervisor().evaluate(
            route="market_scan",
            source_policy=SourcePolicy(freshness="latest", requireSnapshotTime=True),
            evidence=EvidencePack(facts=[{"sourceType": "RANK", "bookName": "榜一书"}]),
        )

        self.assertEqual("needs_fresh_rank", decision.status)
        self.assertFalse(decision.freshnessSatisfied)
        self.assertIn("latest rank snapshot missing", decision.reason or "")

    def test_recent_market_with_snapshot_time_is_answerable(self) -> None:
        decision = AgentSupervisor().evaluate(
            route="market_scan",
            source_policy=SourcePolicy(freshness="latest", requireSnapshotTime=True),
            evidence=EvidencePack(facts=[{"sourceType": "RANK", "snapshotTime": "2026-06-21T00:00:00"}]),
        )

        self.assertEqual("answerable", decision.status)
        self.assertTrue(decision.freshnessSatisfied)
        self.assertTrue(decision.evidenceEnough)

    def test_book_breakdown_without_book_needs_selection(self) -> None:
        decision = AgentSupervisor().evaluate(
            route="book_breakdown",
            source_policy=SourcePolicy(freshness="any", requireSnapshotTime=False),
            evidence=EvidencePack(),
        )

        self.assertEqual("needs_book_selection", decision.status)
        self.assertIn("book", decision.missingSlots)

    def test_creative_project_route_can_answer_without_evidence(self) -> None:
        decision = AgentSupervisor().evaluate(
            route="project_creation",
            source_policy=SourcePolicy(freshness="any", requireSnapshotTime=False),
            evidence=EvidencePack(),
        )

        self.assertEqual("answerable", decision.status)
        self.assertFalse(decision.evidenceEnough)

    def test_followup_revision_without_project_or_thread_context_needs_clarification(self) -> None:
        decision = AgentSupervisor().evaluate(
            route="followup_revision",
            source_policy=SourcePolicy(freshness="any", requireSnapshotTime=False),
            evidence=EvidencePack(),
            has_thread_or_project_context=False,
        )

        self.assertEqual("needs_clarification", decision.status)
        self.assertIn("project_or_thread_context", decision.missingSlots)


if __name__ == "__main__":
    unittest.main()
