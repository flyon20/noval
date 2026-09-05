from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

from app.models.agent_task import RetrievalPlan, TaskNode, TaskType
from app.services.harness.contracts import (
    DataAccessRequest,
    DataFilterField,
    DatasetCapability,
)


_PROJECT_TASK_TYPES = frozenset({
    TaskType.project_knowledge_qa,
    TaskType.foreshadowing_audit,
    TaskType.continuity_check,
})
_DEEP_TASK_TYPES = frozenset({TaskType.foreshadowing_audit, TaskType.continuity_check})
_DIGITS = r"\d{1,5}"
_CHINESE_DIGITS = r"[〇零一二两三四五六七八九十百千万亿]+"
_ENGLISH_RANGE_PATTERN = re.compile(
    rf"\bchapter\s*({_DIGITS})\s*(?:to|through|until|[-~])\s*(?:chapter\s*)?({_DIGITS})",
    re.IGNORECASE,
)
_DIGIT_CHINESE_RANGE_PATTERN = re.compile(
    rf"(?:第\s*)?({_DIGITS})(?:\s*章)?\s*(?:到|至|[-~])\s*(?:第\s*)?({_DIGITS})\s*章"
)
_CHINESE_RANGE_PATTERN = re.compile(
    rf"第?\s*({_CHINESE_DIGITS})\s*章?\s*(?:到|至|[-~])\s*第?\s*({_CHINESE_DIGITS})\s*章"
)
_PREFIX_RANGE_PATTERN = re.compile(
    rf"(?:前|开头|开篇|最初|这)\s*({_DIGITS}|{_CHINESE_DIGITS})\s*章"
)
_SINGLE_CHAPTER_PATTERN = re.compile(
    rf"(?:\bchapter\s*({_DIGITS})|第\s*({_DIGITS}|{_CHINESE_DIGITS})\s*章|({_DIGITS}|{_CHINESE_DIGITS})\s*章)",
    re.IGNORECASE,
)
_DEEP_MARKERS = ("relationship", "causal", "multi-hop", "deep", "关系", "因果", "多跳", "深挖")
_DEFAULT_CHANNELS = ["structured", "fulltext", "vector", "graph"]


