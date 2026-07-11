from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class EditorAgent(BaseSpecialistAgent):
    agent_name = "editor"
    answer_mode = "editor_review"
    llm_enabled = True
    tool_route = "mixed_creation_research"
    deep_reasoning_effort = "high"
    summary = "Review category fit and commercial execution risk."
    generation_instructions = (
        "Assess category fit, hook clarity, update durability, comparable signal, and signing risk.",
        "Prioritize changes that improve market readability.",
        "Separate editorial judgment from factual market claims.",
    )
    evidence_policy = (
        "Ground comparable signals in supplied evidence.",
        "Do not promise publication or monetization outcomes.",
        "Keep rejected snapshot groups out of direct market claims.",
    )
    evidence_refs = ("editor", "market_signal", "evidence_contract")
    actions = ("review_editor_market_fit",)
