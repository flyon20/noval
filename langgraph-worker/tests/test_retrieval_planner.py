from __future__ import annotations

import unittest

from app.models.agent_task import Perspective, TaskNode, TaskType
from app.services.harness.contracts import DataAccessRequest
from app.services.harness.retrieval_planner import ProjectRetrievalPlanner


class ProjectRetrievalPlannerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.planner = ProjectRetrievalPlanner()

    def test_builds_bounded_deep_plan_for_continuity_range(self) -> None:
        task = TaskNode(
            id="continuity",
            type=TaskType.continuity_check,
            goal="Check continuity.",
            perspective=Perspective.editor,
        )

        plan = self.planner.plan(
            task,
            question="Compare chapter 2 to chapter 7 for the protagonist's motivation.",
            entities={"bookName": "Project Alpha", "constraints": ["motivation"]},
            limit=99,
        )

        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual("continuity_check", plan.intent)
        self.assertEqual(2, plan.chapterFrom)
        self.assertEqual(7, plan.chapterTo)
        self.assertTrue(plan.deep)
        self.assertEqual(20, plan.limit)
        self.assertEqual(["structured", "fulltext", "vector", "graph"], plan.channels)
        self.assertIn("Project Alpha", plan.entities)
        self.assertIn("motivation", plan.entities)

    def test_uses_default_hybrid_plan_for_project_question(self) -> None:
        task = TaskNode(
            id="project-qa",
            type=TaskType.project_knowledge_qa,
            goal="Retrieve project evidence.",
            perspective=Perspective.author,
        )

        plan = self.planner.plan(task, question="Where was the signal introduced?", limit=5)

        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertFalse(plan.deep)
        self.assertEqual(5, plan.limit)
        self.assertEqual("intent_aware", plan.rerankPolicy)
        self.assertEqual(300, plan.graphBudgetMillis)

    def test_extracts_first_ten_chapters_from_chinese_review_request(self) -> None:
        task = TaskNode(
            id="project-qa",
            type=TaskType.project_knowledge_qa,
            goal="Review the opening chapters.",
            perspective=Perspective.author,
        )

        plan = self.planner.plan(
            task,
            question="你觉得我写的这十章，设计的如何",
            limit=5,
        )

        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(1, plan.chapterFrom)
        self.assertEqual(10, plan.chapterTo)
        self.assertEqual(10, plan.limit)

    def test_authorized_limit_caps_explicit_chapter_range(self) -> None:
        task = TaskNode(
            id="project-qa",
            type=TaskType.project_knowledge_qa,
            goal="Review the requested chapters.",
            perspective=Perspective.author,
        )
        data_access_request = DataAccessRequest(
            datasetCapability="project.knowledge",
            purpose="project_recall",
            semanticQuery="分析第2章到第7章的节奏",
            limit=4,
        )

        plan = self.planner.plan(
            task,
            question="分析第2章到第7章的节奏",
            limit=5,
            data_access_request=data_access_request,
        )

        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual((2, 7), (plan.chapterFrom, plan.chapterTo))
        self.assertEqual(4, plan.limit)

    def test_extracts_first_ten_chapters_from_prefix_request(self) -> None:
        task = TaskNode(
            id="project-qa",
            type=TaskType.project_knowledge_qa,
            goal="Review the opening chapters.",
            perspective=Perspective.author,
        )

        plan = self.planner.plan(task, question="请点评前十章的节奏")

        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual((1, 10), (plan.chapterFrom, plan.chapterTo))

    def test_extracts_chinese_chapter_range_and_single_chapter(self) -> None:
        task = TaskNode(
            id="project-qa",
            type=TaskType.project_knowledge_qa,
            goal="Review chapters.",
            perspective=Perspective.author,
        )

        range_plan = self.planner.plan(task, question="分析第2章到第7章的伏笔")
        single_plan = self.planner.plan(task, question="第十二章发生了什么")

        assert range_plan is not None
        assert single_plan is not None
        self.assertEqual((2, 7), (range_plan.chapterFrom, range_plan.chapterTo))
        self.assertEqual((12, 12), (single_plan.chapterFrom, single_plan.chapterTo))

    def test_does_not_treat_unscoped_numbers_as_chapters(self) -> None:
        task = TaskNode(
            id="project-qa",
            type=TaskType.project_knowledge_qa,
            goal="Review the project.",
            perspective=Perspective.author,
        )

        plan = self.planner.plan(task, question="帮我比较十个角色在2025到2026年的成长")

        assert plan is not None
        self.assertIsNone(plan.chapterFrom)
        self.assertIsNone(plan.chapterTo)

    def test_returns_no_plan_for_non_project_task(self) -> None:
        task = TaskNode(
            id="market",
            type=TaskType.market_scan,
            goal="Scan market.",
            perspective=Perspective.market,
        )

        self.assertIsNone(self.planner.plan(task, question="market", limit=5))


if __name__ == "__main__":
    unittest.main()
