from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.services.runtime.context_assembler import ContextAssembler


class ProjectMemoryClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def get_project_memory(self, *, project_id: int, user_id: int) -> dict:
        self.calls.append({"project_id": project_id, "user_id": user_id})
        return {
            "projectId": project_id,
            "userId": user_id,
            "memories": {
                "genre": "urban fantasy",
                "styleConstraints": "no harem",
            },
        }


class ContextAssemblerTest(unittest.TestCase):
    def test_builds_current_turn_layer_from_request(self) -> None:
        bundle = ContextAssembler().assemble(
            KnowledgeChatRequest(
                question="最近都市脑洞还能不能写？",
                userId=7,
                projectId=99,
                conversationId="conv-1",
            )
        )

        self.assertEqual("turn", bundle.currentTurn.scope)
        self.assertEqual("最近都市脑洞还能不能写？", bundle.currentTurn.content["question"])
        self.assertEqual(7, bundle.currentTurn.content["userId"])
        self.assertEqual(99, bundle.currentTurn.content["projectId"])
        self.assertEqual("conv-1", bundle.currentTurn.content["conversationId"])

    def test_uses_legacy_context_summary_as_thread_layer(self) -> None:
        bundle = ContextAssembler().assemble(
            KnowledgeChatRequest(
                question="继续优化前三章",
                contextSummary="上轮讨论了不后宫和快节奏前三章",
                history=[{"role": "user", "content": "我要写都市脑洞"}],
            )
        )

        self.assertIsNotNone(bundle.threadSummary)
        self.assertEqual("thread", bundle.threadSummary.scope)
        self.assertIn("不后宫", bundle.threadSummary.content["summary"])
        self.assertEqual(1, len(bundle.threadSummary.content["history"]))

    def test_does_not_create_project_profile_without_project_id(self) -> None:
        bundle = ContextAssembler().assemble(KnowledgeChatRequest(question="玄幻升级流还能写吗？"))

        self.assertIsNone(bundle.projectProfile)

    def test_normalizes_incoming_context_bundle(self) -> None:
        bundle = ContextAssembler().assemble(
            KnowledgeChatRequest(
                question="这个项目怎么改？",
                contextBundle={
                    "projectProfile": {
                        "scope": "project",
                        "content": {"genre": "都市脑洞", "styleConstraints": ["不后宫"]},
                    },
                    "currentTurn": {
                        "scope": "turn",
                        "content": {"question": "这个项目怎么改？"},
                    },
                },
            )
        )

        self.assertIsNotNone(bundle.projectProfile)
        self.assertEqual("都市脑洞", bundle.projectProfile.content["genre"])
        self.assertEqual("这个项目怎么改？", bundle.currentTurn.content["question"])


class AsyncContextAssemblerTest(unittest.IsolatedAsyncioTestCase):
    async def test_fetches_project_memory_when_project_and_user_are_present(self) -> None:
        client = ProjectMemoryClient()
        bundle = await ContextAssembler(memory_client=client).assemble_async(
            KnowledgeChatRequest(
                question="project opening revision",
                userId=7,
                projectId=900,
                conversationId="conv-900",
            )
        )

        self.assertEqual([{"project_id": 900, "user_id": 7}], client.calls)
        self.assertIsNotNone(bundle.projectProfile)
        self.assertEqual("urban fantasy", bundle.projectProfile.content["memories"]["genre"])
        self.assertEqual(900, bundle.projectProfile.content["projectId"])

    async def test_does_not_fetch_project_memory_without_project_id(self) -> None:
        client = ProjectMemoryClient()
        bundle = await ContextAssembler(memory_client=client).assemble_async(
            KnowledgeChatRequest(question="fantasy progression market?", userId=7)
        )

        self.assertEqual([], client.calls)
        self.assertIsNone(bundle.projectProfile)


if __name__ == "__main__":
    unittest.main()
