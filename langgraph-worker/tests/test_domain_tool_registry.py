from __future__ import annotations

import unittest

from app.models.agent_task import TaskType
from app.models.knowledge import RankLookupResult
from app.services.task_graph.decomposer import TaskGraphDecomposer
from app.services.task_graph.planner import DomainToolPlanner
from app.services.tools.registry import DomainToolRegistry


class DomainToolRegistryTest(unittest.IsolatedAsyncioTestCase):
    async def test_registry_registers_and_dispatches_available_tools(self) -> None:
        registry = DomainToolRegistry()

        async def handler(payload):
            return {"ok": True, "payload": payload}

        registry.register("rank.lookup", "rank", {"type": "object"}, handler, check_fn=lambda: True)

        self.assertEqual(["rank.lookup"], [tool.name for tool in registry.available(toolset="rank")])
        run = await registry.dispatch("rank.lookup", {"limit": 3})

        self.assertEqual("rank.lookup", run.name)
        self.assertEqual("succeeded", run.status)
        self.assertEqual({"ok": True, "payload": {"limit": 3}}, run.output)

    async def test_registry_excludes_unavailable_tools_and_wraps_errors(self) -> None:
        registry = DomainToolRegistry()

        async def broken(_payload):
            raise RuntimeError("backend offline")

        registry.register("rank.lookup", "rank", {}, broken, check_fn=lambda: False)
        registry.register("book.research_pack", "book", {}, broken, check_fn=lambda: True)

        self.assertEqual([], registry.available(toolset="rank"))
        run = await registry.dispatch("book.research_pack", {})

        self.assertEqual("failed", run.status)
        self.assertEqual("RuntimeError", run.errorType)
        self.assertIn("backend offline", str(run.output.get("message")))

    async def test_registry_serializes_model_outputs_for_trace_payloads(self) -> None:
        registry = DomainToolRegistry()

        async def handler(_payload):
            return [RankLookupResult(rankNo=1, bookName="榜一书")]

        registry.register("rank.lookup", "rank", {}, handler)

        run = await registry.dispatch("rank.lookup", {})

        self.assertEqual("succeeded", run.status)
        self.assertEqual({"items": [{"rankNo": 1, "bookName": "榜一书"}]}, run.output)


class DomainToolPlannerTest(unittest.TestCase):
    def test_planner_maps_task_graph_to_domain_tools(self) -> None:
        graph = TaskGraphDecomposer().decompose(
            "看最近番茄男频都市脑洞风向，参考榜一卖点，给我一个新题材。"
        )
        plan = DomainToolPlanner().plan(graph)
        by_task_type = {item.taskType: item for item in plan}

        self.assertIn("rank.lookup", by_task_type[TaskType.market_scan].tools)
        self.assertIn("book.research_pack", by_task_type[TaskType.book_breakdown].tools)
        self.assertIn("knowledge.vector_search", by_task_type[TaskType.topic_strategy].tools)

    def test_planner_does_not_emit_skill_mutation_tool_for_normal_chat(self) -> None:
        graph = TaskGraphDecomposer().decompose("帮我新增一个 skill 并发布。")
        plan = DomainToolPlanner().plan(graph)

        self.assertTrue(graph.adminOperationRequested)
        self.assertEqual([], [tool for item in plan for tool in item.tools if tool.startswith("skill.")])


if __name__ == "__main__":
    unittest.main()
