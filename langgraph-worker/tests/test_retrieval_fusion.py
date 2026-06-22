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


if __name__ == "__main__":
    unittest.main()
