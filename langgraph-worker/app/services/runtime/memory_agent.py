from __future__ import annotations

import asyncio
import json
from collections.abc import Iterable
from typing import Any

from app.models.knowledge import KnowledgeChatRequest


class MemoryAgent:
    _ALL_SCOPES = frozenset({"thread", "project", "user", "semantic"})
    _DIAGNOSTIC_KEYS = {
        "thread": "conversationSummary",
        "project": "projectMemory",
        "user": "userMemory",
        "semantic": "semanticMemory",
    }
    _TRACE_METADATA_KEYS = frozenset({
        "candidateScope",
        "candidateType",
        "confidence",
        "evidenceKind",
        "extractor",
        "extractorVersion",
        "factKey",
        "indexGeneration",
        "kind",
        "reason",
        "source",
        "sourceKind",
        "sourceTraceId",
    })

    def __init__(self, memory_client: Any | None = None) -> None:
        self.memory_client = memory_client

    async def load(
        self,
        request: KnowledgeChatRequest,
        *,
        scopes: Iterable[str] | None = None,
    ) -> dict[str, Any]:
        requested_scopes = self._normalize_scopes(scopes)
        context = self.empty_context(scopes=requested_scopes)
        diagnostics = context["diagnostics"]
        pending: list[tuple[str, Any]] = []

        if "thread" in requested_scopes:
            pending.append(("conversationSummary", self._read_summary(request, diagnostics)))
        if "project" in requested_scopes:
            if request.projectId is not None:
                pending.append((
                    "projectMemory",
                    self._search_memory(request, scope="project", limit=12, diagnostics=diagnostics),
                ))
            else:
                diagnostics["projectMemory"] = {"status": "skipped", "reason": "no_project_id"}
        if "user" in requested_scopes:
            pending.append((
                "userMemory",
                self._search_memory(
                    request.model_copy(update={"projectId": None}),
                    scope="user",
                    limit=5,
                    diagnostics=diagnostics,
                ),
            ))
        if "semantic" in requested_scopes:
            pending.append(("semanticMemory", self._search_semantic(request, limit=8, diagnostics=diagnostics)))

        if pending:
            values = await asyncio.gather(*(operation for _key, operation in pending))
            for (key, _operation), value in zip(pending, values, strict=True):
                context[key] = value or ({} if key == "conversationSummary" else [])

        summary = context["conversationSummary"]
        project_memory = context["projectMemory"]
        user_memory = context["userMemory"]
        semantic_memory = context["semanticMemory"]
        memory_evidence = self._memory_evidence(project_memory, user_memory, semantic_memory)
        context["conversationSummary"] = summary or {}
        context["projectMemory"] = project_memory[:12]
        context["userMemory"] = user_memory[:5]
        context["semanticMemory"] = semantic_memory[:8]
        context["memoryUsed"] = {
            "conversationSummary": bool(summary),
            "projectMemoryCount": len(project_memory[:12]),
            "userMemoryCount": len(user_memory[:5]),
            "semanticMemoryCount": len(semantic_memory[:8]),
            "confirmedOnly": True,
        }
        context["memoryEvidence"] = memory_evidence
        return context

    @classmethod
    def empty_context(cls, *, scopes: Iterable[str] = ()) -> dict[str, Any]:
        requested_scopes = cls._normalize_scopes(scopes)
        diagnostics = {
            diagnostic_key: {
                "status": "skipped",
                "reason": "not_loaded" if scope in requested_scopes else "scope_not_requested",
            }
            for scope, diagnostic_key in cls._DIAGNOSTIC_KEYS.items()
        }
        return {
            "conversationSummary": {},
            "projectMemory": [],
            "userMemory": [],
            "semanticMemory": [],
            "memoryUsed": {
                "conversationSummary": False,
                "projectMemoryCount": 0,
                "userMemoryCount": 0,
                "semanticMemoryCount": 0,
                "confirmedOnly": True,
            },
            "memoryEvidence": [],
            "diagnostics": diagnostics,
        }

    @classmethod
    def _normalize_scopes(cls, scopes: Iterable[str] | None) -> frozenset[str]:
        if scopes is None:
            return cls._ALL_SCOPES
        return frozenset(
            normalized
            for scope in scopes
            if (normalized := str(scope or "").strip().lower()) in cls._ALL_SCOPES
        )

    async def _read_summary(self, request: KnowledgeChatRequest, diagnostics: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
        if request.userId is None or not request.conversationId:
            diagnostics["conversationSummary"] = {"status": "skipped", "reason": "missing_user_or_conversation"}
            return None
        method = getattr(self.memory_client, "read_conversation_summary", None)
        if not callable(method):
            diagnostics["conversationSummary"] = {"status": "skipped", "reason": "client_method_missing"}
            return None
        try:
            payload = await method(user_id=request.userId, conversation_id=request.conversationId)
        except Exception as exc:
            diagnostics["conversationSummary"] = {"status": "unavailable", "reason": exc.__class__.__name__}
            return None
        diagnostics["conversationSummary"] = {"status": "loaded" if isinstance(payload, dict) and payload else "empty"}
        return payload if isinstance(payload, dict) else None

    async def _search_memory(
        self,
        request: KnowledgeChatRequest,
        *,
        scope: str,
        limit: int,
        diagnostics: dict[str, dict[str, Any]],
    ) -> list[dict[str, Any]]:
        diagnostic_key = "projectMemory" if scope == "project" else "userMemory"
        if request.userId is None:
            diagnostics[diagnostic_key] = {"status": "skipped", "reason": "missing_user"}
            return []
        method = getattr(self.memory_client, "search_memory", None)
        if not callable(method):
            diagnostics[diagnostic_key] = {"status": "skipped", "reason": "client_method_missing"}
            return []
        try:
            payload = await method(
                user_id=request.userId,
                project_id=request.projectId,
                scope=scope,
                limit=limit,
            )
        except Exception as exc:
            diagnostics[diagnostic_key] = {"status": "unavailable", "reason": exc.__class__.__name__}
            return []
        results, rejected = self._confirmed_memory(payload)
        diagnostics[diagnostic_key] = self._memory_diagnostic(results, rejected)
        return results

    async def _search_semantic(
        self,
        request: KnowledgeChatRequest,
        *,
        limit: int,
        diagnostics: dict[str, dict[str, Any]],
    ) -> list[dict[str, Any]]:
        if request.userId is None:
            diagnostics["semanticMemory"] = {"status": "skipped", "reason": "missing_user"}
            return []
        method = getattr(self.memory_client, "search_semantic_memory", None)
        if not callable(method):
            diagnostics["semanticMemory"] = {"status": "skipped", "reason": "client_method_missing"}
            return []
        try:
            payload = await method(
                query=request.question,
                user_id=request.userId,
                project_id=request.projectId,
                conversation_id=request.conversationId,
                limit=limit,
            )
        except Exception as exc:
            diagnostics["semanticMemory"] = {"status": "unavailable", "reason": exc.__class__.__name__}
            return []
        results, rejected = self._confirmed_memory(payload)
        diagnostics["semanticMemory"] = self._memory_diagnostic(results, rejected)
        return results

    @staticmethod
    def _confirmed_memory(payload: Any) -> tuple[list[dict[str, Any]], list[str]]:
        confirmed: list[dict[str, Any]] = []
        rejected: list[str] = []
        for item in payload if isinstance(payload, list) else []:
            if not isinstance(item, dict):
                rejected.append("INVALID")
                continue
            status = str(item.get("lifecycleStatus") or item.get("status") or "").strip().upper()
            if status != "CONFIRMED":
                rejected.append(status or "MISSING")
                continue
            confirmed.append(dict(item))
        return confirmed, rejected

    @staticmethod
    def _memory_diagnostic(results: list[dict[str, Any]], rejected: list[str]) -> dict[str, Any]:
        diagnostic: dict[str, Any] = {
            "status": "loaded" if results else "empty",
            "count": len(results),
        }
        if rejected:
            diagnostic["rejectedCount"] = len(rejected)
            diagnostic["rejectedStatuses"] = rejected
        return diagnostic

    @staticmethod
    def _memory_evidence(*groups: list[dict[str, Any]]) -> list[dict[str, Any]]:
        evidence: list[dict[str, Any]] = []
        seen: set[tuple[str, str, str]] = set()
        for group in groups:
            for item in group:
                source_trace_id = str(item.get("sourceTraceId") or item.get("source_trace_id") or "").strip()
                memory_id = str(item.get("id") or item.get("memoryId") or "").strip()
                key = (memory_id, source_trace_id, str(item.get("scope") or ""))
                if key in seen:
                    continue
                seen.add(key)
                provenance = MemoryAgent._trace_metadata(
                    item.get("provenance") or item.get("provenanceJson")
                )
                source_evidence = MemoryAgent._trace_metadata(
                    item.get("evidence") or item.get("evidenceJson")
                )
                entry: dict[str, Any] = {
                    "memoryId": memory_id or None,
                    "scope": item.get("scope"),
                    "memoryType": item.get("memoryType") or item.get("memory_type"),
                    "status": "CONFIRMED",
                    "sourceTraceId": source_trace_id or None,
                    "provenance": provenance or source_evidence,
                }
                if provenance and source_evidence:
                    entry["evidence"] = source_evidence
                evidence.append(entry)
        return evidence

    @classmethod
    def _trace_metadata(cls, value: Any) -> dict[str, Any] | None:
        parsed = cls._parse_metadata(value)
        if not isinstance(parsed, dict):
            return None
        safe: dict[str, Any] = {}
        for key, item in parsed.items():
            if key not in cls._TRACE_METADATA_KEYS:
                continue
            if isinstance(item, (bool, int, float)):
                safe[key] = item
                continue
            if isinstance(item, str):
                safe[key] = " ".join(item.split())[:256]
                continue
            if isinstance(item, list) and all(isinstance(entry, (bool, int, float, str)) for entry in item):
                safe[key] = [" ".join(entry.split())[:128] if isinstance(entry, str) else entry for entry in item[:20]]
        return safe or None

    @staticmethod
    def _parse_metadata(value: Any) -> Any:
        if isinstance(value, dict):
            return value
        if not isinstance(value, str) or not value.strip():
            return None
        try:
            return json.loads(value)
        except (TypeError, ValueError):
            return None
