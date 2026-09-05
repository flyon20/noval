from __future__ import annotations

from dataclasses import dataclass, field
import math
import random
from typing import Any, Mapping


@dataclass(frozen=True)
class RetrievalEvalCase:
    case_id: str
    ranked_ids: list[str]
    relevant_ids: set[str]
    k: int = 5
    relevance_grades: Mapping[str, float] = field(default_factory=dict)
    expected_chapter_ids: set[str] = field(default_factory=set)
    retrieved_chapter_ids: set[str] = field(default_factory=set)
    expected_foreshadowing_ids: set[str] = field(default_factory=set)
    retrieved_foreshadowing_ids: set[str] = field(default_factory=set)
    expected_structured_values: Mapping[str, Any] = field(default_factory=dict)
    actual_structured_values: Mapping[str, Any] = field(default_factory=dict)
    expected_path_edges: Mapping[str, set[str]] = field(default_factory=dict)
    observed_path_edges: Mapping[str, set[str]] = field(default_factory=dict)
    expected_stale_rejection: bool | None = None
    stale_rejected: bool | None = None
    expected_cross_user_isolation: bool | None = None
    cross_user_isolated: bool | None = None
    expected_generation_ids: set[str] = field(default_factory=set)
    observed_generation_ids: set[str] = field(default_factory=set)


@dataclass(frozen=True)
class RetrievalEvalThresholds:
    min_hit_rate_at_k: float = 0.0
    min_mrr_at_k: float = 0.0
    min_context_precision_at_k: float = 0.0
    min_context_recall_at_k: float = 0.0
    min_recall_at_5: float = 0.0
    min_recall_at_10: float = 0.0
    min_precision_at_5: float = 0.0
    min_ndcg_at_10: float = 0.0
    min_structured_accuracy: float = 0.0
    min_chapter_location_accuracy: float = 0.0
    min_foreshadowing_coverage: float = 0.0
    min_multi_hop_path_evidence: float = 0.0
    min_stale_rejection_rate: float = 0.0
    min_cross_user_isolation_rate: float = 0.0


@dataclass(frozen=True)
class ProjectRetrievalReleaseGate:
    min_recall_at_5: float = 0.95
    min_chapter_location_accuracy: float = 0.95
    min_structured_accuracy: float = 0.95
    min_foreshadowing_coverage: float = 0.90
    min_multi_hop_path_evidence: float = 0.85
    min_cross_user_isolation_rate: float = 1.0
    max_old_generation_misretrieval_rate: float = 0.01
    max_regression_drop: float = 0.02


