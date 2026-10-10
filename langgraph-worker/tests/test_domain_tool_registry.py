from __future__ import annotations

import asyncio
import unittest

from app.models.agent_task import Perspective, TaskGraph, TaskNode, TaskType
from app.models.knowledge import RankLookupResult
from app.services.harness.budget import run_budget_scope
from app.services.harness.cancellation import CancellationToken, RunCancelledError, cancellation_scope
from app.services.harness.tool_ledger import run_tool_ledger_scope
from app.services.task_graph.decomposer import TaskGraphDecomposer
from app.services.task_graph.planner import DomainToolPlanner
from app.services.tools.registry import DomainToolRegistry


class DomainToolRegistryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self._ledger_scope = run_tool_ledger_scope({
            "runId": "domain-registry-test",
            "userId": "7",
            "projectId": "910",
            "route": "project_creation",
        })
        self._ledger_scope.__enter__()
        self.addAsyncCleanup(self._ledger_scope.__exit__, None, None, None)
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

    async def test_manifest_summary_is_stable_and_excludes_unavailable_tools(self) -> None:
        async def handler(_payload):
            return {"ok": True}

        first = DomainToolRegistry()
        first.register("rank.lookup", "rank", {"type": "object"}, handler)
        first.register("book.search", "book", {"type": "object"}, handler)
        first.register("hidden.tool", "rank", {"type": "object"}, handler, check_fn=lambda: False)
        second = DomainToolRegistry()
        second.register("book.search", "book", {"type": "object"}, handler)
        second.register("rank.lookup", "rank", {"type": "object"}, handler)
        changed = DomainToolRegistry()
        changed.register(
            "rank.lookup",
            "rank",
            {"type": "object", "required": ["platform"]},
            handler,
        )
        changed.register("book.search", "book", {"type": "object"}, handler)

        first_summary = first.manifest_summary()
        second_summary = second.manifest_summary()
        changed_summary = changed.manifest_summary()

        self.assertEqual(["book.search", "rank.lookup"], first_summary["toolNames"])
        self.assertEqual(first_summary["fingerprint"], second_summary["fingerprint"])
        self.assertNotEqual(first_summary["fingerprint"], changed_summary["fingerprint"])
        self.assertNotIn("schema", str(first_summary).lower())

    async def test_registry_serializes_model_outputs_for_trace_payloads(self) -> None:
        registry = DomainToolRegistry()

        async def handler(_payload):
            return [RankLookupResult(rankNo=1, bookName="榜一书")]

        registry.register("rank.lookup", "rank", {}, handler)

        run = await registry.dispatch("rank.lookup", {})

        self.assertEqual("succeeded", run.status)
        self.assertEqual({"items": [{"rankNo": 1, "bookName": "榜一书"}]}, run.output)

    async def test_registry_enforces_shared_run_budget_and_cancellation(self) -> None:
        registry = DomainToolRegistry()
        started = False

        async def handler(_payload):
            nonlocal started
            started = True
            return {"ok": True}

        registry.register("rank.lookup", "rank", {}, handler)
        with run_budget_scope("fast") as budget:
            runs = [await registry.dispatch("rank.lookup", {"limit": index}) for index in range(7)]

        self.assertTrue(all(run.status == "succeeded" for run in runs[:6]))
        self.assertEqual("failed", runs[6].status)
        self.assertEqual("BudgetExceededError", runs[6].errorType)
        self.assertEqual(6, budget.used_tool_calls)

        started = False
        token = CancellationToken()
        token.cancel("tool_cancelled")
        with cancellation_scope(token):
            cancelled = await registry.dispatch("rank.lookup", {"limit": 99})
        self.assertEqual("cancelled", cancelled.status)
        self.assertEqual("RunCancelledError", cancelled.errorType)
        self.assertFalse(started)

    async def test_registry_rejects_forged_project_scope_before_handler(self) -> None:
        registry = DomainToolRegistry()
        calls = 0

        async def handler(_payload):
            nonlocal calls
            calls += 1
            return {"ok": True}

        registry.register("project.chunk_search", "project", {}, handler)
        run = await registry.dispatch("project.chunk_search", {
            "userId": 7,
            "projectId": 901,
            "_expectedUserId": 7,
            "_expectedProjectId": 900,
        })

        self.assertEqual("failed", run.status)
        self.assertEqual("ToolScopeViolation", run.errorType)
        self.assertEqual(0, calls)

    async def test_registry_cancels_running_handler_and_waits_for_cleanup(self) -> None:
        registry = DomainToolRegistry()
        started = asyncio.Event()
        cleaned_up = asyncio.Event()

        async def handler(_payload):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned_up.set()

        registry.register("rank.lookup", "rank", {}, handler)
        token = CancellationToken()

        async def dispatch():
            with cancellation_scope(token):
                return await registry.dispatch("rank.lookup", {})

        task = asyncio.create_task(dispatch())
        await started.wait()
        token.cancel("running_tool_cancelled")

        run = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual("cancelled", run.status)
        self.assertEqual("RunCancelledError", run.errorType)
        await asyncio.wait_for(cleaned_up.wait(), timeout=2.0)


class DomainToolPlannerTest(unittest.TestCase):
    def test_planner_maps_task_graph_to_domain_tools(self) -> None:
        graph = TaskGraphDecomposer().decompose(
            "看最近番茄男频都市脑洞风向，参考榜一卖点，给我一个新题材。"
        )
        plan = DomainToolPlanner().plan(graph)
        by_task_type = {item.taskType: item for item in plan}

        self.assertIn("rank.lookup", by_task_type[TaskType.market_scan].tools)
        self.assertEqual(["rank.research_pack"], by_task_type[TaskType.book_breakdown].tools)
        self.assertEqual(["skill.lookup"], by_task_type[TaskType.topic_strategy].tools)

    def test_planner_keeps_book_pack_for_single_book_breakdown(self) -> None:
        graph = TaskGraphDecomposer().decompose("拆解《星河旧梦》的卖点和前三章钩子。")
        plan = DomainToolPlanner().plan(graph)
        by_task_type = {item.taskType: item for item in plan}

        self.assertIn("book.research_pack", by_task_type[TaskType.book_breakdown].tools)
        self.assertNotIn("rank.research_pack", by_task_type[TaskType.book_breakdown].tools)

    def test_planner_does_not_emit_skill_mutation_tool_for_normal_chat(self) -> None:
        graph = TaskGraphDecomposer().decompose("帮我新增一个 skill 并发布。")
        plan = DomainToolPlanner().plan(graph)

        self.assertTrue(graph.adminOperationRequested)
        self.assertEqual([], [tool for item in plan for tool in item.tools if tool.startswith("skill.")])

    def test_planner_does_not_expand_explicitly_empty_task_tools(self) -> None:
        graph = TaskGraph(
            userGoal="sealed market task",
            tasks=[TaskNode(
                id="task-1",
                type=TaskType.market_scan,
                goal="answer without tools",
                perspective=Perspective.market,
                tools=[],
            )],
        )

        plan = DomainToolPlanner().plan(graph)

        self.assertEqual([], plan[0].tools)


if __name__ == "__main__":
    unittest.main()
