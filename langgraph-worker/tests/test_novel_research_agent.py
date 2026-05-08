from __future__ import annotations

import unittest

from app.models.knowledge import BookCandidate, KnowledgeChatRequest, KnowledgeSource
from app.services.novel_research_agent import NovelResearchAgent


class FakeKnowledgeClient:
    def __init__(self) -> None:
        self.search_books_calls: list[dict] = []
        self.search_evidence_calls: list[dict] = []

    async def search_books(self, *, platform: str, keyword: str, limit: int) -> list[BookCandidate]:
        self.search_books_calls.append({"platform": platform, "keyword": keyword, "limit": limit})
        return [
            BookCandidate(
                bookId=101,
                platform="fanqie",
                platformBookId="fq-101",
                bookName="星河旧梦",
                author="青灯",
                intro="主角在星际废墟中重建文明。",
                bookUrl="https://fanqie.example/page/101",
                local=False,
            )
        ]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
        })
        if book_id == 101:
            return [
                KnowledgeSource(
                    chunkId=1,
                    documentId=10,
                    score=0.91,
                    bookId=101,
                    bookName="星河旧梦",
                    platform="fanqie",
                    sourceType="chapter",
                    sourceRefId=1001,
                    chapterNo=3,
                    analysisType=None,
                    title="第3章 星门残响",
                    preview="主角通过旧星门获得第一个文明坐标。",
                )
            ]
        return []


class FakeAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "这本书的爽点主要来自旧星门坐标带来的目标推进。[1]",
            "token_used": 128,
        }


class UncitedAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "Urban brainhole stories are leaning on veteran identity and enlistment pressure as fast hooks.",
            "token_used": 96,
        }


class ScriptedProvider:
    def __init__(self, responses: list[str]) -> None:
        self.responses = list(responses)
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": self.responses.pop(0),
            "token_used": 64,
        }


class StreamingProvider:
    def __init__(self, chunks: list[str]) -> None:
        self.chunks = list(chunks)
        self.stream_calls: list[dict] = []

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        for chunk in self.chunks:
            yield {"event": "delta", "delta": chunk}


class FailingEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
        })
        raise RuntimeError("embedding backend unavailable")


class IndexedGlobalEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
        })
        if book_id is None:
            return [
                KnowledgeSource(
                    chunkId=2,
                    documentId=11,
                    score=0.93,
                    bookId=101,
                    bookName="星河旧梦",
                    platform="fanqie",
                    sourceType="chapter",
                    sourceRefId=1002,
                    chapterNo=1,
                    analysisType=None,
                    title="第1章 废墟坐标",
                    preview="星河旧梦开篇用废墟坐标制造探索目标。",
                )
            ]
        return await super().search_evidence(
            query=query,
            book_id=book_id,
            platform=platform,
            analysis_type=analysis_type,
            limit=limit,
        )


class MismatchedGlobalEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
        })
        if book_id is None:
            return [
                KnowledgeSource(
                    chunkId=3,
                    documentId=12,
                    score=0.95,
                    bookId=303,
                    bookName="县城花钱日记",
                    platform="fanqie",
                    sourceType="chapter",
                    sourceRefId=1003,
                    chapterNo=1,
                    analysisType=None,
                    title="第1章 到账",
                    preview="主角获得每天六千万的消费任务。",
                )
            ]
        return []


class NovelResearchAgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_should_return_candidates_when_question_has_book_name_without_book_id(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="分析《星河旧梦》的开篇卖点",
            bookName="星河旧梦",
            mode="research",
            limits={"candidateLimit": 3},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual("星河旧梦", client.search_books_calls[0]["keyword"])
        self.assertEqual(1, len(response.candidates))
        self.assertIn("请选择", response.answer)
        self.assertIn("select_candidate", response.actions)

    async def test_should_search_book_for_plain_single_book_query_without_book_name_field(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="凡人修仙传开篇卖点是什么",
            mode="research",
            limits={"candidateLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual("凡人修仙传", client.search_books_calls[0]["keyword"])
        self.assertEqual(1, len(response.candidates))
        self.assertIn("select_candidate", response.actions)

    async def test_should_search_global_evidence_before_book_search_for_inferred_book_name(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦开篇卖点是什么",
            mode="research",
            limits={"candidateLimit": 5, "evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual(1, len(client.search_evidence_calls))
        self.assertIsNone(client.search_evidence_calls[0]["book_id"])
        self.assertEqual(1, len(response.sources))
        self.assertIn("[1]", response.answer)

    async def test_should_search_book_when_global_evidence_matches_different_book(self) -> None:
        client = MismatchedGlobalEvidenceKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="星河旧梦开篇卖点是什么",
            mode="research",
            limits={"candidateLimit": 5, "evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual(1, len(client.search_evidence_calls))
        self.assertEqual(1, len(client.search_books_calls))
        self.assertEqual("星河旧梦", client.search_books_calls[0]["keyword"])
        self.assertEqual([], response.sources)
        self.assertIn("select_candidate", response.actions)

    async def test_should_use_existing_vector_evidence_for_trend_question_without_book_search(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="最近男频题材趋势是什么",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual(1, len(client.search_evidence_calls))
        self.assertIsNone(client.search_evidence_calls[0]["book_id"])

    async def test_should_answer_with_citations_for_indexed_book(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦的爽点来自哪里？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertIn("旧星门坐标", response.answer)
        self.assertIn("[1]", response.answer)
        self.assertEqual(1, len(response.sources))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertIn("第3章 星门残响", provider.invoke_calls[0]["messages"][1]["content"])
        self.assertEqual(101, client.search_evidence_calls[0]["book_id"])
        self.assertEqual("answered", response.resultJson["status"])

    async def test_should_repair_uncited_trend_answer_when_sources_exist(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = UncitedAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="recent fanqie urban brainhole veteran enlistment trend",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(response.sources))
        self.assertIn("[1]", response.answer)
        self.assertTrue(response.resultJson["citationRepairUsed"])
        self.assertTrue(response.resultJson["fallbackUsed"])
        self.assertEqual([], client.search_books_calls)

    async def test_stream_should_emit_model_chunks_before_done_for_indexed_book(self) -> None:
        client = FakeKnowledgeClient()
        provider = StreamingProvider(["chunk one ", "chunk two [1]"])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="鏄熸渤鏃фⅵ",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        self.assertEqual("start", events[0]["event"])
        deltas = [event["delta"] for event in events if event["event"] == "delta"]
        self.assertEqual(["chunk one ", "chunk two [1]"], deltas)
        done = events[-1]
        self.assertEqual("done", done["event"])
        self.assertEqual("answered", done["data"]["status"])
        self.assertEqual("chunk one chunk two [1]", done["data"]["answer"])
        self.assertEqual(1, len(provider.stream_calls))

    async def test_should_include_compressed_context_in_rag_retrieval_query(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="那它的设定是什么？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            contextSummary="current book: 星河旧梦; previous goal: explain opening hook",
            history=[
                {"role": "user", "content": "星河旧梦的开篇卖点是什么？"},
                {"role": "assistant", "content": "它围绕旧星门坐标推进目标。[1]"},
            ],
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        query = client.search_evidence_calls[0]["query"]
        self.assertIn("那它的设定是什么", query)
        self.assertIn("current book: 星河旧梦", query)
        self.assertIn("星河旧梦的开篇卖点", query)

    async def test_should_prioritize_explicit_book_id_over_creative_advice_route(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="凡人修仙传前三章主线冲突是什么？",
            bookId=101,
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(101, client.search_evidence_calls[0]["book_id"])
        self.assertEqual(1, len(response.sources))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertIn("[1]", response.answer)

    async def test_should_refuse_when_no_evidence_is_available(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="这本书后续会怎么写？",
            bookId=202,
            bookName="未知书",
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual([], response.sources)
        self.assertIn("证据不足", response.answer)
        self.assertIn("index_book", response.actions)

    async def test_should_return_insufficient_evidence_when_evidence_search_fails(self) -> None:
        client = FailingEvidenceKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="continue analysis",
            bookId=101,
            bookName="Book A",
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual([], response.sources)
        self.assertIn("index_book", response.actions)
        self.assertEqual(101, response.resultJson["bookId"])

    async def test_should_refuse_out_of_scope_question_with_ai_guardrail(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": false, "intent": "out_of_scope", "bookName": null}'
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="明天香港天气怎么样？", mode="research")

        response = await agent.run(request)

        self.assertEqual("out_of_scope", response.status)
        self.assertIn("网文", response.answer)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_obvious_non_novel_daily_life_question_without_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="番茄炒蛋怎么做？", mode="research")

        response = await agent.run(request)

        self.assertEqual("out_of_scope", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_news_and_entertainment_questions_without_rag_or_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("今天有什么新闻？", "最近娱乐圈有什么八卦？"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("out_of_scope", response.status)
                self.assertIn("网文", response.answer)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)
                self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_travel_and_food_questions_without_rag_or_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("帮我规划明天旅游攻略", "明天吃什么比较好？", "推荐附近餐厅"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("out_of_scope", response.status)
                self.assertIn("网文", response.answer)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)
                self.assertEqual([], provider.invoke_calls)

    async def test_should_keep_entertainment_novel_topics_in_scope(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "creative_advice", "bookName": null}',
            "娱乐圈文开局要先建立职业目标、舆论压力和情绪反馈。"
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="娱乐圈文开局怎么设计爽点？", mode="research")

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual(2, len(provider.invoke_calls))
        self.assertTrue(provider.invoke_calls[0]["require_json"])
        self.assertFalse(provider.invoke_calls[1]["require_json"])

    async def test_should_keep_travel_and_food_novel_topics_in_scope(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "creative_advice", "bookName": null}',
            "旅行题材和美食文都要把目标、反馈和阻力写进具体场景。"
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("旅行题材小说开局怎么写？", "美食文开局怎么设计爽点？"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("answered", response.status)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)

    async def test_should_answer_web_novel_creative_chat_without_rag_evidence(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "creative_advice", "bookName": null}',
            "修仙文开局可以先给主角一个短期目标，再用代价明确的金手指制造期待。",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="修仙文开局怎么设计爽点？", mode="research")

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertIn("短期目标", response.answer)
        self.assertEqual([], response.sources)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertTrue(provider.invoke_calls[0]["require_json"])
        self.assertFalse(provider.invoke_calls[1]["require_json"])

    async def test_should_use_ai_extracted_book_name_for_ambiguous_single_book_question(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "single_book_research", "bookName": "凡人修仙传"}'
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="帮我研究一下凡人修仙传", mode="research")

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual("凡人修仙传", client.search_books_calls[0]["keyword"])
        self.assertIn("select_candidate", response.actions)


if __name__ == "__main__":
    unittest.main()