def evaluate_retrieval_cases(cases: list[RetrievalEvalCase]) -> dict[str, float | int]:
    if not cases:
        return _empty_metrics()

    hit_total = 0.0
    mrr_total = 0.0
    precision_total = 0.0
    recall_total = 0.0
    recall_at_5_total = 0.0
    recall_at_10_total = 0.0
    precision_at_5_total = 0.0
    mrr_at_10_total = 0.0
    ndcg_at_10_total = 0.0
    active_retrieval_cases = 0
    structured_matches = 0
    structured_expected = 0
    chapter_hits = 0
    chapter_expected = 0
    foreshadowing_hits = 0
    foreshadowing_expected = 0
    path_edge_hits = 0
    path_edge_expected = 0
    stale_rejection_hits = 0
    stale_rejection_cases = 0
    cross_user_isolation_hits = 0
    cross_user_isolation_cases = 0
    old_generation_misretrievals = 0
    observed_generation_count = 0
    generation_cases = 0
    for case in cases:
        top_k = case.ranked_ids[: max(1, case.k)]
        relevant = set(case.relevant_ids)
        if relevant:
            active_retrieval_cases += 1
        relevant_hits = [source_id for source_id in top_k if source_id in relevant]
        if relevant:
            hit_total += 1.0 if relevant_hits else 0.0
            mrr_total += _reciprocal_rank(top_k, relevant)
            precision_total += len(relevant_hits) / max(1, len(top_k))
            recall_total += len(relevant_hits) / len(relevant)
            top_5 = case.ranked_ids[:5]
            top_10 = case.ranked_ids[:10]
            recall_at_5_total += _recall(top_5, relevant)
            recall_at_10_total += _recall(top_10, relevant)
            precision_at_5_total += len([item for item in top_5 if item in relevant]) / 5.0
            mrr_at_10_total += _reciprocal_rank(top_10, relevant)
            ndcg_at_10_total += _ndcg_at_k(top_10, relevant, case.relevance_grades)

        structured_expected += len(case.expected_structured_values)
        structured_matches += sum(
            1
            for key, expected in case.expected_structured_values.items()
            if key in case.actual_structured_values and case.actual_structured_values[key] == expected
        )
        chapter_expected += len(case.expected_chapter_ids)
        chapter_hits += len(case.expected_chapter_ids & set(case.retrieved_chapter_ids))
        foreshadowing_expected += len(case.expected_foreshadowing_ids)
        foreshadowing_hits += len(case.expected_foreshadowing_ids & set(case.retrieved_foreshadowing_ids))
        for edge_id, expected_evidence in case.expected_path_edges.items():
            path_edge_expected += 1
            observed_evidence = set(case.observed_path_edges.get(edge_id, set()))
            if expected_evidence and set(expected_evidence).issubset(observed_evidence):
                path_edge_hits += 1
        if case.expected_stale_rejection is not None:
            stale_rejection_cases += 1
            stale_rejection_hits += int(case.stale_rejected is case.expected_stale_rejection)
        if case.expected_cross_user_isolation is not None:
            cross_user_isolation_cases += 1
            cross_user_isolation_hits += int(case.cross_user_isolated is case.expected_cross_user_isolation)
        if case.expected_generation_ids:
            generation_cases += 1
            observed_generation_count += len(case.observed_generation_ids)
            old_generation_misretrievals += len(set(case.observed_generation_ids) - set(case.expected_generation_ids))

    metrics = _empty_metrics()
    metrics.update({
        "case_count": len(cases),
        "active_retrieval_case_count": active_retrieval_cases,
        "structured_case_count": structured_expected,
        "chapter_location_case_count": chapter_expected,
        "foreshadowing_case_count": foreshadowing_expected,
        "multi_hop_path_case_count": path_edge_expected,
        "stale_rejection_case_count": stale_rejection_cases,
        "cross_user_isolation_case_count": cross_user_isolation_cases,
        "generation_case_count": generation_cases,
    })
    if active_retrieval_cases:
        precision = precision_total / active_retrieval_cases
        metrics.update({
            "hit_rate_at_k": hit_total / active_retrieval_cases,
            "mrr_at_k": mrr_total / active_retrieval_cases,
            "precision_at_k": precision,
            "context_precision_at_k": precision,
            "context_recall_at_k": recall_total / active_retrieval_cases,
            "recall_at_5": recall_at_5_total / active_retrieval_cases,
            "recall_at_10": recall_at_10_total / active_retrieval_cases,
            "precision_at_5": precision_at_5_total / active_retrieval_cases,
            "mrr": mrr_at_10_total / active_retrieval_cases,
            "ndcg_at_10": ndcg_at_10_total / active_retrieval_cases,
        })
    if structured_expected:
        metrics["structured_accuracy"] = structured_matches / structured_expected
    if chapter_expected:
        metrics["chapter_location_accuracy"] = chapter_hits / chapter_expected
    if foreshadowing_expected:
        metrics["foreshadowing_coverage"] = foreshadowing_hits / foreshadowing_expected
    if path_edge_expected:
        metrics["multi_hop_path_evidence"] = path_edge_hits / path_edge_expected
    if stale_rejection_cases:
        metrics["stale_rejection_rate"] = stale_rejection_hits / stale_rejection_cases
    if cross_user_isolation_cases:
        metrics["cross_user_isolation_rate"] = cross_user_isolation_hits / cross_user_isolation_cases
    if generation_cases:
        metrics["old_generation_misretrieval_rate"] = old_generation_misretrievals / max(1, observed_generation_count)
    return metrics


