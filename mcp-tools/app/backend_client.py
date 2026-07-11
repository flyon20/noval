from __future__ import annotations

from typing import Any

import httpx

from app.config import settings


class BackendClient:
    def __init__(self, *, async_client_factory: Any | None = None) -> None:
        self._async_client_factory = async_client_factory or httpx.AsyncClient

    async def post(self, path: str, payload: dict[str, Any]) -> Any:
        headers = {"Content-Type": "application/json"}
        if settings.backend_internal_api_key:
            headers["X-Internal-Service-Token"] = settings.backend_internal_api_key
        timeout = httpx.Timeout(max(1, settings.backend_timeout_millis / 1000))
        async with self._async_client_factory(base_url=settings.backend_base_url, timeout=timeout) as client:
            response = await client.post(path, json=payload, headers=headers)
            response.raise_for_status()
            return response.json()
