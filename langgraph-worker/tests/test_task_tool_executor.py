from __future__ import annotations

import unittest

from app.models.agent_task import Perspective, RetrievalPlan, RunToolIdentity, TaskGraph, TaskNode, TaskType, ToolPlan
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.harness.tool_ledger import RunToolLedger
from app.services.task_graph.executor import DomainTaskToolExecutor
from app.services.tools.registry import DomainToolRegistry


class DomainTaskToolExecutorPolicyTest(unittest.IsolatedAsyncioTestCase):
    def _ledger(self) -> RunToolLedger:
        return RunToolLedger(RunToolIdentity(
            runId="task-run-1",
            userId=7,
            projectId=91,
            route="market_scan",
        ))

    async def test_blocks_tools_outside_allowed_tools_before_dispatch(self) -> None:
        calls: list[str] = []
        registry = DomainToolRegistry()
        registry.register(
            "rank.lookup",
            "rank",
            {},
            lambda payload: calls.append("rank.lookup") or {"items": [{"rankNo": 1}]},
        )
        registry.register(
            "book.research_pack",
            "book",
            {},
            lambda payload: calls.append("book.research_pack") or {"items": [{"bookId": 1}]},
        )
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="scan market",
            tasks=[
                TaskNode(
                    id="task-1",
                    type=TaskType.market_scan,
                    goal="scan",
                    perspective=Perspective.market,
                )
            ],
        )
        plan = ToolPlan(
            taskId="task-1",
            taskType=TaskType.market_scan,
            tools=["rank.lookup", "book.research_pack"],
            required=True,
        )

        runs = await executor.execute(graph, [plan], allowed_tools={"rank.lookup"})

        self.assertEqual(["rank.lookup"], calls)
        self.assertEqual("succeeded", runs[0].status)
        self.assertEqual("failed", runs[1].status)
        self.assertEqual("ToolNotAllowed", runs[1].errorType)

    async def test_missing_allowed_tools_fails_closed_before_dispatch(self) -> None:
        calls: list[str] = []
        registry = DomainToolRegistry()
        registry.register(
            "rank.lookup",
            "rank",
            {},
            lambda payload: calls.append("rank.lookup") or {"items": []},
        )
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="scan market",
            tasks=[TaskNode(
                id="task-1",
                type=TaskType.market_scan,
                goal="scan",
                perspective=Perspective.market,
            )],
        )

        runs = await executor.execute(
            graph,
            [ToolPlan(
                taskId="task-1",
                taskType=TaskType.market_scan,
                tools=["rank.lookup"],
                required=True,
            )],
        )

        self.assertEqual([], calls)
        self.assertEqual("ToolNotAllowed", runs[0].errorType)

    async def test_blocks_tools_after_max_tool_call_budget_is_exhausted(self) -> None:
        calls: list[str] = []
        registry = DomainToolRegistry()
        registry.register("rank.lookup", "rank", {}, lambda payload: calls.append("rank.lookup") or {"items": []})
        registry.register("rank.research_pack", "rank", {}, lambda payload: calls.append("rank.research_pack") or {"items": []})
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="scan market",
            tasks=[
                TaskNode(
                    id="task-1",
                    type=TaskType.market_scan,
                    goal="scan",
                    perspective=Perspective.market,
                )
            ],
        )
        plan = ToolPlan(
            taskId="task-1",
            taskType=TaskType.market_scan,
            tools=["rank.lookup", "rank.research_pack"],
            required=True,
        )

        runs = await executor.execute(graph, [plan], allowed_tools={"rank.lookup", "rank.research_pack"}, max_tool_calls=1)

        self.assertEqual(["rank.lookup"], calls)
        self.assertEqual("succeeded", runs[0].status)
        self.assertEqual("failed", runs[1].status)
        self.assertEqual("ToolBudgetExceeded", runs[1].errorType)

    async def test_reserved_required_tools_can_run_after_general_budget_is_exhausted(self) -> None:
        calls: list[str] = []
        registry = DomainToolRegistry()
        for tool_name in ("rank.lookup", "rank.research_pack", "skill.lookup", "memory.project_context"):
            registry.register(tool_name, tool_name.split(".")[0], {}, lambda payload, name=tool_name: calls.append(name) or {"items": [name]})
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="scan and outline",
            tasks=[
                TaskNode(id="task-1", type=TaskType.market_scan, goal="scan", perspective=Perspective.market),
                TaskNode(id="task-2", type=TaskType.outline_building, goal="outline", perspective=Perspective.author),
            ],
        )
        plans = [
            ToolPlan(
                taskId="task-1",
                taskType=TaskType.market_scan,
                tools=["rank.lookup", "rank.research_pack"],
                required=True,
            ),
            ToolPlan(
                taskId="task-2",
                taskType=TaskType.outline_building,
                tools=["skill.lookup", "memory.project_context"],
                required=True,
            ),
        ]

        runs = await executor.execute(
            graph,
            plans,
            allowed_tools={"rank.lookup", "rank.research_pack", "skill.lookup", "memory.project_context"},
            max_tool_calls=2,
            reserved_required_tools={"skill.lookup", "memory.project_context"},
        )

        self.assertEqual(["rank.lookup", "rank.research_pack", "skill.lookup", "memory.project_context"], calls)
        self.assertTrue(all(run.status == "succeeded" for run in runs))

    async def test_exact_foreshadowing_aggregate_keeps_project_execution_order_under_tight_budget(self) -> None:
        calls: list[str] = []
        registry = DomainToolRegistry()

        def resolve(payload):
            calls.append("project.resolve")
            return {
                "status": "resolved",
                "userId": payload["userId"],
                "projectId": 91,
                "workId": 911,
                "title": "Project Novel",
            }

        def aggregate(_payload):
            calls.append("project.foreshadowing.aggregate")
            return {"userId": 7, "projectId": 91, "workId": 911, "count": 4, "complete": True}

        def retrieve(_payload):
            calls.append("project.retrieve")
            return {"evidence": []}

        registry.register("project.resolve", "project", {}, resolve)
        registry.register("project.foreshadowing.aggregate", "project", {}, aggregate)
        registry.register("project.retrieve", "project", {}, retrieve)
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="Count foreshadowing exactly.",
            tasks=[TaskNode(
                id="task-1",
                type=TaskType.foreshadowing_audit,
                goal="Count recognized foreshadowing and retrieve supporting evidence.",
                perspective=Perspective.editor,
            )],
        )
        plan = ToolPlan(
            taskId="task-1",
            taskType=TaskType.foreshadowing_audit,
            tools=["project.resolve", "project.foreshadowing.aggregate", "project.retrieve"],
            required=True,
            retrievalPlan=RetrievalPlan(query="foreshadowing", intent="foreshadowing_audit"),
        )

        runs = await executor.execute(
            graph,
            [plan],
            allowed_tools={"project.resolve", "project.foreshadowing.aggregate", "project.retrieve"},
            context={"userId": 7, "query": "foreshadowing"},
            max_tool_calls=1,
            reserved_required_tools={
                "project.resolve",
                "project.foreshadowing.aggregate",
                "project.retrieve",
            },
        )

        self.assertEqual(
            ["project.resolve", "project.foreshadowing.aggregate", "project.retrieve"],
            calls,
        )
        self.assertTrue(all(run.status == "succeeded" for run in runs))
        self.assertEqual(4, runs[1].output["count"])

    async def test_duplicate_read_plans_share_one_execution_and_one_budget_charge(self) -> None:
        calls: list[dict] = []
        registry = DomainToolRegistry()
        registry.register(
            "rank.lookup",
            "rank",
            {},
            lambda payload: calls.append(payload) or {"items": [{"rankNo": 1}]},
        )
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="scan twice",
            tasks=[TaskNode(id="task-1", type=TaskType.market_scan, goal="scan", perspective=Perspective.market)],
        )
        plans = [
            ToolPlan(taskId="task-1", taskType=TaskType.market_scan, tools=["rank.lookup"], required=True),
            ToolPlan(taskId="task-1", taskType=TaskType.market_scan, tools=["rank.lookup"], required=True),
        ]
        budget = RunBudget.fast()

        with run_budget_scope(budget):
            runs = await executor.execute(
                graph,
                plans,
                allowed_tools={"rank.lookup"},
                context={"query": "trend", "userId": 7, "projectId": 91},
            )

        self.assertEqual(1, len(calls))
        self.assertEqual(1, budget.used_tool_calls)
        self.assertEqual(runs[0].callId, runs[1].callId)
        self.assertFalse(runs[0].reused)
        self.assertTrue(runs[1].reused)

    async def test_timeout_returns_ledger_terminal_record(self) -> None:
        import asyncio

        registry = DomainToolRegistry()
        registry.register("rank.lookup", "rank", {}, lambda payload: asyncio.Event().wait())
        executor = DomainTaskToolExecutor(registry, tool_ledger=self._ledger())
        graph = TaskGraph(
            userGoal="slow scan",
            tasks=[TaskNode(id="task-1", type=TaskType.market_scan, goal="scan", perspective=Perspective.market)],
        )

        runs = await executor.execute(
            graph,
            [ToolPlan(taskId="task-1", taskType=TaskType.market_scan, tools=["rank.lookup"], required=True)],
            allowed_tools={"rank.lookup"},
            context={"query": "trend", "userId": 7, "projectId": 91, "toolTimeoutMillis": 10},
        )

        self.assertEqual("timed_out", runs[0].status)
        self.assertEqual("ToolTimeout", runs[0].errorType)


if __name__ == "__main__":
    unittest.main()
