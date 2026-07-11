from __future__ import annotations

import copy
from typing import Any

from app.config import settings
from app.services.mcp.tool_registry import McpToolRegistry


SENSITIVE_KEYS = {"token", "api_key", "apiKey", "authorization", "Authorization", "secret", "password"}


class ToolCallLoop:
    def __init__(self, *, provider_client: Any, mcp_client: Any, registry: McpToolRegistry) -> None:
        self.provider_client = provider_client
        self.mcp_client = mcp_client
        self.registry = registry
        self.max_tool_calls_per_turn = 8
        self.max_same_tool_calls = 2

    async def run(
        self,
        *,
        messages: list[dict[str, Any]],
        route: str,
        supervisor_permissions: set[str] | None = None,
        model: str | None = None,
        temperature: float | None = 0.2,
        max_tokens: int | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
    ) -> dict[str, Any]:
        working_messages = copy.deepcopy(messages)
        tool_runs: list[dict[str, Any]] = []
        same_tool_counts: dict[str, int] = {}
        for _ in range(self.max_tool_calls_per_turn):
            response = await self.provider_client.invoke(
                messages=working_messages,
                model=model or settings.default_model,
                temperature=temperature,
                max_tokens=max_tokens,
                require_json=False,
                tools=self.registry.openai_tools(route=route, supervisor_permissions=supervisor_permissions),
                reasoning_mode=reasoning_mode,
                reasoning_effort=reasoning_effort,
            )
            tool_calls = self._tool_calls(response)
            if not tool_calls:
                result = dict(response)
                result["toolRuns"] = tool_runs
                return result
            working_messages.append(self._assistant_tool_call_message(response, tool_calls))
            for tool_call in tool_calls:
                name = str(tool_call.get("name") or "")
                arguments = tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {}
                same_tool_counts[name] = same_tool_counts.get(name, 0) + 1
                if same_tool_counts[name] > self.max_same_tool_calls:
                    run = self._run("denied", name, arguments, error="same tool call limit exceeded")
                elif not self.registry.is_allowed(name, route=route, supervisor_permissions=supervisor_permissions):
                    run = self._run("denied", name, arguments, error="tool not allowed")
                else:
                    validation_error = self.registry.validate_arguments(name, arguments)
                    if validation_error:
                        run = self._run("failed", name, arguments, error=validation_error)
                    else:
                        result = await self.mcp_client.call_tool(name, arguments, route=route)
                        run = self._run("succeeded", name, arguments, output=self._redact(result))
                tool_runs.append(run)
                working_messages.append({
                    "role": "tool",
                    "tool_call_id": str(tool_call.get("id") or name),
                    "name": name,
                    "content": self._content_for_tool_message(run),
                })
        return {"content": "", "toolRuns": tool_runs, "finishReason": "tool_call_limit"}

    def _tool_calls(self, response: dict[str, Any]) -> list[dict[str, Any]]:
        raw = response.get("tool_calls") or response.get("toolCalls") or []
        return [item for item in raw if isinstance(item, dict)]

    def _assistant_tool_call_message(
        self,
        response: dict[str, Any],
        tool_calls: list[dict[str, Any]],
    ) -> dict[str, Any]:
        message = {
            "role": "assistant",
            "content": str(response.get("content") or ""),
            "tool_calls": response.get("raw_tool_calls") or tool_calls,
        }
        reasoning_content = response.get("reasoning_content")
        if reasoning_content:
            message["reasoning_content"] = reasoning_content
        return message

    def _run(
        self,
        status: str,
        name: str,
        arguments: dict[str, Any],
        *,
        output: Any | None = None,
        error: str | None = None,
    ) -> dict[str, Any]:
        run = {"name": name, "status": status, "input": arguments}
        if output is not None:
            run["output"] = output
        if error:
            run["error"] = error
        return run

    def _redact(self, value: Any) -> Any:
        if isinstance(value, dict):
            return {
                str(key): "[redacted]" if str(key) in SENSITIVE_KEYS else self._redact(item)
                for key, item in value.items()
            }
        if isinstance(value, list):
            return [self._redact(item) for item in value]
        return value

    def _content_for_tool_message(self, run: dict[str, Any]) -> str:
        if run.get("status") == "succeeded":
            return str(run.get("output") or {})
        return str({"error": run.get("error"), "status": run.get("status")})
