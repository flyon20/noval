from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class SupervisorAgent(BaseSpecialistAgent):
    agent_name = "supervisor"
    answer_mode = "supervisor"
    llm_enabled = True
    tool_route = "mixed_creation_research"
    summary = "Check the final answer boundary against the EvidenceContract."
    generation_instructions = (
        "Validate that factual claims stay within the EvidenceContract.",
        "Reject latest-market claims outside selected evidence.",
        "Require caveats when evidence is degraded, stale, missing, or conflicting.",
    )
    evidence_policy = (
        "EvidenceContract is the source of truth for factual boundaries.",
        "Creative recommendations must be labeled as inference.",
        "Unsupported factual claims should be removed or caveated.",
    )
    evidence_refs = ("evidence_contract", "supervisor")
    actions = ("validate_evidence_boundary",)
