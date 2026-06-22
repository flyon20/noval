from __future__ import annotations

from typing import Any

from app.services.knowledge_client import KnowledgeBackendClient
from app.services.skills import SkillRegistry
from app.services.tools.registry import DomainToolRegistry


def build_domain_tool_registry(
    knowledge_client: KnowledgeBackendClient,
    *,
    skill_registry: SkillRegistry | None = None,
) -> DomainToolRegistry:
    registry = DomainToolRegistry()
    skills = skill_registry or SkillRegistry()

    registry.register(
        "rank.lookup",
        "rank",
        {"type": "object"},
        lambda payload: knowledge_client.lookup_rank(
            platform=str(payload.get("platform") or "fanqie"),
            channel_code=payload.get("channelCode"),
            board_code=payload.get("boardCode"),
            category=payload.get("category"),
            rank_no=payload.get("rankNo"),
            limit=int(payload.get("limit") or 10),
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "lookup_rank", None)),
    )
    registry.register(
        "rank.research_pack",
        "rank",
        {"type": "object"},
        lambda payload: knowledge_client.get_rank_research_pack(
            platform=str(payload.get("platform") or "fanqie"),
            channel_code=payload.get("channelCode"),
            board_code=payload.get("boardCode"),
            category=payload.get("category"),
            rank_no=payload.get("rankNo"),
            limit=int(payload.get("limit") or 10),
            chapter_limit_per_book=int(payload.get("chapterLimitPerBook") or 1),
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "get_rank_research_pack", None)),
    )
    registry.register(
        "book.research_pack",
        "book",
        {"type": "object"},
        lambda payload: knowledge_client.get_book_research_pack(
            platform=str(payload.get("platform") or "fanqie"),
            book_id=payload.get("bookId"),
            book_name=payload.get("bookName"),
            chapter_limit=int(payload.get("chapterLimit") or 3),
            analysis_limit=int(payload.get("analysisLimit") or 3),
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "get_book_research_pack", None)),
    )
    registry.register(
        "knowledge.vector_search",
        "knowledge",
        {"type": "object"},
        lambda payload: knowledge_client.search_evidence(
            query=str(payload.get("query") or ""),
            book_id=payload.get("bookId"),
            platform=payload.get("platform"),
            analysis_type=payload.get("analysisType"),
            source_type=payload.get("sourceType"),
            limit=int(payload.get("limit") or 5),
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "search_evidence", None)),
    )
    registry.register(
        "skill.lookup",
        "skill",
        {"type": "object"},
        lambda _payload: {"skills": [skill.skillId for skill in skills.load_all()]},
    )
    registry.register("editor.risk_check", "simulation", {"type": "object"}, _editor_risk_check)
    registry.register("reader.simulate_feedback", "simulation", {"type": "object"}, _reader_feedback)
    return registry


def _editor_risk_check(payload: dict[str, Any]) -> dict[str, Any]:
    question = str(payload.get("question") or "")
    return {
        "perspective": "editor",
        "signals": [
            "Check whether the hook is visible in chapter one.",
            "Check whether the topic has market-facing differentiation.",
        ],
        "questionPreview": question[:160],
    }


def _reader_feedback(payload: dict[str, Any]) -> dict[str, Any]:
    question = str(payload.get("question") or "")
    return {
        "perspective": "reader",
        "signals": [
            "Watch for unclear goals before the first reward.",
            "Avoid copying the ranked reference's surface premise.",
        ],
        "questionPreview": question[:160],
    }
