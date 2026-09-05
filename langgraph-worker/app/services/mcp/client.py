from __future__ import annotations

import contextlib
import contextvars
import hashlib
import hmac
import json
import time
import uuid
from collections.abc import Callable
from datetime import timedelta
from typing import Any

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from app.config import settings


MCP_PATH = "/mcp/v1/"
ENVELOPE_HEADER = "X-Noval-Mcp-Envelope"
MANIFEST_META_KEY = "noval.ai/tool-manifest"


class _McpRequestAuth(httpx.Auth):
    def __init__(self, internal_api_key: str, envelope: contextvars.ContextVar[str | None]) -> None:
        self.internal_api_key = internal_api_key
        self.envelope = envelope

    def auth_flow(self, request: httpx.Request):
        if self.internal_api_key:
            request.headers["X-Internal-Service-Token"] = self.internal_api_key
        envelope = self.envelope.get()
        if envelope:
            request.headers[ENVELOPE_HEADER] = envelope
        yield request


class McpClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        internal_api_key: str | None = None,
        call_signing_key: str | None = None,
        async_client_factory: Callable[..., Any] | None = None,
        session_factory: Callable[..., Any] | None = None,
        clock: Callable[[], float] | None = None,
        nonce_factory: Callable[[], str] | None = None,
    ) -> None:
        self.base_url = (base_url or getattr(settings, "mcp_base_url", "http://fastmcp-tools:7001")).rstrip("/")
        self.endpoint_url = f"{self.base_url}{MCP_PATH}"
        self.internal_api_key = internal_api_key if internal_api_key is not None else getattr(settings, "mcp_internal_api_key", "")
        self.call_signing_key = (
            call_signing_key
            if call_signing_key is not None
            else getattr(settings, "mcp_call_signing_key", "")
        )
        self.timeout_seconds = max(1, int(getattr(settings, "mcp_timeout_millis", 30000)) / 1000)
        self._async_client_factory = async_client_factory or httpx.AsyncClient
        self._session_factory = session_factory or self._official_session
        self._clock = clock or time.time
        self._nonce_factory = nonce_factory or (lambda: uuid.uuid4().hex)
        self._envelope: contextvars.ContextVar[str | None] = contextvars.ContextVar(
            f"noval_mcp_envelope_{id(self)}",
            default=None,
        )
        self._hidden_input_keys: dict[str, set[str]] = {}
        self._client: Any | None = None

    @property
    def call_signing_available(self) -> bool:
        return self._configured_secret(self.internal_api_key) and self._configured_secret(self.call_signing_key)

    async def aclose(self) -> None:
        if self._client is None:
            return
        await self._client.aclose()
        self._client = None

    async def list_tools(self) -> dict[str, Any]:
        if not self._configured_secret(self.internal_api_key):
            raise RuntimeError("MCP_INTERNAL_API_KEY is required for MCP tool discovery")
        tools: list[dict[str, Any]] = []
        async with self._session(envelope=None, timeout_seconds=self.timeout_seconds) as session:
            cursor: str | None = None
            while True:
                page = await session.list_tools(cursor=cursor)
                tools.extend(self._tool_manifest(tool) for tool in page.tools)
                cursor = str(page.nextCursor) if page.nextCursor else None
                if cursor is None:
                    break
        return {"tools": tools}

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, Any],
        timeout: float | None = None,
        route: str | None = None,
        user_id: str | None = None,
        project_id: str | None = None,
        supervisor_permissions: set[str] | None = None,
    ) -> dict[str, Any]:
        if not self._configured_secret(self.call_signing_key):
            raise RuntimeError("MCP_CALL_SIGNING_KEY is required for MCP tool calls")
        if not self._configured_secret(self.internal_api_key):
            raise RuntimeError("MCP_INTERNAL_API_KEY is required for MCP tool calls")
        normalized_route = str(route or "").strip()
        normalized_user_id = str(user_id or "").strip()
        normalized_project_id = str(project_id or "").strip()
        if not normalized_route or not normalized_user_id:
            raise RuntimeError("trusted MCP route and userId are required")
        permissions = sorted({
            str(permission).strip()
            for permission in supervisor_permissions or set()
            if str(permission).strip()
        })
        claims: dict[str, Any] = {
            "name": name,
            "arguments": arguments,
            "route": normalized_route,
            "userId": normalized_user_id,
            "projectId": normalized_project_id,
            "supervisorPermissions": permissions,
            "timestamp": int(self._clock()),
            "nonce": self._nonce_factory(),
        }
        claims["signature"] = self._sign(claims)
        envelope = json.dumps(
            claims,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        )
        hidden_keys = {"userId", "projectId"} | self._hidden_input_keys.get(name, set())
        public_arguments = {
            key: value
            for key, value in arguments.items()
            if key not in hidden_keys
        }
        requested_timeout = timeout if timeout is not None else self.timeout_seconds
        async with self._session(envelope=envelope, timeout_seconds=requested_timeout) as session:
            result = await session.call_tool(
                name,
                public_arguments,
                read_timeout_seconds=timedelta(seconds=requested_timeout),
            )
        if result.isError:
            detail = next(
                (str(block.text) for block in result.content if getattr(block, "text", None)),
                "MCP tool call failed",
            )
            raise RuntimeError(detail)
        if not isinstance(result.structuredContent, dict):
            raise RuntimeError("MCP tool returned no structured content")
        return {"name": name, "result": result.structuredContent}

    @contextlib.asynccontextmanager
    async def _session(self, *, envelope: str | None, timeout_seconds: float):
        token = self._envelope.set(envelope)
        try:
            async with self._session_factory(
                endpoint_url=self.endpoint_url,
                http_client=self._get_client(),
                timeout_seconds=timeout_seconds,
                envelope=envelope,
            ) as session:
                await session.initialize()
                yield session
        finally:
            self._envelope.reset(token)

    @contextlib.asynccontextmanager
    async def _official_session(
        self,
        *,
        endpoint_url: str,
        http_client: Any,
        timeout_seconds: float,
        envelope: str | None,
    ):
        del timeout_seconds, envelope
        async with streamable_http_client(
            endpoint_url,
            http_client=http_client,
            terminate_on_close=False,
        ) as (read_stream, write_stream, _get_session_id):
            async with ClientSession(read_stream, write_stream) as session:
                yield session

    def _get_client(self) -> Any:
        if self._client is None:
            self._client = self._async_client_factory(
                timeout=httpx.Timeout(self.timeout_seconds),
                auth=_McpRequestAuth(self.internal_api_key, self._envelope),
            )
        return self._client

    def _tool_manifest(self, tool: Any) -> dict[str, Any]:
        meta = tool.meta if isinstance(getattr(tool, "meta", None), dict) else {}
        manifest = meta.get(MANIFEST_META_KEY)
        manifest = manifest if isinstance(manifest, dict) else {}
        converted = {
            "name": str(tool.name),
            "description": str(tool.description or ""),
            "inputSchema": dict(tool.inputSchema or {"type": "object"}),
            "admin": bool(manifest.get("admin")),
            "routes": list(manifest.get("routes") or []),
            "sideEffectType": manifest.get("side_effect_type"),
            "scopeRequirement": manifest.get("scope_requirement"),
            "timeoutMs": manifest.get("timeout_ms"),
            "identityKeys": list(manifest.get("identity_keys") or []),
            "secretInputKeys": list(manifest.get("secret_input_keys") or []),
            "secretOutputKeys": list(manifest.get("secret_output_keys") or []),
            "requiresSupervisorPermission": bool(manifest.get("requires_supervisor_permission")),
        }
        self._hidden_input_keys[converted["name"]] = {
            str(key)
            for key in [*converted["identityKeys"], *converted["secretInputKeys"]]
            if str(key).strip()
        }
        return converted

    def _sign(self, claims: dict[str, Any]) -> str:
        canonical = json.dumps(
            claims,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        )
        return hmac.new(
            self.call_signing_key.encode("utf-8"),
            canonical.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    def _configured_secret(self, value: str | None) -> bool:
        normalized = str(value or "").strip()
        return len(normalized) >= 32 and not normalized.upper().startswith("CHANGE_ME")
