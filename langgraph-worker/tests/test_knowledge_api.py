from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.models.knowledge import KnowledgeChatResponse


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


if __name__ == "__main__":
    unittest.main()
