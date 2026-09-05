from __future__ import annotations

import json
import hashlib
import re
from collections.abc import Iterable
from typing import Any

from app.models.agent_runtime import MemoryCandidate
from app.models.knowledge import KnowledgeChatRequest
from app.services.runtime.memory_candidates import MemoryCandidateExtractor


class MemoryExtractor:
    EXTRACTOR_VERSION = "memory-extractor-v1"

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
            return self._persistence_result(saved=0, failed=0, failures=[], status="skipped", reason="missing_user")
        method = getattr(memory_client, "create_memory_candidate", None)
        if not callable(method):
            return self._persistence_result(saved=0, failed=0, failures=[], status="skipped", reason="client_method_missing")
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
                    fact_key=self.fact_key(candidate),
                    candidate_key=self.candidate_key(candidate, request),
                    provenance_json=self._candidate_provenance(candidate),
                    evidence_json=self._candidate_evidence(candidate, request),
                    extractor_version=self.EXTRACTOR_VERSION,
                    ttl_days=self._ttl_days(candidate),
                )
            except Exception as exc:
                failed += 1
                failures.append({
                    "scope": candidate.scope,
                    "type": candidate.type,
                    "factKey": self.fact_key(candidate),
                    "candidateKey": self.candidate_key(candidate, request),
                    "reason": exc.__class__.__name__,
                })
                continue
            saved += 1
        return self._persistence_result(
            saved=saved,
            failed=failed,
            failures=failures,
            status="partial" if failed and saved else "failed" if failed else "saved" if saved else "empty",
        )

    @staticmethod
    def _persistence_result(
        *,
        saved: int,
        failed: int,
        failures: list[dict[str, Any]],
        status: str,
        reason: str | None = None,
    ) -> dict[str, Any]:
        result: dict[str, Any] = {
            "saved": saved,
            "failed": failed,
            "failures": failures,
            "status": status,
            "candidateStatus": "CANDIDATE",
            "conflictPolicy": "candidate_only",
        }
        if reason:
            result["reason"] = reason
        return result

    def trace_candidate(self, candidate: MemoryCandidate) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "scope": candidate.scope,
            "type": candidate.type,
            "confidence": candidate.confidence,
            "factKey": self.fact_key(candidate),
        }
        if candidate.sourceTraceId:
            payload["sourceTraceId"] = candidate.sourceTraceId
        if candidate.reason:
            payload["reason"] = self._compact_reason(candidate.reason)
        return payload

    def persistence_candidate(
        self,
        candidate: MemoryCandidate,
        request: KnowledgeChatRequest,
    ) -> dict[str, Any]:
        payload = candidate.model_dump(mode="json", exclude_none=True)
        payload.update({
            "factKey": self.fact_key(candidate),
            "candidateKey": self.candidate_key(candidate, request),
            "provenanceJson": self._candidate_provenance(candidate),
            "evidenceJson": self._candidate_evidence(candidate, request),
            "extractorVersion": self.EXTRACTOR_VERSION,
            "ttlDays": self._ttl_days(candidate),
        })
        if candidate.sourceTraceId or request.traceId:
            payload["sourceTraceId"] = candidate.sourceTraceId or request.traceId
        if request.conversationId:
            payload["conversationId"] = request.conversationId
        return payload

    def fact_key(self, candidate: MemoryCandidate) -> str:
        requested = self._normalize_fact_key(candidate.factKey)
        if requested:
            return requested
        normalized = " ".join(candidate.content.casefold().split())
        normalized = re.sub(
            r"\b(?:not|no|never|cannot|can't|cant|can|isn't|isnt|doesn't|doesnt|without)\b",
            " ",
            normalized,
        )
        for marker in ("不能", "不会", "不再", "不", "没有", "无", "可以", "能够"):
            normalized = normalized.replace(marker, "")
        normalized = " ".join(normalized.split()) or candidate.content.strip()
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:24]
        return f"{candidate.scope}.{candidate.type}.{digest}"

    def candidate_key(
        self,
        candidate: MemoryCandidate,
        request: KnowledgeChatRequest | None = None,
    ) -> str:
        scope_id = ""
        if request is not None and candidate.scope == "project":
            scope_id = str(request.projectId or "")
        elif request is not None and candidate.scope == "thread":
            scope_id = str(request.conversationId or "")
        canonical = "\n".join((
            candidate.scope,
            scope_id,
            candidate.type,
            self.fact_key(candidate),
            " ".join(candidate.content.split()),
        ))
        return "memory-candidate-" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32]

    @staticmethod
    def _normalize_fact_key(value: str | None) -> str | None:
        normalized = " ".join(str(value or "").split())
        if not normalized:
            return None
        normalized = re.sub(r"[^\w.:-]+", "_", normalized).strip("_")
        return normalized[:160] or None

    def _candidate_provenance(self, candidate: MemoryCandidate) -> str:
        return self._compact_json({
            "source": "worker_memory_extractor",
            "extractorVersion": self.EXTRACTOR_VERSION,
            "reason": self._compact_reason(candidate.reason),
            "candidateScope": candidate.scope,
            "candidateType": candidate.type,
        })

    @staticmethod
    def _candidate_evidence(candidate: MemoryCandidate, request: KnowledgeChatRequest) -> str:
        return MemoryExtractor._compact_json({
            "sourceKind": "user_turn",
            "sourceTraceId": candidate.sourceTraceId or request.traceId,
        })

    @staticmethod
    def _compact_reason(reason: str | None) -> str:
        normalized = " ".join(str(reason or "candidate extraction").split())
        return normalized[:256]

    @staticmethod
    def _compact_json(value: dict[str, Any]) -> str:
        return json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)

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
