from __future__ import annotations

from typing import Any

from app.config import settings
from app.services.harness.agent_kernel import (
    AgentKernel,
    KernelMessage,
    KernelStopReason,
    KernelToolCall,
    KernelToolObservation,
    KernelTurnRequest,
)
from app.services.mcp.tool_registry import McpToolRegistry
from app.services.harness.cancellation import cancellation_checkpoint
from app.services.harness.tool_ledger import RunToolLedger, current_run_tool_ledger
from app.services.harness.trust import serialize_untrusted_content


SENSITIVE_KEYS = {
    "token", "api_key", "apiKey", "authorization", "Authorization", "secret", "password", "idempotencyKey",
}


class ToolCallLoop:
    def __init__(
        self,
        *,
        agent_kernel: AgentKernel,
        mcp_client: Any,
        registry: McpToolRegistry,
        tool_ledger: RunToolLedger | None = None,
    ) -> None:
        self.agent_kernel = agent_kernel
        self.mcp_client = mcp_client
        self.registry = registry
        self.tool_ledger = tool_ledger
        self.max_tool_calls_per_turn = 12
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
        allowed_tools: set[str] | None = None,
        max_tool_calls: int | None = None,
        cache_affinity: str | None = None,
        request_family: str | None = None,
    ) -> dict[str, Any]:
        tool_ledger = self.tool_ledger or current_run_tool_ledger()
        same_tool_counts: dict[str, int] = {}
        loop_limit = 12 if str(reasoning_mode or "").lower() == "deep" else 6
        if max_tool_calls is not None:
            loop_limit = min(loop_limit, max(0, int(max_tool_calls)))
        effective_tool_limit = loop_limit
        tool_schemas = self._openai_tools(
            route=route,
            supervisor_permissions=supervisor_permissions,
            allowed_tools=allowed_tools,
            project_id=tool_ledger.identity.projectId if tool_ledger is not None else None,
        )
        authorized_names = {
            str((schema.get("function") or {}).get("name") or "").strip()
            for schema in tool_schemas
            if isinstance(schema, dict)
        }

        async def execute_tool(call: KernelToolCall) -> KernelToolObservation:
            return await self._execute_kernel_tool(
                call,
                route=route,
                supervisor_permissions=supervisor_permissions,
                allowed_tools=allowed_tools,
                tool_ledger=tool_ledger,
                same_tool_counts=same_tool_counts,
            )

        cancellation_checkpoint()
        result = await self.agent_kernel.run(
            KernelTurnRequest(
                messages=[self._kernel_message(message) for message in messages],
                model=model or settings.default_model,
                temperature=temperature,
                max_tokens=max_tokens,
                reasoning_mode=reasoning_mode,
                reasoning_effort=reasoning_effort,
                require_json=False,
                cache_affinity=cache_affinity,
                request_family=request_family or "specialist_tool",
                tool_schemas=tool_schemas,
                max_turns=max(1, min(self.max_tool_calls_per_turn, loop_limit) + 1),
                max_tool_calls=effective_tool_limit,
            ),
            authorization={
                "grants": [
                    {"toolName": name}
                    for name in sorted(authorized_names)
                    if name
                ]
            },
            tool_executor=execute_tool,
            tool_call_message_formatter=lambda call: self._safe_kernel_tool_call(call, tool_ledger),
        )
        if result.stop_reason != KernelStopReason.CANCELLED:
            cancellation_checkpoint()
        payload = result.to_provider_result()
        tool_budget_exhausted = any(
            str(run.get("errorType") or "") in {"BudgetExceededError", "ToolBudgetExceeded"}
            for run in result.tool_runs
        )
        if tool_budget_exhausted:
            payload["finishReason"] = "tool_budget_exceeded"
        elif result.stop_reason == KernelStopReason.CANCELLED:
            payload["finishReason"] = "cancelled"
        elif result.stop_reason == KernelStopReason.MAX_TURNS:
            payload["finishReason"] = "tool_call_limit"
        return payload

    def _safe_kernel_tool_call(
        self,
        call: KernelToolCall,
        tool_ledger: RunToolLedger | None,
    ) -> dict[str, Any]:
        safe_arguments = self._safe_arguments(call.name, call.arguments, tool_ledger)
        raw_function = call.raw.get("function") if isinstance(call.raw.get("function"), dict) else {}
        return {
            "id": call.id,
            "type": str(call.raw.get("type") or "function"),
            "function": {
                **{key: value for key, value in raw_function.items() if key not in {"name", "arguments"}},
                "name": call.name,
                "arguments": AgentKernel._dump_arguments(safe_arguments),
            },
        }

    @staticmethod
    def _kernel_message(message: dict[str, Any]) -> KernelMessage:
        return KernelMessage(
            role=str(message.get("role") or "user"),
            content=str(message.get("content") or ""),
            tool_call_id=str(message.get("tool_call_id") or "").strip() or None,
            name=str(message.get("name") or "").strip() or None,
            tool_calls=[item for item in list(message.get("tool_calls") or []) if isinstance(item, dict)],
            reasoning_content=str(message.get("reasoning_content") or "").strip() or None,
        )

    async def _execute_kernel_tool(
        self,
        call: KernelToolCall,
        *,
        route: str,
        supervisor_permissions: set[str] | None,
        allowed_tools: set[str] | None,
        tool_ledger: RunToolLedger | None,
        same_tool_counts: dict[str, int],
    ) -> KernelToolObservation:
        name = call.name
        arguments = dict(call.arguments)
        safe_arguments = self._safe_arguments(name, arguments, tool_ledger)
        same_tool_counts[name] = same_tool_counts.get(name, 0) + 1
        if same_tool_counts[name] > self.max_same_tool_calls:
            run = self._run("denied", name, safe_arguments, error="same tool call limit exceeded")
        elif not self.registry.is_allowed(name, route=route, supervisor_permissions=supervisor_permissions):
            run = self._run("denied", name, safe_arguments, error="tool not allowed")
        elif allowed_tools is not None and name not in allowed_tools:
            run = self._run("denied", name, safe_arguments, error="tool not granted to delegated specialist")
        elif tool_ledger is None:
            run = self._run(
                "failed",
                name,
                safe_arguments,
                error="run tool ledger and scoped identity are required",
                error_type="ToolScopeMissing",
            )
        else:
            scoped_arguments, scope_error = self._scoped_arguments(name, arguments, tool_ledger)
            if self.registry.side_effect_type(name) == "write" and not scoped_arguments.get("idempotencyKey"):
                scoped_arguments["idempotencyKey"] = (
                    f"{tool_ledger.identity.runId}:{name}:"
                    f"{tool_ledger.call_id(name, scoped_arguments, access='write')}"
                )
            validation_error = scope_error or self.registry.validate_arguments(name, scoped_arguments)
            if validation_error:
                run = self._run(
                    "failed",
                    name,
                    self._safe_arguments(name, scoped_arguments, tool_ledger),
                    error=validation_error,
                )
            else:
                timeout = self.registry.timeout_seconds(name)
                secret_input_keys, secret_output_keys = self.registry.secret_keys(name)
                trusted_identity = tool_ledger.for_route(route).identity

                async def call_mcp_tool() -> Any:
                    return await self.mcp_client.call_tool(
                        name,
                        scoped_arguments,
                        timeout=timeout,
                        route=trusted_identity.route,
                        user_id=trusted_identity.userId,
                        project_id=scoped_arguments.get("projectId"),
                        supervisor_permissions=set(supervisor_permissions or set()),
                    )

                ledger_run = await tool_ledger.execute(
                    name,
                    scoped_arguments,
                    call_mcp_tool,
                    access="write" if self.registry.side_effect_type(name) == "write" else "read",
                    idempotency_key=scoped_arguments.get("idempotencyKey"),
                    call_id=call.id,
                    timeout=timeout,
                    toolset="mcp",
                    route=route,
                    secret_input_keys=secret_input_keys,
                    secret_output_keys=secret_output_keys,
                )
                run = self._ledger_run(ledger_run.model_dump(mode="json", exclude_none=True))
        return KernelToolObservation(
            tool_call_id=call.id,
            name=name,
            status=str(run.get("status") or "failed"),
            content=self._content_for_tool_message(run),
            raw=run,
        )

    def _tool_calls(self, response: dict[str, Any]) -> list[dict[str, Any]]:
        raw = response.get("tool_calls") or response.get("toolCalls") or []
        return [item for item in raw if isinstance(item, dict)]

    def _assistant_tool_call_message(
        self,
        response: dict[str, Any],
        tool_calls: list[dict[str, Any]],
        tool_ledger: RunToolLedger | None,
    ) -> dict[str, Any]:
        sanitized_tool_calls = [
            {
                **tool_call,
                "arguments": self._safe_arguments(
                    str(tool_call.get("name") or ""),
                    tool_call.get("arguments") if isinstance(tool_call.get("arguments"), dict) else {},
                    tool_ledger,
                ),
            }
            for tool_call in tool_calls
        ]
        message = {
            "role": "assistant",
            "content": str(response.get("content") or ""),
            "tool_calls": sanitized_tool_calls,
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
        error_type: str | None = None,
    ) -> dict[str, Any]:
        run = {"name": name, "status": status, "input": arguments}
        if output is not None:
            run["output"] = output
        if error:
            run["error"] = error
        if error_type:
            run["errorType"] = error_type
        return run

    def _scoped_arguments(
        self,
        name: str,
        arguments: dict[str, Any],
        tool_ledger: RunToolLedger | None,
    ) -> tuple[dict[str, Any], str | None]:
        scoped = dict(arguments)
        identity = tool_ledger.identity if tool_ledger is not None else None
        if identity is None:
            return scoped, "run tool ledger and scoped identity are required"
        for key in (
            "userId",
            "projectId",
            "permissions",
            "supervisorPermissions",
            "supervisor_permissions",
        ):
            scoped.pop(key, None)
        identity_keys = set(self.registry.identity_keys(name))
        scope_requirement = self.registry.scope_requirement(name)
        if "userId" in identity_keys:
            scoped["userId"] = identity.userId
        if scope_requirement == "project":
            if not identity.projectId:
                return scoped, "missing required scope argument: projectId"
            scoped["projectId"] = identity.projectId
        return scoped, None

    def _ledger_run(self, run: dict[str, Any]) -> dict[str, Any]:
        payload = dict(run)
        output = payload.get("output") if isinstance(payload.get("output"), dict) else {}
        if payload.get("status") != "succeeded" and output.get("message"):
            payload["error"] = str(output["message"])
        return payload

    def _safe_arguments(
        self,
        name: str,
        arguments: dict[str, Any],
        tool_ledger: RunToolLedger | None,
    ) -> dict[str, Any]:
        secret_input_keys, _ = self.registry.secret_keys(name)
        if tool_ledger is not None:
            value = tool_ledger.redact(arguments, secret_keys=secret_input_keys)
            return value if isinstance(value, dict) else {}
        value = self._redact(arguments, secret_keys=secret_input_keys)
        return value if isinstance(value, dict) else {}

    def _redact(self, value: Any, *, secret_keys: set[str] | None = None) -> Any:
        if isinstance(value, dict):
            return {
                str(key): "[redacted]"
                if str(key) in SENSITIVE_KEYS or str(key) in (secret_keys or set())
                else self._redact(item, secret_keys=secret_keys)
                for key, item in value.items()
            }
        if isinstance(value, list):
            return [self._redact(item, secret_keys=secret_keys) for item in value]
        return value

    def _content_for_tool_message(self, run: dict[str, Any]) -> str:
        if run.get("status") == "succeeded":
            return serialize_untrusted_content(run.get("output") or {}, max_chars=24_000)
        return serialize_untrusted_content(
            {"error": run.get("error"), "status": run.get("status")},
            max_chars=4_000,
        )

    def _openai_tools(
        self,
        *,
        route: str,
        supervisor_permissions: set[str] | None,
        allowed_tools: set[str] | None,
        project_id: str | None,
    ) -> list[dict[str, Any]]:
        tools = self.registry.openai_tools(
            route=route,
            supervisor_permissions=supervisor_permissions,
            project_id=project_id,
        )
        if allowed_tools is None:
            return tools
        return [
            tool
            for tool in tools
            if str((tool.get("function") or {}).get("name") or "") in allowed_tools
        ]
