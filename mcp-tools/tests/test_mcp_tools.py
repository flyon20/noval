from __future__ import annotations

import asyncio
import json
import unittest
import uuid

from fastapi import HTTPException
from fastapi.responses import JSONResponse

from app import main, security
from app.backend_client import BackendClient
from app.config import settings


class FakeResponse:
    def __init__(self, payload) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self):
        return self._payload


class FakeAsyncClient:
    calls: list[dict] = []

    def __init__(self, **kwargs) -> None:
        self.kwargs = kwargs

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def post(self, path: str, *, json: dict, headers: dict):
        self.__class__.calls.append({"method": "POST", "path": path, "json": json, "headers": headers})
        return FakeResponse({"code": 200, "data": {"ok": True}})

    async def get(self, path: str, *, headers: dict):
        self.__class__.calls.append({"method": "GET", "path": path, "headers": headers})
        return FakeResponse({"code": 200, "data": {"status": "UP"}})


class FakeDurableRedis:
    def __init__(self, *, ready: bool = True) -> None:
        self.values: dict[str, str] = {}
        self.ready = ready

    async def ping(self):
        if not self.ready:
            raise ConnectionError("redis unavailable")
        return True

    async def get(self, key: str):
        return self.values.get(key)

    async def set(self, key: str, value: str, *, nx: bool = False, ex: int | None = None):
        del ex
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    async def delete(self, key: str):
        self.values.pop(key, None)


class FakeToolBackend:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.refresh_calls: list[dict] = []

    async def post(self, path: str, payload: dict) -> dict:
        self.calls.append({"path": path, "payload": payload})
        return {"items": [{"rankNo": 1}]}

    async def post_governed_rank_refresh(self, payload: dict) -> dict:
        self.refresh_calls.append(payload)
        return {"status": "refreshed", "boardCode": payload.get("boardCode")}


def response_payload(result) -> dict:
    if isinstance(result, JSONResponse):
        return json.loads(result.body)
    return result


class McpToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_mcp_key = settings.mcp_internal_api_key
        self.original_signing_key = settings.mcp_call_signing_key
        self.original_backend_attestation_key = settings.mcp_backend_attestation_key
        self.original_backend_key = settings.backend_internal_api_key
        self.original_redis_host = settings.redis_host
        self.original_nonce_guard = security._NONCE_REPLAY_GUARD
        self.original_backend_client = main.backend_client
        self.original_idempotency_redis = main.tool_registry._idempotency_redis
        settings.mcp_internal_api_key = "fastmcp-test-internal-key-1234567890"
        settings.mcp_call_signing_key = "mcp-signing-test-key-123456789012345"
        settings.mcp_backend_attestation_key = "mcp-backend-attestation-test-key-1234567890"
        settings.backend_internal_api_key = "backend-test-key"
        settings.redis_host = "redis"
        security._NONCE_REPLAY_GUARD = security._BoundedMemoryNonceReplayGuard(max_entries=1000)
        main.tool_registry._idempotency_redis = FakeDurableRedis()
        main.tool_registry._idempotency_completed.clear()
        FakeAsyncClient.calls = []
        main.backend_client = BackendClient(async_client_factory=lambda **kwargs: FakeAsyncClient(**kwargs))

    def tearDown(self) -> None:
        settings.mcp_internal_api_key = self.original_mcp_key
        settings.mcp_call_signing_key = self.original_signing_key
        settings.mcp_backend_attestation_key = self.original_backend_attestation_key
        settings.backend_internal_api_key = self.original_backend_key
        settings.redis_host = self.original_redis_host
        security._NONCE_REPLAY_GUARD = self.original_nonce_guard
        main.backend_client = self.original_backend_client
        main.tool_registry._idempotency_redis = self.original_idempotency_redis
        main.tool_registry._idempotency_completed.clear()

    def test_health_fails_closed_for_invalid_security_redis_or_backend(self) -> None:
        settings.mcp_call_signing_key = "CHANGE_ME_WITH_A_RANDOM_MCP_CALL_SIGNING_KEY"
        self.assertEqual("DEGRADED", response_payload(asyncio.run(main.health()))["status"])
        settings.mcp_call_signing_key = "mcp-signing-test-key-123456789012345"
        main.tool_registry._idempotency_redis = FakeDurableRedis(ready=False)
        self.assertEqual("DEGRADED", response_payload(asyncio.run(main.health()))["status"])

        class UnavailableBackendClient:
            async def health(self) -> bool:
                return False

        main.tool_registry._idempotency_redis = FakeDurableRedis()
        main.backend_client = UnavailableBackendClient()
        self.assertEqual("backend unavailable", response_payload(asyncio.run(main.health()))["reason"])

    def test_health_reports_up_with_security_redis_and_backend(self) -> None:
        self.assertEqual({"status": "UP"}, asyncio.run(main.health()))
        self.assertEqual("GET", FakeAsyncClient.calls[0]["method"])
        self.assertEqual("/api/system/health", FakeAsyncClient.calls[0]["path"])

    def test_historical_rank_lookup_serializes_exact_date_range(self) -> None:
        backend = FakeToolBackend()
        result = asyncio.run(main.tool_registry.call(
            name="rank.lookup",
            arguments={
                "platform": "fanqie",
                "allowHistorical": True,
                "snapshotStartDate": "2026-08-01",
                "snapshotEndDate": "2026-08-07",
                "userId": 7,
            },
            backend_client=backend,
            route="market_scan",
        ))
        self.assertEqual({"items": [{"rankNo": 1}]}, result)
        self.assertEqual("2026-08-01", backend.calls[0]["payload"]["snapshotStartDate"])
        self.assertEqual("2026-08-07", backend.calls[0]["payload"]["snapshotEndDate"])

    def test_rank_lookup_route_and_date_validation_fail_closed(self) -> None:
        backend = FakeToolBackend()
        with self.assertRaises(HTTPException) as route_denied:
            asyncio.run(main.tool_registry.call(
                name="rank.lookup",
                arguments={"platform": "fanqie", "userId": 7},
                backend_client=backend,
                route="project_creation",
            ))
        with self.assertRaises(HTTPException) as invalid_range:
            asyncio.run(main.tool_registry.call(
                name="rank.lookup",
                arguments={
                    "platform": "fanqie",
                    "allowHistorical": True,
                    "snapshotStartDate": "2026-08-07",
                    "snapshotEndDate": "2026-08-01",
                    "userId": 7,
                },
                backend_client=backend,
                route="market_scan",
            ))
        self.assertEqual(403, route_denied.exception.status_code)
        self.assertEqual(400, invalid_range.exception.status_code)

    def test_rank_refresh_requires_permission_and_reuses_durable_result(self) -> None:
        backend = FakeToolBackend()
        arguments = {
            "platform": "fanqie",
            "boardCode": "urban-brain",
            "forceReason": "refresh stale board",
            "idempotencyKey": f"refresh-{uuid.uuid4().hex}",
            "userId": 7,
        }
        with self.assertRaises(HTTPException) as denied:
            asyncio.run(main.tool_registry.call(
                name="rank.refresh",
                arguments=arguments,
                backend_client=backend,
                route="market_scan",
            ))
        first = asyncio.run(main.tool_registry.call(
            name="rank.refresh",
            arguments=arguments,
            backend_client=backend,
            route="market_scan",
            supervisor_permissions={"rank.refresh"},
        ))
        second = asyncio.run(main.tool_registry.call(
            name="rank.refresh",
            arguments=arguments,
            backend_client=backend,
            route="market_scan",
            supervisor_permissions={"rank.refresh"},
        ))
        self.assertEqual(403, denied.exception.status_code)
        self.assertEqual(first, second)
        self.assertEqual(1, len(backend.refresh_calls))


if __name__ == "__main__":
    unittest.main()
