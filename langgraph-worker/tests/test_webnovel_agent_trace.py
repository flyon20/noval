from __future__ import annotations

import unittest

from app.models.agent_task import EvidencePack, Perspective, TaskType
from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.novel_research_agent import NovelResearchAgent
from app.services.task_graph.evidence import EvidencePackBuilder


class EvidencePackBuilderTest(unittest.TestCase):
    def test_sources_are_grouped_by_evidence_role(self) -> None:
        pack = EvidencePackBuilder().from_sources(
            [
                KnowledgeSource(sourceType="RANK", bookName="榜一书", preview="排名事实"),
                KnowledgeSource(sourceType="CHAPTER", bookName="样本书", preview="章节样本"),
                KnowledgeSource(sourceType="INTRO", bookName="简介书", preview="简介样本"),
            ],
            inference_signals=[{"perspective": "reader", "summary": "毒点风险"}],
        )

        self.assertEqual(1, len(pack.facts))
        self.assertEqual(2, len(pack.examples))
        self.assertEqual(1, len(pack.signals))
        self.assertLessEqual(len(pack.summary()["examples"]), 2)

    def test_rank_fact_preserves_snapshot_metadata_for_supervisor(self) -> None:
        pack = EvidencePackBuilder().from_sources([
            KnowledgeSource(
                sourceType="RANK",
                bookName="Rank One",
                rankNo=1,
                snapshotId=10,
                snapshotTime="2026-06-21T00:00:00",
                preview="rank fact",
            )
        ])

        self.assertEqual(1, len(pack.facts))
        self.assertEqual(10, pack.facts[0]["snapshotId"])
        self.assertEqual("2026-06-21T00:00:00", pack.facts[0]["snapshotTime"])


class AgentTraceAttachmentTest(unittest.IsolatedAsyncioTestCase):
    async def test_project_memory_appears_in_trace_context_used(self) -> None:
        class Provider:
            async def invoke(self, **_kwargs):
                return {
                    "content": "按当前项目约束，前三章保持快节奏、不走后宫线。",
                    "token_used": 64,
                }

        class MemoryClient:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            async def get_project_memory(self, *, project_id: int, user_id: int) -> dict:
                self.calls.append({"project_id": project_id, "user_id": user_id})
                return {
                    "projectId": project_id,
                    "userId": user_id,
                    "memories": {"genre": "urban fantasy", "styleConstraints": "no harem"},
                }

            async def search_books(self, **_kwargs) -> list:
                return []

        client = MemoryClient()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=Provider())

        response = await agent.run(
            KnowledgeChatRequest(
                question="这个项目前三章怎么改？",
                userId=7,
                projectId=900,
                conversationId="conv-900",
            )
        )

        self.assertEqual([{"project_id": 900, "user_id": 7}], client.calls)
        context_used = response.resultJson["trace"]["contextUsed"]
        self.assertTrue(context_used["hasProjectProfile"])
        self.assertIn("projectProfile", context_used["layers"])

    async def test_creative_response_includes_task_graph_evidence_and_perspectives(self) -> None:
        class Provider:
            async def invoke(self, **_kwargs):
                return {
                    "content": "前三章细纲：第一章立目标，第二章给反馈，第三章放大危机。",
                    "token_used": 64,
                }

        agent = NovelResearchAgent(provider_client=Provider())
        response = await agent.run(
            KnowledgeChatRequest(question="我想写一本修仙文，帮我设计主角人设和前三章细纲。")
        )

        result = response.resultJson

        self.assertIn("taskGraph", result)
        self.assertIn("evidencePackSummary", result)
        self.assertIn("perspectiveResults", result)
        self.assertIn("toolRuns", result)
        self.assertIn("sourcePolicy", result)
        self.assertIn("supervisorDecision", result)
        self.assertIn("contextUsed", result)
        self.assertIn("sourcePolicy", result["trace"])
        self.assertIn("supervisorDecision", result["trace"])
        self.assertIn("contextUsed", result["trace"])
        runtime_node_names = [node["name"] for node in result["trace"]["nodes"]]
        self.assertEqual(
            [
                "assemble_context",
                "classify_intent",
                "plan_tasks",
                "execute_tools",
                "supervise_evidence",
                "compose_answer",
                "extract_memory_candidates",
                "finalize_trace",
            ],
            runtime_node_names,
        )
        self.assertIn("chapter_outline", {task["type"] for task in result["taskGraph"]["tasks"]})
        self.assertIn("author", {item["perspective"] for item in result["perspectiveResults"]})

    async def test_skill_governance_request_gets_admin_marker_without_skill_tool_run(self) -> None:
        agent = NovelResearchAgent()
        response = await agent.run(KnowledgeChatRequest(question="帮我新增一个 skill 并发布到系统。"))

        result = response.resultJson
        tool_names = {run.get("name") for run in result.get("toolRuns", [])}

        self.assertTrue(result["adminOperationRequested"])
        self.assertNotIn("skill.publish", tool_names)
        self.assertIn("skill_governance", {task["type"] for task in result["taskGraph"]["tasks"]})

    def test_perspective_result_model_serializes_for_trace(self) -> None:
        pack = EvidencePack()
        result = pack.to_perspective_result(
            task_type=TaskType.reader_risk,
            perspective=Perspective.reader,
            summary="读者风险：开局目标不清。",
        )

        self.assertEqual("reader", result.model_dump(mode="json")["perspective"])


if __name__ == "__main__":
    unittest.main()
