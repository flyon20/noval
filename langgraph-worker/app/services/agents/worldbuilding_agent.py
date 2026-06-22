from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class WorldbuildingAgent(BaseSpecialistAgent):
    agent_name = "worldbuilding"
    answer_mode = "creative"
    generation_instructions = (
        "围绕世界规则、势力结构、金手指成本和升级反馈设计世界观。",
        "规则必须能制造持续冲突和阶段目标。",
        "优先解释设定如何服务开篇钩子与中长期主线。",
    )
    evidence_policy = (
        "Use source evidence only for existing-book analysis.",
        "Label new setting proposals as creative inference.",
        "Do not invent platform market facts.",
    )
    actions = ("design_rules", "map_factions", "bind_power_system_to_plot")
