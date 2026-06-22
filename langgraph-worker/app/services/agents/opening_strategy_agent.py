from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class OpeningStrategyAgent(BaseSpecialistAgent):
    agent_name = "opening_strategy"
    answer_mode = "opening_strategy"
    generation_instructions = (
        "围绕开篇钩子、前三章节奏、主卖点和读者承诺组织回答。",
        "给出可执行的开书定位、切入场景、金手指/冲突呈现顺序。",
        "保留市场证据边界，创作建议需说明依据或假设。",
    )
    evidence_policy = (
        "Use market/book evidence when present.",
        "Separate opening strategy recommendations from facts.",
        "Do not invent rank or source details.",
    )
    actions = ("shape_opening_hooks", "plan_first_three_chapters", "extract_selling_points")
