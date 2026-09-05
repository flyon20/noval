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
from app.services.agents.expert_registry import current_eval_delegation


class GoldenEvalRunnerTest(unittest.IsolatedAsyncioTestCase):
    def test_each_eval_execution_gets_a_unique_fresh_trace_identity(self) -> None:
        runner = GoldenEvalRunner(
            agent=object(),
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        )
        case = GoldenEvalCase(
            case_id="unique-eval-trace",
            question="market",
            request_payload={"traceId": "stale-fixture-trace", "resumeFromCheckpoint": True},
        )

        first = runner._build_request(case)
        second = runner._build_request(case)

        self.assertNotEqual(first.traceId, second.traceId)
        self.assertTrue(first.traceId.startswith("eval-unique-eval-trace-"))
        self.assertFalse(first.resumeFromCheckpoint)

    async def test_delegation_eval_uses_control_arm_and_requires_completed_candidate(self) -> None:
        profile = {
            "name": "market_scan",
            "category": "Delegated",
            "maxTokens": 1200,
            "maxToolCalls": 4,
            "requestedToolCapabilities": ["market.read"],
            "promptVersion": "default",
        }
        profile_hash = "a" * 64
        agent = DelegationEvalAgent(profile=profile, profile_hash=profile_hash, specialist_status="completed")
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        base_case_id = "delegation-control-production-case-identity-length-000000000001"
        case = GoldenEvalCase(
            case_id=base_case_id,
            question="market",
            expected_intent="market_scan",
            expected_answer_mode="trend",
            expected_trace=GoldenEvalExpectedTrace(
                required_tool_names={"rank.lookup"},
                require_selected_experts=True,
                expected_delegated_count=1,
            ),
        )

        report = await runner.run_suite([case], suite_name="market")

        self.assertEqual("passed", report["status"], report)
        self.assertEqual(1, report["totalCases"])
        self.assertEqual(2, report["executedCaseCount"])
        self.assertEqual(0.25, report["metrics"]["delegated_eval_config_gains"][profile_hash])
        self.assertEqual(1.0, report["metrics"]["delegated_eval_config_presence_rates"][profile_hash])
        self.assertEqual([profile_hash], report["metrics"]["delegated_eval_config_fingerprints"])
        self.assertNotIn("delegated_profile_hashes", report["metrics"])
        self.assertEqual(["control", "candidate"], agent.eval_modes)
        self.assertEqual(
            f"{base_case_id}::candidate::{profile_hash}",
            report["results"][1].case_id,
        )
        self.assertGreater(len(report["results"][1].case_id), 128)

    async def test_delegation_eval_does_not_credit_selected_but_failed_specialist(self) -> None:
        profile = {
            "name": "market_scan",
            "category": "Delegated",
            "maxTokens": 1200,
            "maxToolCalls": 4,
            "requestedToolCapabilities": ["market.read"],
            "promptVersion": "default",
        }
        profile_hash = "a" * 64
        runner = GoldenEvalRunner(
            agent=DelegationEvalAgent(profile=profile, profile_hash=profile_hash, specialist_status="failed"),
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        )

        report = await runner.run_suite([
            GoldenEvalCase(case_id="delegation-failed-001", question="market")
        ], suite_name="market")

        self.assertEqual(0.0, report["metrics"]["delegated_eval_config_gains"][profile_hash])
        self.assertEqual(0.0, report["metrics"]["delegated_eval_config_presence_rates"][profile_hash])
        self.assertEqual([], report["metrics"]["delegated_eval_config_fingerprints"])

    def test_trace_metrics_does_not_guess_legacy_profile_fingerprint(self) -> None:
        runner = GoldenEvalRunner(agent=object(), faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        response = KnowledgeChatResponse(
            status="answered",
            answer="market answer",
            resultJson={
                "domainIntent": "market_scan",
                "answerBoundary": "market_evidence",
                "sourcePolicy": {"evidenceContract": {"status": "verified_latest"}},
                "taskGraph": {"tasks": [{"type": "market_scan"}]},
                "expertRouter": {
                    "evaluationMode": "candidate",
                    "delegatedCount": 1,
                    "maxParallel": 1,
                    "selectedExperts": [{
                        "name": "market_scan",
                        "category": "Delegated",
                        "profileFingerprint": "a" * 64,
                    }],
                },
                "specialistDiagnostics": [{"agentName": "market_scan", "status": "completed"}],
            },
        )

        metrics = runner._trace_metrics(response)

        self.assertEqual([], metrics["delegated_eval_config_fingerprints"])
        self.assertNotIn("delegated_profile_hashes", metrics)

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

    async def test_scores_project_retrieval_evidence_by_chapter_generation_and_graph_edge(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="The signal is planted in chapter 12.[1]",
                sources=[KnowledgeSource(
                    sourceType="CHAPTER",
                    sourceRefId=12,
                    chapterId=12,
                    chapterNo=12,
                    projectId=900,
                    workId=901,
                    generationId=77,
                )],
                resultJson={
                    "domainIntent": "project_knowledge_qa",
                    "answerMode": "evidence",
                    "projectKnowledge": {
                        "retrievedEvidence": [
                            {
                                "sourceType": "CHAPTER",
                                "chapterId": 12,
                                "chapterNo": 12,
                                "generationId": 77,
                                "userId": 7,
                            },
                            {
                                "sourceType": "FORESHADOWING",
                                "sourceId": "moon",
                                "chapterId": 12,
                                "generationId": 77,
                                "userId": 7,
                            },
                            {
                                "sourceType": "STORY_EDGE",
                                "sourceId": 101,
                                "chapterId": 12,
                                "generationId": 77,
                                "userId": 7,
                                "edge": {"edgeId": 101, "evidenceChapterId": 12},
                            },
                        ],
                        "structuredValues": {"character:hero:status": "injured"},
                    },
                    "retrievalDiagnostics": {"staleRejected": True},
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="project-graph-001",
            question="Where was the signal planted?",
            request_payload={"userId": 7, "projectId": 900},
            relevant_source_ids={"chapter:12"},
            expected_chapter_ids={"chapter:12"},
            expected_foreshadowing_ids={"foreshadowing:moon"},
            expected_structured_values={"character:hero:status": "injured"},
            expected_path_edges={"edge:101": {"chapter:12"}},
            require_stale_rejection=True,
            require_cross_user_isolation=True,
            retrieval_thresholds=RetrievalEvalThresholds(
                min_recall_at_5=1.0,
                min_chapter_location_accuracy=1.0,
                min_foreshadowing_coverage=1.0,
                min_multi_hop_path_evidence=1.0,
                min_stale_rejection_rate=1.0,
                min_cross_user_isolation_rate=1.0,
            ),
        )

        result = await runner.run_case(case)

        self.assertEqual("passed", result.status, result.failures)
        self.assertEqual(1.0, result.retrieval_metrics["chapter_location_accuracy"])
        self.assertEqual(1.0, result.retrieval_metrics["foreshadowing_coverage"])
        self.assertEqual(1.0, result.retrieval_metrics["multi_hop_path_evidence"])
        self.assertEqual(1.0, result.retrieval_metrics["stale_rejection_rate"])
        self.assertEqual(1.0, result.retrieval_metrics["cross_user_isolation_rate"])


    async def test_run_suite_project_release_gate_fails_without_mutating_case_results(self) -> None:
        agent = FakeAgent(
            KnowledgeChatResponse(
                status="answered",
                answer="The signal is planted in chapter 12.[1]",
                sources=[KnowledgeSource(
                    sourceType="CHAPTER",
                    sourceRefId=12,
                    chapterId=12,
                    chapterNo=12,
                    projectId=900,
                    workId=901,
                    generationId=77,
                )],
                resultJson={
                    "domainIntent": "project_knowledge_qa",
                    "answerMode": "evidence",
                    "projectKnowledge": {
                        "retrievedEvidence": [
                            {
                                "sourceType": "CHAPTER",
                                "chapterId": 12,
                                "chapterNo": 12,
                                "generationId": 77,
                                "userId": 7,
                            },
                            {
                                "sourceType": "FORESHADOWING",
                                "sourceId": "moon",
                                "chapterId": 12,
                                "generationId": 77,
                                "userId": 7,
                            },
                            {
                                "sourceType": "STORY_EDGE",
                                "sourceId": 101,
                                "chapterId": 12,
                                "generationId": 77,
                                "userId": 7,
                                "edge": {"edgeId": 101, "evidenceChapterId": 12},
                            },
                        ],
                        "structuredValues": {"character:hero:status": "injured"},
                    },
                    "retrievalDiagnostics": {"staleRejected": True},
                },
            )
        )
        runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
        case = GoldenEvalCase(
            case_id="project-gate-001",
            question="Where was the signal planted?",
            request_payload={"userId": 7, "projectId": 900},
            relevant_source_ids={"chapter:12"},
            expected_chapter_ids={"chapter:12"},
            expected_foreshadowing_ids={"foreshadowing:moon"},
            expected_structured_values={"character:hero:status": "injured"},
            expected_path_edges={"edge:101": {"chapter:12"}},
            require_stale_rejection=True,
            require_cross_user_isolation=True,
            evaluation_cohort={"genre": "urban", "generation": "77"},
            apply_project_release_gate=True,
            retrieval_thresholds=RetrievalEvalThresholds(
                min_recall_at_5=1.0,
                min_chapter_location_accuracy=1.0,
                min_foreshadowing_coverage=1.0,
                min_multi_hop_path_evidence=1.0,
                min_stale_rejection_rate=1.0,
                min_cross_user_isolation_rate=1.0,
            ),
        )

        report = await runner.run_suite([case], suite_name="project-retrieval-gate")

        self.assertEqual("passed", report["results"][0].status)
        self.assertEqual(1, report["passedCases"])
        self.assertEqual(0, report["failedCases"])
        self.assertEqual("passed", report["status"])
        self.assertIsNotNone(report["projectRetrievalReleaseGate"])
        self.assertTrue(report["projectRetrievalReleaseGate"]["passed"])
        self.assertIn("genre:urban", report["metrics"]["projectRetrievalCohorts"])
        self.assertEqual(1.0, report["metrics"]["projectRetrieval"]["chapter_location_accuracy"])
        self.assertEqual(0.0, report["metrics"]["projectRetrieval"]["old_generation_misretrieval_rate"])

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
                    "toolLedger": {
                        "status": "available",
                        "calls": [{"name": "rank.lookup", "status": "succeeded", "executed": True}],
                    },
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

    async def test_required_tool_gate_rejects_failed_or_unexecuted_ledger_calls(self) -> None:
        for call in (
            {"name": "rank.lookup", "status": "failed", "executed": True},
            {"name": "rank.lookup", "status": "succeeded", "executed": False, "reused": False},
        ):
            agent = FakeAgent(KnowledgeChatResponse(
                status="answered",
                answer="answer",
                resultJson={
                    "toolRuns": [{"name": "rank.lookup", "status": "succeeded"}],
                    "toolLedger": {"status": "available", "calls": [call]},
                },
            ))
            runner = GoldenEvalRunner(agent=agent, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
            case = GoldenEvalCase(
                case_id="tool-ledger-required",
                question="question",
                expected_trace=GoldenEvalExpectedTrace(required_tool_names={"rank.lookup"}),
            )

            result = await runner.run_case(case)

            self.assertIn("trace:missing_tool:rank.lookup", result.failures)

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
                    "toolLedger": {
                        "status": "available",
                        "calls": [{"name": "rank.lookup", "status": "succeeded", "executed": True}],
                    },
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

    async def test_selected_capability_gate_requires_successful_execution(self) -> None:
        agent = FakeAgent(KnowledgeChatResponse(
            status="answered",
            answer="answer",
            resultJson={
                "selectedCapabilities": [{"name": "market_scan"}],
                "specialistDiagnostics": [],
            },
        ))
        result = await GoldenEvalRunner(
            agent=agent,
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        ).run_case(GoldenEvalCase(
            case_id="capability-execution-001",
            question="market",
            expected_trace=GoldenEvalExpectedTrace(require_selected_capabilities=True),
        ))

        self.assertEqual("failed", result.status)
        self.assertIn("trace:selected_capabilities_empty", result.failures)

        failed_agent = FakeAgent(KnowledgeChatResponse(
            status="answered",
            answer="answer",
            resultJson={
                "selectedCapabilities": [{"name": "market_scan"}],
                "specialistDiagnostics": [{"agentName": "market_scan", "status": "failed"}],
            },
        ))
        failed_result = await GoldenEvalRunner(
            agent=failed_agent,
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        ).run_case(GoldenEvalCase(
            case_id="capability-execution-002",
            question="market",
            expected_trace=GoldenEvalExpectedTrace(require_selected_capabilities=True),
        ))
        self.assertIn("trace:selected_capabilities_empty", failed_result.failures)

        missing_status_agent = FakeAgent(KnowledgeChatResponse(
            status="answered",
            answer="answer",
            resultJson={
                "selectedCapabilities": [{"name": "market_scan"}],
                "specialistDiagnostics": [{"agentName": "market_scan"}],
            },
        ))
        missing_status_result = await GoldenEvalRunner(
            agent=missing_status_agent,
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        ).run_case(GoldenEvalCase(
            case_id="capability-execution-003",
            question="market",
            expected_trace=GoldenEvalExpectedTrace(require_selected_capabilities=True),
        ))
        self.assertIn("trace:selected_capabilities_empty", missing_status_result.failures)

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


class DelegationEvalAgent:
    def __init__(self, *, profile: dict, profile_hash: str, specialist_status: str) -> None:
        self.profile = profile
        self.profile_hash = profile_hash
        self.specialist_status = specialist_status
        self.requests = []
        self.eval_modes = []

    async def eval_delegation_candidates(self, suite_name: str):
        return [{"name": self.profile["name"], "evalConfigFingerprint": self.profile_hash}]

    async def run(self, request):
        self.requests.append(request)
        eval_mode, _ = current_eval_delegation()
        self.eval_modes.append(eval_mode)
        candidate = eval_mode == "candidate"
        selected = [
            {
                **self.profile,
                "evalConfigFingerprint": self.profile_hash,
                "runtimeBindingFingerprint": "b" * 64,
                "qualityGainVerified": False,
                "qualityGainSource": "unverified",
            }
        ] if candidate else []
        return KnowledgeChatResponse(
            status="answered",
            answer="market answer",
            resultJson={
                "domainIntent": "market_scan",
                "answerMode": "trend",
                "answerBoundary": "market_evidence",
                "sourcePolicy": {"evidenceContract": {"status": "verified_latest"}},
                "taskGraph": {"tasks": [{"type": "market_scan"}]},
                "toolLedger": {
                    "status": "available",
                    "calls": [
                        {
                            "name": "rank.lookup",
                            "status": "succeeded",
                            "executed": True,
                            "reused": False,
                        }
                    ] if candidate else [],
                },
                "expertRouter": {
                    "selectedExperts": selected,
                    "delegatedCount": len(selected),
                    "maxParallel": 1,
                    "reasoningMode": "fast",
                    "evaluationMode": eval_mode,
                },
                "specialistDiagnostics": [
                    {"agentName": self.profile["name"], "status": self.specialist_status}
                ] if candidate else [],
                "trace": {"nodes": [{"name": "compose_answer"}]},
            },
        )


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
