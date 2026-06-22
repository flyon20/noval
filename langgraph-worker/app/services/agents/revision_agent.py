from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class RevisionAgent(BaseSpecialistAgent):
    agent_name = "revision_advice"
    answer_mode = "revision"
    generation_instructions = (
        "先诊断问题属于钩子、节奏、爽点、信息量还是人物动机。",
        "给出可执行修改方案，并说明每处修改要提升的读者反馈。",
        "如果材料不足，明确需要补充正文、简介或榜单对照。",
    )
    evidence_policy = (
        "Prefer chapter and analysis evidence for revision.",
        "Do not rewrite facts not present in source material.",
        "Separate diagnosis from proposed rewrite.",
    )
    actions = ("diagnose_draft", "repair_pacing", "strengthen_hook")
