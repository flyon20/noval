from __future__ import annotations

import re
from typing import Any

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource


TREND_SOURCE_QUOTAS = {
    "RANK": 3,
    "CHAPTER": 2,
    "CHAPTER_PACK": 2,
    "INTRO": 2,
    "ANALYSIS": 2,
}


def fuse_and_rerank_sources(
    *,
    request: KnowledgeChatRequest,
    state: dict[str, Any],
    sources: list[KnowledgeSource],
    limit: int,
) -> list[KnowledgeSource]:
    deduped = _dedupe_sources(sources)
    question_terms = _extract_query_terms(request.question or "")
    intent = str(state.get("intent") or "")
    project_task_type = _project_task_type(state)
    needs_chapter_evidence = _needs_chapter_level_evidence(request)
    ranked = sorted(
        deduped,
        key=lambda source: (
            -_source_rank_score(
                source,
                question_terms,
                intent,
                needs_chapter_evidence,
                project_task_type=project_task_type,
            ),
            _stable_source_key(source),
        ),
    )
    reason_tags = ["static_weight_rerank"]
    if project_task_type:
        reason_tags.append("intent_aware_project_rerank")
    if intent == "trend_research":
        selected = _select_trend_sources(ranked, max(1, limit))
        _attach_retrieval_diagnostics(
            state,
            intent=intent,
            input_sources=sources,
            deduped_sources=deduped,
            selected_sources=selected,
            reason_tags=[*reason_tags, "trend_quota_selection"],
            needs_chapter_evidence=needs_chapter_evidence,
        )
        return selected
    if project_task_type:
        selected = _select_project_sources(ranked, max(1, limit))
        reason_tags.append("project_backend_diversity")
    else:
        selected = ranked[: max(1, limit)]
    _attach_retrieval_diagnostics(
        state,
        intent=intent,
        input_sources=sources,
        deduped_sources=deduped,
        selected_sources=selected,
        reason_tags=reason_tags,
        needs_chapter_evidence=needs_chapter_evidence,
    )
    return selected


def _attach_retrieval_diagnostics(
    state: dict[str, Any],
    *,
    intent: str,
    input_sources: list[KnowledgeSource],
    deduped_sources: list[KnowledgeSource],
    selected_sources: list[KnowledgeSource],
    reason_tags: list[str],
    needs_chapter_evidence: bool,
) -> None:
    tags = list(reason_tags)
    if len(deduped_sources) < len(input_sources):
        tags.append("deduped_sources")
    if needs_chapter_evidence:
        tags.append("chapter_level_boost")
    state["retrieval_diagnostics"] = {
        "inputCount": len(input_sources),
        "dedupedCount": len(deduped_sources),
        "selectedCount": len(selected_sources),
        "intent": intent,
        "inputSourceTypeCounts": _source_type_counts(input_sources),
        "dedupedSourceTypeCounts": _source_type_counts(deduped_sources),
        "selectedSourceTypeCounts": _source_type_counts(selected_sources),
        "inputBackendCounts": _backend_counts(input_sources),
        "dedupedBackendCounts": _backend_counts(deduped_sources),
        "selectedBackendCounts": _backend_counts(selected_sources),
        "vectorUsed": any(_source_backend(source) in {"qdrant", "vector"} for source in selected_sources),
        "reasonTags": sorted(set(tags)),
    }