def retrieval_threshold_failures(
    metrics: dict[str, float | int | Any],
    thresholds: RetrievalEvalThresholds,
) -> list[str]:
    failures: list[str] = []
    requires_retrieval_samples = any((
        thresholds.min_hit_rate_at_k,
        thresholds.min_mrr_at_k,
        thresholds.min_context_precision_at_k,
        thresholds.min_context_recall_at_k,
        thresholds.min_recall_at_5,
        thresholds.min_recall_at_10,
        thresholds.min_precision_at_5,
        thresholds.min_ndcg_at_10,
    ))
    if requires_retrieval_samples and "active_retrieval_case_count" in metrics and int(metrics["active_retrieval_case_count"] or 0) <= 0:
        return ["no_active_retrieval_cases"]
    _append_threshold_failure(failures, "hit_rate_at_k", metrics, thresholds.min_hit_rate_at_k)
    _append_threshold_failure(failures, "mrr_at_k", metrics, thresholds.min_mrr_at_k)
    _append_threshold_failure(
        failures,
        "context_precision_at_k",
        metrics,
        thresholds.min_context_precision_at_k,
    )
    _append_threshold_failure(
        failures,
        "context_recall_at_k",
        metrics,
        thresholds.min_context_recall_at_k,
    )
    _append_threshold_failure(failures, "recall_at_5", metrics, thresholds.min_recall_at_5)
    _append_threshold_failure(failures, "recall_at_10", metrics, thresholds.min_recall_at_10)
    _append_threshold_failure(failures, "precision_at_5", metrics, thresholds.min_precision_at_5)
    _append_threshold_failure(failures, "ndcg_at_10", metrics, thresholds.min_ndcg_at_10)
    _append_dimension_threshold_failure(
        failures, "structured_accuracy", "structured_case_count", metrics, thresholds.min_structured_accuracy
    )
    _append_dimension_threshold_failure(
        failures, "chapter_location_accuracy", "chapter_location_case_count", metrics,
        thresholds.min_chapter_location_accuracy,
    )
    _append_dimension_threshold_failure(
        failures, "foreshadowing_coverage", "foreshadowing_case_count", metrics,
        thresholds.min_foreshadowing_coverage,
    )
    _append_dimension_threshold_failure(
        failures, "multi_hop_path_evidence", "multi_hop_path_case_count", metrics,
        thresholds.min_multi_hop_path_evidence,
    )
    _append_dimension_threshold_failure(
        failures, "stale_rejection_rate", "stale_rejection_case_count", metrics,
        thresholds.min_stale_rejection_rate,
    )
    _append_dimension_threshold_failure(
        failures, "cross_user_isolation_rate", "cross_user_isolation_case_count", metrics,
        thresholds.min_cross_user_isolation_rate,
    )
    return failures


def _reciprocal_rank(ranked_ids: list[str], relevant_ids: set[str]) -> float:
    for index, source_id in enumerate(ranked_ids, start=1):
        if source_id in relevant_ids:
            return 1.0 / index
    return 0.0


def _recall(ranked_ids: list[str], relevant_ids: set[str]) -> float:
    return len([source_id for source_id in ranked_ids if source_id in relevant_ids]) / len(relevant_ids)


def _ndcg_at_k(ranked_ids: list[str], relevant_ids: set[str], relevance_grades: Mapping[str, float]) -> float:
    grades = {source_id: float(relevance_grades.get(source_id, 1.0)) for source_id in relevant_ids}
    if not grades:
        return 0.0
    dcg = sum(
        (2.0 ** grades.get(source_id, 0.0) - 1.0) / math.log2(index + 1)
        for index, source_id in enumerate(ranked_ids, start=1)
    )
    ideal = sorted(grades.values(), reverse=True)[:len(ranked_ids)]
    ideal_dcg = sum((2.0 ** grade - 1.0) / math.log2(index + 1) for index, grade in enumerate(ideal, start=1))
    return dcg / ideal_dcg if ideal_dcg else 0.0


def _empty_metrics() -> dict[str, float | int]:
    return {
        "case_count": 0,
        "active_retrieval_case_count": 0,
        "hit_rate_at_k": 0.0,
        "mrr_at_k": 0.0,
        "precision_at_k": 0.0,
        "context_precision_at_k": 0.0,
        "context_recall_at_k": 0.0,
        "recall_at_5": 0.0,
        "recall_at_10": 0.0,
        "precision_at_5": 0.0,
        "mrr": 0.0,
        "ndcg_at_10": 0.0,
        "structured_accuracy": 0.0,
        "structured_case_count": 0,
        "chapter_location_accuracy": 0.0,
        "chapter_location_case_count": 0,
        "foreshadowing_coverage": 0.0,
        "foreshadowing_case_count": 0,
        "multi_hop_path_evidence": 0.0,
        "multi_hop_path_case_count": 0,
        "stale_rejection_rate": 0.0,
        "stale_rejection_case_count": 0,
        "cross_user_isolation_rate": 0.0,
        "cross_user_isolation_case_count": 0,
        "old_generation_misretrieval_rate": 0.0,
        "generation_case_count": 0,
    }


def bootstrap_confidence_interval(
    values: list[float],
    *,
    iterations: int = 1_000,
    confidence: float = 0.95,
    seed: int = 0,
) -> dict[str, float | int | None]:
    samples = [float(value) for value in values]
    if not samples:
        return {"sample_count": 0, "mean": 0.0, "lower": None, "upper": None}
    rounds = max(1, int(iterations))
    random_source = random.Random(seed)
    means = sorted(
        sum(random_source.choice(samples) for _ in samples) / len(samples)
        for _ in range(rounds)
    )
    tail = max(0.0, min(0.5, (1.0 - confidence) / 2.0))
    lower_index = min(rounds - 1, max(0, int(math.floor(tail * (rounds - 1)))))
    upper_index = min(rounds - 1, max(0, int(math.ceil((1.0 - tail) * (rounds - 1)))))
    return {
        "sample_count": len(samples),
        "mean": sum(samples) / len(samples),
        "lower": means[lower_index],
        "upper": means[upper_index],
    }


