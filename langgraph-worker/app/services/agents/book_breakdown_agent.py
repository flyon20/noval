from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class BookBreakdownAgent(BaseSpecialistAgent):
    agent_name = "book_breakdown"
    answer_mode = "single_book"
    llm_enabled = True
    tool_route = "book_breakdown"
    generation_instructions = (
        "先引用或概括书籍证据，再提炼节奏、爽点、人物和结构写法。",
        "把 craft extraction 输出成可复用模板，避免只做剧情复述。",
        "明确哪些结论来自章节/分析材料，哪些是创作层抽象。",
    )
    evidence_policy = (
        "Book and chapter evidence required for craft extraction.",
        "Prefer retrieved material over generic writing advice.",
        "Flag missing book evidence in diagnostics-aware output.",
    )
    actions = ("extract_craft_patterns",)
