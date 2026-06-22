from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from app.models.agent_task import ToolRun

ToolHandler = Callable[[dict[str, Any]], Awaitable[Any] | Any]
ToolCheck = Callable[[], bool]


@dataclass(slots=True)
class RegisteredDomainTool:
    name: str
    toolset: str
    schema: dict[str, Any]
    handler: ToolHandler
    check_fn: ToolCheck


class DomainToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, RegisteredDomainTool] = {}

    def register(
        self,
        name: str,
        toolset: str,
        schema: dict[str, Any],
        handler: ToolHandler,
        *,
        check_fn: ToolCheck | None = None,
    ) -> None:
        self._tools[name] = RegisteredDomainTool(
            name=name,
            toolset=toolset,
            schema=dict(schema or {}),
            handler=handler,
            check_fn=check_fn or (lambda: True),
        )

    def available(self, *, toolset: str | None = None) -> list[RegisteredDomainTool]:
        tools = [
            tool
            for tool in self._tools.values()
            if (toolset is None or tool.toolset == toolset) and self._is_available(tool)
        ]
        return sorted(tools, key=lambda tool: tool.name)

    async def dispatch(self, name: str, payload: dict[str, Any] | None = None) -> ToolRun:
        tool = self._tools.get(name)
        input_payload = dict(payload or {})
        if tool is None:
            return ToolRun(
                name=name,
                status="failed",
                input=input_payload,
                output={"message": "tool not registered"},
                errorType="ToolNotFound",
            )
        if not self._is_available(tool):
            return ToolRun(
                name=name,
                status="skipped",
                toolset=tool.toolset,
                input=input_payload,
                output={"message": "tool unavailable"},
            )
        try:
            raw = tool.handler(input_payload)
            output = await raw if inspect.isawaitable(raw) else raw
            normalized = self._normalize_output(output)
            return ToolRun(
                name=name,
                status="succeeded",
                toolset=tool.toolset,
                input=input_payload,
                output=normalized,
                resultCount=self._result_count(normalized),
            )
        except Exception as exc:
            return ToolRun(
                name=name,
                status="failed",
                toolset=tool.toolset,
                input=input_payload,
                output={"message": str(exc)},
                errorType=exc.__class__.__name__,
            )

    def _is_available(self, tool: RegisteredDomainTool) -> bool:
        try:
            return bool(tool.check_fn())
        except Exception:
            return False

    def _normalize_output(self, output: Any) -> dict[str, Any]:
        serialized = self._to_json_safe(output)
        if isinstance(serialized, dict):
            return serialized
        if isinstance(serialized, list):
            return {"items": serialized}
        return {"value": serialized}

    def _to_json_safe(self, value: Any) -> Any:
        if hasattr(value, "model_dump") and callable(value.model_dump):
            return self._to_json_safe(value.model_dump(mode="json", exclude_none=True))
        if isinstance(value, dict):
            return {str(key): self._to_json_safe(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self._to_json_safe(item) for item in value]
        if isinstance(value, tuple):
            return [self._to_json_safe(item) for item in value]
        return value

    def _result_count(self, output: Any) -> int:
        if isinstance(output, list):
            return len(output)
        if isinstance(output, dict):
            items = output.get("items")
            if isinstance(items, list):
                return len(items)
            for key in ("ranks", "books", "chapters", "analyses"):
                value = output.get(key)
                if isinstance(value, list) and value:
                    return len(value)
        return 1 if output is not None else 0