class ProjectRetrievalPlanner:
    def plan(
        self,
        task: TaskNode,
        *,
        question: str,
        entities: Mapping[str, Any] | None = None,
        limit: int | None = None,
        data_access_request: DataAccessRequest | None = None,
    ) -> RetrievalPlan | None:
        if task.type not in _PROJECT_TASK_TYPES:
            return None
        query = str(question or task.goal).strip()
        chapter_from, chapter_to = self._chapter_range(query)
        if data_access_request is not None and data_access_request.datasetCapability not in {
            DatasetCapability.PROJECT_KNOWLEDGE,
            DatasetCapability.PROJECT_CONTINUITY,
        }:
            data_access_request = None
        chapter_from, chapter_to = self._constrained_chapter_range(
            chapter_from,
            chapter_to,
            data_access_request,
        )
        normalized_entities = self._entities(
            entities,
            additional=(data_access_request.entities if data_access_request is not None else ()),
        )
        normalized_limit = self._limit(limit)
        if (
            chapter_from is not None
            and chapter_to is not None
            and chapter_to >= chapter_from
        ):
            normalized_limit = max(
                normalized_limit,
                self._limit(chapter_to - chapter_from + 1),
            )
        if data_access_request is not None:
            normalized_limit = min(normalized_limit, self._limit(data_access_request.limit))
        channels = self._channels(data_access_request)
        deep = task.type in _DEEP_TASK_TYPES or any(marker in query.casefold() for marker in _DEEP_MARKERS)
        filters = {
            key: value
            for key, value in {"chapterFrom": chapter_from, "chapterTo": chapter_to}.items()
            if value is not None
        }
        evidence_status = self._filter_value(data_access_request, DataFilterField.EVIDENCE_STATUS)
        if evidence_status is not None:
            filters["evidenceStatus"] = evidence_status
        return RetrievalPlan(
            query=query,
            intent=task.type.value,
            entities=normalized_entities,
            chapterFrom=chapter_from,
            chapterTo=chapter_to,
            channels=channels,
            filters=filters,
            weights={
                key: value
                for key, value in self._weights(task.type).items()
                if key in channels
            },
            limit=normalized_limit,
            deep=deep,
            graphBudgetMillis=300,
            rerankPolicy="intent_aware",
        )

    def _chapter_range(self, question: str) -> tuple[int | None, int | None]:
        for pattern in (_ENGLISH_RANGE_PATTERN, _DIGIT_CHINESE_RANGE_PATTERN):
            match = pattern.search(question)
            if match:
                return int(match.group(1)), int(match.group(2))
        match = _CHINESE_RANGE_PATTERN.search(question)
        if match:
            chapter_from = self._chapter_number(match.group(1))
            chapter_to = self._chapter_number(match.group(2))
            if chapter_from is not None and chapter_to is not None:
                return chapter_from, chapter_to
        match = _PREFIX_RANGE_PATTERN.search(question)
        if match:
            chapter_to = self._chapter_number(match.group(1))
            if chapter_to is not None:
                return 1, chapter_to
        match = _SINGLE_CHAPTER_PATTERN.search(question)
        if match:
            chapter = self._chapter_number(next(group for group in match.groups() if group))
            if chapter is not None:
                return chapter, chapter
        return None, None

    def _chapter_number(self, value: str) -> int | None:
        text = str(value or "").strip()
        if not text:
            return None
        if text.isdigit():
            parsed = int(text)
            return parsed if parsed > 0 else None
        return self._chinese_number(text)

    def _chinese_number(self, value: str) -> int | None:
        digits = {
            "〇": 0, "零": 0, "一": 1, "二": 2, "两": 2, "三": 3,
            "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9,
        }
        units = {"十": 10, "百": 100, "千": 1000, "万": 10_000, "亿": 100_000_000}
        if not value or any(char not in digits and char not in units for char in value):
            return None
        if all(char in digits for char in value):
            parsed = int("".join(str(digits[char]) for char in value))
            return parsed if parsed > 0 else None
        total = 0
        section = 0
        current = 0
        for char in value:
            if char in digits:
                current = digits[char]
                continue
            unit = units[char]
            if unit < 10_000:
                section += (current or 1) * unit
                current = 0
            else:
                section += current
                total += (section or 1) * unit
                section = 0
                current = 0
        parsed = total + section + current
        return parsed if parsed > 0 else None

    def _entities(
        self,
        entities: Mapping[str, Any] | None,
        *,
        additional: tuple[str, ...] = (),
    ) -> list[str]:
        if not isinstance(entities, Mapping):
            entities = {}
        values: list[Any] = []
        for key in ("bookName", "author", "currentTopic", "currentPremise", "constraints"):
            value = entities.get(key)
            if isinstance(value, list):
                values.extend(value)
            else:
                values.append(value)
        values.extend(additional)
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            text = str(value).strip() if value is not None else ""
            key = text.casefold()
            if text and key not in seen:
                seen.add(key)
                normalized.append(text[:120])
            if len(normalized) >= 8:
                break
        return normalized

    def _constrained_chapter_range(
        self,
        chapter_from: int | None,
        chapter_to: int | None,
        request: DataAccessRequest | None,
    ) -> tuple[int | None, int | None]:
        requested_from = self._positive_int(self._filter_value(request, DataFilterField.CHAPTER_FROM))
        requested_to = self._positive_int(self._filter_value(request, DataFilterField.CHAPTER_TO))
        effective_from = max(
            value
            for value in (chapter_from, requested_from)
            if value is not None
        ) if chapter_from is not None or requested_from is not None else None
        effective_to = min(
            value
            for value in (chapter_to, requested_to)
            if value is not None
        ) if chapter_to is not None or requested_to is not None else None
        if effective_from is not None and effective_to is not None and effective_from > effective_to:
            return chapter_from, chapter_to
        return effective_from, effective_to

    def _channels(self, request: DataAccessRequest | None) -> list[str]:
        requested = (
            [channel.value for channel in request.retrievalChannels]
            if request is not None
            else []
        )
        if not requested:
            return list(_DEFAULT_CHANNELS)
        return [channel for channel in _DEFAULT_CHANNELS if channel in requested]

    @staticmethod
    def _filter_value(
        request: DataAccessRequest | None,
        field: DataFilterField,
    ) -> Any:
        if request is None:
            return None
        return next(
            (item.value for item in request.filters if item.field is field),
            None,
        )

    @staticmethod
    def _positive_int(value: Any) -> int | None:
        if isinstance(value, bool):
            return None
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None

    def _limit(self, value: int | None) -> int:
        try:
            parsed = int(value) if value is not None else 10
        except (TypeError, ValueError):
            parsed = 10
        return max(1, min(20, parsed))

    def _weights(self, task_type: TaskType) -> dict[str, float]:
        if task_type in _DEEP_TASK_TYPES:
            return {"structured": 0.90, "fulltext": 0.75, "vector": 0.70, "graph": 1.00}
        return {"structured": 0.95, "fulltext": 0.80, "vector": 0.85, "graph": 0.90}
