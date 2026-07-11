from __future__ import annotations

from typing import Any

from app.models.knowledge import KnowledgeChatRequest


class MemoryAgent:
    def __init__(self, memory_client: Any | None = None) -> None:
        self.memory_client = memory_client

    async def load(self, request: KnowledgeChatRequest) -> dict[str, Any]:
        diagnostics: dict[str, dict[str, Any]] = {}
        summary = await self._read_summary(request, diagnostics)
        project_memory = await self._search_memory(request, scope="project", limit=12, diagnostics=diagnostics) if request.projectId else []
        if not request.projectId:
            diagnostics["projectMemory"] = {"status": "skipped", "reason": "no_project_id"}
        user_memory = await self._search_memory(
            request.model_copy(update={"projectId": None}),
            scope="user",
            limit=5,
            diagnostics=diagnostics,
        )
        semantic_memory = await self._search_semantic(request, limit=8, diagnostics=diagnostics)
        return {
            "conversationSummary": summary or {},
            "projectMemory": project_memory[:12],
            "userMemory": user_memory[:5],
            "semanticMemory": semantic_memory[:8],
            "memoryUsed": {
                "conversationSummary": bool(summary),
                "projectMemoryCount": len(project_memory[:12]),
                "userMemoryCount": len(user_memory[:5]),
                "semanticMemoryCount": len(semantic_memory[:8]),
            },
            "diagnostics": diagnostics,
        }

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
        results = [item for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []
        diagnostics[diagnostic_key] = {"status": "loaded" if results else "empty", "count": len(results)}
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
        results = [item for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []
        diagnostics["semanticMemory"] = {"status": "loaded" if results else "empty", "count": len(results)}
        return results
