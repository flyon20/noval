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
    needs_chapter_evidence = _needs_chapter_level_evidence(request)
    ranked = sorted(
        deduped,
        key=lambda source: _source_rank_score(source, question_terms, intent, needs_chapter_evidence),
        reverse=True,
    )
    if intent == "trend_research":
        return _select_trend_sources(ranked, max(1, limit))
    return ranked[: max(1, limit)]


def _dedupe_sources(sources: list[KnowledgeSource]) -> list[KnowledgeSource]:
    deduped: list[KnowledgeSource] = []
    seen: set[tuple[Any, ...]] = set()
    for source in sources:
        key = (
            source.chunkId,
            source.bookId,
            _normalize(source.sourceType),
            source.sourceRefId,
            source.chapterNo,
            _normalize(source.title),
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(source)
    return deduped


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
    return max(3, min(limit, 10))


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
) -> float:
    score = float(source.score or 0)
    text = f"{source.bookName or ''} {source.title or ''} {source.preview or ''}"
    normalized_text = _normalize(text)
    overlap = sum(1 for term in question_terms if term and term in normalized_text)
    source_type = _source_type(source)
    if intent == "trend_research":
        source_weight = {"RANK": 0.35, "ANALYSIS": 0.18, "INTRO": 0.08, "CHAPTER": 0.04, "CHAPTER_PACK": 0.04}.get(source_type, 0.0)
        rank_bonus = max(0.0, (30 - float(source.rankNo or 30)) * 0.02) if source_type == "RANK" else 0.0
        return score + overlap * 0.04 + source_weight + rank_bonus
    if needs_chapter_level_evidence:
        source_weight = {"CHAPTER": 0.45, "CHAPTER_PACK": 0.45, "ANALYSIS": 0.35, "INTRO": 0.04, "RANK": -0.15}.get(source_type, 0.0)
    else:
        source_weight = {"CHAPTER": 0.18, "CHAPTER_PACK": 0.18, "INTRO": 0.1, "RANK": 0.1, "ANALYSIS": 0.08}.get(source_type, 0.0)
    return score + overlap * 0.04 + source_weight


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
