from __future__ import annotations

from collections.abc import Callable
from typing import Any

import httpx

from app.config import settings
from app.models.knowledge import BookCandidate, BookResearchPack, KnowledgeSource, RankLookupResult, RankResearchPack


class KnowledgeBackendClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        internal_api_key: str | None = None,
        async_client_factory: Callable[..., Any] | None = None,
    ) -> None:
        self.base_url = (base_url or settings.backend_base_url).rstrip("/")
        self.internal_api_key = internal_api_key if internal_api_key is not None else settings.backend_internal_api_key
        self.timeout_seconds = max(1, settings.backend_tool_timeout_millis / 1000)
        self._async_client_factory = async_client_factory or httpx.AsyncClient
        self._client: Any | None = None

    async def aclose(self) -> None:
        if self._client is None:
            return
        await self._client.aclose()
        self._client = None

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
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        payload: dict[str, Any] = {"query": query, "limit": limit}
        if book_id is not None:
            payload["bookId"] = book_id
        if platform:
            payload["platform"] = platform
        if analysis_type:
            payload["analysisType"] = analysis_type
        if source_type:
            payload["sourceType"] = source_type
        data = await self._post_json("/internal/knowledge/search", payload)
        return [KnowledgeSource(**item) for item in self._unwrap_list(data)]

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> list[RankLookupResult]:
        payload: dict[str, Any] = {"platform": platform, "limit": limit}
        if channel_code:
            payload["channelCode"] = channel_code
        if board_code:
            payload["boardCode"] = board_code
        if category:
            payload["category"] = category
        if rank_no is not None:
            payload["rankNo"] = rank_no
        if freshness:
            payload["freshness"] = freshness
        if allow_historical is not None:
            payload["allowHistorical"] = allow_historical
        if time_window_days is not None:
            payload["timeWindowDays"] = time_window_days
        if require_snapshot_time is not None:
            payload["requireSnapshotTime"] = require_snapshot_time
        data = await self._post_json("/internal/knowledge/rank/lookup", payload)
        return [RankLookupResult(**item) for item in self._unwrap_list(data)]

    async def get_book_research_pack(
        self,
        *,
        platform: str,
        book_id: int | None = None,
        book_name: str | None = None,
        chapter_limit: int = 3,
        analysis_limit: int = 3,
    ) -> BookResearchPack:
        payload: dict[str, Any] = {
            "platform": platform,
            "chapterLimit": chapter_limit,
            "analysisLimit": analysis_limit,
        }
        if book_id is not None:
            payload["bookId"] = book_id
        if book_name:
            payload["bookName"] = book_name
        data = await self._post_json("/internal/knowledge/research-pack/book", payload)
        return BookResearchPack(**self._unwrap_object(data))

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> RankResearchPack:
        payload: dict[str, Any] = {
            "platform": platform,
            "limit": limit,
            "chapterLimitPerBook": chapter_limit_per_book,
        }
        if channel_code:
            payload["channelCode"] = channel_code
        if board_code:
            payload["boardCode"] = board_code
        if category:
            payload["category"] = category
        if rank_no is not None:
            payload["rankNo"] = rank_no
        if freshness:
            payload["freshness"] = freshness
        if allow_historical is not None:
            payload["allowHistorical"] = allow_historical
        if time_window_days is not None:
            payload["timeWindowDays"] = time_window_days
        if require_snapshot_time is not None:
            payload["requireSnapshotTime"] = require_snapshot_time
        data = await self._post_json("/internal/knowledge/research-pack/rank", payload)
        return RankResearchPack(**self._unwrap_object(data))

    async def get_project_memory(self, *, project_id: int, user_id: int) -> dict[str, Any]:
        payload = {"userId": user_id}
        data = await self._post_json(f"/internal/knowledge/projects/{project_id}/memory", payload)
        return self._unwrap_object(data)

    async def _post_json(self, path: str, payload: dict[str, Any]) -> Any:
        headers = {"Content-Type": "application/json"}
        if self.internal_api_key:
            headers["X-Internal-Service-Token"] = self.internal_api_key
        client = self._get_client()
        response = await client.post(path, json=payload, headers=headers)
        response.raise_for_status()
        return response.json()

    def _get_client(self) -> Any:
        if self._client is None:
            timeout = httpx.Timeout(self.timeout_seconds)
            self._client = self._async_client_factory(base_url=self.base_url, timeout=timeout)
        return self._client

    def _unwrap_list(self, payload: Any) -> list[dict[str, Any]]:
        if isinstance(payload, list):
            return [item for item in payload if isinstance(item, dict)]
        if isinstance(payload, dict):
            data = payload.get("data")
            if isinstance(data, list):
                return [item for item in data if isinstance(item, dict)]
        return []

    def _unwrap_object(self, payload: Any) -> dict[str, Any]:
        if isinstance(payload, dict):
            data = payload.get("data")
            if isinstance(data, dict):
                return data
            return payload
        return {}
