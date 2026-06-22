from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class RetrievalEvalCase:
    case_id: str
    ranked_ids: list[str]
    relevant_ids: set[str]
    k: int = 5


@dataclass(frozen=True)
class RetrievalEvalThresholds:
    min_hit_rate_at_k: float = 0.0
    min_mrr_at_k: float = 0.0
    min_context_precision_at_k: float = 0.0
    min_context_recall_at_k: float = 0.0


def evaluate_retrieval_cases(cases: list[RetrievalEvalCase]) -> dict[str, float | int]:
    if not cases:
        return {
            "case_count": 0,
            "hit_rate_at_k": 0.0,
            "mrr_at_k": 0.0,
            "precision_at_k": 0.0,
            "context_precision_at_k": 0.0,
            "context_recall_at_k": 0.0,
        }

    hit_total = 0.0
    mrr_total = 0.0
    precision_total = 0.0
    recall_total = 0.0
    for case in cases:
        top_k = case.ranked_ids[: max(1, case.k)]
        relevant = set(case.relevant_ids)
        relevant_hits = [source_id for source_id in top_k if source_id in relevant]
        hit_total += 1.0 if relevant_hits else 0.0
        mrr_total += _reciprocal_rank(top_k, relevant)
        precision_total += len(relevant_hits) / max(1, len(top_k))
        recall_total += len(relevant_hits) / max(1, len(relevant))

    case_count = len(cases)
    precision = precision_total / case_count
    return {
        "case_count": case_count,
        "hit_rate_at_k": hit_total / case_count,
        "mrr_at_k": mrr_total / case_count,
        "precision_at_k": precision,
        "context_precision_at_k": precision,
        "context_recall_at_k": recall_total / case_count,
    }


def retrieval_threshold_failures(
    metrics: dict[str, float | int | Any],
    thresholds: RetrievalEvalThresholds,
) -> list[str]:
    failures: list[str] = []
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
    return failures


def _reciprocal_rank(ranked_ids: list[str], relevant_ids: set[str]) -> float:
    for index, source_id in enumerate(ranked_ids, start=1):
        if source_id in relevant_ids:
            return 1.0 / index
    return 0.0


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
