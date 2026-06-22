from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class InspirationAgent(BaseSpecialistAgent):
    agent_name = "inspiration"
    answer_mode = "creative"
    generation_instructions = (
        "围绕题材混搭、反套路切口、差异化卖点和可开篇场景发散。",
        "每个点子都要能落到主角目标、阻力和爽点反馈。",
        "把市场证据作为灵感边界，不把创意假设说成事实。",
    )
    evidence_policy = (
        "Use market evidence as constraints when present.",
        "Label speculative premises as creative inference.",
        "Avoid unsupported claims about platform trends.",
    )
    actions = ("expand_premise_variants", "differentiate_hooks")
