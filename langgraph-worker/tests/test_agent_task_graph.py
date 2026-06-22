from __future__ import annotations

import unittest

from app.models.agent_task import TaskType
from app.services.intents.domain_intents import Intent, IntentDecision
from app.services.task_graph.decomposer import TaskGraphDecomposer


class TaskGraphDecomposerTest(unittest.TestCase):
    def test_composite_market_reference_outline_and_risk_question_creates_multi_task_graph(self) -> None:
        graph = TaskGraphDecomposer().decompose(
            "看最近番茄男频都市脑洞风向，参考榜一卖点，给我一个不撞车的新题材，前三章细纲也要，顺便从读者角度指出毒点。"
        )

        task_types = {task.type for task in graph.tasks}

        self.assertIn(TaskType.market_scan, task_types)
        self.assertIn(TaskType.book_breakdown, task_types)
        self.assertIn(TaskType.topic_strategy, task_types)
        self.assertIn(TaskType.chapter_outline, task_types)
        self.assertIn(TaskType.reader_risk, task_types)
        self.assertFalse(graph.adminOperationRequested)
        self.assertEqual("project_scoped", graph.projectMemoryPolicy)

    def test_skill_management_request_is_marked_admin_only_without_executable_action(self) -> None:
        graph = TaskGraphDecomposer().decompose("帮我新增一个 skill，并安装到系统里，普通用户以后都能用。")

        self.assertTrue(graph.adminOperationRequested)
        self.assertEqual([TaskType.skill_governance], [task.type for task in graph.tasks])
        self.assertEqual([], graph.tasks[0].tools)
        self.assertIn("admin_only", graph.answerBoundary)

    def test_creative_outline_question_does_not_force_rank_tool(self) -> None:
        graph = TaskGraphDecomposer().decompose("我想写一本修仙文，帮我设计主角人设和前三章细纲。")

        task_types = {task.type for task in graph.tasks}
        tools = {tool for task in graph.tasks for tool in task.tools}

        self.assertIn(TaskType.character_design, task_types)
        self.assertIn(TaskType.chapter_outline, task_types)
        self.assertNotIn(TaskType.market_scan, task_types)
        self.assertNotIn("rank.lookup", tools)

    def test_market_task_carries_latest_freshness_policy(self) -> None:
        decision = IntentDecision(
            primaryIntent=Intent.market_scan,
            sourcePolicy={
                "freshness": "latest",
                "allowHistorical": False,
                "requireSnapshotTime": True,
            },
        )

        graph = TaskGraphDecomposer().decompose("最近都市脑洞还能不能写？", intent_decision=decision)
        market_task = next(task for task in graph.tasks if task.type is TaskType.market_scan)

        self.assertEqual("latest", market_task.freshnessPolicy.get("freshness"))
        self.assertFalse(market_task.freshnessPolicy.get("allowHistorical"))
        self.assertTrue(market_task.freshnessPolicy.get("requireSnapshotTime"))

    def test_history_market_task_carries_time_window_policy(self) -> None:
        decision = IntentDecision(
            primaryIntent=Intent.market_scan,
            sourcePolicy={
                "freshness": "time_window",
                "allowHistorical": True,
                "timeWindowDays": 30,
            },
        )

        graph = TaskGraphDecomposer().decompose("近30天都市脑洞有什么变化？", intent_decision=decision)
        market_task = next(task for task in graph.tasks if task.type is TaskType.market_scan)

        self.assertEqual("time_window", market_task.freshnessPolicy.get("freshness"))
        self.assertTrue(market_task.freshnessPolicy.get("allowHistorical"))
        self.assertEqual(30, market_task.freshnessPolicy.get("timeWindowDays"))


if __name__ == "__main__":
    unittest.main()
