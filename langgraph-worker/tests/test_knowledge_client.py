from __future__ import annotations

import unittest
from typing import Any

from app.services.knowledge_client import KnowledgeBackendClient


class CapturingKnowledgeBackendClient(KnowledgeBackendClient):
    def __init__(self, response_payload: object, get_payload: object | None = None) -> None:
        super().__init__(base_url="http://127.0.0.1:8080", internal_api_key="worker-test-key")
        self.response_payload = response_payload
        self.get_payload = get_payload
        self.post_calls: list[dict] = []
        self.get_calls: list[dict] = []

    async def _post_json(self, path: str, payload: dict) -> object:
        self.post_calls.append({"path": path, "payload": payload})
        return self.response_payload

    async def _get_json(self, path: str) -> object:
        self.get_calls.append({"path": path})
        return self.get_payload


class FakeResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return {"data": []}


class FakeAsyncClient:
    created_count = 0
    closed_count = 0

    def __init__(self) -> None:
        FakeAsyncClient.created_count += 1
        self.posts: list[dict[str, Any]] = []

    async def post(self, path: str, *, json: dict, headers: dict) -> FakeResponse:
        self.posts.append({"path": path, "json": json, "headers": headers})
        return FakeResponse()

    async def aclose(self) -> None:
        FakeAsyncClient.closed_count += 1


