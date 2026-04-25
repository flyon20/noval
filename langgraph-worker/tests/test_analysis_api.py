from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi.testclient import TestClient

from app.api import analysis
from app.config import settings
from app.main import app
from app.models.analysis import RunResponse


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

    def test_run_should_accept_existing_structured_prompt_contract_fields(self) -> None:
        payload = {
            "taskId": "theme-contract",
            "agentType": "trend_theme",
            "promptConfig": {
                "promptType": "theme",
                "promptContent": "JSON ONLY {{content}}",
                "modelName": "deepseek-chat",
                "inputJsonSchema": '{"type":"object"}',
                "inputExampleJson": '{"snapshotCount":3}',
                "outputJsonSchema": '{"type":"object"}',
                "outputExampleJson": '{"boardSummary":"example"}',
                "parseConfigJson": '{"parser":"json"}',
                "postProcessType": "json_extract",
            },
            "sourcePayload": {"inputText": "hello"},
            "limits": {},
            "contextMeta": {},
        }

        with patch.object(
            analysis.analysis_service,
            "run",
            AsyncMock(return_value=RunResponse(
                taskId="theme-contract",
                modelName="deepseek-chat",
                content="{}",
                tokenUsed=1,
                resultJson={"analysisType": "theme"},
            )),
        ) as run_mock:
            with TestClient(app) as client:
                response = client.post(
                    "/internal/analysis/run",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json=payload,
                )

        self.assertEqual(200, response.status_code)
        request_model = run_mock.await_args.args[0]
        self.assertEqual('{"type":"object"}', request_model.promptConfig.inputJsonSchema)
        self.assertEqual('{"snapshotCount":3}', request_model.promptConfig.inputExampleJson)
        self.assertEqual('{"type":"object"}', request_model.promptConfig.outputJsonSchema)
        self.assertEqual('{"boardSummary":"example"}', request_model.promptConfig.outputExampleJson)
        self.assertEqual('{"parser":"json"}', request_model.promptConfig.parseConfigJson)
        self.assertEqual("json_extract", request_model.promptConfig.postProcessType)


if __name__ == "__main__":
    unittest.main()