def project_retrieval_release_gate_failures(
    metrics: Mapping[str, float | int | Any],
    gate: ProjectRetrievalReleaseGate,
    *,
    baseline_metrics: Mapping[str, float | int | Any] | None = None,
    confidence_intervals: Mapping[str, Mapping[str, float | int | None]] | None = None,
    baseline_confidence_intervals: Mapping[str, Mapping[str, float | int | None]] | None = None,
) -> list[str]:
    dimensions = (
        ("recall_at_5", "active_retrieval_case_count", gate.min_recall_at_5),
        ("chapter_location_accuracy", "chapter_location_case_count", gate.min_chapter_location_accuracy),
        ("structured_accuracy", "structured_case_count", gate.min_structured_accuracy),
        ("foreshadowing_coverage", "foreshadowing_case_count", gate.min_foreshadowing_coverage),
        ("multi_hop_path_evidence", "multi_hop_path_case_count", gate.min_multi_hop_path_evidence),
        ("cross_user_isolation_rate", "cross_user_isolation_case_count", gate.min_cross_user_isolation_rate),
    )
    failures: list[str] = []
    absolute_failed: set[str] = set()
    for metric_name, count_name, minimum in dimensions:
        if int(metrics.get(count_name) or 0) <= 0:
            failures.append(f"release_gate:no_active_{count_name}")
            continue
        value = float(metrics.get(metric_name) or 0.0)
        if value < minimum:
            absolute_failed.add(metric_name)
            failures.append(f"release_gate:{metric_name} {value:.4f} < {minimum:.4f}")
    if int(metrics.get("generation_case_count") or 0) <= 0:
        failures.append("release_gate:no_active_generation_case_count")
    else:
        old_generation_rate = float(metrics.get("old_generation_misretrieval_rate") or 0.0)
        if old_generation_rate >= gate.max_old_generation_misretrieval_rate:
            failures.append(
                "release_gate:old_generation_misretrieval_rate "
                f"{old_generation_rate:.4f} >= {gate.max_old_generation_misretrieval_rate:.4f}"
            )
    for metric_name, _, _ in dimensions:
        if baseline_metrics is None or metric_name not in baseline_metrics:
            continue
        baseline = float(baseline_metrics[metric_name] or 0.0)
        current = float(metrics.get(metric_name) or 0.0)
        if current < baseline - gate.max_regression_drop:
            if metric_name in absolute_failed:
                absolute_prefix = f"release_gate:{metric_name} "
                failures = [
                    item
                    for item in failures
                    if not (
                        item.startswith(absolute_prefix)
                        and " regression " not in item
                        and "statistically_regressed" not in item
                    )
                ]
            failures.append(
                f"release_gate:{metric_name} regression "
                f"{current:.4f} < {baseline - gate.max_regression_drop:.4f}"
            )
            continue
        if confidence_intervals is None or baseline_confidence_intervals is None:
            continue
        candidate_interval = confidence_intervals.get(metric_name) or {}
        baseline_interval = baseline_confidence_intervals.get(metric_name) or {}
        candidate_upper = candidate_interval.get("upper")
        baseline_lower = baseline_interval.get("lower")
        if candidate_upper is not None and baseline_lower is not None and float(candidate_upper) < float(baseline_lower):
            failures.append(f"release_gate:{metric_name} statistically_regressed")
    return failures


def _append_threshold_failure(
    failures: list[str],
    metric_name: str,
    metrics: dict[str, float | int | Any],
    minimum: float,
) -> None:
    if minimum <= 0:
        return
    value = float(metrics.get(metric_name) or 0.0)
    if value < minimum:
        failures.append(f"{metric_name} {value:.4f} < {minimum:.4f}")


def _append_dimension_threshold_failure(
    failures: list[str],
    metric_name: str,
    case_count_name: str,
    metrics: dict[str, float | int | Any],
    minimum: float,
) -> None:
    if minimum <= 0:
        return
    if int(metrics.get(case_count_name) or 0) <= 0:
        failures.append(f"no_active_{case_count_name}")
        return
    _append_threshold_failure(failures, metric_name, metrics, minimum)
