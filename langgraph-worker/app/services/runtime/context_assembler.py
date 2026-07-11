from __future__ import annotations

from typing import Any

from app.models.agent_runtime import ContextBundle, ContextLayer
from app.models.knowledge import KnowledgeChatRequest


class ContextAssembler:
    def __init__(self, memory_client: Any | None = None) -> None:
        self.memory_client = memory_client

    def assemble(self, request: KnowledgeChatRequest) -> ContextBundle:
        incoming = request.contextBundle if isinstance(request.contextBundle, dict) else {}
        return ContextBundle(
            systemBaseline=self._layer(incoming.get("systemBaseline")) or self._system_baseline(),
            userProfile=self._layer(incoming.get("userProfile")),
            projectProfile=self._project_layer(request, incoming),
            threadSummary=self._thread_layer(request, incoming),
            currentTurn=self._current_turn_layer(request, incoming),
        )

    async def assemble_async(self, request: KnowledgeChatRequest) -> ContextBundle:
        incoming = request.contextBundle if isinstance(request.contextBundle, dict) else {}
        return ContextBundle(
            systemBaseline=self._layer(incoming.get("systemBaseline")) or self._system_baseline(),
            userProfile=self._layer(incoming.get("userProfile")),
            projectProfile=await self._project_layer_async(request, incoming),
            threadSummary=self._thread_layer(request, incoming),
            currentTurn=self._current_turn_layer(request, incoming),
        )

    def _system_baseline(self) -> ContextLayer:
        return ContextLayer(
            scope="system",
            content={
                "domain": "webnovel",
                "rule": "Use project/thread memory as context, not as market fact evidence.",
            },
        )

    def _project_layer(self, request: KnowledgeChatRequest, incoming: dict[str, Any]) -> ContextLayer | None:
        layer = self._layer(incoming.get("projectProfile"))
        if layer is not None:
            return layer
        if request.projectId is None:
            return None
        return self._project_placeholder_layer(request, reason="sync_project_memory_not_loaded")

    async def _project_layer_async(self, request: KnowledgeChatRequest, incoming: dict[str, Any]) -> ContextLayer | None:
        layer = self._layer(incoming.get("projectProfile"))
        if layer is not None and not self._is_shell_project_layer(layer):
            return layer
        if request.projectId is None:
            return None
        memory, status, reason = await self._fetch_project_memory(request)
        if memory:
            content = {
                "projectId": memory.get("projectId") or request.projectId,
                "userId": memory.get("userId") or request.userId,
                "bookId": request.bookId,
                "bookName": request.bookName,
                "memories": {
                    str(key): value
                    for key, value in dict(memory.get("memories") or {}).items()
                    if value is not None
                },
            }
            return ContextLayer(
                scope="project",
                content={key: value for key, value in content.items() if value is not None},
                sourceIds=["ai_project_memory"],
            )
        return self._project_placeholder_layer(request, reason=reason or status)

    def _is_shell_project_layer(self, layer: ContextLayer) -> bool:
        if layer.scope != "project":
            return False
        if layer.sourceIds:
            return False
        content = dict(layer.content or {})
        if isinstance(content.get("memories"), dict) and content["memories"]:
            return False
        diagnostics = content.get("_diagnostics") if isinstance(content.get("_diagnostics"), dict) else {}
        if diagnostics.get("projectProfileStatus") == "placeholder":
            return True
        meaningful_keys = {
            key
            for key, value in content.items()
            if value is not None and key != "_diagnostics"
        }
        shell_keys = {"projectId", "userId", "bookId", "bookName"}
        return bool(meaningful_keys) and meaningful_keys.issubset(shell_keys)

    async def _fetch_project_memory(self, request: KnowledgeChatRequest) -> tuple[dict[str, Any] | None, str, str | None]:
        if request.projectId is None or request.userId is None:
            return None, "skipped", "missing_project_or_user"
        method = getattr(self.memory_client, "get_project_memory", None)
        if not callable(method):
            return None, "skipped", "client_method_missing"
        try:
            payload = await method(project_id=request.projectId, user_id=request.userId)
        except Exception as exc:
            return None, "unavailable", exc.__class__.__name__
        if isinstance(payload, dict) and payload:
            return payload, "loaded", None
        return None, "empty", "empty"

    def _project_placeholder_layer(self, request: KnowledgeChatRequest, *, reason: str) -> ContextLayer:
        return ContextLayer(
            scope="project",
            content={
                key: value
                for key, value in {
                    "projectId": request.projectId,
                    "bookId": request.bookId,
                    "bookName": request.bookName,
                    "_diagnostics": {
                        "projectProfileStatus": "placeholder",
                        "reason": reason,
                    },
                }.items()
                if value is not None
            },
        )

    def _thread_layer(self, request: KnowledgeChatRequest, incoming: dict[str, Any]) -> ContextLayer | None:
        layer = self._layer(incoming.get("threadSummary"))
        if layer is not None:
            return layer
        if not request.contextSummary and not request.history:
            return None
        return ContextLayer(
            scope="thread",
            content={
                "conversationId": request.conversationId,
                "summary": request.contextSummary or "",
                "history": [
                    {
                        "role": str(message.get("role") or "user"),
                        "content": str(message.get("content") or ""),
                    }
                    for message in request.history[-6:]
                    if isinstance(message, dict) and str(message.get("content") or "").strip()
                ],
            },
        )

    def _current_turn_layer(self, request: KnowledgeChatRequest, incoming: dict[str, Any]) -> ContextLayer:
        layer = self._layer(incoming.get("currentTurn"))
        content = dict(layer.content) if layer is not None else {}
        content.update({
            "question": request.question,
            "userId": request.userId,
            "projectId": request.projectId,
            "conversationId": request.conversationId,
            "bookId": request.bookId,
            "bookName": request.bookName,
            "mode": request.mode,
        })
        return ContextLayer(
            scope="turn",
            content={key: value for key, value in content.items() if value is not None},
            sourceIds=list(layer.sourceIds if layer is not None else []),
        )

    def _layer(self, value: Any) -> ContextLayer | None:
        if value is None:
            return None
        if isinstance(value, ContextLayer):
            return value
        if isinstance(value, dict):
            try:
                return ContextLayer.model_validate(value)
            except Exception:
                return None
        return None
