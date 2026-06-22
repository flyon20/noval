from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.retrieval_eval import (
    RetrievalEvalCase,
    RetrievalEvalThresholds,
    evaluate_retrieval_cases,
    retrieval_threshold_failures,
)
from app.services.retrieval_fusion import fuse_and_rerank_sources


def _source_id(source: KnowledgeSource) -> str:
    return f"{(source.sourceType or '').upper()}:{source.bookId}:{source.rankNo or source.sourceRefId or source.chunkId}"


class RetrievalEvalTest(unittest.TestCase):
    def test_scores_trend_fusion_with_hit_mrr_precision_and_context_recall(self) -> None:
        rank_one = KnowledgeSource(score=0.82, bookId=401, bookName="我下午才营业", sourceType="RANK", rankNo=1)
        rank_two = KnowledgeSource(score=0.81, bookId=402, bookName="长生两十六亿年", sourceType="RANK", rankNo=2)
        rank_three = KnowledgeSource(score=0.8, bookId=403, bookName="归国留洋水货", sourceType="RANK", rankNo=3)
        old_vector = KnowledgeSource(chunkId=99, score=0.99, bookId=499, bookName="旧向量样本", sourceType="INTRO")

        fused = fuse_and_rerank_sources(
            request=KnowledgeChatRequest(question="最近男频都市脑洞题材趋势是什么？"),
            state={"intent": "trend_research"},
            sources=[old_vector, rank_three, rank_two, rank_one],
            limit=3,
        )
        metrics = evaluate_retrieval_cases([
            RetrievalEvalCase(
                case_id="trend-current-topn",
                ranked_ids=[_source_id(source) for source in fused],
                relevant_ids={_source_id(rank_one), _source_id(rank_two), _source_id(rank_three)},
                k=3,
            )
        ])

        self.assertEqual(1.0, metrics["hit_rate_at_k"])
        self.assertEqual(1.0, metrics["mrr_at_k"])
        self.assertEqual(1.0, metrics["precision_at_k"])
        self.assertEqual(1.0, metrics["context_recall_at_k"])
        self.assertEqual(1, metrics["case_count"])

    def test_scores_misses_and_partial_recall_for_regression_reports(self) -> None:
        metrics = evaluate_retrieval_cases([
            RetrievalEvalCase(
                case_id="miss",
                ranked_ids=["INTRO:old", "ANALYSIS:old"],
                relevant_ids={"RANK:top1", "RANK:top2"},
                k=2,
            ),
            RetrievalEvalCase(
                case_id="partial",
                ranked_ids=["RANK:top2", "INTRO:old"],
                relevant_ids={"RANK:top1", "RANK:top2"},
                k=2,
            ),
        ])

        self.assertEqual(0.5, metrics["hit_rate_at_k"])
        self.assertEqual(0.5, metrics["mrr_at_k"])
        self.assertEqual(0.25, metrics["precision_at_k"])
        self.assertEqual(0.25, metrics["context_recall_at_k"])

    def test_reports_threshold_failures_for_ci_golden_gate(self) -> None:
        metrics = {
            "case_count": 2,
            "hit_rate_at_k": 0.5,
            "mrr_at_k": 0.5,
            "precision_at_k": 0.25,
            "context_precision_at_k": 0.25,
            "context_recall_at_k": 0.25,
        }

        failures = retrieval_threshold_failures(
            metrics,
            RetrievalEvalThresholds(
                min_hit_rate_at_k=0.9,
                min_mrr_at_k=0.8,
                min_context_precision_at_k=0.6,
                min_context_recall_at_k=0.7,
            ),
        )

        self.assertEqual(
            [
                "hit_rate_at_k 0.5000 < 0.9000",
                "mrr_at_k 0.5000 < 0.8000",
                "context_precision_at_k 0.2500 < 0.6000",
                "context_recall_at_k 0.2500 < 0.7000",
            ],
            failures,
        )


if __name__ == "__main__":
    unittest.main()
