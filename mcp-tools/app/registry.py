from __future__ import annotations

import asyncio
import copy
import hashlib
import json
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace
from typing import Any

from fastapi import HTTPException
from pydantic import BaseModel, ValidationError

from app.backend_client import BackendClient
from app.security import validate_safe_arguments


ToolHandler = Callable[[BaseModel, BackendClient], Awaitable[Any]]
MCP_MANIFEST_META_KEY = "noval.ai/tool-manifest"


MANIFEST_FIELDS = {
    "routes",
    "side_effect_type",
    "scope_requirement",
    "timeout_ms",
    "identity_keys",
    "secret_input_keys",
    "secret_output_keys",
    "requires_supervisor_permission",
}
BUILTIN_TOOL_MANIFESTS: dict[str, dict[str, Any]] = {
    "rank.lookup": {
        "routes": ("market_scan", "mixed_creation_research"),
        "side_effect_type": "read",
        "scope_requirement": "user",
        "timeout_ms": 30000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "rank.refresh": {
        "routes": ("market_scan", "mixed_creation_research"),
        "side_effect_type": "write",
        "scope_requirement": "user",
        "timeout_ms": 60000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
        "requires_supervisor_permission": True,
    },
    "book.search": {
        "routes": ("book_breakdown",),
        "side_effect_type": "read",
        "scope_requirement": "user",
        "timeout_ms": 30000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "knowledge.vector_search": {
        "routes": ("book_breakdown", "market_scan", "mixed_creation_research"),
        "side_effect_type": "read",
        "scope_requirement": "user",
        "timeout_ms": 30000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "project.retrieve": {
        "routes": ("project_creation", "mixed_creation_research"),
        "side_effect_type": "read",
        "scope_requirement": "project",
        "timeout_ms": 60000,
        "identity_keys": ("userId", "projectId"),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "skill.lookup": {
        "routes": ("mixed_creation_research", "project_creation"),
        "side_effect_type": "none",
        "scope_requirement": "user",
        "timeout_ms": 10000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "memory.project_context": {
        "routes": ("mixed_creation_research", "project_creation"),
        "side_effect_type": "read",
        "scope_requirement": "project",
        "timeout_ms": 30000,
        "identity_keys": ("projectId", "userId"),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "memory.admin.list": {
        "routes": ("admin",),
        "side_effect_type": "read",
        "scope_requirement": "project",
        "timeout_ms": 10000,
        "identity_keys": ("projectId", "userId"),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "reader.simulate_feedback": {
        "routes": ("mixed_creation_research",),
        "side_effect_type": "none",
        "scope_requirement": "user",
        "timeout_ms": 10000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
    "editor.risk_check": {
        "routes": ("mixed_creation_research",),
        "side_effect_type": "none",
        "scope_requirement": "user",
        "timeout_ms": 10000,
        "identity_keys": ("userId",),
        "secret_input_keys": (),
        "secret_output_keys": (),
    },
}


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    args_model: type[BaseModel]
    handler: ToolHandler
    admin: bool = False
    routes: tuple[str, ...] | None = None
    side_effect_type: str | None = None
    scope_requirement: str | None = None
    timeout_ms: int | None = None
    identity_keys: tuple[str, ...] | None = None
    secret_input_keys: tuple[str, ...] | None = None
    secret_output_keys: tuple[str, ...] | None = None
    requires_supervisor_permission: bool = False

    def schema(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": self.args_model.model_json_schema(),
            "admin": self.admin,
            "routes": list(self.routes or ()),
            "sideEffectType": self.side_effect_type,
            "scopeRequirement": self.scope_requirement,
            "timeoutMs": self.timeout_ms,
            "identityKeys": list(self.identity_keys or ()),
            "secretInputKeys": list(self.secret_input_keys or ()),
            "secretOutputKeys": list(self.secret_output_keys or ()),
            "requiresSupervisorPermission": self.requires_supervisor_permission,
        }

    def standard_schema(self) -> dict[str, Any]:
        input_schema = copy.deepcopy(self.args_model.model_json_schema())
        properties = input_schema.get("properties")
        hidden_keys = set(self.identity_keys or ()) | set(self.secret_input_keys or ())
        if isinstance(properties, dict):
            for key in hidden_keys:
                properties.pop(key, None)
        required = input_schema.get("required")
        if isinstance(required, list):
            visible_required = [key for key in required if key not in hidden_keys]
            if visible_required:
                input_schema["required"] = visible_required
            else:
                input_schema.pop("required", None)
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": input_schema,
            "_meta": {
                MCP_MANIFEST_META_KEY: {
                    "admin": self.admin,
                    "routes": list(self.routes or ()),
                    "side_effect_type": self.side_effect_type,
                    "scope_requirement": self.scope_requirement,
                    "timeout_ms": self.timeout_ms,
                    "identity_keys": list(self.identity_keys or ()),
                    "secret_input_keys": list(self.secret_input_keys or ()),
                    "secret_output_keys": list(self.secret_output_keys or ()),
                    "requires_supervisor_permission": self.requires_supervisor_permission,
                }
            },
        }


class ToolRegistry:
    def __init__(self, *, idempotency_redis: Any | None = None) -> None:
        self._tools: dict[str, ToolDefinition] = {}
        self._idempotency_redis = idempotency_redis
        self._idempotency_lock = asyncio.Lock()
        self._idempotency_pending: dict[str, tuple[str, asyncio.Task[Any]]] = {}
        self._idempotency_completed: OrderedDict[str, tuple[str, Any]] = OrderedDict()
        self._idempotency_cache_limit = 1024
        self._idempotency_wait_timeout_seconds: float | None = None
        self._idempotency_poll_interval_seconds = 0.05

    def register(self, tool: ToolDefinition) -> None:
        manifest = BUILTIN_TOOL_MANIFESTS.get(tool.name)
        if manifest is not None:
            tool = replace(tool, **manifest)
        self._validate_manifest(tool)
        self._tools[tool.name] = tool

    def list_tools(self, *, toolset: str = "normal") -> list[dict[str, Any]]:
        include_admin = toolset == "admin"
        return [
            tool.schema()
            for tool in self._tools.values()
            if include_admin or not tool.admin
        ]

    def list_standard_tools(self) -> list[dict[str, Any]]:
        return [
            tool.standard_schema()
            for tool in self._tools.values()
        ]

    def hidden_input_keys(self, name: str) -> set[str]:
        tool = self._tools.get(name)
        if tool is None:
            return set()
        return set(tool.identity_keys or ()) | set(tool.secret_input_keys or ())

    async def idempotency_store_ready(self) -> bool:
        if self._idempotency_redis is None:
            return False
        try:
            return bool(await self._idempotency_redis.ping())
        except Exception:
            return False

    async def call(
        self,
        *,
        name: str,
        arguments: dict[str, Any],
        backend_client: BackendClient,
        route: str | None = None,
        supervisor_permissions: set[str] | None = None,
    ) -> Any:
        tool = self._tools.get(name)
        if tool is None:
            raise HTTPException(status_code=404, detail="tool not found")
        if tool.admin:
            if route != "admin":
                raise HTTPException(status_code=403, detail="admin tool denied")
            permissions = supervisor_permissions or set()
            if tool.name not in permissions and "admin:*" not in permissions:
                raise HTTPException(status_code=403, detail="admin tool permission denied")
        if tool.requires_supervisor_permission:
            permissions = supervisor_permissions or set()
            if tool.name not in permissions and "tools:write" not in permissions and "admin:*" not in permissions:
                raise HTTPException(status_code=403, detail="supervisor tool permission denied")
        if not self._is_route_allowed(tool, route):
            raise HTTPException(status_code=403, detail="tool denied for route")
        self._validate_governance_arguments(tool, arguments or {})
        validate_safe_arguments(arguments or {})
        try:
            args = tool.args_model.model_validate(arguments or {})
        except ValidationError as exc:
            raise HTTPException(
                status_code=400,
                detail=exc.errors(include_context=False),
            ) from exc
        validate_safe_arguments(args.model_dump(exclude_none=True))
        operation = lambda: self._invoke_handler(
            tool,
            args,
            backend_client,
            route=str(route or ""),
            supervisor_permissions=supervisor_permissions or set(),
        )
        if tool.side_effect_type == "write":
            return await self._call_idempotent_write(
                tool=tool,
                route=str(route),
                arguments=arguments or {},
                operation=operation,
            )
        return await operation()

    async def _invoke_handler(
        self,
        tool: ToolDefinition,
        args: BaseModel,
        backend_client: BackendClient,
        *,
        route: str,
        supervisor_permissions: set[str],
    ) -> Any:
        context_token = None
        if hasattr(backend_client, "bind_governance_context"):
            context_token = backend_client.bind_governance_context(
                route=route,
                permissions=supervisor_permissions,
            )
        try:
            return await asyncio.wait_for(
                tool.handler(args, backend_client),
                timeout=float(tool.timeout_ms or 0) / 1000,
            )
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail="tool timeout") from exc
        finally:
            if context_token is not None and hasattr(backend_client, "reset_governance_context"):
                backend_client.reset_governance_context(context_token)

    async def _call_idempotent_write(
        self,
        *,
        tool: ToolDefinition,
        route: str,
        arguments: dict[str, Any],
        operation: Callable[[], Awaitable[Any]],
    ) -> Any:
        if self._idempotency_redis is None:
            raise HTTPException(status_code=503, detail="durable MCP idempotency store is unavailable")
        idempotency_key = str(arguments.get("idempotencyKey") or "").strip()
        scope_key = self._digest({
            "tool": tool.name,
            "route": route,
            "userId": arguments.get("userId"),
            "projectId": arguments.get("projectId"),
            "idempotencyKey": idempotency_key,
        })
        fingerprint = self._digest({
            "tool": tool.name,
            "route": route,
            "arguments": arguments,
        })
        durable = await self._load_durable_result(scope_key, fingerprint)
        if durable is not None:
            return durable
        wait_for_durable = False
        task: asyncio.Task[Any] | None = None
        async with self._idempotency_lock:
            completed = self._idempotency_completed.get(scope_key)
            if completed is not None:
                if completed[0] != fingerprint:
                    raise HTTPException(status_code=409, detail="idempotency key reused with different arguments")
                self._idempotency_completed.move_to_end(scope_key)
                return copy.deepcopy(completed[1])
            pending = self._idempotency_pending.get(scope_key)
            if pending is not None:
                if pending[0] != fingerprint:
                    raise HTTPException(status_code=409, detail="idempotency key reused with different arguments")
                task = pending[1]
            else:
                claim_key = f"noval:mcp:idempotency:claim:{scope_key}"
                result_key = f"noval:mcp:idempotency:result:{scope_key}"
                claim_ttl = max(60, int((tool.timeout_ms or 30000) / 1000) * 2)
                acquired = await self._redis_set(
                    claim_key,
                    fingerprint,
                    nx=True,
                    ex=claim_ttl,
                )
                if not acquired:
                    durable = await self._load_durable_result(scope_key, fingerprint)
                    if durable is not None:
                        return durable
                    active_fingerprint = await self._redis_get(claim_key)
                    if active_fingerprint and self._redis_text(active_fingerprint) != fingerprint:
                        raise HTTPException(status_code=409, detail="idempotency key reused with different arguments")
                    wait_for_durable = True
                else:
                    task = asyncio.create_task(self._execute_durable_write(
                        operation=operation,
                        claim_key=claim_key,
                        result_key=result_key,
                        fingerprint=fingerprint,
                    ))
                    self._idempotency_pending[scope_key] = (fingerprint, task)
                    asyncio.create_task(self._finalize_idempotent_write(scope_key, fingerprint, task))
        if wait_for_durable:
            wait_timeout = self._idempotency_wait_timeout_seconds
            if wait_timeout is None:
                wait_timeout = max(1.0, float(tool.timeout_ms or 30000) / 1000)
            return await self._wait_for_durable_result(
                scope_key=scope_key,
                fingerprint=fingerprint,
                claim_key=claim_key,
                timeout_seconds=wait_timeout,
            )
        if task is None:
            raise HTTPException(status_code=503, detail="durable MCP idempotency state is invalid")
        result = await asyncio.shield(task)
        return copy.deepcopy(result)

    async def _wait_for_durable_result(
        self,
        *,
        scope_key: str,
        fingerprint: str,
        claim_key: str,
        timeout_seconds: float,
    ) -> Any:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + max(0.001, timeout_seconds)
        while True:
            durable = await self._load_durable_result(scope_key, fingerprint)
            if durable is not None:
                return durable
            active_fingerprint = await self._redis_get(claim_key)
            if active_fingerprint is None:
                durable = await self._load_durable_result(scope_key, fingerprint)
                if durable is not None:
                    return durable
                raise HTTPException(status_code=503, detail="idempotent write did not commit a result")
            if self._redis_text(active_fingerprint) != fingerprint:
                raise HTTPException(status_code=409, detail="idempotency key reused with different arguments")
            remaining = deadline - loop.time()
            if remaining <= 0:
                raise HTTPException(status_code=504, detail="idempotent write wait timed out")
            await asyncio.sleep(min(self._idempotency_poll_interval_seconds, remaining))

    async def _execute_durable_write(
        self,
        *,
        operation: Callable[[], Awaitable[Any]],
        claim_key: str,
        result_key: str,
        fingerprint: str,
    ) -> Any:
        try:
            result = await operation()
            payload = json.dumps(
                {"fingerprint": fingerprint, "result": result},
                sort_keys=True,
                separators=(",", ":"),
                ensure_ascii=False,
                default=repr,
            )
            stored = await self._redis_set(result_key, payload, ex=86400)
            if not stored:
                raise HTTPException(status_code=503, detail="durable MCP idempotency store is unavailable")
            return result
        finally:
            current = await self._redis_get(claim_key)
            if current is not None and self._redis_text(current) == fingerprint:
                await self._redis_delete(claim_key)

    async def _load_durable_result(self, scope_key: str, fingerprint: str) -> Any | None:
        raw = await self._redis_get(f"noval:mcp:idempotency:result:{scope_key}")
        if not raw:
            return None
        try:
            payload = json.loads(raw)
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=503, detail="durable MCP idempotency result is invalid") from exc
        if payload.get("fingerprint") != fingerprint:
            raise HTTPException(status_code=409, detail="idempotency key reused with different arguments")
        return copy.deepcopy(payload.get("result"))

    async def _redis_get(self, key: str) -> Any:
        try:
            return await self._idempotency_redis.get(key)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="durable MCP idempotency store is unavailable") from exc

    async def _redis_set(self, key: str, value: Any, **kwargs: Any) -> Any:
        try:
            return await self._idempotency_redis.set(key, value, **kwargs)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="durable MCP idempotency store is unavailable") from exc

    async def _redis_delete(self, key: str) -> Any:
        try:
            return await self._idempotency_redis.delete(key)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="durable MCP idempotency store is unavailable") from exc

    def _redis_text(self, value: Any) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="strict")
        return str(value)

    async def _finalize_idempotent_write(
        self,
        scope_key: str,
        fingerprint: str,
        task: asyncio.Task[Any],
    ) -> None:
        try:
            result = await task
        except BaseException:
            async with self._idempotency_lock:
                current = self._idempotency_pending.get(scope_key)
                if current is not None and current[1] is task:
                    self._idempotency_pending.pop(scope_key, None)
            return
        async with self._idempotency_lock:
            current = self._idempotency_pending.get(scope_key)
            if current is not None and current[1] is task:
                self._idempotency_pending.pop(scope_key, None)
            self._idempotency_completed[scope_key] = (fingerprint, copy.deepcopy(result))
            self._idempotency_completed.move_to_end(scope_key)
            while len(self._idempotency_completed) > self._idempotency_cache_limit:
                self._idempotency_completed.popitem(last=False)

    def _digest(self, payload: dict[str, Any]) -> str:
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True, default=repr)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def _is_route_allowed(self, tool: ToolDefinition, route: str | None) -> bool:
        return bool(route) and route in (tool.routes or ())

    def _validate_manifest(self, tool: ToolDefinition) -> None:
        missing = [field for field in MANIFEST_FIELDS if getattr(tool, field) is None]
        if missing:
            raise ValueError(f"tool manifest missing fields: {', '.join(sorted(missing))}")
        if not tool.routes or not all(isinstance(route, str) and route for route in tool.routes):
            raise ValueError("tool manifest routes must be non-empty")
        if tool.side_effect_type not in {"none", "read", "write"}:
            raise ValueError("tool manifest side effect type is invalid")
        if tool.scope_requirement not in {"user", "project"}:
            raise ValueError("tool manifest scope must be user or project")
        if not isinstance(tool.timeout_ms, int) or tool.timeout_ms <= 0:
            raise ValueError("tool manifest timeout must be positive")
        identity_keys = set(tool.identity_keys or ())
        if "userId" not in identity_keys:
            if tool.scope_requirement == "project":
                raise ValueError("tool manifest identity keys must include userId and projectId")
            raise ValueError("tool manifest identity keys must include userId")
        if tool.scope_requirement == "project" and "projectId" not in identity_keys:
            raise ValueError("tool manifest identity keys must include userId and projectId")
        if tool.scope_requirement == "user" and "projectId" in identity_keys:
            raise ValueError("user-scoped tool manifest must not include projectId")
        schema = tool.args_model.model_json_schema()
        properties = schema.get("properties") if isinstance(schema, dict) else {}
        if tool.scope_requirement == "user" and isinstance(properties, dict) and "projectId" in properties:
            raise ValueError("user-scoped tool argument schema must not expose projectId")
        for keys in (tool.identity_keys, tool.secret_input_keys, tool.secret_output_keys):
            if not all(isinstance(key, str) and key for key in keys or ()):
                raise ValueError("tool manifest key lists must contain non-empty strings")

    def _validate_governance_arguments(self, tool: ToolDefinition, arguments: dict[str, Any]) -> None:
        for key in tool.identity_keys or ():
            if arguments.get(key) is None:
                raise HTTPException(status_code=403, detail=f"missing required identity: {key}")
        if tool.scope_requirement == "project" and arguments.get("projectId") is None:
            raise HTTPException(status_code=403, detail="project scope required")
        if tool.side_effect_type == "write" and not arguments.get("idempotencyKey"):
            raise HTTPException(status_code=400, detail="idempotency key required")
