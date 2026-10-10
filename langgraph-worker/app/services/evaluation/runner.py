from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, replace
import json
import math
from pathlib import Path
import re
from time import perf_counter
from typing import TYPE_CHECKING, Any, Mapping
import uuid

from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.evaluation.faithfulness import RuleBasedFaithfulnessEvaluator
from app.services.evaluation.golden import GoldenEvalCase, GoldenEvalCaseResult, source_eval_id
from app.services.retrieval_eval import (
    ProjectRetrievalReleaseGate,
    RetrievalEvalCase,
    RetrievalEvalThresholds,
    bootstrap_confidence_interval,
    evaluate_retrieval_cases,
    project_retrieval_release_gate_failures,
    retrieval_threshold_failures,
)

if TYPE_CHECKING:
    from app.services.evaluation.repository import MySqlGoldenEvalRepository


@dataclass
class GoldenEvalRunner:
    agent: Any
    faithfulness_evaluator: RuleBasedFaithfulnessEvaluator

    async def run_case(
        self,
        case: GoldenEvalCase,
        *,
        model_name: str | None = None,
        eval_delegation_mode: str | None = None,
        eval_candidate_config_fingerprint: str | None = None,
        result_case_id: str | None = None,
    ) -> GoldenEvalCaseResult:
        from app.services.agents.expert_registry import eval_delegation_scope

        with eval_delegation_scope(eval_delegation_mode, eval_candidate_config_fingerprint):
            response = await self.agent.run(self._build_request(case, model_name=model_name))
        assert isinstance(response, KnowledgeChatResponse)
        retrieval_metrics = evaluate_retrieval_cases([self._retrieval_eval_case(case, response)])
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
            case_id=result_case_id or case.case_id,
            status=status,
            intent=str(response.resultJson.get("domainIntent") or response.resultJson.get("intent") or ""),
            answer_mode=str(response.resultJson.get("answerMode") or ""),
            retrieval_metrics=retrieval_metrics,
            faithfulness=faithfulness,
            failures=failures,
            trace={
                **dict(response.resultJson.get("trace") or {}),
                "answer": response.answer,
                "metrics": trace_metrics,
                "evaluationCohort": dict(case.evaluation_cohort),
                "applyProjectReleaseGate": bool(case.apply_project_release_gate),
            },
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
        delegation_candidates = await self._delegation_candidates(suite_name)
        planned_cases: list[tuple[GoldenEvalCase, str | None, str | None, str]] = []
        if delegation_candidates:
            for case in cases:
                planned_cases.append((
                    self._control_case(case),
                    "control",
                    None,
                    f"{case.case_id}::control",
                ))
                for candidate in delegation_candidates:
                    eval_config_fingerprint = candidate["evalConfigFingerprint"]
                    planned_cases.append((
                        case,
                        "candidate",
                        eval_config_fingerprint,
                        f"{case.case_id}::candidate::{eval_config_fingerprint}",
                    ))
        else:
            planned_cases = [(case, None, None, case.case_id) for case in cases]
        if repository is not None and persisted_run_id is None:
            persisted_run_id = repository.create_run(
                run_key=run_key or f"{suite_name}:{int(started_at * 1000)}",
                suite_name=suite_name,
                runner_name=runner_name,
                evaluator_name=evaluator_name,
                model_name=model_name,
                settings_json={
                    "caseCount": len(cases),
                    "executedCaseCount": len(planned_cases),
                    "delegationCandidates": delegation_candidates,
                },
            )

        results: list[GoldenEvalCaseResult] = []
        if repository is not None and persisted_run_id is not None:
            update_progress = getattr(repository, "update_run_progress", None)
            if callable(update_progress):
                update_progress(run_id=persisted_run_id, current=0, total=len(planned_cases), message="running")
        for index, (case, eval_mode, candidate_fingerprint, result_case_id) in enumerate(planned_cases, start=1):
            if self._is_cancel_requested(repository, persisted_run_id):
                return self._cancel_report(
                    repository=repository,
                    persisted_run_id=persisted_run_id,
                    suite_name=suite_name,
                    total_cases=len(planned_cases),
                    completed_cases=len(results),
                    results=results,
                    started_at=started_at,
                )
            result = await self.run_case(
                case,
                model_name=model_name,
                eval_delegation_mode=eval_mode,
                eval_candidate_config_fingerprint=candidate_fingerprint,
                result_case_id=result_case_id,
            )
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
                        total=len(planned_cases),
                        message=f"completed {index}/{len(planned_cases)}",
                    )
                if self._is_cancel_requested(repository, persisted_run_id):
                    return self._cancel_report(
                        repository=repository,
                        persisted_run_id=persisted_run_id,
                        suite_name=suite_name,
                        total_cases=len(planned_cases),
                        completed_cases=len(results),
                        results=results,
                        started_at=started_at,
                    )

        gated_results = (
            [result for result in results if "::candidate::" in result.case_id]
            if delegation_candidates
            else results
        )
        passed = sum(1 for result in gated_results if result.status == "passed")
        failed = len(gated_results) - passed
        metrics = (
            self._aggregate_delegation_metrics(results, delegation_candidates)
            if delegation_candidates
            else self._aggregate_metrics(results)
        )
        project_gate = self._project_release_gate_report(gated_results)
        if project_gate is not None:
            metrics = {
                **metrics,
                "projectRetrieval": project_gate["metrics"],
                "projectRetrievalCohorts": project_gate["cohorts"],
                "projectRetrievalReleaseGate": project_gate["gate"],
            }
        suite_failed = failed > 0 or bool(project_gate and project_gate["gate"]["failures"])
        if repository is not None and persisted_run_id is not None:
            repository.finish_run(
                run_id=persisted_run_id,
                total_cases=len(gated_results),
                passed_cases=passed,
                failed_cases=failed,
                metrics_json=metrics,
            )
        return {
            "runId": persisted_run_id,
            "suiteName": suite_name,
            "status": "failed" if suite_failed else "passed",
            "totalCases": len(gated_results),
            "executedCaseCount": len(results),
            "passedCases": passed,
            "failedCases": failed,
            "metrics": metrics,
            "results": results,
            "durationMs": int((perf_counter() - started_at) * 1000),
            "projectRetrievalReleaseGate": None if project_gate is None else project_gate["gate"],
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

    def _build_request(
        self,
        case: GoldenEvalCase,
        *,
        model_name: str | None = None,
    ) -> KnowledgeChatRequest:
        payload = dict(case.request_payload)
        payload.pop("_projectRetrievalEval", None)
        payload.setdefault("question", case.question)
        payload["traceId"] = f"eval-{case.case_id}-{uuid.uuid4().hex}"
        payload["resumeFromCheckpoint"] = False
        selected_model = (model_name or "").strip()
        if selected_model:
            limits = payload.get("limits")
            payload["limits"] = dict(limits) if isinstance(limits, dict) else {}
            payload["limits"]["modelName"] = selected_model
        return KnowledgeChatRequest(**payload)

    def _retrieval_eval_case(
        self,
        case: GoldenEvalCase,
        response: KnowledgeChatResponse,
    ) -> RetrievalEvalCase:
        result_json = dict(response.resultJson or {})
        project_knowledge = self._project_knowledge(result_json)
        evidence_items = self._project_evidence_items(project_knowledge)
        chapter_ids = self._chapter_ids(response, evidence_items)
        foreshadowing_ids = self._foreshadowing_ids(response, evidence_items, project_knowledge)
        path_edges = self._path_edge_evidence(evidence_items, project_knowledge)
        structured_values = project_knowledge.get("structuredValues")
        if not isinstance(structured_values, dict):
            structured_values = result_json.get("structuredValues") if isinstance(result_json.get("structuredValues"), dict) else {}
        diagnostics = self._retrieval_diagnostics(result_json, project_knowledge)
        expected_generation_ids = self._expected_generation_ids(case)
        observed_generation_ids = self._observed_generation_ids(response, evidence_items)
        return RetrievalEvalCase(
            case_id=case.case_id,
            ranked_ids=[source_eval_id(source) for source in response.sources],
            relevant_ids=set(case.relevant_source_ids),
            k=case.k,
            relevance_grades=dict(case.relevance_grades),
            expected_chapter_ids=set(case.expected_chapter_ids),
            retrieved_chapter_ids=chapter_ids,
            expected_foreshadowing_ids=set(case.expected_foreshadowing_ids),
            retrieved_foreshadowing_ids=foreshadowing_ids,
            expected_structured_values=dict(case.expected_structured_values),
            actual_structured_values=dict(structured_values),
            expected_path_edges={key: set(value) for key, value in case.expected_path_edges.items()},
            observed_path_edges=path_edges,
            expected_stale_rejection=True if case.require_stale_rejection else None,
            stale_rejected=self._stale_rejected(diagnostics),
            expected_cross_user_isolation=True if case.require_cross_user_isolation else None,
            cross_user_isolated=self._cross_user_isolated(case, evidence_items),
            expected_generation_ids=expected_generation_ids,
            observed_generation_ids=observed_generation_ids,
        )

    @classmethod
    def _project_release_gate_report(
        cls,
        results: list[GoldenEvalCaseResult],
        *,
        baseline_metrics: Mapping[str, float | int | Any] | None = None,
        baseline_confidence_intervals: Mapping[str, Mapping[str, float | int | None]] | None = None,
    ) -> dict[str, Any] | None:
        project_results = [
            result for result in results
            if bool(result.trace.get("applyProjectReleaseGate"))
        ]
        if not project_results:
            return None
        overall_metrics = cls._aggregate_project_retrieval_metrics(project_results)
        confidence = cls._project_metric_confidence_intervals(project_results)
        failures = project_retrieval_release_gate_failures(
            overall_metrics,
            ProjectRetrievalReleaseGate(),
            baseline_metrics=baseline_metrics,
            confidence_intervals=confidence,
            baseline_confidence_intervals=baseline_confidence_intervals,
        )
        cohorts: dict[str, list[GoldenEvalCaseResult]] = {}
        for result in project_results:
            cohort = result.trace.get("evaluationCohort") if isinstance(result.trace.get("evaluationCohort"), dict) else {}
            for key, value in cohort.items():
                bucket_key = f"{key}:{value}"
                cohorts.setdefault(bucket_key, []).append(result)
        cohort_reports: dict[str, Any] = {}
        for bucket_key, bucket_results in cohorts.items():
            metrics = cls._aggregate_project_retrieval_metrics(bucket_results)
            intervals = cls._project_metric_confidence_intervals(bucket_results)
            cohort_reports[bucket_key] = {
                "caseCount": len(bucket_results),
                "metrics": metrics,
                "confidenceIntervals": intervals,
            }
        return {
            "metrics": overall_metrics,
            "confidenceIntervals": confidence,
            "cohorts": cohort_reports,
            "gate": {
                "enabled": True,
                "passed": not failures,
                "failures": failures,
                "thresholds": {
                    "min_recall_at_5": ProjectRetrievalReleaseGate.min_recall_at_5,
                    "min_chapter_location_accuracy": ProjectRetrievalReleaseGate.min_chapter_location_accuracy,
                    "min_structured_accuracy": ProjectRetrievalReleaseGate.min_structured_accuracy,
                    "min_foreshadowing_coverage": ProjectRetrievalReleaseGate.min_foreshadowing_coverage,
                    "min_multi_hop_path_evidence": ProjectRetrievalReleaseGate.min_multi_hop_path_evidence,
                    "min_cross_user_isolation_rate": ProjectRetrievalReleaseGate.min_cross_user_isolation_rate,
                    "max_old_generation_misretrieval_rate": ProjectRetrievalReleaseGate.max_old_generation_misretrieval_rate,
                    "max_regression_drop": ProjectRetrievalReleaseGate.max_regression_drop,
                },
            },
        }

    @staticmethod
    def _aggregate_project_retrieval_metrics(results: list[GoldenEvalCaseResult]) -> dict[str, float | int]:
        if not results:
            return {}
        metric_names = (
            "hit_rate_at_k",
            "mrr_at_k",
            "context_precision_at_k",
            "context_recall_at_k",
            "recall_at_5",
            "recall_at_10",
            "precision_at_5",
            "ndcg_at_10",
            "structured_accuracy",
            "chapter_location_accuracy",
            "foreshadowing_coverage",
            "multi_hop_path_evidence",
            "stale_rejection_rate",
            "cross_user_isolation_rate",
            "old_generation_misretrieval_rate",
        )
        count_names = (
            "active_retrieval_case_count",
            "structured_case_count",
            "chapter_location_case_count",
            "foreshadowing_case_count",
            "multi_hop_path_case_count",
            "stale_rejection_case_count",
            "cross_user_isolation_case_count",
            "generation_case_count",
        )
        totals: dict[str, float] = {name: 0.0 for name in metric_names}
        counts: dict[str, int] = {name: 0 for name in count_names}
        weighted: dict[str, float] = {name: 0.0 for name in metric_names}
        for result in results:
            metrics = result.retrieval_metrics or {}
            for count_name in count_names:
                counts[count_name] += int(metrics.get(count_name) or 0)
            for metric_name in metric_names:
                value = float(metrics.get(metric_name) or 0.0)
                weight = int(metrics.get("active_retrieval_case_count") or 0)
                if metric_name == "structured_accuracy":
                    weight = int(metrics.get("structured_case_count") or 0)
                elif metric_name == "chapter_location_accuracy":
                    weight = int(metrics.get("chapter_location_case_count") or 0)
                elif metric_name == "foreshadowing_coverage":
                    weight = int(metrics.get("foreshadowing_case_count") or 0)
                elif metric_name == "multi_hop_path_evidence":
                    weight = int(metrics.get("multi_hop_path_case_count") or 0)
                elif metric_name == "stale_rejection_rate":
                    weight = int(metrics.get("stale_rejection_case_count") or 0)
                elif metric_name == "cross_user_isolation_rate":
                    weight = int(metrics.get("cross_user_isolation_case_count") or 0)
                elif metric_name == "old_generation_misretrieval_rate":
                    weight = int(metrics.get("generation_case_count") or 0)
                if weight <= 0:
                    continue
                weighted[metric_name] += value * weight
                totals[metric_name] += weight
        aggregated: dict[str, float | int] = dict(counts)
        for metric_name in metric_names:
            denominator = totals[metric_name]
            aggregated[metric_name] = 0.0 if denominator <= 0 else weighted[metric_name] / denominator
        return aggregated

    @staticmethod
    def _project_metric_confidence_intervals(
        results: list[GoldenEvalCaseResult],
        *,
        seed: int = 11,
    ) -> dict[str, dict[str, float | int | None]]:
        dimensions = (
            ("recall_at_5", "active_retrieval_case_count"),
            ("chapter_location_accuracy", "chapter_location_case_count"),
            ("structured_accuracy", "structured_case_count"),
            ("foreshadowing_coverage", "foreshadowing_case_count"),
            ("multi_hop_path_evidence", "multi_hop_path_case_count"),
            ("cross_user_isolation_rate", "cross_user_isolation_case_count"),
        )
        intervals: dict[str, dict[str, float | int | None]] = {}
        for metric_name, count_name in dimensions:
            values = [
                float(result.retrieval_metrics.get(metric_name) or 0.0)
                for result in results
                if int((result.retrieval_metrics or {}).get(count_name) or 0) > 0
            ]
            intervals[metric_name] = bootstrap_confidence_interval(values, iterations=200, seed=seed)
        return intervals

    @staticmethod
    def _expected_generation_ids(case: GoldenEvalCase) -> set[str]:
        expected: set[str] = set()
        generation = case.evaluation_cohort.get("generation")
        if generation not in {None, ""}:
            expected.add(str(generation))
        request_generation = case.request_payload.get("generationId") or case.request_payload.get("generation_id")
        if request_generation not in {None, ""}:
            expected.add(str(request_generation))
        return expected

    @staticmethod
    def _observed_generation_ids(
        response: KnowledgeChatResponse,
        evidence_items: list[dict[str, Any]],
    ) -> set[str]:
        observed: set[str] = set()
        for source in response.sources:
            generation_id = getattr(source, "generationId", None)
            if generation_id not in {None, ""}:
                observed.add(str(generation_id))
        for item in evidence_items:
            generation_id = item.get("generationId") or item.get("generation_id")
            if generation_id not in {None, ""}:
                observed.add(str(generation_id))
        return observed

    @staticmethod
    def _project_knowledge(result_json: dict[str, Any]) -> dict[str, Any]:

        project_knowledge = result_json.get("projectKnowledge")
        if isinstance(project_knowledge, dict):
            return project_knowledge
        trace = result_json.get("trace")
        if isinstance(trace, dict) and isinstance(trace.get("projectKnowledge"), dict):
            return dict(trace["projectKnowledge"])
        return {}

    @staticmethod
    def _project_evidence_items(project_knowledge: dict[str, Any]) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for key in ("retrievedEvidence", "retrievedChapters", "retrievedChunks"):
            value = project_knowledge.get(key)
            if isinstance(value, list):
                items.extend(item for item in value if isinstance(item, dict))
        return items

    @staticmethod
    def _chapter_ids(response: KnowledgeChatResponse, evidence_items: list[dict[str, Any]]) -> set[str]:
        chapter_ids: set[str] = set()
        for source in response.sources:
            if "chapter" not in str(source.sourceType or "").lower():
                continue
            identifier = source.chapterId or source.sourceRefId or source.chapterNo
            if identifier is not None:
                chapter_ids.add(f"chapter:{identifier}")
        for item in evidence_items:
            identifier = item.get("chapterId") or item.get("chapter_id") or item.get("chapterNo")
            if identifier is not None:
                chapter_ids.add(f"chapter:{identifier}")
        return chapter_ids

    @staticmethod
    def _foreshadowing_ids(
        response: KnowledgeChatResponse,
        evidence_items: list[dict[str, Any]],
        project_knowledge: dict[str, Any],
    ) -> set[str]:
        identifiers: set[str] = set()
        for source in response.sources:
            if "foreshadow" not in str(source.sourceType or "").lower():
                continue
            identifier = source.sourceRefId or source.chunkId or source.documentId
            if identifier is not None:
                identifiers.add(f"foreshadowing:{identifier}")
        for item in evidence_items + [item for item in project_knowledge.get("matchedForeshadowings", []) if isinstance(item, dict)]:
            if "foreshadow" not in str(item.get("sourceType") or item.get("type") or "").lower():
                continue
            identifier = item.get("sourceId") or item.get("foreshadowingId") or item.get("id")
            if identifier is not None:
                identifiers.add(f"foreshadowing:{identifier}")
        return identifiers

    @staticmethod
    def _path_edge_evidence(
        evidence_items: list[dict[str, Any]],
        project_knowledge: dict[str, Any],
    ) -> dict[str, set[str]]:
        observed: dict[str, set[str]] = {}
        graph = project_knowledge.get("storyGraph") if isinstance(project_knowledge.get("storyGraph"), dict) else {}
        candidates = evidence_items + [item for item in graph.get("edges", []) if isinstance(item, dict)]
        for item in candidates:
            edge = item.get("edge") if isinstance(item.get("edge"), dict) else item
            edge_id = edge.get("edgeId") or item.get("edgeId")
            evidence_chapter_id = edge.get("evidenceChapterId") or item.get("chapterId") or item.get("chapter_id")
            if edge_id is None or evidence_chapter_id is None:
                continue
            observed.setdefault(f"edge:{edge_id}", set()).add(f"chapter:{evidence_chapter_id}")
        return observed

    @staticmethod
    def _retrieval_diagnostics(result_json: dict[str, Any], project_knowledge: dict[str, Any]) -> dict[str, Any]:
        diagnostics = result_json.get("retrievalDiagnostics")
        if isinstance(diagnostics, dict):
            return diagnostics
        diagnostics = project_knowledge.get("retrievalDiagnostics")
        return diagnostics if isinstance(diagnostics, dict) else {}

    @staticmethod
    def _stale_rejected(diagnostics: dict[str, Any]) -> bool | None:
        if "staleRejected" in diagnostics:
            return bool(diagnostics["staleRejected"])
        for key in ("generationRejectedCount", "oldGenerationRejectedCount", "staleRejectedCount"):
            if key in diagnostics:
                try:
                    return int(diagnostics[key]) > 0
                except (TypeError, ValueError):
                    return False
        return None

    @staticmethod
    def _cross_user_isolated(case: GoldenEvalCase, evidence_items: list[dict[str, Any]]) -> bool | None:
        expected_user = case.request_payload.get("userId")
        if expected_user is None:
            return None
        scoped_user_ids = [item.get("userId") or item.get("user_id") for item in evidence_items if item.get("userId") is not None or item.get("user_id") is not None]
        if not scoped_user_ids:
            return None
        return all(str(user_id) == str(expected_user) for user_id in scoped_user_ids)

    async def _delegation_candidates(self, suite_name: str) -> list[dict[str, str]]:
        loader = getattr(self.agent, "eval_delegation_candidates", None)
        if not callable(loader):
            return []
        candidates = await loader(suite_name)
        return [
            {
                "name": str(item.get("name") or ""),
                "evalConfigFingerprint": str(item.get("evalConfigFingerprint") or ""),
            }
            for item in candidates or []
            if isinstance(item, dict) and item.get("name") and item.get("evalConfigFingerprint")
        ]

    def _control_case(self, case: GoldenEvalCase) -> GoldenEvalCase:
        expected = case.expected_trace
        capability_categories = {
            name: category
            for name, category in expected.required_capability_categories.items()
            if str(category).lower() != "delegated"
        }
        return replace(case, expected_trace=replace(
            expected,
            require_selected_experts=False,
            required_capability_categories=capability_categories,
            expected_delegated_count=0,
            expected_quality_gain_threshold=None,
        ))

    def _aggregate_delegation_metrics(
        self,
        results: list[GoldenEvalCaseResult],
        candidates: list[dict[str, str]],
    ) -> dict[str, Any]:
        controls = {
            result.case_id.removesuffix("::control"): result
            for result in results
            if result.case_id.endswith("::control")
        }
        candidate_results = [result for result in results if "::candidate::" in result.case_id]
        metrics = self._aggregate_metrics(candidate_results)
        gains: dict[str, float] = {}
        presence_rates: dict[str, float] = {}
        for candidate in candidates:
            eval_config_fingerprint = candidate["evalConfigFingerprint"]
            suffix = f"::candidate::{eval_config_fingerprint}"
            profile_results = [result for result in candidate_results if result.case_id.endswith(suffix)]
            if not profile_results:
                gains[eval_config_fingerprint] = 0.0
                presence_rates[eval_config_fingerprint] = 0.0
                continue
            deltas: list[float] = []
            successful = 0
            for result in profile_results:
                base_id = result.case_id.removesuffix(suffix)
                control = controls.get(base_id)
                if control is None:
                    continue
                if eval_config_fingerprint in result.trace.get("metrics", {}).get(
                    "delegated_eval_config_fingerprints",
                    [],
                ):
                    successful += 1
                    deltas.append(self._quality_score(result) - self._quality_score(control))
            presence_rates[eval_config_fingerprint] = successful / len(profile_results)
            gains[eval_config_fingerprint] = round(max(0.0, min(deltas)), 6) if deltas else 0.0
        metrics["delegated_eval_config_gains"] = gains
        metrics["delegated_eval_config_presence_rates"] = presence_rates
        metrics["delegated_eval_config_fingerprints"] = sorted(
            fingerprint for fingerprint, rate in presence_rates.items() if rate >= 1.0
        )
        metrics["delegated_expert_presence_rate"] = min(presence_rates.values(), default=0.0)
        metrics["delegated_quality_gain"] = min(gains.values(), default=0.0)
        metrics["eval_control_case_count"] = len(controls)
        metrics["eval_candidate_case_count"] = len(candidate_results)
        return metrics

    def _quality_score(self, result: GoldenEvalCaseResult) -> float:
        metrics = result.trace.get("metrics", {})
        values = [
            float(metrics.get("faithfulness_pass") or 0.0),
            float(metrics.get("required_tool_pass") or 0.0),
            float(metrics.get("trace_complete") or 0.0),
            float(metrics.get("answer_boundary_correct") or 0.0),
        ]
        return sum(values) / len(values)

    def _aggregate_metrics(self, results: list[GoldenEvalCaseResult]) -> dict[str, Any]:
        if not results:
            return {
                "hit_rate_at_k": 0.0,
                "mrr_at_k": 0.0,
                "context_precision_at_k": 0.0,
                "context_recall_at_k": 0.0,
                "faithfulness_pass_rate": 0.0,
            }
        count = len(results)
        delegated_eval_config_fingerprints = sorted({
            str(fingerprint)
            for result in results
            for fingerprint in result.trace.get("metrics", {}).get(
                "delegated_eval_config_fingerprints",
                [],
            )
            if str(fingerprint)
        })
        delegated_results = [
            result for result in results
            if result.trace.get("metrics", {}).get("delegated_expert_present")
        ]
        delegated_quality_gain = 0.0
        if delegated_results:
            delegated_scores = [
                min(
                    float(result.trace.get("metrics", {}).get(key) or 0.0)
                    for key in ("faithfulness_pass", "required_tool_pass", "trace_complete", "answer_boundary_correct")
                )
                for result in delegated_results
            ]
            delegated_quality_gain = max(0.0, min(delegated_scores) - 0.75)
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
            "delegation_policy_pass_rate": sum(
                float(result.trace.get("metrics", {}).get("delegation_policy_correct") or 0.0)
                for result in results
            ) / count,
            "delegated_expert_presence_rate": len(delegated_results) / count,
            "delegated_quality_gain": delegated_quality_gain,
            "delegated_eval_config_fingerprints": delegated_eval_config_fingerprints,
        }

    def _trace_metrics(self, response: KnowledgeChatResponse) -> dict[str, Any]:
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
        router = result_json.get("expertRouter")
        if not isinstance(router, dict):
            router = trace.get("expertRouter") if isinstance(trace.get("expertRouter"), dict) else {}
        mode = str(router.get("reasoningMode") or "fast").lower()
        delegated_limit = 2 if mode == "deep" else 1
        selected_experts = router.get("selectedExperts") if isinstance(router, dict) else []
        if not isinstance(selected_experts, list):
            selected_experts = []
        specialist_diagnostics = result_json.get("specialistDiagnostics")
        if not isinstance(specialist_diagnostics, list):
            specialist_diagnostics = []
        completed_specialists = {
            str(item.get("agentName") or "")
            for item in specialist_diagnostics
            if isinstance(item, dict) and item.get("status") == "completed"
        }
        delegated_experts = [
            item for item in selected_experts
            if isinstance(item, dict)
            and item.get("category") == "Delegated"
            and str(item.get("name") or "") in completed_specialists
        ]
        eval_candidate_mode = router.get("evaluationMode") == "candidate"
        delegation_policy_correct = 1.0 if (
            int(router.get("delegatedCount") or 0) <= delegated_limit
            and int(router.get("maxParallel") or 0) <= 1
            and all(
                isinstance(item, dict)
                and item.get("category") == "Delegated"
                and (
                    eval_candidate_mode
                    or (
                        item.get("qualityGainVerified") is True
                        and item.get("qualityGainSource") == "admin_configured_eval"
                    )
                )
                for item in selected_experts
            )
            and len(delegated_experts) == len(selected_experts)
        ) else 0.0
        metrics: dict[str, Any] = {
            "intent_accuracy": intent_correct,
            "tool_selection_correct": tool_selection_correct,
            "evidence_contract_correct": evidence_contract_correct,
            "answer_boundary_correct": answer_boundary_correct,
            "citation_present": citation_present,
            "memory_isolation_correct": memory_isolation_correct,
            "trace_complete": trace_complete,
            "delegation_policy_correct": delegation_policy_correct,
            "delegated_expert_present": bool(delegated_experts),
            "delegated_eval_config_fingerprints": [
                fingerprint
                for item in delegated_experts
                if (fingerprint := self._eval_config_fingerprint(item)) is not None
            ],
        }
        return metrics

    def _eval_config_fingerprint(self, item: dict[str, Any]) -> str | None:
        provided = str(item.get("evalConfigFingerprint") or "")
        if len(provided) == 64 and all(character in "0123456789abcdef" for character in provided.lower()):
            return provided.lower()
        return None

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
            "faithfulness_pass": 1.0 if faithfulness.get("passed") else 0.0,
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
            or expected.require_selected_capabilities
            or expected.required_capability_categories
            or expected.expected_delegated_count is not None
            or expected.expected_max_parallel is not None
            or expected.expected_quality_gain_threshold is not None
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
        if expected.require_selected_capabilities and not self._has_selected_capabilities(result_json, trace):
            failures.append("selected_capabilities_empty")
        router = result_json.get("expertRouter")
        if not isinstance(router, dict):
            router = trace.get("expertRouter") if isinstance(trace.get("expertRouter"), dict) else {}
        capabilities = result_json.get("selectedCapabilities")
        if not isinstance(capabilities, list):
            capabilities = router.get("selectedCapabilities") if isinstance(router, dict) else []
        capability_categories = {
            str(item.get("name")): str(item.get("category"))
            for item in capabilities or []
            if isinstance(item, dict) and item.get("name")
        }
        for name, category in sorted(expected.required_capability_categories.items()):
            if capability_categories.get(name) != category:
                failures.append(
                    f"capability_category:{name}:{capability_categories.get(name)}!={category}"
                )
        if expected.expected_delegated_count is not None:
            actual = int(router.get("delegatedCount") or 0) if isinstance(router, dict) else 0
            if actual != expected.expected_delegated_count:
                failures.append(f"delegated_count:{actual}!={expected.expected_delegated_count}")
        if expected.expected_max_parallel is not None:
            actual = int(router.get("maxParallel") or 0) if isinstance(router, dict) else 0
            if actual != expected.expected_max_parallel:
                failures.append(f"max_parallel:{actual}!={expected.expected_max_parallel}")
        if expected.expected_quality_gain_threshold is not None:
            try:
                actual_threshold = float(router.get("qualityGainThreshold"))
            except (TypeError, ValueError):
                actual_threshold = -1.0
            if abs(actual_threshold - expected.expected_quality_gain_threshold) > 1e-9:
                failures.append(
                    f"quality_gain_threshold:{actual_threshold}!={expected.expected_quality_gain_threshold}"
                )
        answer = response.answer or ""
        for term in sorted(expected.required_answer_terms):
            if term not in answer:
                failures.append(f"missing_answer_term:{term}")
        for pattern in sorted(expected.forbidden_answer_patterns):
            if pattern and pattern in answer:
                failures.append(f"forbidden_answer_pattern:{pattern}")
        return failures

    def _tool_names_from_result(self, result_json: dict[str, Any]) -> set[str]:
        ledger = result_json.get("toolLedger")
        if not isinstance(ledger, dict):
            trace = result_json.get("trace")
            ledger = trace.get("toolLedger") if isinstance(trace, dict) else None
        if not isinstance(ledger, dict) or ledger.get("status") != "available":
            return set()
        return {
            str(run.get("name"))
            for run in ledger.get("calls") or []
            if isinstance(run, dict)
            and run.get("name")
            and run.get("status") == "succeeded"
            and (run.get("executed") is True or run.get("reused") is True)
        }

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

    def _has_selected_capabilities(self, result_json: dict[str, Any], trace: dict[str, Any]) -> bool:
        selected = result_json.get("selectedCapabilities")
        if not isinstance(selected, list):
            selected = trace.get("selectedCapabilities")
        selected_names = {
            str(item.get("name"))
            for item in selected or []
            if isinstance(item, dict) and item.get("name")
        } if isinstance(selected, list) else set()
        router = result_json.get("expertRouter")
        if not isinstance(router, dict):
            router = trace.get("expertRouter")
        if isinstance(router, dict):
            routed = router.get("selectedCapabilities")
            if not selected_names and isinstance(routed, list):
                selected_names = {
                    str(item.get("name"))
                    for item in routed
                    if isinstance(item, dict) and item.get("name")
                }
        if not selected_names:
            return False
        diagnostics = result_json.get("specialistDiagnostics")
        if not isinstance(diagnostics, list):
            diagnostics = trace.get("specialistAgents")
        completed_names = {
            str(item.get("agentName"))
            for item in diagnostics or []
            if isinstance(item, dict)
            and item.get("agentName")
            and str(item.get("status") or "").lower() == "completed"
        } if isinstance(diagnostics, list) else set()
        return selected_names.issubset(completed_names)


