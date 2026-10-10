from __future__ import annotations

import inspect
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
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.lookup_rank,
            {
                "platform": str(payload.get("platform") or "fanqie"),
                "channel_code": payload.get("channelCode"),
                "board_code": payload.get("boardCode"),
                "category": payload.get("category"),
                "rank_no": payload.get("rankNo"),
                "limit": int(payload.get("limit") or 30),
                "freshness": payload.get("freshness"),
                "allow_historical": payload.get("allowHistorical"),
                "time_window_days": payload.get("timeWindowDays"),
                "snapshot_start_date": payload.get("snapshotStartDate"),
                "snapshot_end_date": payload.get("snapshotEndDate"),
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
                "limit": int(payload.get("limit") or 30),
                "chapter_limit_per_book": int(payload.get("chapterLimitPerBook") or 1),
                "freshness": payload.get("freshness"),
                "allow_historical": payload.get("allowHistorical"),
                "time_window_days": payload.get("timeWindowDays"),
                "snapshot_start_date": payload.get("snapshotStartDate"),
                "snapshot_end_date": payload.get("snapshotEndDate"),
                "require_snapshot_time": payload.get("requireSnapshotTime"),
                "user_id": _required_user_id(payload),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "get_rank_research_pack", None)),
    )
    registry.register(
        "book.research_pack",
        "book",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.get_book_research_pack,
            {
                "platform": str(payload.get("platform") or "fanqie"),
                "book_id": payload.get("bookId"),
                "book_name": payload.get("bookName"),
                "chapter_limit": int(payload.get("chapterLimit") or 5),
                "analysis_limit": int(payload.get("analysisLimit") or 5),
                "user_id": _required_user_id(payload),
            },
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "get_book_research_pack", None)),
    )
    registry.register(
        "knowledge.vector_search",
        "knowledge",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.search_evidence,
            {
                "query": str(payload.get("query") or ""),
                "book_id": payload.get("bookId"),
                "platform": payload.get("platform"),
                "analysis_type": payload.get("analysisType"),
                "source_type": payload.get("sourceType"),
                "limit": int(payload.get("limit") or 5),
                "user_id": _required_user_id(payload),
            },
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
        "project.retrieve",
        "project",
        {"type": "object"},
        lambda payload: knowledge_client.retrieve_project_knowledge(
            user_id=int(payload.get("userId") or payload.get("user_id") or 0),
            project_id=int(payload.get("projectId") or payload.get("project_id") or 0),
            work_id=int(payload.get("workId") or payload.get("work_id") or 0),
            query=str(payload.get("query") or ""),
            intent=str(payload.get("intent") or "project_knowledge_qa"),
            entities=_string_list(payload.get("entities")),
            chapter_from=_optional_int(payload.get("chapterFrom") or payload.get("chapter_from")),
            chapter_to=_optional_int(payload.get("chapterTo") or payload.get("chapter_to")),
            channels=_string_list(payload.get("channels"), maximum=4),
            filters=_dict_value(payload.get("filters")),
            weights=_float_map(payload.get("weights")),
            limit=_int_value(payload.get("limit"), default=10, minimum=1, maximum=20),
            deep=_bool_value(payload.get("deep")),
            graph_budget_millis=_int_value(
                payload.get("graphBudgetMillis"), default=300, minimum=1, maximum=300,
            ),
            timeout_millis=_optional_int(payload.get("timeoutMillis")),
            rerank_policy=str(payload.get("rerankPolicy") or "intent_aware"),
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "retrieve_project_knowledge", None)),
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
        "project.foreshadowing.aggregate",
        "project",
        {"type": "object"},
        lambda payload: _call_with_supported_kwargs(
            knowledge_client.aggregate_project_foreshadowings,
            _project_scope_kwargs(payload),
        ),
        check_fn=lambda: callable(getattr(knowledge_client, "aggregate_project_foreshadowings", None)),
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
    requested_ids = tuple(dict.fromkeys(
        str(skill_id).strip()
        for skill_id in list(payload.get("eligibleSkillIds") or [])
        if str(skill_id).strip()
    ))
    activated_ids = {
        str(skill_id).strip()
        for skill_id in list(payload.get("activatedSkillIds") or [])
        if str(skill_id).strip()
    }
    candidate_by_id = {
        skill.skillId: skill
        for skill in skills.load_all()
    }
    candidates = tuple(
        candidate_by_id[skill_id]
        for skill_id in requested_ids
        if skill_id in candidate_by_id
    )
    skill_items = [
        {
            "skillId": skill.skillId,
            "version": skill.version,
            "title": skill.title or skill.skillId,
            "description": skill.description or skill.skillId,
            "intents": [intent.value for intent in skill.intents],
            "triggers": list(skill.triggers),
            "requestedCapabilities": list(skill.requestedCapabilities),
            "state": "ACTIVATED" if skill.skillId in activated_ids else "ELIGIBLE",
        }
        for skill in candidates
    ]
    return {
        "eligibleSkillIds": [skill["skillId"] for skill in skill_items],
        "activatedSkillIds": [skill["skillId"] for skill in skill_items if skill["state"] == "ACTIVATED"],
        "skills": skill_items,
}


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


def _required_user_id(payload: dict[str, Any]) -> int:
    value = _optional_int(payload.get("userId") or payload.get("user_id"))
    if value is None or value <= 0:
        raise ValueError("user scope required")
    return value


def _bool_value(value: Any) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return bool(value)


def _string_list(value: Any, *, maximum: int = 8) -> list[str]:
    if not isinstance(value, list):
        return []
    values: list[str] = []
    seen: set[str] = set()
    for item in value:
        text = str(item).strip()
        key = text.casefold()
        if text and key not in seen:
            seen.add(key)
            values.append(text[:120])
        if len(values) >= maximum:
            break
    return values


def _dict_value(value: Any) -> dict[str, Any]:
    return dict(value) if isinstance(value, dict) else {}


def _float_map(value: Any) -> dict[str, float]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, float] = {}
    for key, item in value.items():
        try:
            result[str(key)] = float(item)
        except (TypeError, ValueError):
            continue
    return result


def _project_scope_kwargs(payload: dict[str, Any]) -> dict[str, int]:
    return {
        "user_id": int(payload.get("userId") or payload.get("user_id") or 0),
        "project_id": int(payload.get("projectId") or payload.get("project_id") or 0),
        "work_id": int(payload.get("workId") or payload.get("work_id") or 0),
    }
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
