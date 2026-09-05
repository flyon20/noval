from __future__ import annotations

import unittest

from app.services.harness.tool_ledger import run_tool_ledger_scope
from app.services.tools.domain_tools import build_domain_tool_registry


class NullKnowledgeClient:
    pass


class RecordingRankKnowledgeClient:
    def __init__(self) -> None:
        self.lookup_rank_calls: list[dict] = []
        self.rank_pack_calls: list[dict] = []

    async def lookup_rank(self, **kwargs) -> list:
        self.lookup_rank_calls.append(dict(kwargs))
        return []

    async def get_rank_research_pack(self, **kwargs) -> dict:
        self.rank_pack_calls.append(dict(kwargs))
        return {"ranks": [], "books": [], "chapters": [], "analyses": []}


class RecordingSensitiveKnowledgeClient:
    def __init__(self) -> None:
        self.book_pack_calls: list[dict] = []
        self.vector_calls: list[dict] = []

    async def get_book_research_pack(self, **kwargs) -> dict:
        self.book_pack_calls.append(dict(kwargs))
        return {"book": None, "ranks": [], "chapters": [], "analyses": []}

    async def search_evidence(self, **kwargs) -> list:
        self.vector_calls.append(dict(kwargs))
        return []


class RecordingProjectKnowledgeClient:
    def __init__(self) -> None:
        self.project_resolve_calls: list[dict] = []
        self.project_retrieval_calls: list[dict] = []
        self.foreshadowing_calls: list[dict] = []
        self.foreshadowing_aggregate_calls: list[dict] = []
        self.timeline_calls: list[dict] = []
        self.character_state_calls: list[dict] = []
        self.world_rule_calls: list[dict] = []

    async def resolve_project_work(self, **kwargs) -> dict:
        self.project_resolve_calls.append(dict(kwargs))
        return {
            "status": "resolved",
            "userId": kwargs["user_id"],
            "projectId": kwargs.get("project_id") or 910,
            "workId": kwargs.get("work_id") or 920,
            "title": "Project Vector Novel",
        }

    async def retrieve_project_knowledge(self, **kwargs) -> dict:
        self.project_retrieval_calls.append(dict(kwargs))
        return {
            "evidence": [
                {
                    "source": "project_document",
                    "backend": "structured",
                    "documentId": 101,
                    "sourceType": "CHAPTER",
                    "chapterId": 101,
                    "chapterNo": 12,
                    "generationId": 701,
                    "chapterVersion": 3,
                    "contentHash": "hash-101",
                    "title": "Delivery",
                    "preview": "admin signal remains unresolved",
                    "score": 0.91,
                }
            ],
            "gaps": [],
            "diagnostics": {"channels": {"structured": 1}},
            "partial": False,
        }

    async def list_project_foreshadowings(self, **kwargs) -> list:
        self.foreshadowing_calls.append(dict(kwargs))
        return [{"title": "moon-admin-signal", "status": "OPEN"}]

    async def aggregate_project_foreshadowings(self, **kwargs) -> dict:
        self.foreshadowing_aggregate_calls.append(dict(kwargs))
        return {
            "metric": "foreshadowing_count",
            "count": 3,
            "breakdown": {"OPEN": 2, "PAID_OFF": 1},
            "complete": True,
        }

    async def lookup_project_timeline(self, **kwargs) -> list:
        self.timeline_calls.append(dict(kwargs))
        return [{"title": "backend-signal", "chapterNo": 30}]

    async def lookup_project_character_states(self, **kwargs) -> list:
        self.character_state_calls.append(dict(kwargs))
        return [{"characterName": "lin-zhou", "stateSummary": "suspects-platform"}]

    async def lookup_project_world_rules(self, **kwargs) -> list:
        self.world_rule_calls.append(dict(kwargs))
        return [{"title": "three-terminal-settlement", "ruleType": "system"}]


class LegacyRankKnowledgeClient:
    def __init__(self) -> None:
        self.lookup_rank_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
    ) -> list:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        return [{"rankNo": 1}]


class DomainToolsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self._ledger_scope = run_tool_ledger_scope({
            "runId": "domain-tools-test",
            "userId": "7",
            "projectId": "910",
            "route": "project_creation",
        })
        self._ledger_scope.__enter__()
        self.addAsyncCleanup(self._ledger_scope.__exit__, None, None, None)
    async def test_skill_lookup_returns_only_run_eligible_descriptors_without_prompt_bodies(self) -> None:
        registry = build_domain_tool_registry(NullKnowledgeClient())

        run = await registry.dispatch("skill.lookup", {
            "taskType": "outline_building",
            "eligibleSkillIds": ["webnovel-outline-building", "webnovel-market-scan"],
            "activatedSkillIds": ["webnovel-outline-building"],
        })

        self.assertEqual("succeeded", run.status)
        skills = run.output["skills"]
        self.assertEqual(
            ["webnovel-outline-building", "webnovel-market-scan"],
            run.output["eligibleSkillIds"],
        )
        self.assertEqual(["webnovel-outline-building"], run.output["activatedSkillIds"])
        self.assertEqual(
            ["webnovel-outline-building", "webnovel-market-scan"],
            [skill["skillId"] for skill in skills],
        )
        self.assertEqual("ACTIVATED", skills[0]["state"])
        self.assertEqual("ELIGIBLE", skills[1]["state"])
        self.assertTrue(skills[0]["description"])
        self.assertNotIn("selectedSkills", run.output)
        self.assertNotIn("prompt", run.output)
        self.assertNotIn("promptPreview", run.output)

        no_eligible_ids = await registry.dispatch("skill.lookup", {"taskType": "outline_building"})
        self.assertEqual([], no_eligible_ids.output["skills"])
        self.assertEqual([], no_eligible_ids.output["eligibleSkillIds"])

        task_only_match = await registry.dispatch("skill.lookup", {
            "taskType": "reader_risk",
            "eligibleSkillIds": ["reader-risk-review"],
        })
        self.assertEqual(["reader-risk-review"], task_only_match.output["eligibleSkillIds"])

    async def test_rank_tools_forward_freshness_policy_to_backend_client(self) -> None:
        client = RecordingRankKnowledgeClient()
        registry = build_domain_tool_registry(client)
        payload = {
            "userId": 7,
            "projectId": 910,
            "platform": "fanqie",
            "channelCode": "male-new",
            "category": "urban-brain",
            "limit": 10,
            "freshness": "time_window",
            "allowHistorical": True,
            "timeWindowDays": 7,
            "snapshotStartDate": "2026-08-03",
            "snapshotEndDate": "2026-08-09",
            "requireSnapshotTime": True,
        }

        lookup_run = await registry.dispatch("rank.lookup", payload)
        pack_run = await registry.dispatch("rank.research_pack", payload)

        self.assertEqual("succeeded", lookup_run.status)
        self.assertEqual("succeeded", pack_run.status)
        for call in [client.lookup_rank_calls[0], client.rank_pack_calls[0]]:
            self.assertEqual("time_window", call["freshness"])
            self.assertTrue(call["allow_historical"])
            self.assertEqual(7, call["time_window_days"])
            self.assertEqual("2026-08-03", call["snapshot_start_date"])
            self.assertEqual("2026-08-09", call["snapshot_end_date"])
            self.assertTrue(call["require_snapshot_time"])
        self.assertEqual(7, client.rank_pack_calls[0]["user_id"])

    async def test_sensitive_domain_tools_forward_trusted_user_scope(self) -> None:
        client = RecordingSensitiveKnowledgeClient()
        registry = build_domain_tool_registry(client)
        payload = {
            "userId": 7,
            "projectId": 910,
            "platform": "fanqie",
            "bookId": 101,
            "query": "opening hook",
        }

        book_run = await registry.dispatch("book.research_pack", payload)
        vector_run = await registry.dispatch("knowledge.vector_search", payload)

        self.assertEqual("succeeded", book_run.status)
        self.assertEqual("succeeded", vector_run.status)
        self.assertEqual(7, client.book_pack_calls[0]["user_id"])
        self.assertEqual(7, client.vector_calls[0]["user_id"])

    async def test_rank_lookup_remains_compatible_with_clients_without_freshness_kwargs(self) -> None:
        client = LegacyRankKnowledgeClient()
        registry = build_domain_tool_registry(client)

        run = await registry.dispatch("rank.lookup", {
            "platform": "fanqie",
            "channelCode": "male-new",
            "category": "urban-brain",
            "limit": 10,
            "freshness": "latest",
            "allowHistorical": False,
            "timeWindowDays": 2,
            "requireSnapshotTime": True,
        })

        self.assertEqual("succeeded", run.status)
        self.assertEqual(1, run.resultCount)
        self.assertEqual("male-new", client.lookup_rank_calls[0]["channel_code"])

    async def test_legacy_project_search_tools_are_not_registered(self) -> None:
        client = RecordingProjectKnowledgeClient()
        registry = build_domain_tool_registry(client)

        for name in ("project.chapter_search", "project.chunk_search"):
            run = await registry.dispatch(name, {"userId": 7, "projectId": 910, "workId": 920})
            self.assertEqual("failed", run.status)
            self.assertEqual("ToolNotFound", run.errorType)


    async def test_project_resolve_tool_forwards_user_scope_and_title_query(self) -> None:
        client = RecordingProjectKnowledgeClient()
        registry = build_domain_tool_registry(client)

        run = await registry.dispatch("project.resolve", {
            "userId": 7,
            "query": "Project Vector Novel",
            "limit": 5,
        })

        self.assertEqual("succeeded", run.status)
        self.assertEqual("resolved", run.output["status"])
        self.assertEqual(
            {
                "user_id": 7,
                "project_id": None,
                "work_id": None,
                "query": "Project Vector Novel",
                "limit": 5,
            },
            client.project_resolve_calls[0],
        )

    async def test_project_structured_tools_filter_by_user_project_and_work(self) -> None:
        client = RecordingProjectKnowledgeClient()
        registry = build_domain_tool_registry(client)
        base_payload = {
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "signal",
            "limit": 5,
        }

        foreshadowing = await registry.dispatch(
            "project.foreshadowing.list",
            {**base_payload, "status": "OPEN"},
        )
        aggregate = await registry.dispatch("project.foreshadowing.aggregate", base_payload)
        timeline = await registry.dispatch("project.timeline_lookup", base_payload)
        character = await registry.dispatch("project.character_state_lookup", base_payload)
        world_rule = await registry.dispatch("project.world_rule_lookup", base_payload)

        self.assertEqual("succeeded", foreshadowing.status)
        self.assertEqual("succeeded", aggregate.status)
        self.assertEqual("succeeded", timeline.status)
        self.assertEqual("succeeded", character.status)
        self.assertEqual("succeeded", world_rule.status)
        self.assertEqual("OPEN", client.foreshadowing_calls[0]["status"])
        self.assertEqual(3, aggregate.output["count"])
        self.assertEqual(
            {"user_id": 7, "project_id": 910, "work_id": 920},
            client.foreshadowing_aggregate_calls[0],
        )
        for call in [
            client.foreshadowing_calls[0],
            client.timeline_calls[0],
            client.character_state_calls[0],
            client.world_rule_calls[0],
        ]:
            self.assertEqual(7, call["user_id"])
            self.assertEqual(910, call["project_id"])
            self.assertEqual(920, call["work_id"])
            self.assertEqual(5, call["limit"])

    async def test_project_retrieve_tool_uses_hybrid_backend_contract(self) -> None:
        client = RecordingProjectKnowledgeClient()
        registry = build_domain_tool_registry(client)

        run = await registry.dispatch("project.retrieve", {
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "admin signal",
            "intent": "continuity_check",
            "entities": ["Lin Zhou"],
            "chapterFrom": 2,
            "chapterTo": 7,
            "channels": ["structured", "vector"],
            "filters": {"chapterFrom": 2, "chapterTo": 7},
            "weights": {"structured": 0.95, "vector": 0.85},
            "limit": 5,
            "deep": True,
            "graphBudgetMillis": 123,
            "timeoutMillis": 1500,
            "rerankPolicy": "raw_score",
        })

        self.assertEqual("succeeded", run.status)
        self.assertEqual(1, run.resultCount)
        self.assertEqual({
            "user_id": 7,
            "project_id": 910,
            "work_id": 920,
            "query": "admin signal",
            "intent": "continuity_check",
            "entities": ["Lin Zhou"],
            "chapter_from": 2,
            "chapter_to": 7,
            "channels": ["structured", "vector"],
            "filters": {"chapterFrom": 2, "chapterTo": 7},
            "weights": {"structured": 0.95, "vector": 0.85},
            "limit": 5,
            "deep": True,
            "graph_budget_millis": 123,
            "timeout_millis": 1500,
            "rerank_policy": "raw_score",
        }, client.project_retrieval_calls[0])
        self.assertEqual("structured", run.output["evidence"][0]["backend"])


if __name__ == "__main__":
    unittest.main()