_CORPUS_TOKEN_PATTERN = re.compile(r"[a-z0-9]+(?:[-_][a-z0-9]+)*", re.IGNORECASE)
_THRESHOLD_FIELDS = {
    "minHitRateAtK": "min_hit_rate_at_k",
    "minMrrAtK": "min_mrr_at_k",
    "minContextPrecisionAtK": "min_context_precision_at_k",
    "minContextRecallAtK": "min_context_recall_at_k",
    "minRecallAt5": "min_recall_at_5",
    "minRecallAt10": "min_recall_at_10",
    "minPrecisionAt5": "min_precision_at_5",
    "minNdcgAt10": "min_ndcg_at_10",
    "minStructuredAccuracy": "min_structured_accuracy",
    "minChapterLocationAccuracy": "min_chapter_location_accuracy",
    "minForeshadowingCoverage": "min_foreshadowing_coverage",
    "minMultiHopPathEvidence": "min_multi_hop_path_evidence",
    "minStaleRejectionRate": "min_stale_rejection_rate",
    "minCrossUserIsolationRate": "min_cross_user_isolation_rate",
}


@dataclass(frozen=True)
class _IndexedProjectDocument:
    payload: dict[str, Any]
    term_counts: Counter[str]
    length: int


class ProjectRetrievalCorpusRunner:
    RUNNER_VERSION = "scoped-bm25-v1"

    def __init__(self, documents: list[dict[str, Any]]) -> None:
        self.documents = [dict(document) for document in documents]
        grouped: dict[tuple[int, int, int, str], list[_IndexedProjectDocument]] = {}
        for document in self.documents:
            if str(document.get("generationStatus") or "").upper() != "ACTIVE":
                continue
            scope = self._document_scope(document)
            terms = _corpus_tokens(f"{document.get('title') or ''} {document.get('content') or ''}")
            grouped.setdefault(scope, []).append(_IndexedProjectDocument(
                payload=document,
                term_counts=Counter(terms),
                length=max(1, len(terms)),
            ))
        self._indexes: dict[
            tuple[int, int, int, str],
            tuple[list[_IndexedProjectDocument], Counter[str], float],
        ] = {}
        for scope, indexed_documents in grouped.items():
            document_frequency: Counter[str] = Counter()
            for indexed_document in indexed_documents:
                document_frequency.update(indexed_document.term_counts.keys())
            average_length = sum(item.length for item in indexed_documents) / max(1, len(indexed_documents))
            self._indexes[scope] = (indexed_documents, document_frequency, average_length)

    def search(self, case: Mapping[str, Any], *, limit: int = 10) -> list[dict[str, Any]]:
        request = case.get("requestPayload")
        if not isinstance(request, Mapping):
            raise ValueError("project retrieval golden case requires requestPayload")
        scope = self._request_scope(request)
        index = self._indexes.get(scope)
        if index is None:
            return []
        indexed_documents, document_frequency, average_length = index
        query_terms = _corpus_tokens(str(case.get("question") or ""))
        if not query_terms:
            return []
        scored: list[tuple[float, _IndexedProjectDocument]] = []
        document_count = len(indexed_documents)
        for indexed_document in indexed_documents:
            score = self._bm25_score(
                query_terms,
                indexed_document,
                document_frequency,
                document_count,
                average_length,
            )
            if score > 0.0:
                scored.append((score, indexed_document))
        scored.sort(key=lambda item: (
            -item[0],
            int(item[1].payload.get("chapterNo") or 0),
            str(item[1].payload.get("documentId") or ""),
        ))
        results: list[dict[str, Any]] = []
        for score, indexed_document in scored[:max(1, limit)]:
            result = dict(indexed_document.payload)
            result["score"] = score
            results.append(result)
        return results

    def run(
        self,
        cases: list[dict[str, Any]],
        *,
        manifest: Mapping[str, Any],
        baseline: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        results = [self._evaluate_case(case) for case in cases]
        baseline_metrics = _mapping_or_none(baseline, "metrics")
        baseline_intervals = _mapping_or_none(baseline, "confidenceIntervals")
        gate_report = GoldenEvalRunner._project_release_gate_report(
            results,
            baseline_metrics=baseline_metrics,
            baseline_confidence_intervals=baseline_intervals,
        )
        if gate_report is None:
            raise ValueError("project retrieval corpus has no release-gated cases")
        failed_results = [result for result in results if result.status != "passed"]
        dimensions = self._dimension_reports(gate_report["cohorts"])
        gate = dict(gate_report["gate"])
        status = "passed" if not failed_results and gate.get("passed") is True else "failed"
        return {
            "schemaVersion": "project-retrieval-golden-report/v1",
            "corpusVersion": str(manifest.get("corpusVersion") or "unknown"),
            "runnerVersion": self.RUNNER_VERSION,
            "status": status,
            "caseCount": len(cases),
            "passedCaseCount": len(results) - len(failed_results),
            "failedCaseCount": len(failed_results),
            "corpus": self._corpus_stats(manifest),
            "overall": {
                "metrics": gate_report["metrics"],
                "confidenceIntervals": gate_report["confidenceIntervals"],
            },
            "dimensions": dimensions,
            "baselineComparison": self._baseline_comparison(
                gate_report["metrics"],
                gate_report["confidenceIntervals"],
                baseline,
            ),
            "gate": gate,
            "failedCases": [
                {"caseId": result.case_id, "failures": list(result.failures)}
                for result in failed_results[:50]
            ],
        }

    def _evaluate_case(self, case: dict[str, Any]) -> GoldenEvalCaseResult:
        ranked_documents = self.search(case, limit=max(10, int(case.get("k") or 5)))
        ranked_ids = [str(document.get("sourceId") or "") for document in ranked_documents]
        retrieved_chapter_ids = {source_id for source_id in ranked_ids if source_id.startswith("chapter:")}
        retrieved_foreshadowing_ids: set[str] = set()
        actual_structured_values: dict[str, Any] = {}
        observed_path_edges: dict[str, set[str]] = {}
        for document in ranked_documents:
            retrieved_foreshadowing_ids.update(str(value) for value in document.get("foreshadowingIds") or [])
            structured_values = document.get("structuredValues")
            if isinstance(structured_values, Mapping):
                for key, value in structured_values.items():
                    actual_structured_values.setdefault(str(key), value)
            path_edges = document.get("pathEdges")
            if isinstance(path_edges, Mapping):
                for edge_id, evidence_ids in path_edges.items():
                    observed_path_edges.setdefault(str(edge_id), set()).update(
                        str(value) for value in evidence_ids or []
                    )
        request = case.get("requestPayload") if isinstance(case.get("requestPayload"), Mapping) else {}
        expected_generation = str(request.get("generationId") or request.get("generation_id") or "")
        observed_generations = {
            str(document.get("generationId"))
            for document in ranked_documents
            if document.get("generationId") not in {None, ""}
        }
        expected_path_edges = {
            str(edge_id): {str(value) for value in evidence_ids or []}
            for edge_id, evidence_ids in dict(case.get("expectedPathEdges") or {}).items()
        }
        retrieval_case = RetrievalEvalCase(
            case_id=str(case.get("caseId") or "unknown"),
            ranked_ids=ranked_ids,
            relevant_ids={str(value) for value in case.get("relevantSourceIds") or []},
            k=max(1, int(case.get("k") or 5)),
            relevance_grades={
                str(key): float(value)
                for key, value in dict(case.get("relevanceGrades") or {}).items()
            },
            expected_chapter_ids={str(value) for value in case.get("expectedChapterIds") or []},
            retrieved_chapter_ids=retrieved_chapter_ids,
            expected_foreshadowing_ids={str(value) for value in case.get("expectedForeshadowingIds") or []},
            retrieved_foreshadowing_ids=retrieved_foreshadowing_ids,
            expected_structured_values=dict(case.get("expectedStructuredValues") or {}),
            actual_structured_values=actual_structured_values,
            expected_path_edges=expected_path_edges,
            observed_path_edges=observed_path_edges,
            expected_stale_rejection=True if case.get("requireStaleRejection") else None,
            stale_rejected=all(
                str(document.get("generationStatus") or "").upper() == "ACTIVE"
                and str(document.get("generationId") or "") == expected_generation
                for document in ranked_documents
            ),
            expected_cross_user_isolation=True if case.get("requireCrossUserIsolation") else None,
            cross_user_isolated=all(
                int(document.get("userId") or 0) == int(request.get("userId") or 0)
                for document in ranked_documents
            ),
            expected_generation_ids={expected_generation} if expected_generation else set(),
            observed_generation_ids=observed_generations,
        )
        metrics = evaluate_retrieval_cases([retrieval_case])
        thresholds = _retrieval_thresholds(case.get("retrievalThresholds"))
        failures = [f"retrieval:{failure}" for failure in retrieval_threshold_failures(metrics, thresholds)]
        cohort = dict(case.get("evaluationCohort") or {})
        return GoldenEvalCaseResult(
            case_id=retrieval_case.case_id,
            status="passed" if not failures else "failed",
            intent=str(cohort.get("intent") or ""),
            answer_mode="project_retrieval_corpus",
            retrieval_metrics=metrics,
            faithfulness={},
            failures=failures,
            trace={
                "evaluationCohort": cohort,
                "applyProjectReleaseGate": bool(case.get("applyProjectReleaseGate")),
            },
        )

    def _corpus_stats(self, manifest: Mapping[str, Any]) -> dict[str, Any]:
        role_counts = Counter(str(document.get("documentRole") or "unknown") for document in self.documents)
        return {
            "bookCount": len(manifest.get("books") or []),
            "documentCount": len(self.documents),
            "canonicalChapterCount": role_counts.get("canonical", 0),
            "retiredDecoyCount": role_counts.get("retired_decoy", 0),
            "crossUserDecoyCount": role_counts.get("cross_user_decoy", 0),
            "chapterCountMinimum": int(manifest.get("chapterCountMinimum") or 0),
            "chapterCountMaximum": int(manifest.get("chapterCountMaximum") or 0),
        }

    @staticmethod
    def _dimension_reports(cohorts: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
        dimensions: dict[str, dict[str, Any]] = {}
        for cohort_key, report in sorted(cohorts.items()):
            dimension, separator, value = str(cohort_key).partition(":")
            if separator and dimension in {"intent", "genre", "lengthBucket", "generation"}:
                dimensions.setdefault(dimension, {})[value] = report
        return dimensions

    @staticmethod
    def _baseline_comparison(
        metrics: Mapping[str, Any],
        confidence_intervals: Mapping[str, Any],
        baseline: Mapping[str, Any] | None,
    ) -> dict[str, Any] | None:
        baseline_metrics = _mapping_or_none(baseline, "metrics")
        if baseline_metrics is None:
            return None
        baseline_intervals = _mapping_or_none(baseline, "confidenceIntervals") or {}
        metric_names = (
            "recall_at_5",
            "chapter_location_accuracy",
            "structured_accuracy",
            "foreshadowing_coverage",
            "multi_hop_path_evidence",
            "cross_user_isolation_rate",
            "old_generation_misretrieval_rate",
        )
        comparisons: dict[str, Any] = {}
        for metric_name in metric_names:
            current = float(metrics.get(metric_name) or 0.0)
            baseline_value = float(baseline_metrics.get(metric_name) or 0.0)
            comparisons[metric_name] = {
                "baseline": baseline_value,
                "current": current,
                "delta": current - baseline_value,
                "current95": confidence_intervals.get(metric_name),
                "baseline95": baseline_intervals.get(metric_name),
            }
        return {
            "baselineVersion": str(baseline.get("baselineVersion") or "unknown"),
            "maxRegressionDrop": ProjectRetrievalReleaseGate.max_regression_drop,
            "metrics": comparisons,
        }

    @staticmethod
    def _document_scope(document: Mapping[str, Any]) -> tuple[int, int, int, str]:
        return (
            int(document.get("userId") or 0),
            int(document.get("projectId") or 0),
            int(document.get("workId") or 0),
            str(document.get("generationId") or ""),
        )

    @staticmethod
    def _request_scope(request: Mapping[str, Any]) -> tuple[int, int, int, str]:
        return (
            int(request.get("userId") or request.get("user_id") or 0),
            int(request.get("projectId") or request.get("project_id") or 0),
            int(request.get("workId") or request.get("work_id") or 0),
            str(request.get("generationId") or request.get("generation_id") or ""),
        )

    @staticmethod
    def _bm25_score(
        query_terms: list[str],
        document: _IndexedProjectDocument,
        document_frequency: Counter[str],
        document_count: int,
        average_length: float,
    ) -> float:
        score = 0.0
        saturation = 1.2
        length_weight = 0.75
        for term in set(query_terms):
            frequency = document.term_counts.get(term, 0)
            if frequency <= 0:
                continue
            inverse_document_frequency = math.log(
                1.0 + (document_count - document_frequency.get(term, 0) + 0.5)
                / (document_frequency.get(term, 0) + 0.5)
            )
            denominator = frequency + saturation * (
                1.0 - length_weight + length_weight * document.length / max(1.0, average_length)
            )
            score += inverse_document_frequency * frequency * (saturation + 1.0) / denominator
        return score


def run_project_retrieval_golden_fixture(
    fixture_dir: str | Path,
    *,
    use_baseline: bool = True,
) -> dict[str, Any]:
    directory = Path(fixture_dir)
    manifest = _read_json_mapping(directory / "manifest.json")
    cases = _read_json_list(directory / str(manifest.get("casesFile") or "cases.json"))
    corpus = _read_json_list(directory / str(manifest.get("corpusFile") or "corpus.json"))
    baseline: Mapping[str, Any] | None = None
    if use_baseline:
        baseline = _read_json_mapping(directory / str(manifest.get("baselineFile") or "baseline.json"))
    return ProjectRetrievalCorpusRunner(corpus).run(cases, manifest=manifest, baseline=baseline)


def _corpus_tokens(value: str) -> list[str]:
    return [match.group(0).lower() for match in _CORPUS_TOKEN_PATTERN.finditer(value)]


def _retrieval_thresholds(value: Any) -> RetrievalEvalThresholds:
    raw = value if isinstance(value, Mapping) else {}
    kwargs = {
        target: float(raw[source])
        for source, target in _THRESHOLD_FIELDS.items()
        if source in raw and raw[source] is not None
    }
    return RetrievalEvalThresholds(**kwargs)


def _mapping_or_none(value: Mapping[str, Any] | None, key: str) -> Mapping[str, Any] | None:
    if not isinstance(value, Mapping):
        return None
    nested = value.get(key)
    return nested if isinstance(nested, Mapping) else None


def _read_json_mapping(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"expected JSON object: {path}")
    return payload


def _read_json_list(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list) or any(not isinstance(item, dict) for item in payload):
        raise ValueError(f"expected JSON object list: {path}")
    return payload
