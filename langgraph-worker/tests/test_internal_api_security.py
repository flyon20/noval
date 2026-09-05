from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.config import settings
from app import main as worker_main


class InternalApiSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        self.original_backend_internal_api_key = settings.backend_internal_api_key
        self.original_mcp_internal_api_key = settings.mcp_internal_api_key
        self.original_mcp_call_signing_key = settings.mcp_call_signing_key
        settings.internal_api_key = "langgraph-test-key-123456"
        settings.backend_internal_api_key = "backend-test-key-123456"
        settings.mcp_internal_api_key = "mcp-worker-test-internal-key-1234567890"
        settings.mcp_call_signing_key = "mcp-signing-key-12345678901234567890"

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key
        settings.backend_internal_api_key = self.original_backend_internal_api_key
        settings.mcp_internal_api_key = self.original_mcp_internal_api_key
        settings.mcp_call_signing_key = self.original_mcp_call_signing_key

    def test_should_reject_internal_analysis_without_token(self) -> None:
        with TestClient(worker_main.app) as client:
            response = client.post(
                "/internal/analysis/run",
                json={
                    "taskId": "t1",
                    "agentType": "deconstruct",
                    "promptConfig": {},
                    "sourcePayload": {},
                    "limits": {},
                    "contextMeta": {},
                },
            )

        self.assertEqual(401, response.status_code)

    def test_should_allow_health_endpoint(self) -> None:
        probe = AsyncMock(return_value=(True, None))
        with (
            patch.object(worker_main, "_READINESS_CACHE", None),
            patch.object(worker_main, "_probe_mcp_readiness", probe),
            TestClient(worker_main.app) as client,
        ):
            response = client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual("UP", response.json()["data"]["status"])


if __name__ == "__main__":
    unittest.main()
