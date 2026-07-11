from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatResponse, KnowledgeSource
from app.services.evaluation import (
    GoldenEvalCase,
    GoldenEvalExpectedTrace,
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

    async def test_run_suite_stops_when_persisted_run_is_cancelled(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="Top One[1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={"domainIntent": "market_scan", "answerMode": "trend"},
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        repository = FakeEvalRepository(cancel_after_cases=1)
        cases = [
            GoldenEvalCase(case_id="market-001", question="trend 1", relevant_source_ids={"rank:101"}),
            GoldenEvalCase(case_id="market-002", question="trend 2", relevant_source_ids={"rank:101"}),
        ]

        report = await runner.run_suite(cases, suite_name="agent-runtime", repository=repository, persisted_run_id=7)

        self.assertEqual("cancelled", report["status"])
        self.assertEqual(2, report["totalCases"])
        self.assertEqual(1, report["completedCases"])
        self.assertEqual(1, len(agent.requests))
        self.assertEqual(1, len(repository.case_results))
        self.assertEqual([{"run_id": 7, "completed_cases": 1, "total_cases": 2}], repository.cancelled_runs)

    async def test_run_suite_injects_selected_model_into_case_request_limits(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="Top One[1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={"domainIntent": "market_scan", "answerMode": "trend"},
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="market-model-001",
            question="trend 1",
            request_payload={"limits": {"evidenceLimit": 3}},
            relevant_source_ids={"rank:101"},
        )

        await runner.run_suite([case], suite_name="agent-runtime", model_name="deepseek-eval")

        self.assertEqual(1, len(agent.requests))
        self.assertEqual("deepseek-eval", agent.requests[0].limits["modelName"])
        self.assertEqual(3, agent.requests[0].limits["evidenceLimit"])

    async def test_scores_agent_runtime_metrics_from_trace_contract_and_memory(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="当前榜单证据显示，Top One 是前排信号。[1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={
                    "projectId": 7,
                    "domainIntent": "mixed_creation_research",
                    "answerMode": "mixed_creation",
                    "domainAnswerBoundary": "market_evidence_plus_author_inference",
                    "sourcePolicy": {
                        "evidenceContract": {"status": "degraded_directional"},
                        "selectedSnapshotGroup": {"snapshotId": 9001},
                    },
                    "toolRuns": [{"name": "rank.lookup", "status": "succeeded"}],
                    "taskGraph": {"route": "mixed_creation_research"},
                    "contextUsed": {
                        "memoryContext": {
                            "projectMemory": [{"projectId": 7, "content": "no harem"}],
                        }
                    },
                    "trace": {"nodes": [{"name": "compose_answer"}]},
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="trace-metrics-001",
            question="结合当前榜单给我一个新题材。",
            expected_intent="mixed_creation_research",
            expected_answer_mode="mixed_creation",
            relevant_source_ids={"rank:101"},
            expected_trace=GoldenEvalExpectedTrace(
                required_tool_names={"rank.lookup"},
                required_trace_fields={"taskGraph"},
                required_source_policy_fields={"evidenceContract"},
                required_evidence_statuses={"degraded_directional"},
                require_valid_answer_boundary=True,
                require_citations=True,
                forbid_memory_cross_project=True,
            ),
        )

        result = await runner.run_case(case)
        report = await runner.run_suite([case], suite_name="agent-runtime")

        self.assertEqual("passed", result.status, result.failures)
        metrics = result.trace["metrics"]
        self.assertEqual(1.0, metrics["intent_accuracy"])
        self.assertEqual(1.0, metrics["tool_selection_correct"])
        self.assertEqual(1.0, metrics["evidence_contract_correct"])
        self.assertEqual(1.0, metrics["answer_boundary_correct"])
        self.assertEqual(1.0, metrics["citation_present"])
        self.assertEqual(1.0, metrics["memory_isolation_correct"])
        self.assertEqual(1.0, metrics["trace_complete"])
        self.assertEqual(1.0, report["metrics"]["tool_selection_pass_rate"])
        self.assertEqual(1.0, report["metrics"]["evidence_contract_pass_rate"])
        self.assertEqual(1.0, report["metrics"]["answer_boundary_pass_rate"])
        self.assertEqual(1.0, report["metrics"]["citation_presence_rate"])
        self.assertEqual(1.0, report["metrics"]["memory_isolation_pass_rate"])
        self.assertEqual(1.0, report["metrics"]["trace_completeness_rate"])

    async def test_reports_agent_runtime_metric_failures(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="这里没有引用。",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={
                    "projectId": 7,
                    "domainIntent": "mixed_creation_research",
                    "answerMode": "mixed_creation",
                    "sourcePolicy": {},
                    "contextUsed": {
                        "memoryContext": {
                            "projectMemory": [{"projectId": 8, "content": "wrong project"}],
                        }
                    },
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="trace-metrics-002",
            question="结合当前榜单给我一个新题材。",
            expected_trace=GoldenEvalExpectedTrace(
                required_tool_names={"rank.lookup"},
                required_trace_fields={"taskGraph"},
                required_source_policy_fields={"evidenceContract"},
                required_evidence_statuses={"verified_latest", "degraded_directional"},
                require_valid_answer_boundary=True,
                require_citations=True,
                forbid_memory_cross_project=True,
            ),
        )

        result = await runner.run_case(case)

        self.assertEqual("failed", result.status)
        self.assertIn("trace:missing_tool:rank.lookup", result.failures)
        self.assertIn("trace:missing_trace_field:taskGraph", result.failures)
        self.assertIn("trace:missing_source_policy_field:evidenceContract", result.failures)
        self.assertIn("trace:evidence_status:None", result.failures)
        self.assertIn("trace:missing_answer_boundary", result.failures)
        self.assertIn("trace:missing_citation", result.failures)
        self.assertIn("trace:memory_cross_project", result.failures)

    async def test_fails_when_grounded_claim_is_not_supported_by_sources(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="Top One is the rank leader and has a hidden billionaire protagonist. [1]",
                sources=[
                    KnowledgeSource(
                        sourceType="RANK",
                        bookId=101,
                        rankNo=1,
                        bookName="Top One",
                        title="Top One rank row",
                        preview="Top One is rank leader on the current board.",
                    )
                ],
                resultJson={"domainIntent": "market_scan", "answerMode": "trend"},
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="grounded-claim-001",
            question="trend",
            grounded_claims=["hidden billionaire protagonist"],
        )

        result = await runner.run_case(case)

        self.assertEqual("failed", result.status)
        self.assertIn("faithfulness:unsupported_claim:hidden billionaire protagonist", result.failures)
        self.assertEqual(0.0, result.faithfulness["claim_support_rate"])
        self.assertEqual(0.0, result.trace["metrics"]["claim_support_rate"])

    async def test_fails_when_required_source_type_is_missing(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="Top One is rank leader. [1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={
                    "domainIntent": "mixed_creation_research",
                    "answerMode": "mixed_creation",
                    "toolRuns": [{"name": "rank.lookup", "status": "succeeded"}],
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="source-contract-001",
            question="outline with chapter evidence",
            expected_trace=GoldenEvalExpectedTrace(
                required_tool_names={"rank.lookup"},
                required_source_types={"CHAPTER"},
            ),
        )

        result = await runner.run_case(case)

        self.assertEqual("failed", result.status)
        self.assertNotIn("trace:missing_tool:rank.lookup", result.failures)
        self.assertIn("trace:missing_source_type:CHAPTER", result.failures)
        self.assertEqual(1.0, result.trace["metrics"]["required_tool_pass"])
        self.assertEqual(0.0, result.trace["metrics"]["required_source_type_pass"])

    async def test_fails_when_answer_quality_trace_contract_is_violated(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="围绕用户问题拆出主角困境，然后给前三章。[1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={
                    "domainIntent": "mixed_creation_research",
                    "answerMode": "mixed_creation",
                    "fallbackUsed": True,
                    "providerCalls": [{"node": "compose_answer", "status": "failed"}],
                    "selectedExperts": [],
                    "toolRuns": [{"name": "rank.lookup", "status": "succeeded"}],
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="answer-quality-contract-001",
            question="outline",
            expected_trace=GoldenEvalExpectedTrace(
                forbid_fallback=True,
                require_provider_success=True,
                require_selected_experts=True,
                required_answer_terms={"底层职业", "诸天万界", "三端一体"},
                forbidden_answer_patterns={"围绕用户问题拆出"},
            ),
        )

        result = await runner.run_case(case)

        self.assertEqual("failed", result.status)
        self.assertIn("trace:fallback_used", result.failures)
        self.assertIn("trace:provider_not_succeeded", result.failures)
        self.assertIn("trace:selected_experts_empty", result.failures)
        self.assertIn("trace:missing_answer_term:底层职业", result.failures)
        self.assertIn("trace:missing_answer_term:诸天万界", result.failures)
        self.assertIn("trace:missing_answer_term:三端一体", result.failures)
        self.assertIn("trace:forbidden_answer_pattern:围绕用户问题拆出", result.failures)

    async def test_trace_metrics_tolerate_null_context_used(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="当前榜单证据显示，Top One 是前排信号。[1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={
                    "projectId": 7,
                    "domainIntent": "market_scan",
                    "answerMode": "trend",
                    "answerBoundary": "market_evidence",
                    "sourcePolicy": {"trendGateFailed": False},
                    "contextUsed": None,
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="trace-metrics-null-context",
            question="最近男频都市脑洞题材趋势是什么？",
            expected_trace=GoldenEvalExpectedTrace(require_valid_answer_boundary=True),
        )

        result = await runner.run_case(case)

        self.assertEqual("passed", result.status, result.failures)
        self.assertEqual(1.0, result.trace["metrics"]["memory_isolation_correct"])

    async def test_golden_trace_contract_accepts_preconditions_field(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="褰撳墠姒滃崟璇佹嵁鏄剧ず锛孴op One 鏄墠鎺掍俊鍙枫€俒1]",
                sources=[KnowledgeSource(sourceType="RANK", bookId=101, rankNo=1, bookName="Top One")],
                resultJson={
                    "domainIntent": "market_scan",
                    "answerMode": "trend",
                    "answerBoundary": "market_evidence",
                    "sourcePolicy": {"evidenceContract": {"status": "verified_latest"}},
                    "taskGraph": {"tasks": [{"type": "market_scan"}]},
                    "trace": {
                        "nodes": [{"name": "validate_preconditions"}],
                        "preconditions": {
                            "domainAllowed": True,
                            "needsBookSelection": False,
                            "needsLatestRankEvidence": True,
                            "projectMemoryAllowed": False,
                            "evidenceInsufficiencyMode": "satisfied",
                        },
                    },
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="trace-preconditions-001",
            question="最近榜单趋势怎么样？",
            expected_trace=GoldenEvalExpectedTrace(required_trace_fields={"preconditions"}),
        )

        result = await runner.run_case(case)

        self.assertEqual("passed", result.status, result.failures)
        self.assertTrue(result.trace["preconditions"]["domainAllowed"])


class FakeAgent:
    def __init__(self, response: KnowledgeChatResponse) -> None:
        self.response = response
        self.requests = []

    async def run(self, request):
        self.requests.append(request)
        return self.response


class FakeEvalRepository:
    def __init__(self, *, cancel_after_cases: int | None = None) -> None:
        self.created_runs: list[dict] = []
        self.case_results: list[dict] = []
        self.finished_runs: list[dict] = []
        self.cancelled_runs: list[dict] = []
        self.cancel_after_cases = cancel_after_cases

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

    def is_run_cancelled(self, run_id: int) -> bool:
        return self.cancel_after_cases is not None and len(self.case_results) >= self.cancel_after_cases

    def cancel_run(self, **kwargs):
        self.cancelled_runs.append(kwargs)


if __name__ == "__main__":
    unittest.main()
