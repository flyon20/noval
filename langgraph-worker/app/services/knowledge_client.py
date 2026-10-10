from __future__ import annotations

from collections.abc import Callable
import json
from typing import Any

import httpx

from app.config import settings
from app.models.knowledge import BookCandidate, BookResearchPack, KnowledgeSource, RankLookupResult, RankResearchPack
from app.services.harness.provider_dispatch_scope import ProviderDispatch
from app.services.harness.tool_ledger import current_run_tool_ledger


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
        user_id: int | None = None,
    ) -> list[KnowledgeSource]:
        payload: dict[str, Any] = {
            "userId": self._trusted_user_id(user_id),
            "query": query,
            "limit": limit,
        }
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
        snapshot_start_date: str | None = None,
        snapshot_end_date: str | None = None,
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
        if snapshot_start_date:
            payload["snapshotStartDate"] = snapshot_start_date
        if snapshot_end_date:
            payload["snapshotEndDate"] = snapshot_end_date
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
        user_id: int | None = None,
    ) -> BookResearchPack:
        payload: dict[str, Any] = {
            "userId": self._trusted_user_id(user_id),
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
        snapshot_start_date: str | None = None,
        snapshot_end_date: str | None = None,
        require_snapshot_time: bool | None = None,
        user_id: int | None = None,
    ) -> RankResearchPack:
        payload: dict[str, Any] = {
            "userId": self._trusted_user_id(user_id),
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
        if snapshot_start_date:
            payload["snapshotStartDate"] = snapshot_start_date
        if snapshot_end_date:
            payload["snapshotEndDate"] = snapshot_end_date
        if require_snapshot_time is not None:
            payload["requireSnapshotTime"] = require_snapshot_time
        data = await self._post_json("/internal/knowledge/research-pack/rank", payload)
        return RankResearchPack(**self._unwrap_object(data))

    async def refresh_rank_board(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_fetch_count: int | None = None,
        refresh_mode: str | None = None,
        force_reason: str | None = None,
        user_id: int | None = None,
        project_id: int | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"platform": platform}
        if user_id is not None:
            payload["userId"] = user_id
        if project_id is not None:
            payload["projectId"] = project_id
        if channel_code:
            payload["channelCode"] = channel_code
        if board_code:
            payload["boardCode"] = board_code
        if category:
            payload["category"] = category
        if rank_fetch_count is not None:
            payload["rankFetchCount"] = rank_fetch_count
        if refresh_mode:
            payload["refreshMode"] = refresh_mode
        if force_reason:
            payload["forceReason"] = force_reason
        if idempotency_key:
            payload["idempotencyKey"] = idempotency_key
        data = await self._post_json("/internal/knowledge/rank/refresh", payload)
        return self._unwrap_object(data)

    async def get_project_memory(self, *, project_id: int, user_id: int) -> dict[str, Any]:
        payload = {"userId": user_id}
        data = await self._post_json(f"/internal/knowledge/projects/{project_id}/memory", payload)
        return self._unwrap_object(data)

    async def read_conversation_summary(self, *, user_id: int, conversation_id: str) -> dict[str, Any]:
        payload = {"userId": user_id, "conversationId": conversation_id}
        data = await self._post_json("/internal/knowledge/conversation-summary/read", payload)
        return self._unwrap_object(data)

    async def search_memory(
        self,
        *,
        user_id: int,
        project_id: int | None = None,
        scope: str | None = None,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        payload: dict[str, Any] = {"userId": user_id, "limit": limit}
        if project_id is not None:
            payload["projectId"] = project_id
        if scope:
            payload["scope"] = scope
        data = await self._post_json("/internal/knowledge/memory/search", payload)
        return self._unwrap_list(data)

    async def resolve_project_work(
        self,
        *,
        user_id: int,
        project_id: int | None = None,
        work_id: int | None = None,
        query: str | None = None,
        limit: int = 10,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"userId": user_id, "limit": limit}
        if project_id is not None:
            payload["projectId"] = project_id
        if work_id is not None:
            payload["workId"] = work_id
        if query:
            payload["query"] = query
        data = await self._post_json("/internal/knowledge/projects/resolve", payload)
        return self._unwrap_object(data)

    async def retrieve_project_knowledge(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
        query: str,
        intent: str | None = None,
        entities: list[str] | None = None,
        chapter_from: int | None = None,
        chapter_to: int | None = None,
        channels: list[str] | None = None,
        filters: dict[str, Any] | None = None,
        weights: dict[str, float] | None = None,
        limit: int = 10,
        deep: bool = False,
        graph_budget_millis: int = 300,
        timeout_millis: int | None = None,
        rerank_policy: str = "intent_aware",
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "userId": user_id,
            "projectId": project_id,
            "workId": work_id,
            "query": query,
            "limit": limit,
            "deep": bool(deep),
            "channels": list(channels or []),
            "filters": dict(filters or {}),
            "weights": dict(weights or {}),
            "graphBudgetMillis": graph_budget_millis,
            "rerankPolicy": rerank_policy,
        }
        if intent:
            payload["intent"] = intent
        if entities:
            payload["entities"] = list(entities)
        if chapter_from is not None:
            payload["chapterFrom"] = chapter_from
        if chapter_to is not None:
            payload["chapterTo"] = chapter_to
        if timeout_millis is not None:
            payload["timeoutMillis"] = timeout_millis
        data = await self._post_json("/internal/knowledge/projects/retrieval", payload)
        return self._unwrap_object(data)

    async def list_project_foreshadowings(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
        status: str | None = None,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        payload: dict[str, Any] = {
            "userId": user_id,
            "projectId": project_id,
            "workId": work_id,
            "limit": limit,
        }
        if status:
            payload["status"] = status
        data = await self._post_json("/internal/knowledge/projects/foreshadowings/list", payload)
        return self._unwrap_list(data)

    async def aggregate_project_foreshadowings(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
    ) -> dict[str, Any]:
        payload = {
            "userId": self._trusted_user_id(user_id),
            "projectId": project_id,
            "workId": work_id,
        }
        data = await self._post_json("/internal/knowledge/projects/foreshadowings/aggregate", payload)
        return self._unwrap_object(data)

    async def lookup_project_timeline(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
        query: str | None = None,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        payload = self._project_lookup_payload(user_id=user_id, project_id=project_id, work_id=work_id, query=query, limit=limit)
        data = await self._post_json("/internal/knowledge/projects/timeline/lookup", payload)
        return self._unwrap_list(data)

    async def lookup_project_character_states(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
        query: str | None = None,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        payload = self._project_lookup_payload(user_id=user_id, project_id=project_id, work_id=work_id, query=query, limit=limit)
        data = await self._post_json("/internal/knowledge/projects/character-states/lookup", payload)
        return self._unwrap_list(data)

    async def lookup_project_world_rules(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
        query: str | None = None,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        payload = self._project_lookup_payload(user_id=user_id, project_id=project_id, work_id=work_id, query=query, limit=limit)
        data = await self._post_json("/internal/knowledge/projects/world-rules/lookup", payload)
        return self._unwrap_list(data)

    async def create_memory_candidate(
        self,
        *,
        user_id: int,
        project_id: int | None,
        conversation_id: str | None,
        scope: str,
        memory_type: str,
        content: str,
        summary: str | None,
        confidence: float,
        source_trace_id: str | None,
        fact_key: str | None = None,
        candidate_key: str | None = None,
        provenance_json: str | None = None,
        evidence_json: str | None = None,
        extractor_version: str | None = None,
        ttl_days: int = 30,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "userId": user_id,
            "scope": scope,
            "memoryType": memory_type,
            "content": content,
            "confidence": confidence,
            "ttlDays": ttl_days,
        }
        if project_id is not None:
            payload["projectId"] = project_id
        if conversation_id:
            payload["conversationId"] = conversation_id
        if summary:
            payload["summary"] = summary
        if source_trace_id:
            payload["sourceTraceId"] = source_trace_id
        if fact_key:
            payload["factKey"] = fact_key
        if candidate_key:
            payload["candidateKey"] = candidate_key
        if provenance_json:
            payload["provenanceJson"] = provenance_json
        if evidence_json:
            payload["evidenceJson"] = evidence_json
        if extractor_version:
            payload["extractorVersion"] = extractor_version
        data = await self._post_json("/internal/knowledge/memory/candidates", payload)
        return self._unwrap_object(data)

    async def get_agent_runtime_config(self) -> dict[str, Any]:
        data = await self._get_json("/internal/knowledge/agent/runtime-config")
        return self._unwrap_object(data)

    async def resolve_provider_dispatch(
        self,
        profile_key: str,
        profile_version: str,
    ) -> ProviderDispatch:
        normalized_key = str(profile_key or "").strip()
        normalized_version = str(profile_version or "").strip()
        if not normalized_key:
            raise ValueError("provider profile key is required")
        if not normalized_version:
            raise ValueError("provider profile version is required")
        data = await self._post_json(
            "/internal/knowledge/agent/provider-dispatch/resolve",
            {
                "profileKey": normalized_key,
                "profileVersion": normalized_version,
            },
        )
        return ProviderDispatch.from_payload(
            self._unwrap_object(data),
            expected_profile_key=normalized_key,
            expected_profile_version=normalized_version,
        )

    async def report_provider_routing_outcome(self, outcome: dict[str, Any]) -> None:
        profile_key = str(outcome.get("profileKey") or "").strip()
        profile_version = str(outcome.get("profileVersion") or "").strip()
        outcome_name = str(outcome.get("outcome") or "").strip().upper()
        failure_class = str(outcome.get("failureClass") or "").strip().upper()
        switched = outcome.get("switched")
        if not profile_key or not profile_version:
            raise ValueError("provider routing outcome identity is required")
        if outcome_name not in {"SUCCEEDED", "TRANSIENT_FAILURE"}:
            raise ValueError("provider routing outcome is invalid")
        if type(switched) is not bool:
            raise ValueError("provider routing outcome switched must be boolean")
        payload: dict[str, Any] = {
            "profileKey": profile_key,
            "profileVersion": profile_version,
            "outcome": outcome_name,
            "switched": switched,
        }
        if failure_class:
            payload["failureClass"] = failure_class
        await self._post_json("/internal/knowledge/agent/provider-routing/outcome", payload)

    async def get_agent_expert_profiles(self) -> list[dict[str, Any]]:
        data = await self._get_json("/internal/knowledge/agent/experts")
        return self._unwrap_list(data)

    async def get_runtime_skills(self) -> list[dict[str, Any]]:
        data = await self._get_json("/internal/knowledge/runtime-skills")
        return self._unwrap_list(data)

    async def post_agent_telemetry(
        self,
        *,
        trace_id: str,
        cache_events: list[dict[str, Any]] | None = None,
        token_metrics: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        payload = {
            "traceId": trace_id,
            "cacheEvents": list(cache_events or []),
            "tokenMetrics": list(token_metrics or []),
        }
        data = await self._post_json("/internal/knowledge/agent/telemetry", payload)
        return self._unwrap_object(data)

    async def append_semantic_checkpoint(
        self,
        *,
        run_id: str,
        user_id: int,
        event_type: str,
        event_idempotency_key: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        data = await self._post_json(
            "/internal/knowledge/chat-runs/semantic-checkpoints",
            {
                "runId": str(run_id).strip(),
                "userId": self._trusted_user_id(user_id),
                "eventType": str(event_type).strip(),
                "eventIdempotencyKey": str(event_idempotency_key).strip(),
                "payload": dict(payload),
            },
        )
        return self._normalize_semantic_checkpoint(self._unwrap_object(data))

    async def list_semantic_checkpoints(
        self,
        *,
        run_id: str,
        user_id: int,
        after_sequence: int = 0,
        limit: int = 500,
    ) -> list[dict[str, Any]]:
        data = await self._post_json(
            "/internal/knowledge/chat-runs/semantic-checkpoints/query",
            {
                "runId": str(run_id).strip(),
                "userId": self._trusted_user_id(user_id),
                "afterSequence": max(0, int(after_sequence)),
                "limit": max(1, min(500, int(limit))),
            },
        )
        return [self._normalize_semantic_checkpoint(item) for item in self._unwrap_list(data)]

    def _normalize_semantic_checkpoint(self, event: dict[str, Any]) -> dict[str, Any]:
        normalized = dict(event)
        payload = normalized.get("payload")
        if isinstance(payload, str):
            try:
                decoded = json.loads(payload)
            except (TypeError, ValueError, json.JSONDecodeError):
                decoded = {}
            normalized["payload"] = decoded if isinstance(decoded, dict) else {}
        elif not isinstance(payload, dict):
            normalized["payload"] = {}
        self._validate_semantic_event_envelope(normalized)
        return normalized

    @staticmethod
    def _validate_semantic_event_envelope(event: dict[str, Any]) -> None:
        payload = event.get("payload")
        envelope = payload.get("_event") if isinstance(payload, dict) else None
        if envelope is None:
            return
        if not isinstance(envelope, dict):
            raise ValueError("semantic checkpoint _event must be an object")
        schema_version = envelope.get("schemaVersion")
        if isinstance(schema_version, bool) or schema_version != 1:
            raise ValueError("unsupported semantic checkpoint event schema")
        if str(envelope.get("visibility") or "").strip() != "internal":
            raise ValueError("semantic checkpoint visibility must be internal")

        comparisons = (
            ("eventId", "eventId"),
            ("runId", "runId"),
            ("sequence", "sequenceNo"),
            ("eventType", "eventType"),
            ("eventIdempotencyKey", "eventIdempotencyKey"),
        )
        for envelope_key, event_key in comparisons:
            envelope_value = envelope.get(envelope_key)
            event_value = event.get(event_key)
            if envelope_value is None or str(envelope_value).strip() == "":
                raise ValueError(f"semantic checkpoint {envelope_key} is required")
            if event_value is not None and str(envelope_value) != str(event_value):
                raise ValueError(f"semantic checkpoint {envelope_key} mismatch")

    def _trusted_user_id(self, supplied_user_id: Any | None) -> int:
        supplied = self._positive_int(supplied_user_id)
        ledger = current_run_tool_ledger()
        trusted = self._positive_int(ledger.identity.userId) if ledger is not None else None
        if trusted is not None:
            if supplied is not None and supplied != trusted:
                raise ValueError("user scope mismatch")
            return trusted
        if supplied is None:
            raise ValueError("user scope required")
        return supplied

    def _positive_int(self, value: Any | None) -> int | None:
        if value is None or isinstance(value, bool):
            return None
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None

    async def _post_json(self, path: str, payload: dict[str, Any]) -> Any:
        headers = {"Content-Type": "application/json"}
        if self.internal_api_key:
            headers["X-Internal-Service-Token"] = self.internal_api_key
        client = self._get_client()
        response = await client.post(path, json=payload, headers=headers)
        response.raise_for_status()
        return response.json()

    async def _get_json(self, path: str) -> Any:
        headers: dict[str, str] = {}
        if self.internal_api_key:
            headers["X-Internal-Service-Token"] = self.internal_api_key
        client = self._get_client()
        response = await client.get(path, headers=headers)
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

    def _project_lookup_payload(
        self,
        *,
        user_id: int,
        project_id: int,
        work_id: int,
        query: str | None,
        limit: int,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "userId": user_id,
            "projectId": project_id,
            "workId": work_id,
            "limit": limit,
        }
        if query:
            payload["query"] = query
        return payload
