from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class OutlineAgent(BaseSpecialistAgent):
    agent_name = "outline"
    answer_mode = "outline"
    llm_enabled = True
    tool_route = "mixed_creation_research"
    generation_instructions = (
        "围绕主线、卷/阶段目标、关键反转和阶段性爽点生成大纲。",
        "保持目标、阻力、升级收益和读者期待逐阶段递进。",
        "结合已有设定和 skill fragments，避免覆盖用户已给约束。",
    )
    evidence_policy = (
        "Use outline memory and supplied materials before inventing structure.",
        "Treat generated plot as creative inference.",
        "Preserve explicit user constraints.",
    )
    actions = ("build_mainline_outline", "plan_volume_stage_goals")
