from __future__ import annotations

import unittest

from app.models.agent_task import Perspective, TaskGraph, TaskNode, TaskType, ToolPlan
from app.services.task_graph.executor import DomainTaskToolExecutor
from app.services.tools.registry import DomainToolRegistry


class DomainTaskToolExecutorPolicyTest(unittest.IsolatedAsyncioTestCase):
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
        executor = DomainTaskToolExecutor(registry)
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

    async def test_blocks_tools_after_max_tool_call_budget_is_exhausted(self) -> None:
        calls: list[str] = []
        registry = DomainToolRegistry()
        registry.register("rank.lookup", "rank", {}, lambda payload: calls.append("rank.lookup") or {"items": []})
        registry.register("rank.research_pack", "rank", {}, lambda payload: calls.append("rank.research_pack") or {"items": []})
        executor = DomainTaskToolExecutor(registry)
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
        executor = DomainTaskToolExecutor(registry)
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


if __name__ == "__main__":
    unittest.main()
