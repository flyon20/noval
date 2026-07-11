from __future__ import annotations

import inspect
from typing import Any

from app.services.intents import Intent, IntentDecision, ToolNeeds
from app.services.knowledge_client import KnowledgeBackendClient
from app.services.skills import SkillRegistry
from app.services.tools.registry import DomainToolRegistry


TASK_TYPE_INTENT_MAP = {
    "market_scan": Intent.market_scan,
    "book_breakdown": Intent.book_breakdown,
    "topic_strategy": Intent.opening_strategy,
    "outline_building": Intent.outline_building,
    "chapter_outline": Intent.chapter_outline,
    "character_design": Intent.character_design,
    "worldbuilding": Intent.worldbuilding,
    "revision_advice": Intent.revision_advice,
    "followup_context": Intent.followup_context,
}


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
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.lookup_rank,
            {
                "platform": str(payload.get("platform") or "fanqie"),
                "channel_code": payload.get("channelCode"),
                "board_code": payload.get("boardCode"),
                "category": payload.get("category"),
                "rank_no": payload.get("rankNo"),
                "limit": int(payload.get("limit") or 10),
                "freshness": payload.get("freshness"),
                "allow_historical": payload.get("allowHistorical"),
                "time_window_days": payload.get("timeWindowDays"),
                "require_snapshot_time": payload.get("requireSnapshotTime"),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "lookup_rank", None)),
    )
    registry.register(
        "rank.research_pack",
        "rank",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.get_rank_research_pack,
            {
                "platform": str(payload.get("platform") or "fanqie"),
                "channel_code": payload.get("channelCode"),
                "board_code": payload.get("boardCode"),
                "category": payload.get("category"),
                "rank_no": payload.get("rankNo"),
                "limit": int(payload.get("limit") or 10),
                "chapter_limit_per_book": int(payload.get("chapterLimitPerBook") or 1),
                "freshness": payload.get("freshness"),
                "allow_historical": payload.get("allowHistorical"),
                "time_window_days": payload.get("timeWindowDays"),
                "require_snapshot_time": payload.get("requireSnapshotTime"),
            },
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
        lambda payload: _lookup_skills(payload, skills),
    )
    registry.register(
        "project.resolve",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.resolve_project_work,
            {
                "user_id": int(payload.get("userId") or payload.get("user_id") or 0),
                "project_id": _optional_int(payload.get("projectId") or payload.get("project_id")),
                "work_id": _optional_int(payload.get("workId") or payload.get("work_id")),
                "query": payload.get("query"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=20),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "resolve_project_work", None)),
    )
    registry.register(
        "project.chapter_search",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.search_project_chapters,
            {
                "user_id": int(payload.get("userId") or payload.get("user_id") or 0),
                "project_id": int(payload.get("projectId") or payload.get("project_id") or 0),
                "work_id": int(payload.get("workId") or payload.get("work_id") or 0),
                "query": payload.get("query"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=50),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "search_project_chapters", None)),
    )
    registry.register(
        "project.chunk_search",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.search_project_chunks,
            {
                "user_id": int(payload.get("userId") or payload.get("user_id") or 0),
                "project_id": int(payload.get("projectId") or payload.get("project_id") or 0),
                "work_id": int(payload.get("workId") or payload.get("work_id") or 0),
                "query": payload.get("query"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=50),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "search_project_chunks", None)),
    )
    registry.register(
        "project.foreshadowing.list",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.list_project_foreshadowings,
            {
                **_project_scope_kwargs(payload),
                "status": payload.get("status"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=100),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "list_project_foreshadowings", None)),
    )
    registry.register(
        "project.timeline_lookup",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.lookup_project_timeline,
            {
                **_project_scope_kwargs(payload),
                "query": payload.get("query"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=100),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "lookup_project_timeline", None)),
    )
    registry.register(
        "project.character_state_lookup",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.lookup_project_character_states,
            {
                **_project_scope_kwargs(payload),
                "query": payload.get("query"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=100),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "lookup_project_character_states", None)),
    )
    registry.register(
        "project.world_rule_lookup",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.lookup_project_world_rules,
            {
                **_project_scope_kwargs(payload),
                "query": payload.get("query"),
                "limit": _int_value(payload.get("limit"), default=10, minimum=1, maximum=100),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "lookup_project_world_rules", None)),
    )
    registry.register("editor.risk_check", "simulation", {"type": "object"}, _editor_risk_check)
    registry.register("reader.simulate_feedback", "simulation", {"type": "object"}, _reader_feedback)
    return registry


def _call_with_supported_kwargs(method: Any, kwargs: dict[str, Any]) -> Any:
    try:
        signature = inspect.signature(method)
    except (TypeError, ValueError):
        return method(**kwargs)
    if any(parameter.kind == inspect.Parameter.VAR_KEYWORD for parameter in signature.parameters.values()):
        return method(**kwargs)
    supported = {
        name
        for name, parameter in signature.parameters.items()
        if parameter.kind in {inspect.Parameter.POSITIONAL_OR_KEYWORD, inspect.Parameter.KEYWORD_ONLY}
    }
    return method(**{key: value for key, value in kwargs.items() if key in supported})


def _lookup_skills(payload: dict[str, Any], skills: SkillRegistry) -> dict[str, Any]:
    decision = _skill_lookup_decision(payload)
    max_chars = _int_value(payload.get("maxSkillChars"), default=1600, minimum=200, maximum=4000)
    selection = skills.select_for_intent(decision, max_chars=max_chars)
    skill_items = [
        {
            "skillId": skill.skillId,
            "version": skill.version,
            "intents": [intent.value for intent in skill.intents],
            "triggers": list(skill.triggers),
            "promptPreview": _short_text(skill.promptFragment, 300),
        }
        for skill in selection.skills
    ]
    return {
        "selectedSkills": [skill["skillId"] for skill in skill_items],
        "skills": skill_items,
        "prompt": selection.prompt,
        "promptChars": len(selection.prompt),
    }


def _skill_lookup_decision(payload: dict[str, Any]) -> IntentDecision:
    task_type = str(payload.get("taskType") or payload.get("intent") or "").strip()
    intent = TASK_TYPE_INTENT_MAP.get(task_type)
    if intent is None:
        try:
            intent = Intent(task_type)
        except ValueError:
            intent = Intent.mixed_creation_research
    needs_rank_data = bool(payload.get("needsRankData")) or intent == Intent.market_scan
    return IntentDecision(
        primaryIntent=intent,
        toolNeeds=ToolNeeds(
            needsRankData=needs_rank_data,
            needsSkillPack=True,
        ),
    )


def _int_value(value: Any, *, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _project_scope_kwargs(payload: dict[str, Any]) -> dict[str, int]:
    return {
        "user_id": int(payload.get("userId") or payload.get("user_id") or 0),
        "project_id": int(payload.get("projectId") or payload.get("project_id") or 0),
        "work_id": int(payload.get("workId") or payload.get("work_id") or 0),
    }


def _short_text(value: str, max_chars: int) -> str:
    text = (value or "").strip()
    if len(text) <= max_chars:
        return text
    return text[:max_chars].rstrip() + "..."


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
