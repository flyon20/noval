from __future__ import annotations

import asyncio
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
            return [{
                "id": 101,
                "scope": "project",
                "memoryType": "fact",
                "content": "three terminal setting",
                "status": "CONFIRMED",
                "sourceTraceId": "trace-project-memory",
            }]
        if scope == "user":
            return [{
                "id": 102,
                "scope": "user",
                "memoryType": "preference",
                "content": "prefers urban fantasy",
                "status": "CONFIRMED",
                "sourceTraceId": "trace-user-memory",
            }]
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
        return [{
            "id": 103,
            "scope": "project",
            "memoryType": "summary",
            "content": "semantic recall",
            "status": "CONFIRMED",
            "sourceTraceId": "trace-semantic-memory",
        }]


class MixedStatusMemoryClient(MemoryClient):
    async def search_memory(self, **kwargs) -> list[dict]:
        scope = kwargs["scope"]
        if scope == "project":
            return [
                {"id": 201, "scope": "project", "memoryType": "fact", "content": "confirmed", "status": "CONFIRMED", "sourceTraceId": "trace-201"},
                {"id": 202, "scope": "project", "memoryType": "fact", "content": "candidate", "status": "CANDIDATE", "sourceTraceId": "trace-202"},
                {"id": 203, "scope": "project", "memoryType": "fact", "content": "stale", "status": "STALE", "sourceTraceId": "trace-203"},
            ]
        return []

    async def search_semantic_memory(self, **kwargs) -> list[dict]:
        return [
            {"id": 204, "scope": "project", "memoryType": "fact", "content": "rejected", "status": "REJECTED", "sourceTraceId": "trace-204"},
            {"id": 205, "scope": "project", "memoryType": "fact", "content": "semantic confirmed", "status": "CONFIRMED", "sourceTraceId": "trace-205"},
        ]


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
        self.assertEqual(
            {"trace-project-memory", "trace-user-memory", "trace-semantic-memory"},
            {item["sourceTraceId"] for item in bundle["memoryEvidence"]},
        )

    async def test_loads_only_requested_memory_scopes(self) -> None:
        client = MemoryClient()

        bundle = await MemoryAgent(memory_client=client).load(
            KnowledgeChatRequest(
                question="continue the project",
                userId=7,
                projectId=900,
                conversationId="conv-scoped",
            ),
            scopes=("thread", "project"),
        )

        self.assertEqual("previous outline choices", bundle["conversationSummary"]["summary"])
        self.assertEqual("three terminal setting", bundle["projectMemory"][0]["content"])
        self.assertEqual([], bundle["userMemory"])
        self.assertEqual([], bundle["semanticMemory"])
        self.assertEqual(["project"], [call["scope"] for call in client.search_calls])
        self.assertEqual([], client.semantic_calls)
        self.assertEqual("scope_not_requested", bundle["diagnostics"]["userMemory"]["reason"])
        self.assertEqual("scope_not_requested", bundle["diagnostics"]["semanticMemory"]["reason"])

    async def test_empty_scope_returns_no_io_memory_context(self) -> None:
        client = MemoryClient()

        bundle = await MemoryAgent(memory_client=client).load(
            KnowledgeChatRequest(
                question="current market ranking",
                userId=7,
                projectId=900,
                conversationId="conv-no-memory",
            ),
            scopes=(),
        )

        self.assertEqual({}, bundle["conversationSummary"])
        self.assertEqual([], bundle["memoryEvidence"])
        self.assertEqual([], client.summary_calls)
        self.assertEqual([], client.search_calls)
        self.assertEqual([], client.semantic_calls)
        self.assertTrue(all(
            diagnostic["reason"] == "scope_not_requested"
            for diagnostic in bundle["diagnostics"].values()
        ))

    async def test_full_memory_reads_start_concurrently(self) -> None:
        class ConcurrentMemoryClient(MemoryClient):
            def __init__(self) -> None:
                super().__init__()
                self.started: set[str] = set()
                self.release = asyncio.Event()

            async def _arrive(self, name: str) -> None:
                self.started.add(name)
                if len(self.started) == 4:
                    self.release.set()
                await asyncio.wait_for(self.release.wait(), timeout=0.2)

            async def read_conversation_summary(self, **kwargs) -> dict:
                await self._arrive("thread")
                return await super().read_conversation_summary(**kwargs)

            async def search_memory(self, **kwargs) -> list[dict]:
                await self._arrive(str(kwargs["scope"]))
                return await super().search_memory(**kwargs)

            async def search_semantic_memory(self, **kwargs) -> list[dict]:
                await self._arrive("semantic")
                return await super().search_semantic_memory(**kwargs)

        client = ConcurrentMemoryClient()

        bundle = await MemoryAgent(memory_client=client).load(
            KnowledgeChatRequest(
                question="continue",
                userId=7,
                projectId=900,
                conversationId="conv-concurrent",
            )
        )

        self.assertEqual({"thread", "project", "user", "semantic"}, client.started)
        self.assertTrue(bundle["memoryUsed"]["confirmedOnly"])

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

    async def test_loads_only_confirmed_memory_and_reports_rejected_states_with_provenance(self) -> None:
        bundle = await MemoryAgent(memory_client=MixedStatusMemoryClient()).load(
            KnowledgeChatRequest(
                question="audit the project memory",
                userId=7,
                projectId=900,
                conversationId="conv-4",
            )
        )

        self.assertEqual(["confirmed"], [item["content"] for item in bundle["projectMemory"]])
        self.assertEqual(["semantic confirmed"], [item["content"] for item in bundle["semanticMemory"]])
        self.assertEqual(2, bundle["diagnostics"]["projectMemory"]["rejectedCount"])
        self.assertEqual(["CANDIDATE", "STALE"], bundle["diagnostics"]["projectMemory"]["rejectedStatuses"])
        self.assertEqual(1, bundle["diagnostics"]["semanticMemory"]["rejectedCount"])
        self.assertEqual(
            {"trace-201", "trace-205"},
            {item["sourceTraceId"] for item in bundle["memoryEvidence"]},
        )
        self.assertTrue(bundle["memoryUsed"]["confirmedOnly"])

    async def test_uses_lifecycle_status_and_parses_safe_backend_provenance_without_memory_body(self) -> None:
        class LifecycleClient(MemoryClient):
            async def search_memory(self, **kwargs) -> list[dict]:
                if kwargs["scope"] != "project":
                    return []
                return [{
                    "id": 301,
                    "scope": "project",
                    "memoryType": "constraint",
                    "content": "the raw memory body must stay out of Trace",
                    "status": "LEGACY",
                    "lifecycleStatus": "CONFIRMED",
                    "sourceTraceId": "trace-301",
                    "provenanceJson": '{"content":"must not be shown","extractorVersion":"memory-extractor-v1","reason":"explicit constraint"}',
                    "evidenceJson": '{"sourceKind":"user_turn","sourceTraceId":"trace-301","text":"must not be shown"}',
                }]

            async def search_semantic_memory(self, **_kwargs) -> list[dict]:
                return []

        bundle = await MemoryAgent(memory_client=LifecycleClient()).load(
            KnowledgeChatRequest(question="continue", userId=7, projectId=900, conversationId="conv-5")
        )

        self.assertEqual(["the raw memory body must stay out of Trace"], [item["content"] for item in bundle["projectMemory"]])
        evidence = bundle["memoryEvidence"][0]
        self.assertEqual("memory-extractor-v1", evidence["provenance"]["extractorVersion"])
        self.assertEqual("explicit constraint", evidence["provenance"]["reason"])
        self.assertEqual({"sourceKind": "user_turn", "sourceTraceId": "trace-301"}, evidence["evidence"])
        self.assertNotIn("must not be shown", str(evidence))


if __name__ == "__main__":
    unittest.main()
