from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatResponse, KnowledgeSource
from app.services.evaluation import (
    GoldenEvalCase,
    GoldenEvalRunner,
    RetrievalEvalThresholds,
    RuleBasedFaithfulnessEvaluator,
)


class GoldenEvalRunnerTest(unittest.IsolatedAsyncioTestCase):
    async def test_runs_golden_case_with_retrieval_thresholds_and_faithfulness(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="榜一是《Top One》[1]。作者侧推演：可以围绕现实压力设计前三章。",
                sources=[
                    KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One"),
                    KnowledgeSource(chunkId=31, sourceType="INTRO", bookId=101, bookName="Top One"),
                ],
                resultJson={
                    "domainIntent": "mixed_creation_research",
                    "answerMode": "mixed_creation",
                    "trace": {"promptPolicy": "rank_first_market_then_author_inference"},
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="mixed-rank-outline-001",
            question="根据当前男频新书榜都市脑洞第一的书，模仿题材和前三章细纲怎么设计？",
            expected_intent="mixed_creation_research",
            expected_answer_mode="mixed_creation",
            relevant_source_ids={"rank:101", "chunk:31"},
            forbidden_claims=["世界首富"],
            retrieval_thresholds=RetrievalEvalThresholds(
                min_hit_rate_at_k=1.0,
                min_mrr_at_k=1.0,
                min_context_precision_at_k=1.0,
                min_context_recall_at_k=1.0,
            ),
        )

        result = await runner.run_case(case)

        self.assertEqual("passed", result.status)
        self.assertEqual("mixed_creation_research", result.intent)
        self.assertEqual("mixed_creation", result.answer_mode)
        self.assertEqual([], result.failures)
        self.assertEqual(1.0, result.retrieval_metrics["hit_rate_at_k"])
        self.assertTrue(result.faithfulness["passed"])

    async def test_reports_retrieval_and_faithfulness_failures_separately(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="这本书一定会成为世界首富爆款[9]。",
                sources=[KnowledgeSource(sourceType="RANK", bookId=202, rankNo=24, bookName="Low Rank")],
                resultJson={"domainIntent": "market_scan", "answerMode": "trend"},
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="trend-001",
            question="最近男频都市脑洞趋势是什么？",
            expected_intent="market_scan",
            expected_answer_mode="trend",
            relevant_source_ids={"rank:101"},
            forbidden_claims=["世界首富"],
            retrieval_thresholds=RetrievalEvalThresholds(
                min_hit_rate_at_k=1.0,
                min_context_recall_at_k=1.0,
            ),
        )

        result = await runner.run_case(case)

        self.assertEqual("failed", result.status)
        self.assertIn("retrieval:hit_rate_at_k 0.0000 < 1.0000", result.failures)
        self.assertIn("retrieval:context_recall_at_k 0.0000 < 1.0000", result.failures)
        self.assertIn("faithfulness:forbidden_claim:世界首富", result.failures)
        self.assertIn("faithfulness:invalid_citation:[9]", result.failures)

    async def test_run_suite_persists_case_results_and_summary(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="榜一是《Top One》[1]。作者侧推演：可以围绕现实压力设计前三章。",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={"domainIntent": "mixed_creation_research", "answerMode": "mixed_creation"},
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        repository = FakeEvalRepository()
        case = GoldenEvalCase(
            case_id="mixed-001",
            question="根据当前男频新书榜都市脑洞第一的书，细纲怎么设计？",
            relevant_source_ids={"rank:101"},
            retrieval_thresholds=RetrievalEvalThresholds(min_hit_rate_at_k=1.0),
        )

        report = await runner.run_suite([case], suite_name="rag-smoke", repository=repository)

        self.assertEqual("passed", report["status"])
        self.assertEqual(1, report["totalCases"])
        self.assertEqual(1, repository.created_runs[0]["id"])
        self.assertEqual(1, len(repository.case_results))
        self.assertEqual(1, len(repository.finished_runs))
        self.assertEqual("mixed-001", repository.case_results[0]["case_key"])
        self.assertEqual("PASSED", repository.case_results[0]["status"])


class FakeAgent:
    def __init__(self, response: KnowledgeChatResponse) -> None:
        self.response = response
        self.requests = []

    async def run(self, request):
        self.requests.append(request)
        return self.response


class FakeEvalRepository:
    def __init__(self) -> None:
        self.created_runs: list[dict] = []
        self.case_results: list[dict] = []
        self.finished_runs: list[dict] = []

    def create_run(self, **kwargs):
        row = {"id": len(self.created_runs) + 1, **kwargs}
        self.created_runs.append(row)
        return row["id"]

    def record_case_result(self, **kwargs):
        result = kwargs["result"]
        self.case_results.append({
            "case_key": result.case_id,
            "status": result.status.upper(),
            **kwargs,
        })

    def finish_run(self, **kwargs):
        self.finished_runs.append(kwargs)


if __name__ == "__main__":
    unittest.main()
