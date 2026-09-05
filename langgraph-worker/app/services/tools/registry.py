from __future__ import annotations

import hashlib
import inspect
import json
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from app.models.agent_task import ToolRun
from app.services.harness.budget import current_run_budget
from app.services.harness.cancellation import cancellable_await, cancellation_checkpoint
from app.services.harness.validators import ProjectScopeValidator
from app.services.harness.tool_ledger import RunToolLedger, current_run_tool_ledger

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
    MANIFEST_VERSION = "domain-tool-manifest-v1"

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

    def manifest_summary(self, *, toolset: str | None = None) -> dict[str, Any]:
        identities = [
            {
                "name": tool.name,
                "toolset": tool.toolset,
                "definitionFingerprint": self._fingerprint(tool.schema),
            }
            for tool in self.available(toolset=toolset)
        ]
        return {
            "version": self.MANIFEST_VERSION,
            "fingerprint": self._fingerprint({
                "version": self.MANIFEST_VERSION,
                "entries": identities,
            }),
            "toolNames": [entry["name"] for entry in identities],
            "entries": identities,
        }

    async def dispatch(
        self,
        name: str,
        payload: dict[str, Any] | None = None,
        *,
        tool_ledger: RunToolLedger | None = None,
        timeout: float | None = None,
        identity_payload: dict[str, Any] | None = None,
    ) -> ToolRun:
        tool_ledger = tool_ledger or current_run_tool_ledger()
        tool = self._tools.get(name)
        input_payload = dict(payload or {})
        expected_user_id = input_payload.pop("_expectedUserId", None)
        expected_project_id = input_payload.pop("_expectedProjectId", None)
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
        if expected_user_id is not None or expected_project_id is not None:
            scope = ProjectScopeValidator().validate(
                actual_user_id=input_payload.get("userId") or input_payload.get("user_id"),
                actual_project_id=input_payload.get("projectId") or input_payload.get("project_id"),
                expected_user_id=str(expected_user_id or ""),
                expected_project_id=None if expected_project_id is None else str(expected_project_id),
            )
            if not scope.valid:
                return ToolRun(
                    name=name,
                    status="failed",
                    toolset=tool.toolset,
                    input=input_payload,
                    output={"message": scope.reason},
                    errorType="ToolScopeViolation",
                )
        if tool_ledger is None:
            return ToolRun(
                name=name,
                status="failed",
                toolset=tool.toolset,
                input=input_payload,
                output={"message": "run tool ledger is required"},
                errorType="ToolLedgerRequired",
            )
        return await tool_ledger.execute(
            name,
            input_payload,
            lambda: self._execute_handler(tool, input_payload),
            access="read",
            timeout=timeout,
            toolset=tool.toolset,
            identity_arguments=identity_payload,
        )

    async def _execute_handler(
        self,
        tool: RegisteredDomainTool,
        input_payload: dict[str, Any],
    ) -> dict[str, Any]:
        cancellation_checkpoint()
        raw = tool.handler(input_payload)
        output = await cancellable_await(raw) if inspect.isawaitable(raw) else raw
        cancellation_checkpoint()
        return self._normalize_output(output)

    def _is_available(self, tool: RegisteredDomainTool) -> bool:
        try:
            return bool(tool.check_fn())
        except Exception:
            return False

    @staticmethod
    def _fingerprint(value: Any) -> str:
        canonical = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        return f"sha256:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"

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
            for key in ("items", "evidence", "ranks", "books", "chapters", "analyses"):
                value = output.get(key)
                if isinstance(value, list):
                    return len(value)
        return 1 if output is not None else 0
