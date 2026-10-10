from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.models.agent_task import TaskType
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import CapabilityScope
from app.services.intents.domain_intents import Intent, IntentDecision
from app.services.runtime.intent_agent import IntentAgent
from app.services.task_graph.decomposer import TaskGraphDecomposer
from app.services.task_graph.planner import DomainToolPlanner


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

    def test_foreshadowing_audit_creates_project_scoped_retrieval_task(self) -> None:
        graph = TaskGraphDecomposer().decompose("我这本书还有哪些伏笔和暗线没有回收？")

        self.assertEqual([TaskType.foreshadowing_audit], [task.type for task in graph.tasks])
        self.assertEqual("project_knowledge", graph.answerBoundary)
        self.assertEqual(
            [
                "project.resolve",
                "project.retrieve",
            ],
            graph.tasks[0].tools,
        )
        self.assertEqual("project_bound_chapter_or_memory_evidence", graph.tasks[0].evidencePolicy)

    def test_foreshadowing_count_wording_routes_to_exact_aggregate(self) -> None:
        for question in ("我前面一共有多少伏笔？", "我埋了几条暗线？"):
            with self.subTest(question=question):
                graph = TaskGraphDecomposer().decompose(question)

                self.assertEqual([TaskType.foreshadowing_audit], [task.type for task in graph.tasks])
                self.assertEqual(
                    ["project.resolve", "project.foreshadowing.aggregate", "project.retrieve"],
                    graph.tasks[0].tools,
                )

    def test_continuity_question_creates_project_evidence_task_without_market_tools(self) -> None:
        graph = TaskGraphDecomposer().decompose("第12章和第37章的人物动机是否冲突，时间线有没有矛盾？")

        task_types = {task.type for task in graph.tasks}
        tools = {tool for task in graph.tasks for tool in task.tools}
        self.assertIn(TaskType.continuity_check, task_types)
        self.assertIn("project.retrieve", tools)
        self.assertNotIn("project.chunk_search", tools)
        self.assertNotIn("project.chapter_search", tools)
        self.assertNotIn("rank.lookup", tools)

    def test_authored_chapter_review_and_project_recall_require_project_retrieval(self) -> None:
        for question in (
            "你觉得我写的这十章，设计的如何",
            "当前项目不是有吗，章节",
        ):
            with self.subTest(question=question):
                graph = TaskGraphDecomposer().decompose(question)

                self.assertEqual([TaskType.project_knowledge_qa], [task.type for task in graph.tasks])
                self.assertEqual(["project.resolve", "project.retrieve"], graph.tasks[0].tools)
                self.assertEqual("project_knowledge", graph.answerBoundary)

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
            entities={"marketRequestLevel": "ANALYSIS"},
            sourcePolicy={
                "freshness": "time_window",
                "allowHistorical": True,
                "timeWindowDays": 30,
                "currentRankLimit": 30,
                "snapshotCount": 2,
            },
        )

        graph = TaskGraphDecomposer().decompose("近30天都市脑洞有什么变化？", intent_decision=decision)
        market_task = next(task for task in graph.tasks if task.type is TaskType.market_scan)

        self.assertEqual("time_window", market_task.freshnessPolicy.get("freshness"))
        self.assertTrue(market_task.freshnessPolicy.get("allowHistorical"))
        self.assertEqual(30, market_task.freshnessPolicy.get("timeWindowDays"))
        self.assertEqual(30, market_task.freshnessPolicy.get("currentRankLimit"))
        self.assertEqual(2, market_task.freshnessPolicy.get("snapshotCount"))

    def test_compiled_market_plan_prunes_category_words_that_look_like_creation_tasks(self) -> None:
        request = KnowledgeChatRequest(question="男频都市脑洞新书榜最近热度")
        decision = IntentDecision(primaryIntent=Intent.market_scan, confidence=0.96)
        envelope = IntentAgent().to_envelope(decision, request=request)
        capability_plan = CapabilityCompiler().compile(
            envelope,
            request_scope=CapabilityScope(),
        )

        graph = TaskGraphDecomposer().decompose(
            request.question,
            intent_decision=decision,
            capability_plan=capability_plan,
        )

        self.assertEqual([TaskType.market_scan], [task.type for task in graph.tasks])
        self.assertEqual(["rank.lookup"], graph.tasks[0].tools)
        self.assertEqual("market_evidence", graph.answerBoundary)

    def test_market_taxonomy_questions_do_not_add_topic_strategy_task(self) -> None:
        cases = [
            ("榜单里为什么没有福娃题材，是不是不火？", "taxonomy_absence"),
            ("福娃这种题材一般叫什么，还有什么别名？", "taxonomy_classification"),
            ("福娃有哪些同类题材和融合方向？", "derivative_genre"),
        ]

        for question, market_question_type in cases:
            with self.subTest(question=question):
                decision = IntentDecision(
                    primaryIntent=Intent.market_scan,
                    confidence=0.96,
                    entities={
                        "marketRequestLevel": "ANALYSIS",
                        "marketQuestionType": market_question_type,
                    },
                )

                graph = TaskGraphDecomposer().decompose(
                    question,
                    intent_decision=decision,
                )

                self.assertEqual([TaskType.market_scan], [task.type for task in graph.tasks])

    def test_compiled_ranked_book_imitation_uses_rank_research_pack(self) -> None:
        request = KnowledgeChatRequest(
            question="根据当前男频都市脑洞新书榜第一的书，模仿题材并给前三章细纲。"
        )
        intent_agent = IntentAgent()
        decision = intent_agent.router.classify(request.question)
        capability_plan = CapabilityCompiler().compile(
            intent_agent.to_envelope(decision, request=request),
            request_scope=CapabilityScope(),
        )

        graph = TaskGraphDecomposer().decompose(
            request.question,
            intent_decision=decision,
            capability_plan=capability_plan,
        )
        book_task = next(task for task in graph.tasks if task.type is TaskType.book_breakdown)
        topic_task = next(task for task in graph.tasks if task.type is TaskType.topic_strategy)
        book_plan = next(
            plan
            for plan in DomainToolPlanner().plan(graph)
            if plan.taskType is TaskType.book_breakdown
        )

        self.assertEqual(["rank.research_pack"], book_task.tools)
        self.assertEqual(["skill.lookup"], topic_task.tools)
        self.assertEqual(["rank.research_pack"], book_plan.tools)
        self.assertIn("book.source_material", capability_plan.evidenceRequirements)

    def test_market_context_does_not_rebind_the_users_named_book_to_rank_research(self) -> None:
        graph = TaskGraphDecomposer().decompose(
            "结合男频都市脑洞新书榜趋势，拆解《星河旧梦》的卖点。"
        )
        book_task = next(task for task in graph.tasks if task.type is TaskType.book_breakdown)
        book_plan = next(
            plan
            for plan in DomainToolPlanner().plan(graph)
            if plan.taskType is TaskType.book_breakdown
        )

        self.assertIn(TaskType.market_scan, {task.type for task in graph.tasks})
        self.assertEqual(["book.research_pack", "knowledge.vector_search"], book_task.tools)
        self.assertEqual(book_task.tools, book_plan.tools)

    def test_moe_governance_named_task_graph_cases(self) -> None:
        cases = [
            {
                "name": "pure_market",
                "question": "最近男频新书榜单趋势怎么样？",
                "expected_types": {TaskType.market_scan},
                "forbidden_types": {TaskType.topic_strategy, TaskType.chapter_outline},
                "answer_boundary": "market_evidence",
            },
            {
                "name": "market_plus_creation",
                "question": "参考榜单趋势，给我一个都市脑洞新题材和前三章细纲。",
                "expected_types": {TaskType.market_scan, TaskType.topic_strategy, TaskType.chapter_outline},
                "forbidden_types": set(),
                "answer_boundary": "market_evidence_plus_author_inference",
            },
            {
                "name": "single_book_breakdown",
                "question": "拆解《星河旧梦》的卖点和前三章钩子。",
                "expected_types": {TaskType.book_breakdown},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "book_evidence_plus_craft_extraction",
            },
            {
                "name": "outline_chapter_outline",
                "question": "帮我做一本修仙文的大纲和前三章细纲。",
                "expected_types": {TaskType.outline_building, TaskType.chapter_outline},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "revision",
                "question": "帮我给这段开篇改稿，指出问题并给修改建议。",
                "expected_types": {TaskType.revision_advice},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "memory_project_contamination",
                "question": "沿用上个项目的设定给当前项目写前三章细纲。",
                "expected_types": {TaskType.chapter_outline},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
                "expected_tools": {"memory.project_context"},
            },
            {
                "name": "admin_governance_refusal",
                "question": "帮我新增一个 skill 并发布给所有用户。",
                "expected_types": {TaskType.skill_governance},
                "forbidden_types": {TaskType.market_scan, TaskType.topic_strategy},
                "answer_boundary": "admin_only_skill_governance",
                "admin": True,
            },
            {
                "name": "pure_market_top10",
                "question": "看一下男频新书榜 top10 的趋势。",
                "expected_types": {TaskType.market_scan},
                "forbidden_types": {TaskType.topic_strategy, TaskType.chapter_outline},
                "answer_boundary": "market_evidence",
                "expected_tools": {"rank.lookup"},
            },
            {
                "name": "pure_market_ranking_fact",
                "question": "当前榜一是哪本书，排名依据是什么？",
                "expected_types": {TaskType.market_scan},
                "forbidden_types": {TaskType.topic_strategy, TaskType.outline_building},
                "answer_boundary": "market_evidence",
            },
            {
                "name": "pure_market_hot_topics",
                "question": "最近热门题材只做市场扫描，不要给我新书方案。",
                "expected_types": {TaskType.market_scan},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence_plus_author_inference",
            },
            {
                "name": "market_plus_reference_book",
                "question": "参考榜一卖点，拆解热书为什么能上榜。",
                "expected_types": {TaskType.market_scan, TaskType.book_breakdown},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence",
            },
            {
                "name": "market_plus_topic_strategy",
                "question": "结合新书榜趋势，给一个不撞车的都市脑洞方向。",
                "expected_types": {TaskType.market_scan, TaskType.topic_strategy},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence_plus_author_inference",
            },
            {
                "name": "market_plus_outline",
                "question": "扫榜后给我一个可写三卷的主线结构。",
                "expected_types": {TaskType.market_scan, TaskType.outline_building},
                "forbidden_types": set(),
                "answer_boundary": "market_evidence_plus_author_inference",
            },
            {
                "name": "market_plus_character",
                "question": "看榜单趋势后，设计主角人设和核心卖点。",
                "expected_types": {TaskType.market_scan, TaskType.character_design, TaskType.book_breakdown},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence",
            },
            {
                "name": "market_plus_worldbuilding",
                "question": "根据市场风向，设计一个都市异能体系和规则。",
                "expected_types": {TaskType.market_scan, TaskType.worldbuilding},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence",
            },
            {
                "name": "market_plus_reader_risk",
                "question": "看排行风向，指出这个题材可能的读者毒点。",
                "expected_types": {TaskType.market_scan, TaskType.topic_strategy, TaskType.reader_risk},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence_plus_author_inference",
            },
            {
                "name": "market_plus_editor_risk",
                "question": "结合市场趋势，判断这个方向的签约和商业性风险。",
                "expected_types": {TaskType.market_scan, TaskType.topic_strategy, TaskType.editor_risk},
                "forbidden_types": {TaskType.chapter_outline},
                "answer_boundary": "market_evidence_plus_author_inference",
            },
            {
                "name": "book_breakdown_selling_points",
                "question": "拆书《都市神医》的卖点和爆款结构。",
                "expected_types": {TaskType.book_breakdown},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "book_evidence_plus_craft_extraction",
            },
            {
                "name": "book_breakdown_opening",
                "question": "拆解《豪门弃少归来》前三章章节钩子。",
                "expected_types": {TaskType.book_breakdown, TaskType.chapter_outline},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "book_evidence_plus_craft_extraction",
            },
            {
                "name": "book_breakdown_no_rank",
                "question": "只分析这本热书的人物和卖点。",
                "expected_types": {TaskType.book_breakdown, TaskType.character_design},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "book_evidence_plus_craft_extraction",
            },
            {
                "name": "topic_strategy_only",
                "question": "给我一个都市脑洞新题材和开书定位。",
                "expected_types": {TaskType.topic_strategy},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "outline_only",
                "question": "帮我做一个三幕结构和三卷大纲。",
                "expected_types": {TaskType.outline_building},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "chapter_outline_only",
                "question": "写前3章细纲，每章要有冲突和钩子。",
                "expected_types": {TaskType.chapter_outline},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
                "expected_tools": {"memory.project_context"},
            },
            {
                "name": "character_design_only",
                "question": "设计主角、配角和反派人物关系。",
                "expected_types": {TaskType.character_design},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "worldbuilding_only",
                "question": "设计世界观、势力体系和能力规则。",
                "expected_types": {TaskType.worldbuilding},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "outline_character_worldbuilding",
                "question": "给我大纲、人设和世界观设定。",
                "expected_types": {TaskType.outline_building, TaskType.character_design, TaskType.worldbuilding},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "revision_rewrite",
                "question": "把这段开篇重写并润色，给修改建议。",
                "expected_types": {TaskType.revision_advice},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
            },
            {
                "name": "reader_risk_only",
                "question": "从读者角度看这个开局有哪些毒点和劝退风险？",
                "expected_types": {TaskType.reader_risk},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
                "expected_tools": {"reader.simulate_feedback"},
            },
            {
                "name": "editor_risk_only",
                "question": "从编辑角度看这个选题过稿和签约概率。",
                "expected_types": {TaskType.topic_strategy, TaskType.editor_risk},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
                "expected_tools": {"editor.risk_check"},
            },
            {
                "name": "followup_context_only",
                "question": "继续上面的方案，扩成下一版。",
                "expected_types": {TaskType.followup_context},
                "forbidden_types": {TaskType.market_scan, TaskType.book_breakdown},
                "answer_boundary": "creative_inference",
                "expected_tools": {"memory.project_context"},
            },
            {
                "name": "project_memory_current_only",
                "question": "沿用当前项目记忆，补一个章节细纲和人物弧线。",
                "expected_types": {TaskType.chapter_outline, TaskType.character_design},
                "forbidden_types": {TaskType.market_scan},
                "answer_boundary": "creative_inference",
                "expected_tools": {"memory.project_context"},
            },
            {
                "name": "admin_skill_modify_refusal",
                "question": "帮我修改 skill 并 approve 给全站。",
                "expected_types": {TaskType.skill_governance},
                "forbidden_types": {TaskType.market_scan, TaskType.topic_strategy},
                "answer_boundary": "admin_only_skill_governance",
                "admin": True,
            },
            {
                "name": "admin_skill_disable_refusal",
                "question": "把这个 skill 禁用并删除。",
                "expected_types": {TaskType.skill_governance},
                "forbidden_types": {TaskType.market_scan, TaskType.topic_strategy},
                "answer_boundary": "admin_only_skill_governance",
                "admin": True,
            },
            {
                "name": "full_mixed_planning_bundle",
                "question": "扫榜、参考榜一卖点，给新题材、大纲、前三章细纲、人设、世界观、读者毒点和编辑风险。",
                "expected_types": {
                    TaskType.market_scan,
                    TaskType.book_breakdown,
                    TaskType.topic_strategy,
                    TaskType.outline_building,
                    TaskType.chapter_outline,
                    TaskType.character_design,
                    TaskType.worldbuilding,
                    TaskType.reader_risk,
                    TaskType.editor_risk,
                },
                "forbidden_types": set(),
                "answer_boundary": "market_evidence_plus_author_inference",
            },
        ]
        self.assertGreaterEqual(len(cases), 30)

        for case in cases:
            with self.subTest(case=case["name"]):
                graph = TaskGraphDecomposer().decompose(case["question"])
                task_types = {task.type for task in graph.tasks}

                self.assertTrue(case["expected_types"].issubset(task_types))
                self.assertTrue(task_types.isdisjoint(case["forbidden_types"]))
                self.assertEqual(case["answer_boundary"], graph.answerBoundary)
                self.assertEqual(bool(case.get("admin", False)), graph.adminOperationRequested)
                self.assertEqual("project_scoped", graph.projectMemoryPolicy)
                tools = {tool for task in graph.tasks for tool in task.tools}
                self.assertTrue(set(case.get("expected_tools", set())).issubset(tools))


if __name__ == "__main__":
    unittest.main()
