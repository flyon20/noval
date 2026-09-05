from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.retrieval_eval import (
    ProjectRetrievalReleaseGate,
    RetrievalEvalCase,
    RetrievalEvalThresholds,
    bootstrap_confidence_interval,
    evaluate_retrieval_cases,
    project_retrieval_release_gate_failures,
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

    def test_scores_project_retrieval_dimensions_and_requires_active_cases_for_gates(self) -> None:
        metrics = evaluate_retrieval_cases([
            RetrievalEvalCase(
                case_id="project-graph-001",
                ranked_ids=["chapter:12", "noise:1", "chapter:13", "foreshadowing:moon", "noise:2"],
                relevant_ids={"chapter:12", "chapter:13"},
                relevance_grades={"chapter:12": 3, "chapter:13": 2},
                expected_chapter_ids={"chapter:12", "chapter:13"},
                retrieved_chapter_ids={"chapter:12", "chapter:13"},
                expected_foreshadowing_ids={"foreshadowing:moon"},
                retrieved_foreshadowing_ids={"foreshadowing:moon"},
                expected_structured_values={"character:hero:status": "injured"},
                actual_structured_values={"character:hero:status": "injured"},
                expected_path_edges={
                    "chapter:12->foreshadowing:moon": {"chapter:12", "foreshadowing:moon"},
                    "foreshadowing:moon->chapter:13": {"foreshadowing:moon", "chapter:13"},
                },
                observed_path_edges={
                    "chapter:12->foreshadowing:moon": {"chapter:12", "foreshadowing:moon"},
                    "foreshadowing:moon->chapter:13": {"foreshadowing:moon", "chapter:13"},
                },
                expected_stale_rejection=True,
                stale_rejected=True,
                expected_cross_user_isolation=True,
                cross_user_isolated=True,
                expected_generation_ids={"77"},
                observed_generation_ids={"77"},
            )
        ])

        self.assertEqual(1.0, metrics["recall_at_5"])
        self.assertEqual(1.0, metrics["recall_at_10"])
        self.assertEqual(0.4, metrics["precision_at_5"])
        self.assertEqual(1.0, metrics["mrr"])
        self.assertAlmostEqual(0.956, float(metrics["ndcg_at_10"]), places=3)
        self.assertEqual(1.0, metrics["structured_accuracy"])
        self.assertEqual(1.0, metrics["chapter_location_accuracy"])
        self.assertEqual(1.0, metrics["foreshadowing_coverage"])
        self.assertEqual(1.0, metrics["multi_hop_path_evidence"])
        self.assertEqual(1.0, metrics["stale_rejection_rate"])
        self.assertEqual(1.0, metrics["cross_user_isolation_rate"])
        self.assertEqual(0.0, metrics["old_generation_misretrieval_rate"])

        failures = retrieval_threshold_failures(
            {"active_retrieval_case_count": 0, "recall_at_5": 1.0},
            RetrievalEvalThresholds(min_recall_at_5=0.95),
        )
        self.assertEqual(["no_active_retrieval_cases"], failures)

    def test_release_gate_uses_confidence_intervals_and_blocks_generation_regressions(self) -> None:
        metrics = {
            "active_retrieval_case_count": 4,
            "structured_case_count": 4,
            "chapter_location_case_count": 4,
            "foreshadowing_case_count": 4,
            "multi_hop_path_case_count": 4,
            "stale_rejection_case_count": 4,
            "cross_user_isolation_case_count": 4,
            "generation_case_count": 4,
            "recall_at_5": 0.96,
            "structured_accuracy": 0.96,
            "chapter_location_accuracy": 0.96,
            "foreshadowing_coverage": 0.91,
            "multi_hop_path_evidence": 0.86,
            "cross_user_isolation_rate": 1.0,
            "old_generation_misretrieval_rate": 0.0,
        }
        interval = bootstrap_confidence_interval([0.9, 0.95, 1.0, 1.0], iterations=200, seed=7)

        self.assertEqual(4, interval["sample_count"])
        self.assertLess(float(interval["lower"]), float(interval["mean"]))
        self.assertGreater(float(interval["upper"]), float(interval["mean"]))
        self.assertEqual([], project_retrieval_release_gate_failures(
            metrics,
            ProjectRetrievalReleaseGate(),
            baseline_metrics={"chapter_location_accuracy": 0.97},
        ))
        self.assertEqual(
            ["release_gate:chapter_location_accuracy regression 0.9300 < 0.9500"],
            project_retrieval_release_gate_failures(
                {**metrics, "chapter_location_accuracy": 0.93},
                ProjectRetrievalReleaseGate(),
                baseline_metrics={"chapter_location_accuracy": 0.97},
            ),
        )


if __name__ == "__main__":
    unittest.main()
