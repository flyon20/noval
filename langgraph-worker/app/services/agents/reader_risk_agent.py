from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class ReaderRiskAgent(BaseSpecialistAgent):
    agent_name = "reader_risk"
    answer_mode = "reader_risk"
    llm_enabled = True
    tool_route = "mixed_creation_research"
    deep_reasoning_effort = "high"
    summary = "Review the proposed direction for reader retention risks."
    generation_instructions = (
        "Risk: identify confusion, delayed payoff, weak motivation, genre promise drift, and pacing stalls.",
        "Explain why each risk may hurt reader retention.",
        "Give a concrete repair move for every important risk.",
    )
    evidence_policy = (
        "Use draft, outline, memory, and market signals when present.",
        "Treat missing details as uncertainty.",
        "Do not invent reader data.",
    )
    evidence_refs = ("reader", "outline", "market_signal")
    actions = ("review_reader_retention_risks",)