def _source_type_counts(sources: list[KnowledgeSource]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for source in sources:
        source_type = _source_type(source) or "UNKNOWN"
        counts[source_type] = counts.get(source_type, 0) + 1
    return dict(sorted(counts.items()))


def _backend_counts(sources: list[KnowledgeSource]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for source in sources:
        backend = _source_backend(source) or "unknown"
        counts[backend] = counts.get(backend, 0) + 1
    return dict(sorted(counts.items()))


def _select_project_sources(ranked: list[KnowledgeSource], limit: int) -> list[KnowledgeSource]:
    selected: list[KnowledgeSource] = []
    represented_backends: set[str] = set()
    for source in ranked:
        backend = _source_backend(source)
        if backend and backend not in represented_backends:
            represented_backends.add(backend)
            selected.append(source)
            if len(selected) >= limit:
                return selected
    for source in ranked:
        if source not in selected:
            selected.append(source)
            if len(selected) >= limit:
                break
    return selected


def _dedupe_sources(sources: list[KnowledgeSource]) -> list[KnowledgeSource]:
    ordered_keys: list[tuple[Any, ...]] = []
    best_by_key: dict[tuple[Any, ...], KnowledgeSource] = {}
    for source in sources:
        key = _source_dedupe_key(source)
        existing = best_by_key.get(key)
        if existing is None:
            ordered_keys.append(key)
            best_by_key[key] = source
        elif _duplicate_preference(source) > _duplicate_preference(existing):
            best_by_key[key] = source
    return [best_by_key[key] for key in ordered_keys]


def _source_dedupe_key(source: KnowledgeSource) -> tuple[Any, ...]:
    if _is_project_source(source):
        scope = (source.projectId, source.workId, source.generationId)
        content_hash = _normalize(source.contentHash)
        if content_hash and _source_type(source) != "PROJECT_GRAPH" and _source_backend(source) != "graph":
            return ("project_content", *scope, content_hash)
        return (
            "project_source",
            *scope,
            _source_type(source),
            source.documentId,
            source.chunkId,
            source.sourceRefId,
            source.chapterId,
            source.sceneId,
            _normalize(source.contentHash),
            _normalize(source.title),
        )
    return (
        source.chunkId,
        source.bookId,
        _normalize(source.sourceType),
        source.sourceRefId,
        source.chapterNo,
        _normalize(source.title),
    )


def _duplicate_preference(source: KnowledgeSource) -> tuple[float, ...]:
    score = float(source.score or 0.0)
    if _is_project_source(source):
        return float(_project_backend_preference(source)), score
    return score, float(_project_backend_preference(source))


def _project_backend_preference(source: KnowledgeSource) -> int:
    return {
        "structured": 5,
        "graph": 4,
        "qdrant": 3,
        "fulltext": 2,
        "lexical": 1,
    }.get(_source_backend(source), 0)


def _stable_source_key(source: KnowledgeSource) -> tuple[Any, ...]:
    return (
        _stable_int(source.projectId),
        _stable_int(source.workId),
        _stable_int(source.generationId),
        _stable_int(source.documentId),
        _stable_int(source.chunkId),
        _stable_int(source.sourceRefId),
        _stable_int(source.chapterId),
        _stable_int(source.sceneId),
        _stable_int(source.bookId),
        _stable_int(source.chapterNo),
        _normalize(source.sourceType),
        _normalize(source.contentHash),
        _normalize(source.title),
    )


def _stable_int(value: Any) -> int:
    try:
        return int(value) if value is not None else 2**63 - 1
    except (TypeError, ValueError):
        return 2**63 - 1


def _select_trend_sources(ranked: list[KnowledgeSource], limit: int) -> list[KnowledgeSource]:
    ranked = _dedupe_rank_sources_by_book(ranked)
    selected: list[KnowledgeSource] = []
    front_rank_cutoff = _front_rank_cutoff(limit)

    def add(source: KnowledgeSource | None) -> None:
        if source is not None and len(selected) < limit and source not in selected:
            selected.append(source)

    add(next((source for source in ranked if _source_type(source) == "RANK" and source.rankNo == 1), None))
    _add_sources_by_type(selected, ranked, limit, {"RANK"}, max_count=min(3, limit))
    _add_sources_by_type(selected, ranked, limit, {"CHAPTER", "CHAPTER_PACK"}, max_count=2)
    _add_sources_by_type(selected, ranked, limit, {"INTRO", "ANALYSIS"}, max_count=1 if limit <= 5 else 2)
    add(_first_supplemental_trend_source(ranked, selected))
    for source in ranked:
        if len(selected) >= limit:
            break
        if _is_low_priority_rank_source(source, front_rank_cutoff):
            continue
        add(source)
    return selected[:limit]


def _front_rank_cutoff(limit: int) -> int:
    return max(3, limit)


def _is_low_priority_rank_source(source: KnowledgeSource, front_rank_cutoff: int) -> bool:
    return (
        _source_type(source) == "RANK"
        and source.rankNo is not None
        and source.rankNo > front_rank_cutoff
    )


def _dedupe_rank_sources_by_book(ranked: list[KnowledgeSource]) -> list[KnowledgeSource]:
    best_by_book: dict[int | None, KnowledgeSource] = {}
    ordered: list[KnowledgeSource] = []
    for source in ranked:
        if _source_type(source) != "RANK" or source.bookId is None:
            ordered.append(source)
            continue
        current = best_by_book.get(source.bookId)
        if current is None or _rank_source_preference(source) > _rank_source_preference(current):
            best_by_book[source.bookId] = source
    ordered.extend(best_by_book.values())
    return sorted(ordered, key=_rank_source_preference, reverse=True)


def _rank_source_preference(source: KnowledgeSource) -> tuple[str, int, float]:
    snapshot_time = str(getattr(source, "snapshotTime", "") or "")
    rank_no = -(source.rankNo or 9999)
    score = float(source.score or 0.0)
    return (snapshot_time, rank_no, score)


def _add_sources_by_type(
    selected: list[KnowledgeSource],
    ranked: list[KnowledgeSource],
    limit: int,
    source_types: set[str],
    *,
    max_count: int,
) -> None:
    count = sum(1 for source in selected if _source_type(source) in source_types)
    for source in ranked:
        if len(selected) >= limit or count >= max_count:
            return
        if source in selected or _source_type(source) not in source_types:
            continue
        selected.append(source)
        count += 1


def _first_supplemental_trend_source(
    ranked: list[KnowledgeSource],
    selected: list[KnowledgeSource],
) -> KnowledgeSource | None:
    selected_book_ids = {source.bookId for source in selected if source.bookId is not None}
    for source in ranked:
        source_type = _source_type(source)
        if source in selected or source_type not in {"INTRO", "ANALYSIS", "CHAPTER", "CHAPTER_PACK"}:
            continue
        if source.chunkId is not None or source.documentId is not None:
            return source
    for source in ranked:
        source_type = _source_type(source)
        if source in selected or source_type not in {"INTRO", "ANALYSIS", "CHAPTER", "CHAPTER_PACK"}:
            continue
        if source.bookId is None or source.bookId not in selected_book_ids:
            return source
    return None


def _source_rank_score(
    source: KnowledgeSource,
    question_terms: set[str],
    intent: str,
    needs_chapter_level_evidence: bool,
    *,
    project_task_type: str = "",
) -> float:
    score = float(source.score or 0)
    text = f"{source.bookName or ''} {source.title or ''} {source.preview or ''}"
    normalized_text = _normalize(text)
    overlap = sum(1 for term in question_terms if term and term in normalized_text)
    source_type = _source_type(source)
    if _is_project_source(source):
        return score + min(overlap, 3) * 0.04 + _project_source_weight(source, project_task_type)
    if intent == "trend_research":
        source_weight = {"RANK": 0.35, "ANALYSIS": 0.18, "INTRO": 0.08, "CHAPTER": 0.04, "CHAPTER_PACK": 0.04}.get(source_type, 0.0)
        rank_bonus = max(0.0, (30 - float(source.rankNo or 30)) * 0.02) if source_type == "RANK" else 0.0
        return score + overlap * 0.04 + source_weight + rank_bonus
    if needs_chapter_level_evidence:
        source_weight = {"CHAPTER": 0.45, "CHAPTER_PACK": 0.45, "ANALYSIS": 0.35, "INTRO": 0.04, "RANK": -0.15}.get(source_type, 0.0)
    else:
        source_weight = {"CHAPTER": 0.18, "CHAPTER_PACK": 0.18, "INTRO": 0.1, "RANK": 0.1, "ANALYSIS": 0.08}.get(source_type, 0.0)
    return score + overlap * 0.04 + source_weight


def _project_task_type(state: dict[str, Any]) -> str:
    task_types = {
        str(task.get("type") or "").strip()
        for task in list((state.get("task_graph") or {}).get("tasks") or [])
        if isinstance(task, dict)
    }
    for task_type in ("continuity_check", "foreshadowing_audit", "project_knowledge_qa"):
        if task_type in task_types:
            return task_type
    return ""


def _is_project_source(source: KnowledgeSource) -> bool:
    return _source_type(source).startswith("PROJECT_")


def _source_backend(source: KnowledgeSource) -> str:
    return str(source.retrievalBackend or "").strip().lower()


def _project_source_weight(source: KnowledgeSource, task_type: str) -> float:
    source_type = _source_type(source)
    if task_type == "continuity_check":
        source_weight = {
            "PROJECT_GRAPH": 0.45,
            "PROJECT_CHARACTER_STATE": 0.42,
            "PROJECT_TIMELINE_EVENT": 0.40,
            "PROJECT_WORLD_RULE": 0.40,
            "PROJECT_CHAPTER": 0.25,
            "PROJECT_SCENE": 0.25,
        }.get(source_type, 0.22)
    elif task_type == "foreshadowing_audit":
        source_weight = {
            "PROJECT_GRAPH": 0.45,
            "PROJECT_FORESHADOWING": 0.42,
            "PROJECT_CHAPTER": 0.28,
            "PROJECT_SCENE": 0.28,
            "PROJECT_TIMELINE_EVENT": 0.25,
        }.get(source_type, 0.22)
    else:
        source_weight = {
            "PROJECT_CHAPTER": 0.35,
            "PROJECT_SCENE": 0.32,
            "PROJECT_CHARACTER_STATE": 0.30,
            "PROJECT_WORLD_RULE": 0.30,
            "PROJECT_TIMELINE_EVENT": 0.30,
            "PROJECT_FORESHADOWING": 0.30,
            "PROJECT_GRAPH": 0.28,
        }.get(source_type, 0.24)
    backend_weight = {
        "structured": 0.07,
        "graph": 0.08,
        "qdrant": 0.04,
        "fulltext": 0.02,
        "lexical": 0.01,
    }.get(_source_backend(source), 0.0)
    return source_weight + backend_weight


def _extract_query_terms(question: str) -> set[str]:
    normalized = _normalize(question)
    terms: set[str] = set()
    for size in (6, 4, 3, 2):
        for index in range(0, max(0, len(normalized) - size + 1)):
            term = normalized[index:index + size]
            if term and not _is_low_value_term(term):
                terms.add(term)
    return terms


def _needs_chapter_level_evidence(request: KnowledgeChatRequest) -> bool:
    question = request.question or ""
    return any(keyword in question for keyword in (
        "前三章",
        "前3章",
        "第一章",
        "第1章",
        "金手指",
        "剧情",
        "手法",
        "钩子",
        "三幕式",
        "三翻四震",
        "伏笔",
        "爽点",
        "开篇",
        "章节",
    ))


def _source_type(source: KnowledgeSource) -> str:
    return (source.sourceType or "").upper()


def _normalize(value: str | None) -> str:
    if not value:
        return ""
    return re.sub(r"[\s《》「」【】（）()，。！？、：:；;,.!?\"']+", "", value).lower()


def _is_low_value_term(term: str) -> bool:
    return term in {"什么", "怎么", "分析", "一个", "这个", "那个", "它的", "的是", "方向"}
