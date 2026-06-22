from __future__ import annotations

import unittest
from typing import Any

from app.services.knowledge_client import KnowledgeBackendClient


class CapturingKnowledgeBackendClient(KnowledgeBackendClient):
    def __init__(self, response_payload: object) -> None:
        super().__init__(base_url="http://127.0.0.1:8080", internal_api_key="worker-test-key")
        self.response_payload = response_payload
        self.post_calls: list[dict] = []

    async def _post_json(self, path: str, payload: dict) -> object:
        self.post_calls.append({"path": path, "payload": payload})
        return self.response_payload


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
