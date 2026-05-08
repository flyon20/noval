from __future__ import annotations

from typing import Any

import httpx

from app.config import settings
from app.models.knowledge import BookCandidate, KnowledgeSource


class KnowledgeBackendClient:
    def __init__(self, *, base_url: str | None = None, internal_api_key: str | None = None) -> None:
        self.base_url = (base_url or settings.backend_base_url).rstrip("/")
        self.internal_api_key = internal_api_key if internal_api_key is not None else settings.backend_internal_api_key
        self.timeout_seconds = max(1, settings.backend_tool_timeout_millis / 1000)

    async def search_books(self, *, platform: str, keyword: str, limit: int) -> list[BookCandidate]:
        payload = {"platform": platform, "keyword": keyword, "limit": limit}
        data = await self._post_json("/internal/knowledge/books/search", payload)
        return [BookCandidate(**item) for item in self._unwrap_list(data)]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
    ) -> list[KnowledgeSource]:
        payload: dict[str, Any] = {"query": query, "limit": limit}
        if book_id is not None:
            payload["bookId"] = book_id
        if platform:
            payload["platform"] = platform
        if analysis_type:
            payload["analysisType"] = analysis_type
        data = await self._post_json("/internal/knowledge/search", payload)
        return [KnowledgeSource(**item) for item in self._unwrap_list(data)]

    async def _post_json(self, path: str, payload: dict[str, Any]) -> Any:
        headers = {"Content-Type": "application/json"}
        if self.internal_api_key:
            headers["X-Internal-Service-Token"] = self.internal_api_key
        timeout = httpx.Timeout(self.timeout_seconds)
        async with httpx.AsyncClient(base_url=self.base_url, timeout=timeout) as client:
            response = await client.post(path, json=payload, headers=headers)
            response.raise_for_status()
            return response.json()

    def _unwrap_list(self, payload: Any) -> list[dict[str, Any]]:
        if isinstance(payload, list):
            return [item for item in payload if isinstance(item, dict)]
        if isinstance(payload, dict):
            data = payload.get("data")
            if isinstance(data, list):
                return [item for item in data if isinstance(item, dict)]
        return []
