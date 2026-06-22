from __future__ import annotations

import asyncio
import unittest

from app.models.agent_task import Perspective, TaskGraph, TaskNode, TaskType, ToolPlan
from app.services.task_graph.executor import DomainTaskToolExecutor
from app.services.tools.registry import DomainToolRegistry


class DomainTaskToolExecutorTest(unittest.IsolatedAsyncioTestCase):
    async def test_executes_planned_tools_with_context_payload(self) -> None:
        registry = DomainToolRegistry()
        seen_payloads: list[dict] = []

        async def lookup_rank(payload):
            seen_payloads.append(payload)
            return {"items": [{"bookName": "榜一书", "rankNo": 1}]}

        registry.register("rank.lookup", "rank", {"type": "object"}, lookup_rank)
        graph = TaskGraph(
            userGoal="看榜单风向",
            tasks=[
                TaskNode(
                    id="task_market",
                    type=TaskType.market_scan,
                    goal="Extract rank signals.",
                    perspective=Perspective.market,
                )
            ],
        )
        plans = [
            ToolPlan(
                taskId="task_market",
                taskType=TaskType.market_scan,
                tools=["rank.lookup"],
                required=True,
            )
        ]

        runs = await DomainTaskToolExecutor(registry).execute(
            graph,
            plans,
            context={"query": "男频都市脑洞榜单", "limit": 3, "projectId": 99},
        )

        self.assertEqual(1, len(runs))
        self.assertEqual("rank.lookup", runs[0].name)
        self.assertEqual("succeeded", runs[0].status)
        self.assertEqual(1, runs[0].resultCount)
        self.assertEqual("task_market", runs[0].input["taskId"])
        self.assertEqual("market_scan", runs[0].input["taskType"])
        self.assertEqual("男频都市脑洞榜单", seen_payloads[0]["query"])
        self.assertEqual(3, seen_payloads[0]["limit"])

    async def test_records_missing_tools_instead_of_dropping_plan(self) -> None:
        graph = TaskGraph(
            userGoal="写前三章细纲",
            tasks=[
                TaskNode(
                    id="task_outline",
                    type=TaskType.chapter_outline,
                    goal="Draft chapter beats.",
                    perspective=Perspective.author,
                )
            ],
        )
        plans = [
            ToolPlan(
                taskId="task_outline",
                taskType=TaskType.chapter_outline,
                tools=["memory.project_context"],
            )
        ]

        runs = await DomainTaskToolExecutor(DomainToolRegistry()).execute(graph, plans, context={})

        self.assertEqual(1, len(runs))
        self.assertEqual("memory.project_context", runs[0].name)
        self.assertEqual("failed", runs[0].status)
        self.assertEqual("ToolNotFound", runs[0].errorType)
        self.assertEqual("task_outline", runs[0].input["taskId"])

    async def test_passes_source_policy_to_rank_tools(self) -> None:
        registry = DomainToolRegistry()
        seen_payloads: list[dict] = []

        async def lookup_rank(payload):
            seen_payloads.append(payload)
            return {"items": [{"bookName": "榜一书", "rankNo": 1}]}

        registry.register("rank.lookup", "rank", {"type": "object"}, lookup_rank)
        graph = TaskGraph(
            userGoal="最近都市脑洞还能不能写？",
            tasks=[
                TaskNode(
                    id="task_market",
                    type=TaskType.market_scan,
                    goal="Extract latest rank signals.",
                    perspective=Perspective.market,
                    freshnessPolicy={
                        "freshness": "latest",
                        "allowHistorical": False,
                        "requireSnapshotTime": True,
                    },
                )
            ],
        )
        plans = [
            ToolPlan(
                taskId="task_market",
                taskType=TaskType.market_scan,
                tools=["rank.lookup"],
                required=True,
            )
        ]

        await DomainTaskToolExecutor(registry).execute(
            graph,
            plans,
            context={
                "query": "都市脑洞最新榜单",
                "sourcePolicy": {
                    "freshness": "latest",
                    "allowHistorical": False,
                    "requireSnapshotTime": True,
                },
            },
        )

        self.assertEqual("latest", seen_payloads[0]["freshness"])
        self.assertFalse(seen_payloads[0]["allowHistorical"])
        self.assertTrue(seen_payloads[0]["sourcePolicy"]["requireSnapshotTime"])

    async def test_applies_tool_timeout_from_context(self) -> None:
        registry = DomainToolRegistry()

        async def slow_vector(_payload):
            await asyncio.sleep(3600)
            return {"items": [{"bookName": "不应等待"}]}

        registry.register("knowledge.vector_search", "knowledge", {"type": "object"}, slow_vector)
        graph = TaskGraph(
            userGoal="慢向量检索不应卡住",
            tasks=[
                TaskNode(
                    id="task_topic",
                    type=TaskType.topic_strategy,
                    goal="Find vector signals.",
                    perspective=Perspective.author,
                )
            ],
        )
        plans = [
            ToolPlan(
                taskId="task_topic",
                taskType=TaskType.topic_strategy,
                tools=["knowledge.vector_search"],
            )
        ]

        runs = await DomainTaskToolExecutor(registry).execute(
            graph,
            plans,
            context={"query": "都市脑洞", "toolTimeoutMillis": 20},
        )

        self.assertEqual(1, len(runs))
        self.assertEqual("knowledge.vector_search", runs[0].name)
        self.assertEqual("failed", runs[0].status)
        self.assertEqual("ToolTimeout", runs[0].errorType)


if __name__ == "__main__":
    unittest.main()
