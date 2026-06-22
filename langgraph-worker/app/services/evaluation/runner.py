from __future__ import annotations

from dataclasses import dataclass
from time import perf_counter
from typing import Any

from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.evaluation.faithfulness import RuleBasedFaithfulnessEvaluator
from app.services.evaluation.golden import GoldenEvalCase, GoldenEvalCaseResult, source_eval_id
from app.services.evaluation.repository import MySqlGoldenEvalRepository
from app.services.retrieval_eval import (
    RetrievalEvalCase,
    evaluate_retrieval_cases,
    retrieval_threshold_failures,
)


@dataclass
class GoldenEvalRunner:
    agent: Any
    faithfulness_evaluator: RuleBasedFaithfulnessEvaluator

    async def run_case(self, case: GoldenEvalCase) -> GoldenEvalCaseResult:
        response = await self.agent.run(self._build_request(case))
        assert isinstance(response, KnowledgeChatResponse)
        retrieval_metrics = evaluate_retrieval_cases([
            RetrievalEvalCase(
                case_id=case.case_id,
                ranked_ids=[source_eval_id(source) for source in response.sources],
                relevant_ids=set(case.relevant_source_ids),
                k=case.k,
            )
        ])
        faithfulness = self.faithfulness_evaluator.evaluate(
            answer=response.answer,
            sources=response.sources,
            forbidden_claims=case.forbidden_claims,
        )

        failures: list[str] = []
        if case.expected_intent and response.resultJson.get("domainIntent") != case.expected_intent:
            failures.append(
                f"intent:{response.resultJson.get('domainIntent')} != {case.expected_intent}"
            )
        if case.expected_answer_mode and response.resultJson.get("answerMode") != case.expected_answer_mode:
            failures.append(
                f"answer_mode:{response.resultJson.get('answerMode')} != {case.expected_answer_mode}"
            )
        for failure in retrieval_threshold_failures(retrieval_metrics, case.retrieval_thresholds):
            failures.append(f"retrieval:{failure}")
        for failure in faithfulness["failures"]:
            failures.append(f"faithfulness:{failure}")

        status = "passed" if not failures else "failed"
        return GoldenEvalCaseResult(
            case_id=case.case_id,
            status=status,
            intent=str(response.resultJson.get("domainIntent") or response.resultJson.get("intent") or ""),
            answer_mode=str(response.resultJson.get("answerMode") or ""),
            retrieval_metrics=retrieval_metrics,
            faithfulness=faithfulness,
            failures=failures,
            trace={**dict(response.resultJson.get("trace") or {}), "answer": response.answer},
        )

    async def run_suite(
        self,
        cases: list[GoldenEvalCase],
        *,
        suite_name: str,
        repository: MySqlGoldenEvalRepository | None = None,
        run_key: str | None = None,
        runner_name: str = "local-golden-runner",
        evaluator_name: str = "rule-based",
        model_name: str | None = None,
    ) -> dict[str, Any]:
        started_at = perf_counter()
        persisted_run_id = None
        if repository is not None:
            persisted_run_id = repository.create_run(
                run_key=run_key or f"{suite_name}:{int(started_at * 1000)}",
                suite_name=suite_name,
                runner_name=runner_name,
                evaluator_name=evaluator_name,
                model_name=model_name,
                settings_json={"caseCount": len(cases)},
            )

        results: list[GoldenEvalCaseResult] = []
        for case in cases:
            result = await self.run_case(case)
            results.append(result)
            if repository is not None and persisted_run_id is not None:
                repository.record_case_result(
                    run_id=persisted_run_id,
                    result=result,
                    response_json={
                        "status": result.status,
                        "answer": result.trace.get("answer"),
                        "trace": result.trace,
                    },
                    answer_text=str(result.trace.get("answer") or ""),
                    duration_ms=0,
                )

        passed = sum(1 for result in results if result.status == "passed")
        failed = len(results) - passed
        metrics = self._aggregate_metrics(results)
        if repository is not None and persisted_run_id is not None:
            repository.finish_run(
                run_id=persisted_run_id,
                total_cases=len(results),
                passed_cases=passed,
                failed_cases=failed,
                metrics_json=metrics,
            )
        return {
            "runId": persisted_run_id,
            "suiteName": suite_name,
            "status": "passed" if failed == 0 else "failed",
            "totalCases": len(results),
            "passedCases": passed,
            "failedCases": failed,
            "metrics": metrics,
            "results": results,
            "durationMs": int((perf_counter() - started_at) * 1000),
        }

    def _build_request(self, case: GoldenEvalCase) -> KnowledgeChatRequest:
        payload = dict(case.request_payload)
        payload.setdefault("question", case.question)
        return KnowledgeChatRequest(**payload)

    def _aggregate_metrics(self, results: list[GoldenEvalCaseResult]) -> dict[str, float]:
        if not results:
            return {
                "hit_rate_at_k": 0.0,
                "mrr_at_k": 0.0,
                "context_precision_at_k": 0.0,
                "context_recall_at_k": 0.0,
                "faithfulness_pass_rate": 0.0,
            }
        count = len(results)
        return {
            "hit_rate_at_k": sum(float(result.retrieval_metrics.get("hit_rate_at_k") or 0.0) for result in results) / count,
            "mrr_at_k": sum(float(result.retrieval_metrics.get("mrr_at_k") or 0.0) for result in results) / count,
            "context_precision_at_k": sum(
                float(result.retrieval_metrics.get("context_precision_at_k") or 0.0) for result in results
            ) / count,
            "context_recall_at_k": sum(float(result.retrieval_metrics.get("context_recall_at_k") or 0.0) for result in results) / count,
            "faithfulness_pass_rate": sum(1.0 for result in results if result.faithfulness.get("passed")) / count,
        }
