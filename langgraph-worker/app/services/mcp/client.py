from __future__ import annotations

from collections.abc import Callable
from typing import Any

import httpx

from app.config import settings


class McpClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        internal_api_key: str | None = None,
        async_client_factory: Callable[..., Any] | None = None,
    ) -> None:
        self.base_url = (base_url or getattr(settings, "mcp_base_url", "http://fastmcp-tools:7001")).rstrip("/")
        self.internal_api_key = internal_api_key if internal_api_key is not None else getattr(settings, "mcp_internal_api_key", "")
        self.timeout_seconds = max(1, int(getattr(settings, "mcp_timeout_millis", 30000)) / 1000)
        self._async_client_factory = async_client_factory or httpx.AsyncClient
        self._client: Any | None = None

    async def aclose(self) -> None:
        if self._client is None:
            return
        await self._client.aclose()
        self._client = None

    async def list_tools(self) -> dict[str, Any]:
        client = self._get_client()
        response = await client.get("/mcp/tools", headers=self._headers())
        response.raise_for_status()
        return response.json()

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, Any],
        timeout: float | None = None,
        route: str | None = None,
    ) -> dict[str, Any]:
        client = self._get_client(timeout=timeout)
        payload: dict[str, Any] = {"name": name, "arguments": arguments}
        if route:
            payload["route"] = route
        response = await client.post("/mcp/call", json=payload, headers=self._headers())
        response.raise_for_status()
        return response.json()

    def _get_client(self, timeout: float | None = None) -> Any:
        if self._client is None:
            self._client = self._async_client_factory(base_url=self.base_url, timeout=httpx.Timeout(timeout or self.timeout_seconds))
        return self._client

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.internal_api_key:
            headers["X-Internal-Service-Token"] = self.internal_api_key
        return headers
