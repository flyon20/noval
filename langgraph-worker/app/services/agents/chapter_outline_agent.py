from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class ChapterOutlineAgent(BaseSpecialistAgent):
    agent_name = "chapter_outline"
    answer_mode = "chapter_outline"
    llm_enabled = True
    tool_route = "mixed_creation_research"
    deep_reasoning_effort = "high"
    generation_instructions = (
        "按单章目标、冲突推进、爽点反馈和章末钩子组织细纲。",
        "每章要明确读者期待和下一章牵引，不写空泛说明。",
        "有作品或章节证据时先对齐已有节奏，再给改造方案。",
    )
    evidence_policy = (
        "Prefer chapter-level evidence when a book context exists.",
        "Use outline memory to keep continuity.",
        "Do not claim unseen chapter facts.",
    )
    actions = ("plan_chapter_goal", "shape_conflict_progression", "add_cliffhanger")
