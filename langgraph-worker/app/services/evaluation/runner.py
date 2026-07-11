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

    async def run_case(self, case: GoldenEvalCase, *, model_name: str | None = None) -> GoldenEvalCaseResult:
        response = await self.agent.run(self._build_request(case, model_name=model_name))
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
            grounded_claims=case.grounded_claims,
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
        trace_metrics = self._trace_metrics(response)
        trace_metrics.update(self._expected_contract_metrics(case, response, faithfulness))
        for failure in self._trace_failures(case, response, trace_metrics):
            failures.append(f"trace:{failure}")

        status = "passed" if not failures else "failed"
        return GoldenEvalCaseResult(
            case_id=case.case_id,
            status=status,
            intent=str(response.resultJson.get("domainIntent") or response.resultJson.get("intent") or ""),
            answer_mode=str(response.resultJson.get("answerMode") or ""),
            retrieval_metrics=retrieval_metrics,
            faithfulness=faithfulness,
            failures=failures,
            trace={**dict(response.resultJson.get("trace") or {}), "answer": response.answer, "metrics": trace_metrics},
        )

    async def run_suite(
        self,
        cases: list[GoldenEvalCase],
        *,
        suite_name: str,
        repository: MySqlGoldenEvalRepository | None = None,
        persisted_run_id: int | None = None,
        run_key: str | None = None,
        runner_name: str = "local-golden-runner",
        evaluator_name: str = "rule-based",
        model_name: str | None = None,
    ) -> dict[str, Any]:
        started_at = perf_counter()
        if repository is not None and persisted_run_id is None:
            persisted_run_id = repository.create_run(
                run_key=run_key or f"{suite_name}:{int(started_at * 1000)}",
                suite_name=suite_name,
                runner_name=runner_name,
                evaluator_name=evaluator_name,
                model_name=model_name,
                settings_json={"caseCount": len(cases)},
            )

        results: list[GoldenEvalCaseResult] = []
        if repository is not None and persisted_run_id is not None:
            update_progress = getattr(repository, "update_run_progress", None)
            if callable(update_progress):
                update_progress(run_id=persisted_run_id, current=0, total=len(cases), message="running")
        for index, case in enumerate(cases, start=1):
            if self._is_cancel_requested(repository, persisted_run_id):
                return self._cancel_report(
                    repository=repository,
                    persisted_run_id=persisted_run_id,
                    suite_name=suite_name,
                    total_cases=len(cases),
                    completed_cases=len(results),
                    results=results,
                    started_at=started_at,
                )
            result = await self.run_case(case, model_name=model_name)
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
                update_progress = getattr(repository, "update_run_progress", None)
                if callable(update_progress):
                    update_progress(
                        run_id=persisted_run_id,
                        current=index,
                        total=len(cases),
                        message=f"completed {index}/{len(cases)}",
                    )
                if self._is_cancel_requested(repository, persisted_run_id):
                    return self._cancel_report(
                        repository=repository,
                        persisted_run_id=persisted_run_id,
                        suite_name=suite_name,
                        total_cases=len(cases),
                        completed_cases=len(results),
                        results=results,
                        started_at=started_at,
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

    def _is_cancel_requested(
        self,
        repository: MySqlGoldenEvalRepository | None,
        persisted_run_id: int | None,
    ) -> bool:
        if repository is None or persisted_run_id is None:
            return False
        checker = getattr(repository, "is_run_cancelled", None)
        if not callable(checker):
            return False
        return bool(checker(persisted_run_id))

    def _cancel_report(
        self,
        *,
        repository: MySqlGoldenEvalRepository | None,
        persisted_run_id: int | None,
        suite_name: str,
        total_cases: int,
        completed_cases: int,
        results: list[GoldenEvalCaseResult],
        started_at: float,
    ) -> dict[str, Any]:
        if repository is not None and persisted_run_id is not None:
            cancel_run = getattr(repository, "cancel_run", None)
            if callable(cancel_run):
                cancel_run(
                    run_id=persisted_run_id,
                    completed_cases=completed_cases,
                    total_cases=total_cases,
                )
        passed_cases = sum(1 for result in results if result.status == "passed")
        failed_cases = len(results) - passed_cases
        return {
            "runId": persisted_run_id,
            "suiteName": suite_name,
            "status": "cancelled",
            "totalCases": total_cases,
            "completedCases": completed_cases,
            "passedCases": passed_cases,
            "failedCases": failed_cases,
            "metrics": {},
            "results": results,
            "durationMs": int((perf_counter() - started_at) * 1000),
        }

    def _build_request(self, case: GoldenEvalCase, *, model_name: str | None = None) -> KnowledgeChatRequest:
        payload = dict(case.request_payload)
        payload.setdefault("question", case.question)
        selected_model = (model_name or "").strip()
        if selected_model:
            limits = payload.get("limits")
            payload["limits"] = dict(limits) if isinstance(limits, dict) else {}
            payload["limits"]["modelName"] = selected_model
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
            "tool_selection_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("tool_selection_correct") or 0.0)
                for result in results
            ) / count,
            "evidence_contract_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("evidence_contract_correct") or 0.0)
                for result in results
            ) / count,
            "answer_boundary_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("answer_boundary_correct") or 0.0)
                for result in results
            ) / count,
            "citation_presence_rate": sum(
                float(result.trace.get("metrics", {}).get("citation_present") or 0.0)
                for result in results
            ) / count,
            "memory_isolation_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("memory_isolation_correct") or 0.0)
                for result in results
            ) / count,
            "trace_completeness_rate": sum(
                float(result.trace.get("metrics", {}).get("trace_complete") or 0.0)
                for result in results
            ) / count,
            "claim_support_rate": sum(
                float(result.trace.get("metrics", {}).get("claim_support_rate") or 0.0)
                for result in results
            ) / count,
            "required_tool_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("required_tool_pass") or 0.0)
                for result in results
            ) / count,
            "required_source_type_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("required_source_type_pass") or 0.0)
                for result in results
            ) / count,
        }

    def _trace_metrics(self, response: KnowledgeChatResponse) -> dict[str, float]:
        result_json = dict(response.resultJson or {})
        source_policy = result_json.get("sourcePolicy") if isinstance(result_json.get("sourcePolicy"), dict) else {}
        trace = result_json.get("trace") if isinstance(result_json.get("trace"), dict) else {}
        intent_correct = 1.0 if result_json.get("domainIntent") or result_json.get("intent") else 0.0
        tool_selection_correct = 1.0 if result_json.get("toolRuns") or result_json.get("mcpToolCalls") else 0.0
        evidence_contract_correct = 1.0 if source_policy.get("evidenceContract") else 0.0
        answer_boundary_correct = 1.0 if (
            result_json.get("answerBoundary")
            or result_json.get("finalAnswerBoundary")
            or result_json.get("domainAnswerBoundary")
            or trace.get("answerBoundary")
        ) else 0.0
        citation_present = 1.0 if response.answer and "[" in response.answer and "]" in response.answer else 0.0
        context_used = result_json.get("contextUsed")
        if not isinstance(context_used, dict):
            context_used = {}
        memory_context = result_json.get("memoryUsed") or result_json.get("memoryContext") or context_used.get("memoryContext", {})
        memory_isolation_correct = 1.0 if not self._has_cross_project_memory(response, memory_context) else 0.0
        trace_complete = 1.0 if trace and result_json.get("sourcePolicy") and result_json.get("taskGraph") else 0.0
        return {
            "intent_accuracy": intent_correct,
            "tool_selection_correct": tool_selection_correct,
            "evidence_contract_correct": evidence_contract_correct,
            "answer_boundary_correct": answer_boundary_correct,
            "citation_present": citation_present,
            "memory_isolation_correct": memory_isolation_correct,
            "trace_complete": trace_complete,
        }

    def _expected_contract_metrics(
        self,
        case: GoldenEvalCase,
        response: KnowledgeChatResponse,
        faithfulness: dict[str, Any],
    ) -> dict[str, float]:
        expected = case.expected_trace
        tool_names = self._tool_names_from_result(dict(response.resultJson or {}))
        source_types = self._source_types_from_response(response)
        required_tool_pass = 1.0
        if expected.required_tool_names:
            required_tool_pass = 1.0 if expected.required_tool_names.issubset(tool_names) else 0.0
        required_source_type_pass = 1.0
        if expected.required_source_types:
            normalized_required = {
                self._normalize_source_type(source_type)
                for source_type in expected.required_source_types
            }
            required_source_type_pass = 1.0 if normalized_required.issubset(source_types) else 0.0
        return {
            "claim_support_rate": float(faithfulness.get("claim_support_rate") or 0.0),
            "required_tool_pass": required_tool_pass,
            "required_source_type_pass": required_source_type_pass,
        }

    def _trace_failures(
        self,
        case: GoldenEvalCase,
        response: KnowledgeChatResponse,
        trace_metrics: dict[str, float],
    ) -> list[str]:
        expected = case.expected_trace
        if not (
            expected.required_tool_names
            or expected.required_source_types
            or expected.required_trace_fields
            or expected.required_source_policy_fields
            or expected.required_evidence_statuses
            or expected.required_answer_terms
            or expected.forbidden_answer_patterns
            or expected.require_valid_answer_boundary
            or expected.require_citations
            or expected.forbid_memory_cross_project
            or expected.forbid_fallback
            or expected.require_provider_success
            or expected.require_selected_experts
        ):
            return []

        result_json = dict(response.resultJson or {})
        trace = result_json.get("trace") if isinstance(result_json.get("trace"), dict) else {}
        source_policy = result_json.get("sourcePolicy") if isinstance(result_json.get("sourcePolicy"), dict) else {}
        failures: list[str] = []

        tool_names = self._tool_names_from_result(result_json)
        for name in sorted(expected.required_tool_names):
            if name not in tool_names:
                failures.append(f"missing_tool:{name}")

        source_types = self._source_types_from_response(response)
        for source_type in sorted(expected.required_source_types):
            normalized_source_type = self._normalize_source_type(source_type)
            if normalized_source_type not in source_types:
                failures.append(f"missing_source_type:{normalized_source_type}")

        for field in sorted(expected.required_trace_fields):
            if field not in result_json and field not in trace:
                failures.append(f"missing_trace_field:{field}")

        for field in sorted(expected.required_source_policy_fields):
            if field not in source_policy:
                failures.append(f"missing_source_policy_field:{field}")

        if expected.required_evidence_statuses:
            contract = source_policy.get("evidenceContract")
            status = contract.get("status") if isinstance(contract, dict) else source_policy.get("evidenceStatus")
            if status not in expected.required_evidence_statuses:
                failures.append(f"evidence_status:{status}")

        if expected.require_valid_answer_boundary and trace_metrics.get("answer_boundary_correct") != 1.0:
            failures.append("missing_answer_boundary")
        if expected.require_citations and trace_metrics.get("citation_present") != 1.0:
            failures.append("missing_citation")
        if expected.forbid_memory_cross_project and trace_metrics.get("memory_isolation_correct") != 1.0:
            failures.append("memory_cross_project")
        if expected.forbid_fallback and self._is_fallback_response(result_json):
            failures.append("fallback_used")
        if expected.require_provider_success and not self._has_successful_provider_call(result_json, trace):
            failures.append("provider_not_succeeded")
        if expected.require_selected_experts and not self._has_selected_experts(result_json, trace):
            failures.append("selected_experts_empty")
        answer = response.answer or ""
        for term in sorted(expected.required_answer_terms):
            if term not in answer:
                failures.append(f"missing_answer_term:{term}")
        for pattern in sorted(expected.forbidden_answer_patterns):
            if pattern and pattern in answer:
                failures.append(f"forbidden_answer_pattern:{pattern}")
        return failures

    def _tool_names_from_result(self, result_json: dict[str, Any]) -> set[str]:
        names: set[str] = set()
        for key in ("toolRuns", "mcpToolCalls"):
            for run in result_json.get(key) or []:
                if isinstance(run, dict) and run.get("name"):
                    names.add(str(run.get("name")))
        return names

    def _source_types_from_response(self, response: KnowledgeChatResponse) -> set[str]:
        return {
            self._normalize_source_type(str(source.sourceType or ""))
            for source in response.sources
            if str(source.sourceType or "").strip()
        }

    def _normalize_source_type(self, source_type: str) -> str:
        value = str(source_type or "").strip().upper()
        if value == "CHAPTER_PACK":
            return "CHAPTER"
        return value

    def _has_cross_project_memory(self, response: KnowledgeChatResponse, memory_context: Any) -> bool:
        if not isinstance(memory_context, dict):
            return False
        request_project_id = response.resultJson.get("projectId")
        if request_project_id is None:
            return False
        memories: list[Any] = []
        for key in ("projectMemory", "semanticMemory", "userMemory"):
            value = memory_context.get(key)
            if isinstance(value, list):
                memories.extend(value)
        for memory in memories:
            if isinstance(memory, dict) and memory.get("projectId") not in {None, request_project_id}:
                return True
        return False

    def _is_fallback_response(self, result_json: dict[str, Any]) -> bool:
        return bool(
            result_json.get("fallbackUsed")
            or result_json.get("degraded")
            or str(result_json.get("answerStatus") or "") == "degraded_model_fallback"
        )

    def _has_successful_provider_call(self, result_json: dict[str, Any], trace: dict[str, Any]) -> bool:
        calls = result_json.get("providerCalls")
        if not isinstance(calls, list):
            calls = trace.get("providerCalls")
        if not isinstance(calls, list):
            return False
        return any(
            isinstance(call, dict)
            and str(call.get("status") or "").lower() in {"succeeded", "completed", "success"}
            for call in calls
        )

    def _has_selected_experts(self, result_json: dict[str, Any], trace: dict[str, Any]) -> bool:
        selected = result_json.get("selectedExperts")
        if not isinstance(selected, list):
            selected = trace.get("selectedExperts")
        if isinstance(selected, list) and selected:
            return True
        router = result_json.get("expertRouter")
        if not isinstance(router, dict):
            router = trace.get("expertRouter")
        if isinstance(router, dict):
            routed = router.get("selectedExperts") or router.get("selected")
            return isinstance(routed, list) and bool(routed)
        return False
