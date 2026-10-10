from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.retrieval_fusion import fuse_and_rerank_sources


class RetrievalFusionTest(unittest.TestCase):
    def test_preserves_structured_rank_sources_before_vector_evidence_for_trends(self) -> None:
        rank_source = KnowledgeSource(
            chunkId=None,
            score=1.0,
            bookId=701,
            bookName="榜一作品",
            sourceType="RANK",
            rankNo=1,
            title="男频新书榜 / 都市脑洞 #1",
            preview="榜一简介",
        )
        vector_source = KnowledgeSource(
            chunkId=10,
            score=0.98,
            bookId=801,
            bookName="向量样本",
            sourceType="INTRO",
            title="向量样本简介",
            preview="都市脑洞趋势样本",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="当前男频新书榜都市脑洞第一是什么，并模仿题材"),
            state={"intent": "trend_research"},
            sources=[vector_source, rank_source],
            limit=5,
        )

        self.assertEqual("RANK", sources[0].sourceType)
        self.assertEqual(1, sources[0].rankNo)

    def test_dedupes_sources_by_book_source_chapter_and_chunk_identity(self) -> None:
        first = KnowledgeSource(
            chunkId=11,
            score=0.8,
            bookId=101,
            bookName="样本书",
            sourceType="CHAPTER",
            sourceRefId=501,
            chapterNo=1,
            title="第一章",
            preview="主角获得系统",
        )
        duplicate = KnowledgeSource(
            chunkId=11,
            score=0.7,
            bookId=101,
            bookName="样本书",
            sourceType="CHAPTER",
            sourceRefId=501,
            chapterNo=1,
            title="第一章",
            preview="重复材料",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="拆一下第一章钩子"),
            state={"intent": "single_book_research"},
            sources=[first, duplicate],
            limit=5,
        )

        self.assertEqual(1, len(sources))
        self.assertEqual("主角获得系统", sources[0].preview)

    def test_trend_selection_keeps_rank_one_ahead_of_higher_scoring_old_vector_sources(self) -> None:
        top1 = KnowledgeSource(
            score=0.88,
            bookId=201,
            bookName="最新榜首",
            sourceType="RANK",
            rankNo=1,
            title="男频新书榜 / 都市脑洞 #1",
            preview="最新榜首简介",
        )
        old_vector = KnowledgeSource(
            chunkId=31,
            score=0.99,
            bookId=202,
            bookName="旧榜靠后作品",
            sourceType="INTRO",
            title="旧榜靠后作品简介",
            preview="旧榜但语义更像趋势样本",
        )
        second_rank = KnowledgeSource(
            score=0.7,
            bookId=203,
            bookName="第二名",
            sourceType="RANK",
            rankNo=2,
            title="男频新书榜 / 都市脑洞 #2",
            preview="第二名简介",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="最近男频都市脑洞题材趋势是什么"),
            state={"intent": "trend_research"},
            sources=[old_vector, second_rank, top1],
            limit=3,
        )

        self.assertEqual(["RANK", "RANK", "INTRO"], [source.sourceType for source in sources])
        self.assertEqual(1, sources[0].rankNo)
        self.assertEqual("最新榜首", sources[0].bookName)

    def test_trend_selection_does_not_fill_context_with_low_rank_vector_rank_sources(self) -> None:
        top1 = KnowledgeSource(score=0.88, bookId=201, bookName="Top One", sourceType="RANK", rankNo=1)
        top2 = KnowledgeSource(score=0.87, bookId=202, bookName="Top Two", sourceType="RANK", rankNo=2)
        top3 = KnowledgeSource(score=0.86, bookId=203, bookName="Top Three", sourceType="RANK", rankNo=3)
        low_rank_vector = KnowledgeSource(
            chunkId=2401,
            score=0.99,
            bookId=224,
            bookName="Low Rank Vector",
            sourceType="RANK",
            rankNo=24,
            title="Male new book / urban brain #24",
            preview="This old vector rank should not fill a trend answer context.",
        )
        intro = KnowledgeSource(
            chunkId=31,
            score=0.7,
            bookId=201,
            bookName="Top One",
            sourceType="INTRO",
            title="Top One intro",
            preview="Supplemental intro for the current front rank.",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="recent male urban brain trend and outline"),
            state={"intent": "trend_research"},
            sources=[low_rank_vector, intro, top3, top2, top1],
            limit=5,
        )

        self.assertEqual(["RANK", "RANK", "RANK", "INTRO"], [source.sourceType for source in sources])
        self.assertNotIn(24, [source.rankNo for source in sources if source.sourceType == "RANK"])

    def test_trend_selection_dedupes_same_book_rank_sources_by_best_rank(self) -> None:
        duplicated_low_rank = KnowledgeSource(
            score=0.99,
            bookId=201,
            bookName="同一本书",
            sourceType="RANK",
            rankNo=5,
            title="男频新书榜 / 都市脑洞 #5",
            preview="同书低排名旧材料",
        )
        duplicated_top_rank = KnowledgeSource(
            score=0.82,
            bookId=201,
            bookName="同一本书",
            sourceType="RANK",
            rankNo=1,
            title="男频新书榜 / 都市脑洞 #1",
            preview="同书当前榜一材料",
        )
        second_rank = KnowledgeSource(
            score=0.81,
            bookId=202,
            bookName="第二名",
            sourceType="RANK",
            rankNo=2,
            title="男频新书榜 / 都市脑洞 #2",
            preview="第二名材料",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="最近男频都市脑洞题材趋势是什么"),
            state={"intent": "trend_research"},
            sources=[duplicated_low_rank, second_rank, duplicated_top_rank],
            limit=5,
        )

        rank_sources = [source for source in sources if source.sourceType == "RANK"]
        self.assertEqual([201, 202], [source.bookId for source in rank_sources])
        self.assertEqual(1, rank_sources[0].rankNo)

    def test_boosts_chapter_sources_for_chapter_level_questions(self) -> None:
        intro = KnowledgeSource(
            chunkId=21,
            score=0.96,
            bookId=101,
            bookName="样本书",
            sourceType="INTRO",
            title="简介",
            preview="简介里提到金手指",
        )
        chapter = KnowledgeSource(
            chunkId=22,
            score=0.75,
            bookId=101,
            bookName="样本书",
            sourceType="CHAPTER",
            chapterNo=1,
            title="第一章",
            preview="第一章展示钩子和冲突",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="这本书前三章钩子怎么设计？"),
            state={"intent": "single_book_research"},
            sources=[intro, chapter],
            limit=5,
        )

        self.assertEqual("CHAPTER", sources[0].sourceType)

    def test_returns_selection_diagnostics_for_operator_trace(self) -> None:
        top1 = KnowledgeSource(score=0.88, bookId=201, bookName="Top One", sourceType="RANK", rankNo=1)
        duplicate_top1 = KnowledgeSource(score=0.77, bookId=201, bookName="Top One", sourceType="RANK", rankNo=1)
        chapter = KnowledgeSource(
            chunkId=31,
            score=0.7,
            bookId=201,
            bookName="Top One",
            sourceType="CHAPTER",
            title="Top One chapter",
            preview="chapter evidence",
        )
        state = {"intent": "trend_research"}

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="recent trend"),
            state=state,
            sources=[top1, duplicate_top1, chapter],
            limit=2,
        )

        diagnostics = state["retrieval_diagnostics"]
        self.assertEqual(3, diagnostics["inputCount"])
        self.assertEqual(2, diagnostics["dedupedCount"])
        self.assertEqual(len(sources), diagnostics["selectedCount"])
        self.assertEqual("trend_research", diagnostics["intent"])
        self.assertEqual({"CHAPTER": 1, "RANK": 2}, diagnostics["inputSourceTypeCounts"])
        self.assertIn("trend_quota_selection", diagnostics["reasonTags"])

    def test_dedupes_project_evidence_by_generation_and_content_hash(self) -> None:
        structured = KnowledgeSource(
            documentId=101,
            score=0.82,
            projectId=91,
            workId=911,
            generationId=701,
            chapterVersion=3,
            contentHash="content-hash-101",
            sourceType="PROJECT_SCENE",
            sourceRefId=101,
            chapterNo=12,
            retrievalBackend="structured",
            title="Signal scene",
            preview="structured evidence",
        )
        vector = KnowledgeSource(
            chunkId=201,
            score=0.81,
            projectId=91,
            workId=911,
            generationId=701,
            chapterVersion=3,
            contentHash="content-hash-101",
            sourceType="PROJECT_SCENE",
            sourceRefId=201,
            chapterNo=12,
            retrievalBackend="qdrant",
            title="Signal scene",
            preview="vector duplicate",
        )

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="Where was the signal introduced?"),
            state={"intent": "answer_question", "task_graph": {"tasks": [{"type": "project_knowledge_qa"}]}},
            sources=[vector, structured],
            limit=5,
        )

        self.assertEqual(1, len(sources))
        self.assertEqual("structured", sources[0].retrievalBackend)

    def test_project_selection_preserves_distinct_vector_backend(self) -> None:
        structured_one = KnowledgeSource(
            documentId=101,
            score=0.95,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="structured-101",
            sourceType="PROJECT_CHAPTER",
            sourceRefId=101,
            retrievalBackend="structured",
            title="Structured one",
            preview="first structured fact",
        )
        structured_two = KnowledgeSource(
            documentId=102,
            score=0.94,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="structured-102",
            sourceType="PROJECT_CHAPTER",
            sourceRefId=102,
            retrievalBackend="structured",
            title="Structured two",
            preview="second structured fact",
        )
        vector = KnowledgeSource(
            chunkId=201,
            score=0.80,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="vector-201",
            sourceType="PROJECT_CHAPTER",
            sourceRefId=201,
            retrievalBackend="qdrant",
            title="Semantic match",
            preview="a distinct semantic scene match",
        )
        state = {"intent": "answer_question", "task_graph": {"tasks": [{"type": "project_knowledge_qa"}]}}

        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="Where is the semantic scene foreshadowed?"),
            state=state,
            sources=[structured_one, structured_two, vector],
            limit=2,
        )

        self.assertEqual({"structured", "qdrant"}, {source.retrievalBackend for source in sources})
        diagnostics = state["retrieval_diagnostics"]
        self.assertEqual({"qdrant": 1, "structured": 1}, diagnostics["selectedBackendCounts"])
        self.assertTrue(diagnostics["vectorUsed"])
        self.assertIn("project_backend_diversity", diagnostics["reasonTags"])

    def test_continuity_retrieval_prioritizes_story_graph_evidence(self) -> None:
        chapter = KnowledgeSource(
            documentId=101,
            score=0.80,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="chapter-hash",
            sourceType="PROJECT_CHAPTER",
            sourceRefId=101,
            chapterNo=12,
            retrievalBackend="structured",
            title="Chapter evidence",
            preview="The motivation changed.",
        )
        graph = KnowledgeSource(
            sourceRefId=202,
            score=0.80,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="graph-hash",
            sourceType="PROJECT_GRAPH",
            chapterNo=12,
            retrievalBackend="graph",
            title="MOTIVATION_CONFLICT",
            preview="Evidence-backed conflict edge.",
        )

        state = {"intent": "answer_question", "task_graph": {"tasks": [{"type": "continuity_check"}]}}
        sources = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="Check whether the motivation is consistent."),
            state=state,
            sources=[chapter, graph],
            limit=5,
        )

        self.assertEqual("PROJECT_GRAPH", sources[0].sourceType)
        self.assertIn("intent_aware_project_rerank", state["retrieval_diagnostics"]["reasonTags"])

    def test_project_evidence_tie_break_is_stable(self) -> None:
        first = KnowledgeSource(
            documentId=202,
            score=0.80,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="hash-b",
            sourceType="PROJECT_CHAPTER",
            sourceRefId=202,
            title="B evidence",
            preview="B",
        )
        second = KnowledgeSource(
            documentId=101,
            score=0.80,
            projectId=91,
            workId=911,
            generationId=701,
            contentHash="hash-a",
            sourceType="PROJECT_CHAPTER",
            sourceRefId=101,
            title="A evidence",
            preview="A",
        )
        state = {"intent": "answer_question", "task_graph": {"tasks": [{"type": "project_knowledge_qa"}]}}

        forward = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="recall chapter evidence"),
            state=dict(state),
            sources=[first, second],
            limit=5,
        )
        reverse = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="recall chapter evidence"),
            state=dict(state),
            sources=[second, first],
            limit=5,
        )

        self.assertEqual([101, 202], [source.documentId for source in forward])
        self.assertEqual([source.documentId for source in forward], [source.documentId for source in reverse])


if __name__ == "__main__":
    unittest.main()