class KnowledgeBackendClientTest(unittest.IsolatedAsyncioTestCase):
    def test_should_use_explicit_base_url_when_provided(self) -> None:
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-test-key",
        )

        self.assertEqual("http://127.0.0.1:8080", client.base_url)
        self.assertEqual("worker-test-key", client.internal_api_key)

    def test_should_use_separate_backend_tool_timeout(self) -> None:
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-test-key",
        )

        self.assertGreaterEqual(client.timeout_seconds, 60)

    async def test_should_lookup_rank_with_structured_payload(self) -> None:
        client = CapturingKnowledgeBackendClient([
            {
                "rankId": 9001,
                "snapshotId": 10,
                "snapshotTime": "2026-05-10T00:00:00",
                "platform": "fanqie",
                "channelCode": "male-new",
                "boardCode": "urban-brain",
                "channelName": "男频新书榜",
                "boardName": "都市脑洞",
                "category": "都市脑洞",
                "rankNo": 1,
                "bookId": 201,
                "bookName": "入伍两次！我被原部队拉进黑名单",
                "author": "朝朝和",
                "intro": "退伍入伍都市脑洞",
                "sourceLabel": "男频新书榜 / 都市脑洞 #1",
            }
        ])

        results = await client.lookup_rank(
            platform="fanqie",
            channel_code="male-new",
            board_code="urban-brain",
            category="都市脑洞",
            rank_no=1,
            limit=5,
        )

        self.assertEqual("/internal/knowledge/rank/lookup", client.post_calls[0]["path"])
        self.assertEqual(
            {
                "platform": "fanqie",
                "channelCode": "male-new",
                "boardCode": "urban-brain",
                "category": "都市脑洞",
                "rankNo": 1,
                "limit": 5,
            },
            client.post_calls[0]["payload"],
        )
        self.assertEqual(1, len(results))
        self.assertEqual("入伍两次！我被原部队拉进黑名单", results[0].bookName)
        self.assertEqual("朝朝和", results[0].author)

    async def test_should_lookup_rank_with_source_policy_payload(self) -> None:
        client = CapturingKnowledgeBackendClient([])

        await client.lookup_rank(
            platform="fanqie",
            channel_code="male-new",
            board_code="urban-brain",
            category="Urban Brain",
            limit=10,
            freshness="time_window",
            allow_historical=True,
            time_window_days=30,
            require_snapshot_time=True,
        )

        self.assertEqual(
            {
                "platform": "fanqie",
                "limit": 10,
                "channelCode": "male-new",
                "boardCode": "urban-brain",
                "category": "Urban Brain",
                "freshness": "time_window",
                "allowHistorical": True,
                "timeWindowDays": 30,
                "requireSnapshotTime": True,
            },
            client.post_calls[0]["payload"],
        )

    async def test_should_get_book_research_pack_with_camel_case_payload(self) -> None:
        client = CapturingKnowledgeBackendClient({
            "book": {"bookId": 101, "bookName": "Book Alpha", "platform": "fanqie"},
            "chapters": [{"chapterId": 1001, "chapterNo": 1, "title": "Opening", "content": "Chapter text"}],
        })

        pack = await client.get_book_research_pack(
            platform="fanqie",
            book_id=101,
            book_name="Book Alpha",
            chapter_limit=3,
            analysis_limit=2,
        )

        self.assertEqual("/internal/knowledge/research-pack/book", client.post_calls[0]["path"])
        self.assertEqual(
            {
                "platform": "fanqie",
                "bookId": 101,
                "bookName": "Book Alpha",
                "chapterLimit": 3,
                "analysisLimit": 2,
            },
            client.post_calls[0]["payload"],
        )
        self.assertEqual("Book Alpha", pack.book.bookName)
        self.assertEqual(1, pack.chapters[0].chapterNo)

    async def test_should_get_rank_research_pack_with_camel_case_payload(self) -> None:
        client = CapturingKnowledgeBackendClient({
            "ranks": [{"rankId": 9001, "bookId": 101, "bookName": "Rank One", "rankNo": 1}],
            "books": [{"bookId": 101, "bookName": "Rank One", "platform": "fanqie"}],
            "chapters": [{"bookId": 101, "chapterNo": 1, "title": "Opening", "content": "Top book chapter"}],
        })

        pack = await client.get_rank_research_pack(
            platform="fanqie",
            channel_code="male-new",
            board_code="urban-brain",
            category="urban",
            rank_no=1,
            limit=5,
            chapter_limit_per_book=2,
        )

        self.assertEqual("/internal/knowledge/research-pack/rank", client.post_calls[0]["path"])
        self.assertEqual(
            {
                "platform": "fanqie",
                "channelCode": "male-new",
                "boardCode": "urban-brain",
                "category": "urban",
                "rankNo": 1,
                "limit": 5,
                "chapterLimitPerBook": 2,
            },
            client.post_calls[0]["payload"],
        )
        self.assertEqual("Rank One", pack.ranks[0].bookName)
        self.assertEqual("Top book chapter", pack.chapters[0].content)

    async def test_should_get_rank_research_pack_with_source_policy_payload(self) -> None:
        client = CapturingKnowledgeBackendClient({"ranks": [], "books": [], "chapters": []})

        await client.get_rank_research_pack(
            platform="fanqie",
            channel_code="male-new",
            board_code="urban-brain",
            category="Urban Brain",
            limit=10,
            chapter_limit_per_book=1,
            freshness="time_window",
            allow_historical=True,
            time_window_days=30,
            require_snapshot_time=True,
        )

        self.assertEqual(
            {
                "platform": "fanqie",
                "limit": 10,
                "chapterLimitPerBook": 1,
                "channelCode": "male-new",
                "boardCode": "urban-brain",
                "category": "Urban Brain",
                "freshness": "time_window",
                "allowHistorical": True,
                "timeWindowDays": 30,
                "requireSnapshotTime": True,
            },
            client.post_calls[0]["payload"],
        )

    async def test_should_get_project_memory_with_project_and_user_scope(self) -> None:
        client = CapturingKnowledgeBackendClient({
            "projectId": 900,
            "userId": 7,
            "memories": {"genre": "urban fantasy", "styleConstraints": "no harem"},
        })

        memory = await client.get_project_memory(project_id=900, user_id=7)

        self.assertEqual("/internal/knowledge/projects/900/memory", client.post_calls[0]["path"])
        self.assertEqual({"userId": 7}, client.post_calls[0]["payload"])
        self.assertEqual(900, memory["projectId"])
        self.assertEqual("urban fantasy", memory["memories"]["genre"])

    async def test_should_read_conversation_summary(self) -> None:
        client = CapturingKnowledgeBackendClient({
            "conversationId": "conv-1",
            "userId": 7,
            "summary": "previous project choices",
        })

        summary = await client.read_conversation_summary(user_id=7, conversation_id="conv-1")

        self.assertEqual("/internal/knowledge/conversation-summary/read", client.post_calls[0]["path"])
        self.assertEqual({"userId": 7, "conversationId": "conv-1"}, client.post_calls[0]["payload"])
        self.assertEqual("previous project choices", summary["summary"])

    async def test_should_search_confirmed_memory(self) -> None:
        client = CapturingKnowledgeBackendClient([
            {"scope": "project", "memoryType": "fact", "content": "three terminal setting"}
        ])

        memories = await client.search_memory(user_id=7, project_id=900, scope="project", limit=12)

        self.assertEqual("/internal/knowledge/memory/search", client.post_calls[0]["path"])
        self.assertEqual({"userId": 7, "projectId": 900, "scope": "project", "limit": 12}, client.post_calls[0]["payload"])
        self.assertEqual("three terminal setting", memories[0]["content"])

    async def test_should_create_memory_candidate(self) -> None:
        client = CapturingKnowledgeBackendClient({"id": 123})

        result = await client.create_memory_candidate(
            user_id=7,
            project_id=900,
            conversation_id="conv-1",
            scope="project",
            memory_type="fact",
            content="setting",
            summary=None,
            confidence=0.87,
            source_trace_id="trace-1",
            ttl_days=30,
        )

        self.assertEqual("/internal/knowledge/memory/candidates", client.post_calls[0]["path"])
        self.assertEqual({
            "userId": 7,
            "projectId": 900,
            "conversationId": "conv-1",
            "scope": "project",
            "memoryType": "fact",
            "content": "setting",
            "confidence": 0.87,
            "sourceTraceId": "trace-1",
            "ttlDays": 30,
        }, client.post_calls[0]["payload"])
        self.assertEqual(123, result["id"])

    async def test_should_fetch_agent_runtime_config(self) -> None:
        client = CapturingKnowledgeBackendClient(
            {},
            get_payload={
                "reasoningModeDefault": "deep",
                "maxParallelSpecialists": 2,
                "maxEvidenceItems": 40,
            },
        )

        config = await client.get_agent_runtime_config()

        self.assertEqual("/internal/knowledge/agent/runtime-config", client.get_calls[0]["path"])
        self.assertEqual("deep", config["reasoningModeDefault"])
        self.assertEqual(2, config["maxParallelSpecialists"])
        self.assertEqual(40, config["maxEvidenceItems"])

    async def test_should_fetch_agent_expert_profiles(self) -> None:
        client = CapturingKnowledgeBackendClient(
            {},
            get_payload=[
                {
                    "expertName": "market_scan",
                    "displayName": "Market Agent",
                    "enabled": False,
                    "priority": 10,
                    "maxTokens": 1200,
                    "maxToolCalls": 4,
                    "allowedTools": ["rank.lookup"],
                }
            ],
        )

        experts = await client.get_agent_expert_profiles()

        self.assertEqual("/internal/knowledge/agent/experts", client.get_calls[0]["path"])
        self.assertEqual("market_scan", experts[0]["expertName"])
        self.assertFalse(experts[0]["enabled"])

    async def test_should_fetch_backend_published_runtime_skills(self) -> None:
        client = CapturingKnowledgeBackendClient(
            {},
            get_payload=[
                {
                    "skillId": "webnovel-market-scan",
                    "version": "2026.07.02",
                    "content": "Backend published prompt",
                    "intents": ["market_scan"],
                    "allowedTools": ["rank.lookup"],
                    "requiredEvidence": ["fresh_rank"],
                    "source": "backend",
                }
            ],
        )

        skills = await client.get_runtime_skills()

        self.assertEqual("/internal/knowledge/runtime-skills", client.get_calls[0]["path"])
        self.assertEqual("webnovel-market-scan", skills[0]["skillId"])
        self.assertEqual("Backend published prompt", skills[0]["content"])
        self.assertEqual(["rank.lookup"], skills[0]["allowedTools"])

    async def test_should_post_agent_runtime_telemetry(self) -> None:
        client = CapturingKnowledgeBackendClient({"cacheEvents": 1, "tokenMetrics": 1})

        result = await client.post_agent_telemetry(
            trace_id="trace-1",
            cache_events=[
                {
                    "cacheScope": "tool",
                    "nodeName": "execute_tools",
                    "cacheStatus": "MISS",
                    "promptPrefixStable": True,
                }
            ],
            token_metrics=[
                {
                    "nodeName": "answer_writer",
                    "expertName": "market_scan",
                    "modelName": "deepseek-chat",
                    "promptTokens": 100,
                    "completionTokens": 50,
                    "tokenCount": 150,
                }
            ],
        )

        self.assertEqual("/internal/knowledge/agent/telemetry", client.post_calls[0]["path"])
        self.assertEqual("trace-1", client.post_calls[0]["payload"]["traceId"])
        self.assertEqual("MISS", client.post_calls[0]["payload"]["cacheEvents"][0]["cacheStatus"])
        self.assertEqual(150, client.post_calls[0]["payload"]["tokenMetrics"][0]["tokenCount"])
        self.assertEqual({"cacheEvents": 1, "tokenMetrics": 1}, result)

    async def test_should_reuse_async_client_until_closed(self) -> None:
        FakeAsyncClient.created_count = 0
        FakeAsyncClient.closed_count = 0
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-test-key",
            async_client_factory=lambda **_kwargs: FakeAsyncClient(),
        )

        await client.search_books(platform="fanqie", keyword="都市脑洞", limit=1)
        await client.lookup_rank(platform="fanqie", limit=1)
        await client.aclose()

        self.assertEqual(1, FakeAsyncClient.created_count)
        self.assertEqual(1, FakeAsyncClient.closed_count)


if __name__ == "__main__":
    unittest.main()
