from __future__ import annotations

from app.models.agent_runtime import SourcePolicy, SupervisorDecision
from app.models.agent_task import EvidencePack


class AgentSupervisor:
    def evaluate(
        self,
        *,
        route: str,
        source_policy: SourcePolicy,
        evidence: EvidencePack,
        has_book_context: bool = False,
        has_thread_or_project_context: bool = True,
    ) -> SupervisorDecision:
        if route == "book_breakdown" and not has_book_context and not evidence.examples:
            return SupervisorDecision(
                status="needs_book_selection",
                evidenceEnough=False,
                missingSlots=["book"],
                requiredActions=["select_candidate"],
                reason="book breakdown requires a resolved book or candidate selection",
                nextRoute="finalize_candidate_selection",
            )

        if route == "followup_revision" and not has_thread_or_project_context:
            return SupervisorDecision(
                status="needs_clarification",
                evidenceEnough=False,
                missingSlots=["project_or_thread_context"],
                requiredActions=["clarify_context"],
                reason="follow-up revision requires project or thread context",
                nextRoute="finalize_clarification",
            )

        if self._requires_fresh_rank(route, source_policy):
            if not evidence.facts:
                return SupervisorDecision(
                    status="needs_fresh_rank",
                    freshnessSatisfied=False,
                    evidenceEnough=False,
                    missingSlots=["latest_rank_snapshot"],
                    requiredActions=["fetch_latest_rank"],
                    reason="latest rank evidence missing",
                    nextRoute="market_research_subgraph",
                )
            if not self._has_snapshot_time(evidence):
                return SupervisorDecision(
                    status="needs_fresh_rank",
                    freshnessSatisfied=False,
                    evidenceEnough=False,
                    missingSlots=["snapshotTime"],
                    requiredActions=["fetch_latest_rank"],
                    reason="latest rank snapshot missing snapshotTime",
                    nextRoute="market_research_subgraph",
                )

        evidence_enough = bool(evidence.facts or evidence.examples or evidence.signals or evidence.inferenceSeeds)
        return SupervisorDecision(
            status="answerable",
            freshnessSatisfied=True,
            evidenceEnough=evidence_enough,
            reason="evidence requirements satisfied" if evidence_enough else "creative inference can proceed without external evidence",
            nextRoute="compose_answer",
        )

    def _requires_fresh_rank(self, route: str, source_policy: SourcePolicy) -> bool:
        if source_policy.freshness != "latest" or not source_policy.requireSnapshotTime:
            return False
        return route in {"market_scan", "mixed_creation_research", "trend_research"}

    def _has_snapshot_time(self, evidence: EvidencePack) -> bool:
        return any(bool(item.get("snapshotTime") or item.get("snapshotId")) for item in evidence.facts)
