from __future__ import annotations

import asyncio
import unittest

from app.models.agent_task import Perspective, RetrievalPlan, RunToolIdentity, TaskGraph, TaskNode, TaskType, ToolPlan
from app.services.harness.tool_ledger import RunToolLedger
from app.services.task_graph.executor import DomainTaskToolExecutor
from app.services.tools.registry import DomainToolRegistry


class DomainTaskToolExecutorTest(unittest.IsolatedAsyncioTestCase):
    def _executor(self, registry: DomainToolRegistry) -> DomainTaskToolExecutor:
        return DomainTaskToolExecutor(
            registry,
            tool_ledger=RunToolLedger(RunToolIdentity(
                runId="domain-task-tool-test",
                userId=7,
                projectId=99,
                route="task_graph",
            )),
        )

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

        runs = await self._executor(registry).execute(
            graph,
            plans,
            allowed_tools={"rank.lookup"},
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

        runs = await self._executor(DomainToolRegistry()).execute(
            graph,
            plans,
            context={},
            allowed_tools={"memory.project_context"},
        )

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

        await self._executor(registry).execute(
            graph,
            plans,
            allowed_tools={"rank.lookup"},
            context={
                "query": "都市脑洞最新榜单",
                "sourcePolicy": {
                    "freshness": "time_window",
                    "allowHistorical": True,
                    "snapshotStartDate": "2026-08-03",
                    "snapshotEndDate": "2026-08-09",
                    "requireSnapshotTime": True,
                },
            },
        )

        self.assertEqual("time_window", seen_payloads[0]["freshness"])
        self.assertTrue(seen_payloads[0]["allowHistorical"])
        self.assertEqual("2026-08-03", seen_payloads[0]["snapshotStartDate"])
        self.assertEqual("2026-08-09", seen_payloads[0]["snapshotEndDate"])
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

        runs = await self._executor(registry).execute(
            graph,
            plans,
            allowed_tools={"knowledge.vector_search"},
            context={"query": "都市脑洞", "toolTimeoutMillis": 20},
        )

        self.assertEqual(1, len(runs))
        self.assertEqual("knowledge.vector_search", runs[0].name)
        self.assertEqual("timed_out", runs[0].status)
        self.assertEqual("ToolTimeout", runs[0].errorType)

    async def test_project_resolution_updates_scope_for_following_tools(self) -> None:
        registry = DomainToolRegistry()
        seen_payloads: list[dict] = []

        async def resolve_project(payload):
            return {
                "status": "resolved",
                "userId": payload["userId"],
                "projectId": 910,
                "workId": 920,
                "title": "诸天外包特效师",
            }

        async def list_foreshadowings(payload):
            seen_payloads.append(dict(payload))
            return {"items": [{"projectId": 910, "workId": 920, "title": "月背信号", "status": "OPEN"}]}

        registry.register("project.resolve", "project", {"type": "object"}, resolve_project)
        registry.register("project.foreshadowing.list", "project", {"type": "object"}, list_foreshadowings)
        graph = TaskGraph(
            userGoal="检查未回收伏笔",
            tasks=[TaskNode(
                id="task_project",
                type=TaskType.foreshadowing_audit,
                goal="Find unresolved foreshadowing.",
                perspective=Perspective.editor,
            )],
        )
        plans = [ToolPlan(
            taskId="task_project",
            taskType=TaskType.foreshadowing_audit,
            tools=["project.resolve", "project.foreshadowing.list"],
            required=True,
        )]

        runs = await self._executor(registry).execute(
            graph,
            plans,
            allowed_tools={"project.resolve", "project.foreshadowing.list"},
            context={"userId": 7, "query": "未回收伏笔"},
        )

        self.assertEqual(["succeeded", "succeeded"], [run.status for run in runs])
        self.assertEqual(910, seen_payloads[0]["projectId"])
        self.assertEqual(920, seen_payloads[0]["workId"])
        self.assertEqual(910, runs[1].input["projectId"])
        self.assertEqual(920, runs[1].input["workId"])

    async def test_project_retrieve_requires_plan_and_expands_only_supported_request_fields(self) -> None:
        registry = DomainToolRegistry()
        retrieval_payloads: list[dict] = []

        async def resolve_project(payload):
            return {
                "status": "resolved",
                "userId": payload["userId"],
                "projectId": 910,
                "workId": 920,
                "title": "Project Vector Novel",
            }

        async def retrieve_project(payload):
            retrieval_payloads.append(dict(payload))
            return {"evidence": []}

        registry.register("project.resolve", "project", {"type": "object"}, resolve_project)
        registry.register("project.retrieve", "project", {"type": "object"}, retrieve_project)
        graph = TaskGraph(
            userGoal="Check continuity.",
            tasks=[TaskNode(
                id="task_project",
                type=TaskType.continuity_check,
                goal="Check continuity.",
                perspective=Perspective.editor,
            )],
        )
        retrieval_plan = RetrievalPlan(
            query="Compare chapters 2 to 7.",
            intent="continuity_check",
            entities=["Lin Zhou", "moon signal"],
            chapterFrom=2,
            chapterTo=7,
            channels=["structured", "fulltext", "graph"],
            filters={"chapterFrom": 2, "chapterTo": 7},
            weights={"structured": 0.9, "fulltext": 0.7, "graph": 1.0},
            limit=8,
            deep=True,
            graphBudgetMillis=175,
            timeoutMillis=1500,
            rerankPolicy="raw_score",
        )
        plans = [ToolPlan(
            taskId="task_project",
            taskType=TaskType.continuity_check,
            tools=["project.resolve", "project.retrieve"],
            required=True,
            retrievalPlan=retrieval_plan,
        )]

        runs = await self._executor(registry).execute(
            graph,
            plans,
            allowed_tools={"project.resolve", "project.retrieve"},
            context={"userId": 7, "query": "ignored global query", "projectQuery": "Project Vector Novel"},
        )

        self.assertEqual(["succeeded", "succeeded"], [run.status for run in runs])
        self.assertEqual(1, len(retrieval_payloads))
        self.assertEqual({
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "Compare chapters 2 to 7.",
            "intent": "continuity_check",
            "entities": ["Lin Zhou", "moon signal"],
            "chapterFrom": 2,
            "chapterTo": 7,
            "channels": ["structured", "fulltext", "graph"],
            "filters": {"chapterFrom": 2, "chapterTo": 7},
            "weights": {"structured": 0.9, "fulltext": 0.7, "graph": 1.0},
            "limit": 8,
            "deep": True,
            "graphBudgetMillis": 175,
            "timeoutMillis": 1500,
            "rerankPolicy": "raw_score",
        }, {
            key: retrieval_payloads[0][key]
            for key in (
                "userId", "projectId", "workId", "query", "intent", "entities", "chapterFrom", "chapterTo",
                "channels", "filters", "weights", "limit", "deep", "graphBudgetMillis", "timeoutMillis",
                "rerankPolicy",
            )
        })

    async def test_project_retrieve_fails_closed_without_retrieval_plan(self) -> None:
        registry = DomainToolRegistry()
        registry.register("project.retrieve", "project", {"type": "object"}, lambda _payload: {"evidence": []})
        graph = TaskGraph(
            userGoal="Retrieve project.",
            tasks=[TaskNode(
                id="task_project",
                type=TaskType.project_knowledge_qa,
                goal="Retrieve project.",
                perspective=Perspective.author,
            )],
        )

        runs = await self._executor(registry).execute(
            graph,
            [ToolPlan(
                taskId="task_project",
                taskType=TaskType.project_knowledge_qa,
                tools=["project.retrieve"],
                required=True,
            )],
            allowed_tools={"project.retrieve"},
            context={"userId": 7, "projectId": 910, "workId": 920},
        )

        self.assertEqual("failed", runs[0].status)
        self.assertEqual("RetrievalPlanRequired", runs[0].errorType)

    async def test_project_retrieve_expands_only_active_and_explicit_reference_scopes(self) -> None:
        registry = DomainToolRegistry()
        seen: list[dict] = []

        async def retrieve(payload):
            seen.append(dict(payload))
            return {"evidence": [{
                "userId": payload["userId"],
                "projectId": payload["projectId"],
                "workId": payload["workId"],
                "title": payload.get("projectWorkTitle"),
            }]}

        registry.register("project.retrieve", "project", {"type": "object"}, retrieve)
        graph = TaskGraph(
            userGoal="Compare selected works.",
            tasks=[TaskNode(
                id="task_project",
                type=TaskType.project_knowledge_qa,
                goal="Compare selected works.",
                perspective=Perspective.author,
            )],
        )
        plan = ToolPlan(
            taskId="task_project",
            taskType=TaskType.project_knowledge_qa,
            tools=["project.retrieve"],
            required=True,
            retrievalPlan=RetrievalPlan(query="compare", intent="project_knowledge_qa", limit=6),
        )

        runs = await self._executor(registry).execute(
            graph,
            [plan],
            allowed_tools={"project.retrieve"},
            context={
                "userId": 7,
                "projectId": 10,
                "workId": 100,
                "projectWorkTitle": "Current",
                "referenceWorks": [
                    {"projectId": 20, "workId": 200, "title": "Reference A"},
                    {"projectId": 30, "workId": 300, "title": "Reference B"},
                ],
            },
        )

        self.assertEqual([(10, 100), (20, 200), (30, 300)], [
            (item["projectId"], item["workId"]) for item in seen
        ])
        self.assertEqual(3, len(runs))
        self.assertTrue(all("referenceWorks" not in run.input for run in runs))
        self.assertEqual(["active", "reference", "reference"], [
            run.input["projectScopeRole"] for run in runs
        ])
        self.assertEqual(["10", "20", "30"], [run.projectId for run in runs])
        self.assertTrue(all(run.userId == "7" for run in runs))

    async def test_project_retrieve_identity_contains_all_retrieval_semantics(self) -> None:
        executor = self._executor(DomainToolRegistry())
        payload = {
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "Compare chapters 2 to 7.",
            "intent": "continuity_check",
            "entities": ["Lin Zhou", "moon signal"],
            "chapterFrom": 2,
            "chapterTo": 7,
            "channels": ["structured", "fulltext", "graph"],
            "filters": {"chapterFrom": 2, "chapterTo": 7},
            "weights": {"structured": 0.9, "fulltext": 0.7, "graph": 1.0},
            "limit": 8,
            "deep": True,
            "graphBudgetMillis": 175,
            "timeoutMillis": 1500,
            "rerankPolicy": "raw_score",
            "history": [{"role": "user", "content": "not part of identity"}],
        }

        identity = executor._identity_payload_for_tool("project.retrieve", payload)

        self.assertEqual({
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "Compare chapters 2 to 7.",
            "intent": "continuity_check",
            "entities": ["Lin Zhou", "moon signal"],
            "chapterFrom": 2,
            "chapterTo": 7,
            "channels": ["structured", "fulltext", "graph"],
            "filters": {"chapterFrom": 2, "chapterTo": 7},
            "weights": {"structured": 0.9, "fulltext": 0.7, "graph": 1.0},
            "limit": 8,
            "deep": True,
            "graphBudgetMillis": 175,
            "timeoutMillis": 1500,
            "rerankPolicy": "raw_score",
        }, identity)

    def test_project_retrieve_uses_stricter_context_or_plan_timeout(self) -> None:
        executor = self._executor(DomainToolRegistry())

        self.assertEqual(20, executor._timeout_millis({"toolTimeoutMillis": 100}, {"timeoutMillis": 20}))
        self.assertEqual(25, executor._timeout_millis({"toolTimeoutMillis": 25}, {"timeoutMillis": 100}))


if __name__ == "__main__":
    unittest.main()
