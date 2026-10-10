from __future__ import annotations

import contextlib
import json
from collections.abc import Callable
from typing import Any

from fastapi import HTTPException
from mcp.server.fastmcp import Context, FastMCP
from mcp.server.fastmcp.exceptions import ToolError
from mcp.server.fastmcp.tools import Tool
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import Field
from starlette.datastructures import Headers
from starlette.types import Receive, Scope, Send

from app.backend_client import BackendClient
from app.registry import ToolRegistry
from app.security import SignedCallEnvelope, internal_service_token_valid, verify_standard_signed_call


MCP_PATH = "/mcp/v1"
ENVELOPE_HEADER = "x-noval-mcp-envelope"


async def _unused_tool(context: Context, arguments: dict[str, Any]) -> Any:
    del context
    return arguments


_TOOL_TEMPLATE = Tool.from_function(
    _unused_tool,
    name="noval.registry.dispatch",
    structured_output=False,
)


class GovernedRegistryTool(Tool):
    registry: Any = Field(exclude=True)
    backend_client_provider: Any = Field(exclude=True)

    async def run(
        self,
        arguments: dict[str, Any],
        context: Context | None = None,
        convert_result: bool = False,
    ) -> Any:
        del convert_result
        if context is None:
            raise ToolError("MCP request context is unavailable")
        try:
            request = context.request_context.request
            raw_envelope = request.headers.get(ENVELOPE_HEADER)
            envelope = _parse_envelope(raw_envelope)
            permissions, signed_arguments, route = verify_standard_signed_call(
                name=self.name,
                public_arguments=arguments or {},
                envelope=envelope,
                hidden_keys=self.registry.hidden_input_keys(self.name),
            )
            result = await self.registry.call(
                name=self.name,
                arguments=signed_arguments,
                backend_client=self.backend_client_provider(),
                route=route,
                supervisor_permissions=permissions,
            )
        except HTTPException as exc:
            raise ToolError(str(exc.detail)) from exc
        if isinstance(result, dict):
            return result
        return {"result": result}


def _parse_envelope(raw: str | None) -> SignedCallEnvelope:
    if not raw:
        raise HTTPException(status_code=401, detail="missing MCP call signature envelope")
    try:
        return SignedCallEnvelope.model_validate_json(raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=401, detail="invalid MCP call signature envelope") from exc


def _transport_security() -> TransportSecuritySettings:
    return TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=[
            "fastmcp-tools",
            "fastmcp-tools:*",
            "127.0.0.1:*",
            "localhost:*",
            "testserver",
            "testserver:*",
        ],
        allowed_origins=[
            "http://127.0.0.1:*",
            "http://localhost:*",
            "http://testserver:*",
        ],
    )


async def _send_json(send: Send, status_code: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    await send({
        "type": "http.response.start",
        "status": status_code,
        "headers": [
            (b"content-type", b"application/json; charset=utf-8"),
            (b"content-length", str(len(body)).encode("ascii")),
        ],
    })
    await send({"type": "http.response.body", "body": body})


class FastMcpRuntime:
    def __init__(
        self,
        registry: ToolRegistry,
        backend_client_provider: Callable[[], BackendClient],
    ) -> None:
        self.registry = registry
        self.backend_client_provider = backend_client_provider
        tools = [
            self._governed_tool(schema)
            for schema in registry.list_standard_tools()
        ]
        self.server = FastMCP(
            "noval-mcp-tools",
            instructions="Noval governed web-novel research and project knowledge tools.",
            tools=tools,
            streamable_http_path="/",
            json_response=True,
            stateless_http=True,
            transport_security=_transport_security(),
        )
        self._app = self.server.streamable_http_app()

    def _governed_tool(self, schema: dict[str, Any]) -> GovernedRegistryTool:
        return GovernedRegistryTool(
            fn=_TOOL_TEMPLATE.fn,
            name=str(schema["name"]),
            description=str(schema.get("description") or ""),
            parameters=dict(schema.get("inputSchema") or {"type": "object"}),
            fn_metadata=_TOOL_TEMPLATE.fn_metadata,
            is_async=True,
            context_kwarg="context",
            meta=dict(schema.get("_meta") or {}),
            registry=self.registry,
            backend_client_provider=self.backend_client_provider,
        )

    @contextlib.asynccontextmanager
    async def lifespan(self):
        async with self._app.router.lifespan_context(self._app):
            yield

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            headers = Headers(scope=scope)
            if not internal_service_token_valid(headers.get("x-internal-service-token")):
                await _send_json(send, 401, {"detail": "invalid internal service token"})
                return
        await self._app(scope, receive, send)
