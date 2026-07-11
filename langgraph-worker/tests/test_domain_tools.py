from __future__ import annotations

import unittest

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


class RecordingProjectKnowledgeClient:
    def __init__(self) -> None:
        self.project_resolve_calls: list[dict] = []
        self.project_chapter_calls: list[dict] = []
        self.project_chunk_calls: list[dict] = []
        self.foreshadowing_calls: list[dict] = []
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

    async def search_project_chapters(self, **kwargs) -> list:
        self.project_chapter_calls.append(dict(kwargs))
        return [
            {
                "projectId": kwargs["project_id"],
                "workId": kwargs["work_id"],
                "chapterNo": 12,
                "title": "御剑交付",
                "content": "洛风用真正的御剑轨迹完成仙侠特效。",
            }
        ]

    async def search_project_chunks(self, **kwargs) -> list:
        self.project_chunk_calls.append(dict(kwargs))
        return [
            {
                "projectId": kwargs["project_id"],
                "workId": kwargs["work_id"],
                "chapterId": 101,
                "sourceType": "scene",
                "chunkText": "unknown admin signal remains unresolved",
            }
        ]

    async def list_project_foreshadowings(self, **kwargs) -> list:
        self.foreshadowing_calls.append(dict(kwargs))
        return [{"title": "moon-admin-signal", "status": "OPEN"}]

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
    async def test_skill_lookup_returns_relevant_skill_fragments_for_task_type(self) -> None:
        registry = build_domain_tool_registry(NullKnowledgeClient())

        run = await registry.dispatch("skill.lookup", {"taskType": "outline_building"})

        self.assertEqual("succeeded", run.status)
        skills = run.output["skills"]
        self.assertTrue(any(skill["skillId"] == "webnovel-outline-building" for skill in skills))
        self.assertFalse(any(skill["skillId"] == "webnovel-chapter-outline" for skill in skills))
        self.assertIn("selectedSkills", run.output)
        self.assertIn("prompt", run.output)
        self.assertIn("webnovel-outline-building", run.output["prompt"])
        self.assertLessEqual(len(run.output["prompt"]), 1600)

    async def test_rank_tools_forward_freshness_policy_to_backend_client(self) -> None:
        client = RecordingRankKnowledgeClient()
        registry = build_domain_tool_registry(client)
        payload = {
            "platform": "fanqie",
            "channelCode": "male-new",
            "category": "urban-brain",
            "limit": 10,
            "freshness": "latest",
            "allowHistorical": False,
            "timeWindowDays": 2,
            "requireSnapshotTime": True,
        }

        lookup_run = await registry.dispatch("rank.lookup", payload)
        pack_run = await registry.dispatch("rank.research_pack", payload)

        self.assertEqual("succeeded", lookup_run.status)
        self.assertEqual("succeeded", pack_run.status)
        for call in [client.lookup_rank_calls[0], client.rank_pack_calls[0]]:
            self.assertEqual("latest", call["freshness"])
            self.assertFalse(call["allow_historical"])
            self.assertEqual(2, call["time_window_days"])
            self.assertTrue(call["require_snapshot_time"])

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

    async def test_project_chapter_search_tool_filters_by_user_project_and_work(self) -> None:
        client = RecordingProjectKnowledgeClient()
        registry = build_domain_tool_registry(client)

        run = await registry.dispatch("project.chapter_search", {
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "御剑",
            "limit": 5,
        })

        self.assertEqual("succeeded", run.status)
        self.assertEqual(1, run.resultCount)
        self.assertEqual(
            {
                "user_id": 7,
                "project_id": 910,
                "work_id": 920,
                "query": "御剑",
                "limit": 5,
            },
            client.project_chapter_calls[0],
        )
        self.assertEqual("御剑交付", run.output["items"][0]["title"])


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
        timeline = await registry.dispatch("project.timeline_lookup", base_payload)
        character = await registry.dispatch("project.character_state_lookup", base_payload)
        world_rule = await registry.dispatch("project.world_rule_lookup", base_payload)

        self.assertEqual("succeeded", foreshadowing.status)
        self.assertEqual("succeeded", timeline.status)
        self.assertEqual("succeeded", character.status)
        self.assertEqual("succeeded", world_rule.status)
        self.assertEqual("OPEN", client.foreshadowing_calls[0]["status"])
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

    async def test_project_chunk_search_tool_filters_by_user_project_and_work(self) -> None:
        client = RecordingProjectKnowledgeClient()
        registry = build_domain_tool_registry(client)

        run = await registry.dispatch("project.chunk_search", {
            "userId": 7,
            "projectId": 910,
            "workId": 920,
            "query": "admin signal",
            "limit": 5,
        })

        self.assertEqual("succeeded", run.status)
        self.assertEqual(1, run.resultCount)
        self.assertEqual(
            {
                "user_id": 7,
                "project_id": 910,
                "work_id": 920,
                "query": "admin signal",
                "limit": 5,
            },
            client.project_chunk_calls[0],
        )
        self.assertEqual("scene", run.output["items"][0]["sourceType"])


if __name__ == "__main__":
    unittest.main()
