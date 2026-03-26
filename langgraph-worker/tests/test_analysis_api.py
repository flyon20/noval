from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi.testclient import TestClient

from app.api import analysis
from app.config import settings
from app.main import app


class AnalysisApiErrorHandlingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = "langgraph-test-key-123456"

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def test_run_should_return_readable_message_when_provider_connection_fails(self) -> None:
        request = httpx.Request("POST", "https://api.deepseek.com/v1/chat/completions")
        payload = {
            "taskId": "t1",
            "agentType": "deconstruct",
            "promptConfig": {},
            "sourcePayload": {"inputText": "hello"},
            "limits": {},
            "contextMeta": {},
        }

        with patch.object(
            analysis.analysis_service,
            "run",
            AsyncMock(side_effect=httpx.ConnectError("provider down", request=request)),
        ):
            with TestClient(app) as client:
                response = client.post(
                    "/internal/analysis/run",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json=payload,
                )

        self.assertEqual(502, response.status_code)
        self.assertEqual("AI provider connection failed, please retry.", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()
