from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.services.runtime.memory_agent import MemoryAgent


class MemoryClient:
    def __init__(self) -> None:
        self.summary_calls: list[dict] = []
        self.search_calls: list[dict] = []
        self.semantic_calls: list[dict] = []

    async def read_conversation_summary(self, *, user_id: int, conversation_id: str) -> dict:
        self.summary_calls.append({"user_id": user_id, "conversation_id": conversation_id})
        return {
            "conversationId": conversation_id,
            "userId": user_id,
            "projectId": 900,
            "summary": "previous outline choices",
            "sourceTraceId": "trace-summary",
        }

    async def search_memory(
        self,
        *,
        user_id: int,
        project_id: int | None,
        scope: str | None,
        limit: int,
    ) -> list[dict]:
        self.search_calls.append({
            "user_id": user_id,
            "project_id": project_id,
            "scope": scope,
            "limit": limit,
        })
        if scope == "project":
            return [{"scope": "project", "memoryType": "fact", "content": "three terminal setting"}]
        if scope == "user":
            return [{"scope": "user", "memoryType": "preference", "content": "prefers urban fantasy"}]
        return []

    async def search_semantic_memory(
        self,
        *,
        query: str,
        user_id: int,
        project_id: int | None,
        conversation_id: str | None,
        limit: int,
    ) -> list[dict]:
        self.semantic_calls.append({
            "query": query,
            "user_id": user_id,
            "project_id": project_id,
            "conversation_id": conversation_id,
            "limit": limit,
        })
        return [{"scope": "project", "memoryType": "summary", "content": "semantic recall"}]


class FailingMemoryClient(MemoryClient):
    async def read_conversation_summary(self, *, user_id: int, conversation_id: str) -> dict:
        raise TimeoutError("summary backend unavailable")

    async def search_memory(
        self,
        *,
        user_id: int,
        project_id: int | None,
        scope: str | None,
        limit: int,
    ) -> list[dict]:
        if scope == "project":
            raise RuntimeError("project memory unavailable")
        return []

    async def search_semantic_memory(
        self,
        *,
        query: str,
        user_id: int,
        project_id: int | None,
        conversation_id: str | None,
        limit: int,
    ) -> list[dict]:
        raise ConnectionError("semantic memory unavailable")


class MemoryAgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_loads_summary_project_user_and_semantic_memory(self) -> None:
        client = MemoryClient()
        bundle = await MemoryAgent(memory_client=client).load(
            KnowledgeChatRequest(
                question="continue the outline",
                userId=7,
                projectId=900,
                conversationId="conv-1",
            )
        )

        self.assertEqual("previous outline choices", bundle["conversationSummary"]["summary"])
        self.assertEqual("three terminal setting", bundle["projectMemory"][0]["content"])
        self.assertEqual("prefers urban fantasy", bundle["userMemory"][0]["content"])
        self.assertEqual("semantic recall", bundle["semanticMemory"][0]["content"])
        self.assertEqual([{"user_id": 7, "conversation_id": "conv-1"}], client.summary_calls)
        self.assertIn({"user_id": 7, "project_id": 900, "scope": "project", "limit": 12}, client.search_calls)
        self.assertIn({"user_id": 7, "project_id": None, "scope": "user", "limit": 5}, client.search_calls)
        self.assertEqual(900, client.semantic_calls[0]["project_id"])
        self.assertEqual(8, client.semantic_calls[0]["limit"])

    async def test_does_not_load_project_memory_without_project_id(self) -> None:
        client = MemoryClient()
        bundle = await MemoryAgent(memory_client=client).load(
            KnowledgeChatRequest(
                question="new conversation",
                userId=7,
                conversationId="conv-2",
            )
        )

        self.assertEqual([], bundle["projectMemory"])
        self.assertNotIn({"user_id": 7, "project_id": None, "scope": "project", "limit": 12}, client.search_calls)
        self.assertIsNone(client.semantic_calls[0]["project_id"])

    async def test_reports_memory_layer_degradation_without_user_memory_ui_contract(self) -> None:
        bundle = await MemoryAgent(memory_client=FailingMemoryClient()).load(
            KnowledgeChatRequest(
                question="continue project",
                userId=7,
                projectId=900,
                conversationId="conv-3",
            )
        )

        self.assertEqual({}, bundle["conversationSummary"])
        self.assertEqual([], bundle["projectMemory"])
        self.assertEqual([], bundle["semanticMemory"])
        diagnostics = bundle["diagnostics"]
        self.assertEqual("unavailable", diagnostics["conversationSummary"]["status"])
        self.assertEqual("TimeoutError", diagnostics["conversationSummary"]["reason"])
        self.assertEqual("unavailable", diagnostics["projectMemory"]["status"])
        self.assertEqual("RuntimeError", diagnostics["projectMemory"]["reason"])
        self.assertEqual("empty", diagnostics["userMemory"]["status"])
        self.assertEqual("unavailable", diagnostics["semanticMemory"]["status"])
        self.assertEqual("ConnectionError", diagnostics["semanticMemory"]["reason"])


if __name__ == "__main__":
    unittest.main()
