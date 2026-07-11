from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.models.knowledge import KnowledgeChatResponse
from app.services.evaluation.golden import GoldenEvalCase


class KnowledgeApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = "langgraph-test-key-123456"

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def test_should_reject_knowledge_chat_without_internal_token(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/internal/knowledge/chat",
                json={"question": "星河旧梦有什么卖点？", "bookName": "星河旧梦"},
            )

        self.assertEqual(401, response.status_code)

    def test_should_return_knowledge_chat_response(self) -> None:
        payload = {"question": "星河旧梦有什么卖点？", "bookId": 101, "bookName": "星河旧梦"}

        with patch(
            "app.api.knowledge.research_agent.run",
            AsyncMock(return_value=KnowledgeChatResponse(
                status="answered",
                answer="开篇卖点来自星门线索。[1]",
                candidates=[],
                sources=[],
                actions=[],
                resultJson={"status": "answered"},
            )),
        ) as run_mock:
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/chat",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json=payload,
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual("answered", response.json()["status"])
        self.assertEqual("星河旧梦", run_mock.await_args.args[0].bookName)

    def test_should_return_runtime_skills_for_admin_dashboard(self) -> None:
        with TestClient(app) as client:
            response = client.get(
                "/internal/knowledge/runtime-skills",
                headers={"X-Internal-Service-Token": settings.internal_api_key},
            )

        self.assertEqual(200, response.status_code)
        skill_ids = [item["skillId"] for item in response.json()]
        self.assertIn("webnovel-market-scan", skill_ids)
        self.assertTrue(all("intents" in item for item in response.json()))

    def test_should_accept_admin_eval_run_and_schedule_suite_execution(self) -> None:
        case = GoldenEvalCase(
            case_id="market-001",
            question="What is trending?",
            request_payload={"question": "What is trending?"},
        )
        repository = SimpleNamespace(
            list_active_cases=unittest.mock.Mock(return_value=[case]),
            create_run=unittest.mock.Mock(return_value=42),
        )

        async def fake_run_suite(cases, **kwargs):
            self.assertEqual([case], cases)
            self.assertEqual("agent-runtime", kwargs["suite_name"])
            self.assertEqual(repository, kwargs["repository"])
            self.assertEqual(42, kwargs["persisted_run_id"])

        with patch("app.api.knowledge.MySqlGoldenEvalRepository", return_value=repository), \
            patch("app.api.knowledge.GoldenEvalRunner") as runner_class:
            runner_class.return_value.run_suite = AsyncMock(side_effect=fake_run_suite)
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/eval-runs",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "suiteName": "agent-runtime",
                        "runKey": "agent-runtime:manual-001",
                        "runnerName": "admin-trigger",
                        "evaluatorName": "rule-based",
                        "modelName": "deepseek-chat",
                        "caseLimit": 10,
                    },
                )

        self.assertEqual(202, response.status_code)
        self.assertEqual(42, response.json()["runId"])
        self.assertEqual("RUNNING", response.json()["status"])
        repository.list_active_cases.assert_called_once_with("agent-runtime", limit=10)
        repository.create_run.assert_called_once()
        runner_class.return_value.run_suite.assert_awaited_once()

    def test_should_reject_unsupported_admin_eval_evaluator(self) -> None:
        with patch("app.api.knowledge.MySqlGoldenEvalRepository") as repository_class:
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/eval-runs",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "suiteName": "agent-runtime",
                        "evaluatorName": "llm-judge",
                        "caseLimit": 10,
                    },
                )

        self.assertEqual(400, response.status_code)
        self.assertIn("Unsupported evaluatorName", response.json()["detail"])
        repository_class.assert_not_called()

    def test_should_execute_synchronous_backend_eval_run_with_progress_fields(self) -> None:
        case = GoldenEvalCase(
            case_id="market-001",
            question="What is trending?",
            request_payload={"question": "What is trending?"},
        )
        repository = SimpleNamespace(
            list_active_cases=unittest.mock.Mock(return_value=[case]),
            create_run=unittest.mock.Mock(return_value=99),
            update_run_progress=unittest.mock.Mock(),
        )

        async def fake_run_suite(cases, **kwargs):
            self.assertEqual([case], cases)
            self.assertEqual(77, kwargs["persisted_run_id"])
            return {
                "runId": 77,
                "status": "passed",
                "totalCases": 1,
                "passedCases": 1,
                "failedCases": 0,
            }

        with patch("app.api.knowledge.MySqlGoldenEvalRepository", return_value=repository), \
            patch("app.api.knowledge.GoldenEvalRunner") as runner_class:
            runner_class.return_value.run_suite = AsyncMock(side_effect=fake_run_suite)
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/eval-runs",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "runId": 77,
                        "suiteName": "agent-runtime",
                        "runKey": "agent-runtime:queued-77",
                        "runnerName": "rabbit-consumer",
                        "evaluatorName": "rule-based",
                        "modelName": "deepseek-chat",
                        "caseLimit": 10,
                        "synchronous": True,
                        "cancelKey": "ai:agent:eval:cancel:77",
                        "progressKey": "ai:agent:eval:progress:77",
                    },
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual(77, response.json()["runId"])
        self.assertEqual("PASSED", response.json()["status"])
        self.assertEqual(1, response.json()["progressCurrent"])
        self.assertEqual(1, response.json()["progressTotal"])
        self.assertFalse(response.json()["queued"])
        repository.create_run.assert_not_called()
        repository.update_run_progress.assert_called()
        runner_class.return_value.run_suite.assert_awaited_once()

    def test_should_stream_knowledge_chat_events(self) -> None:
        payload = {"question": "Book Alpha setting?", "bookId": 101, "bookName": "Book Alpha"}

        async def fake_stream(_request):
            yield {"event": "start", "phase": "langgraph"}
            yield {"event": "delta", "delta": "Setting "}
            yield {"event": "delta", "delta": "answer[1]"}
            yield {
                "event": "done",
                "data": KnowledgeChatResponse(
                    status="answered",
                    answer="Setting answer[1]",
                    candidates=[],
                    sources=[],
                    actions=[],
                    resultJson={"status": "answered", "source": "rag"},
                ).model_dump(),
            }

        with patch("app.api.knowledge.research_agent.stream", fake_stream):
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/chat/stream",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json=payload,
                )

        self.assertEqual(200, response.status_code)
        self.assertIn("text/event-stream", response.headers["content-type"])
        self.assertIn("event: start", response.text)
        self.assertIn("event: delta", response.text)
        self.assertIn("Setting answer[1]", response.text)
        self.assertIn("event: done", response.text)

    def test_should_close_research_agent_client_on_app_shutdown(self) -> None:
        with patch("app.api.knowledge.research_agent.aclose", AsyncMock()) as close_mock:
            with TestClient(app):
                pass

        close_mock.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
