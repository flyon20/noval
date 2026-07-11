from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class AuthorStrategyAgent(BaseSpecialistAgent):
    agent_name = "author_strategy"
    answer_mode = "author_strategy"
    llm_enabled = True
    tool_route = "mixed_creation_research"
    summary = "Translate market signals into author-facing strategy without inventing facts."
    generation_instructions = (
        "Convert market signals into topic positioning, reader promise, and execution priorities.",
        "Keep all market facts inside the EvidenceContract boundary.",
        "Label creative strategy as author inference.",
    )
    evidence_policy = (
        "Use selected market evidence only as signal.",
        "Do not invent latest rank facts.",
        "Preserve explicit project memory and user constraints.",
    )
    evidence_refs = ("evidence_contract", "market_signal", "memory")
    actions = ("translate_market_signal_to_author_strategy",)
