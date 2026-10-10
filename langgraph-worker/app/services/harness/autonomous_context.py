"""Bounded, caller-owned continuation snapshots; never a cache of current project state."""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import hashlib
import json
import time
from typing import Callable

from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse, KnowledgeSource
from app.services.conversation_context import project_conversation_context


def _digest(value: object) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ContinuationSnapshot:
    scope: str
    expected_history: str
    created_at: float
    sources: tuple[KnowledgeSource, ...]
    skill_pins: tuple[dict, ...]


class AutonomousConversationContext:
    """One bounded conversation branch, supplied explicitly by its owning caller."""

    def __init__(self, *, ttl_seconds: float = 1800, clock: Callable[[], float] = time.monotonic) -> None:
        if ttl_seconds <= 0:
            raise ValueError("continuation TTL must be positive")
        self.ttl_seconds = ttl_seconds
        self.clock = clock
        self._snapshot: ContinuationSnapshot | None = None

    def clear(self) -> None:
        self._snapshot = None

    @staticmethod
    def _scope(request: KnowledgeChatRequest) -> str | None:
        if not request.userId or request.userId <= 0 or not (request.conversationId or "").strip():
            return None
        return _digest({
            "user": request.userId, "conversation": request.conversationId,
            "project": request.projectId, "work": request.workId,
            "book": request.bookId, "bookName": request.bookName,
            "preferredSkill": request.preferredSkillId,
            "references": [item.model_dump(mode="json") for item in request.referenceWorks],
        })

    @staticmethod
    def _history(request: KnowledgeChatRequest, answer: str | None = None) -> str:
        context = project_conversation_context(request)
        history = list(context.history)
        if answer is not None:
            history.extend(({"role": "user", "content": request.question.strip()},
                            {"role": "assistant", "content": answer.strip()}))
        return _digest({"summary": context.summary, "history": history})

    def restore(self, request: KnowledgeChatRequest) -> ContinuationSnapshot | None:
        snapshot = self._snapshot
        if snapshot is None:
            return None
        if (self._scope(request) != snapshot.scope or self._history(request) != snapshot.expected_history
                or self.clock() - snapshot.created_at >= self.ttl_seconds):
            self.clear()
            return None
        return deepcopy(snapshot)

    def remember(
        self, request: KnowledgeChatRequest, response: KnowledgeChatResponse, *,
        skill_pins: list[dict], previous: ContinuationSnapshot | None = None,
    ) -> None:
        scope = self._scope(request)
        if scope is None or response.status != "answered":
            self.clear()
            return
        sources = response.sources
        # Preserve citation order as a unit; partial caching could silently change old numbers.
        if (not request.projectId or not request.workId or len(sources) > 20
                or any(source.projectId != request.projectId or source.workId != request.workId
                       or not source.generationId or not source.chapterVersion or not source.contentHash
                       or not source.preview for source in sources)
                or sum(len(source.model_dump_json()) for source in sources) > 16_000):
            sources = []
        self._snapshot = ContinuationSnapshot(
            scope=scope, expected_history=self._history(request, response.answer),
            created_at=previous.created_at if previous is not None else self.clock(),
            sources=tuple(source.model_copy(deep=True) for source in sources),
            skill_pins=tuple(deepcopy(skill_pins[:8])),
        )
