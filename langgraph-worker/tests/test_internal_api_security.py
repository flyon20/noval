from __future__ import annotations

import unittest

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


class InternalApiSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = "langgraph-test-key-123456"

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def test_should_reject_internal_analysis_without_token(self) -> None:
        with TestClient(app) as client:
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
        with TestClient(app) as client:
            response = client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual("UP", response.json()["data"]["status"])


if __name__ == "__main__":
    unittest.main()
