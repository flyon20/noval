from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.config import settings
from app import main as worker_main


class WorkerHealthTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_values = {
            "internal_api_key": settings.internal_api_key,
            "backend_internal_api_key": settings.backend_internal_api_key,
            "mcp_base_url": settings.mcp_base_url,
            "mcp_internal_api_key": settings.mcp_internal_api_key,
            "mcp_call_signing_key": settings.mcp_call_signing_key,
        }
        settings.internal_api_key = "langgraph-test-key-123456"
        settings.backend_internal_api_key = "backend-test-key-123456"
        settings.mcp_base_url = "http://mcp.test"
        settings.mcp_internal_api_key = "mcp-worker-test-internal-key-1234567890"
        settings.mcp_call_signing_key = "mcp-signing-key-12345678901234567890"

    def test_health_is_degraded_when_mcp_signing_key_is_too_short(self) -> None:
        settings.mcp_call_signing_key = "short-signing-key"

        with patch.object(worker_main, "_READINESS_CACHE", None, create=True):
            with TestClient(worker_main.app) as client:
                response = client.get("/health")

        self.assertEqual(503, response.status_code)
        self.assertIn(
            "MCP_CALL_SIGNING_KEY",
            response.json()["data"]["checks"]["configuration"]["missing"],
        )

    def tearDown(self) -> None:
        for name, value in self.original_values.items():
            setattr(settings, name, value)

    def test_health_is_degraded_when_mcp_security_config_is_missing(self) -> None:
        settings.mcp_internal_api_key = ""

        with patch.object(worker_main, "_READINESS_CACHE", None, create=True):
            with TestClient(worker_main.app) as client:
                response = client.get("/health")

        self.assertEqual(503, response.status_code)
        self.assertEqual("DEGRADED", response.json()["data"]["status"])
        self.assertIn("MCP_INTERNAL_API_KEY", response.json()["data"]["checks"]["configuration"]["missing"])

    def test_health_is_degraded_when_mcp_service_is_unavailable(self) -> None:
        probe = AsyncMock(return_value=(False, "connection failed"))

        with (
            patch.object(worker_main, "_READINESS_CACHE", None, create=True),
            patch.object(worker_main, "_probe_mcp_readiness", probe, create=True),
            TestClient(worker_main.app) as client,
        ):
            response = client.get("/health")

        self.assertEqual(503, response.status_code)
        self.assertEqual("DEGRADED", response.json()["data"]["status"])
        self.assertEqual("DOWN", response.json()["data"]["checks"]["mcp"]["status"])

    def test_health_caches_mcp_probe_for_short_ttl(self) -> None:
        probe = AsyncMock(return_value=(True, None))

        with (
            patch.object(worker_main, "_READINESS_CACHE", None, create=True),
            patch.object(worker_main, "_probe_mcp_readiness", probe, create=True),
            TestClient(worker_main.app) as client,
        ):
            first = client.get("/health")
            second = client.get("/health")

        self.assertEqual(200, first.status_code)
        self.assertEqual(200, second.status_code)
        self.assertEqual(1, probe.await_count)

    def test_health_reflects_backend_failure_reported_through_mcp(self) -> None:
        probe = AsyncMock(return_value=(False, "backend unavailable"))

        with (
            patch.object(worker_main, "_READINESS_CACHE", None, create=True),
            patch.object(worker_main, "_probe_mcp_readiness", probe, create=True),
            TestClient(worker_main.app) as client,
        ):
            response = client.get("/health")

        self.assertEqual(503, response.status_code)
        self.assertEqual(
            "backend unavailable",
            response.json()["data"]["checks"]["mcp"]["reason"],
        )


if __name__ == "__main__":
    unittest.main()
