from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from app.models.agent_runtime import MemoryCandidate
from app.models.knowledge import KnowledgeChatRequest
from app.services.runtime.memory_candidates import MemoryCandidateExtractor


class MemoryExtractor:
    def __init__(self, legacy_extractor: MemoryCandidateExtractor | None = None) -> None:
        self.legacy_extractor = legacy_extractor or MemoryCandidateExtractor()

    def extract(self, request: KnowledgeChatRequest) -> list[MemoryCandidate]:
        candidates = list(self._extract_structured_markers(request))
        candidates.extend(self.legacy_extractor.extract(request))
        return self._dedupe(candidates)

    async def persist_candidates(
        self,
        memory_client: Any,
        request: KnowledgeChatRequest,
        candidates: Iterable[MemoryCandidate],
    ) -> dict[str, Any]:
        if request.userId is None:
            return {"saved": 0, "failed": 0, "failures": [], "status": "skipped", "reason": "missing_user"}
        method = getattr(memory_client, "create_memory_candidate", None)
        if not callable(method):
            return {"saved": 0, "failed": 0, "failures": [], "status": "skipped", "reason": "client_method_missing"}
        saved = 0
        failed = 0
        failures: list[dict[str, Any]] = []
        for candidate in candidates:
            if candidate.scope == "discard":
                continue
            try:
                await method(
                    user_id=request.userId,
                    project_id=self._project_id_for(request, candidate),
                    conversation_id=request.conversationId,
                    scope=candidate.scope,
                    memory_type=candidate.type,
                    content=candidate.content,
                    summary=None,
                    confidence=candidate.confidence,
                    source_trace_id=candidate.sourceTraceId or request.traceId,
                    ttl_days=self._ttl_days(candidate),
                )
            except Exception as exc:
                failed += 1
                failures.append({
                    "scope": candidate.scope,
                    "type": candidate.type,
                    "reason": exc.__class__.__name__,
                })
                continue
            saved += 1
        return {
            "saved": saved,
            "failed": failed,
            "failures": failures,
            "status": "partial" if failed and saved else "failed" if failed else "saved" if saved else "empty",
        }

    def _extract_structured_markers(self, request: KnowledgeChatRequest) -> list[MemoryCandidate]:
        text = " ".join((request.question or "").split())
        lowered = text.lower()
        if lowered.startswith("project setting:"):
            return [MemoryCandidate(
                scope="project" if request.projectId is not None else "thread",
                type="fact",
                content=text.split(":", 1)[1].strip(),
                confidence=0.87,
                sourceTraceId=request.traceId,
                reason="explicit project setting marker",
            )]
        if lowered.startswith("temporary preference:"):
            return [MemoryCandidate(
                scope="project" if request.projectId is not None else "thread",
                type="preference",
                content=text.split(":", 1)[1].strip(),
                confidence=0.64,
                sourceTraceId=request.traceId,
                reason="temporary preference marker",
            )]
        if lowered.startswith("long-term preference:"):
            return [MemoryCandidate(
                scope="user",
                type="preference",
                content=text.split(":", 1)[1].strip(),
                confidence=0.8,
                sourceTraceId=request.traceId,
                reason="long-term preference marker",
            )]
        return []

    def _project_id_for(self, request: KnowledgeChatRequest, candidate: MemoryCandidate) -> int | None:
        if candidate.scope == "user":
            return None
        return request.projectId

    def _ttl_days(self, candidate: MemoryCandidate) -> int:
        if candidate.scope == "thread":
            return 7
        if candidate.scope == "user":
            return 30
        return 30

    def _dedupe(self, candidates: list[MemoryCandidate]) -> list[MemoryCandidate]:
        seen: set[tuple[str, str, str]] = set()
        unique: list[MemoryCandidate] = []
        for candidate in candidates:
            key = (candidate.scope, candidate.type, candidate.content.strip())
            if key in seen:
                continue
            seen.add(key)
            unique.append(candidate)
        return unique
