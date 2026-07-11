from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.models.knowledge import KnowledgeSource
from app.services.retrieval_eval import RetrievalEvalThresholds


@dataclass(frozen=True)
class GoldenEvalExpectedTrace:
    required_tool_names: set[str] = field(default_factory=set)
    required_source_types: set[str] = field(default_factory=set)
    required_trace_fields: set[str] = field(default_factory=set)
    required_source_policy_fields: set[str] = field(default_factory=set)
    required_evidence_statuses: set[str] = field(default_factory=set)
    required_answer_terms: set[str] = field(default_factory=set)
    forbidden_answer_patterns: set[str] = field(default_factory=set)
    require_valid_answer_boundary: bool = False
    require_citations: bool = False
    forbid_memory_cross_project: bool = False
    forbid_fallback: bool = False
    require_provider_success: bool = False
    require_selected_experts: bool = False


@dataclass(frozen=True)
class GoldenEvalCase:
    case_id: str
    question: str
    request_payload: dict[str, Any] = field(default_factory=dict)
    expected_intent: str | None = None
    expected_answer_mode: str | None = None
    expected_sub_intents: set[str] = field(default_factory=set)
    relevant_source_ids: set[str] = field(default_factory=set)
    grounded_claims: list[str] = field(default_factory=list)
    forbidden_claims: list[str] = field(default_factory=list)
    retrieval_thresholds: RetrievalEvalThresholds = field(default_factory=RetrievalEvalThresholds)
    expected_trace: GoldenEvalExpectedTrace = field(default_factory=GoldenEvalExpectedTrace)
    k: int = 5


@dataclass(frozen=True)
class GoldenEvalCaseResult:
    case_id: str
    status: str
    intent: str | None
    answer_mode: str | None
    retrieval_metrics: dict[str, float | int]
    faithfulness: dict[str, Any]
    failures: list[str]
    trace: dict[str, Any]


def source_eval_id(source: KnowledgeSource) -> str:
    source_type = _canonical_source_type(str(source.sourceType or "source").lower())
    if source.chunkId is not None:
        return f"chunk:{source.chunkId}"
    if source.sourceRefId is not None:
        return f"{source_type}:{source.sourceRefId}"
    if source.bookId is not None:
        return f"{source_type}:{source.bookId}"
    if source.documentId is not None:
        return f"document:{source.documentId}"
    title = source.title or source.bookName or "unknown"
    return f"{source_type}:{title}"


def _canonical_source_type(source_type: str) -> str:
    if source_type == "chapter_pack":
        return "chapter"
    return source_type
