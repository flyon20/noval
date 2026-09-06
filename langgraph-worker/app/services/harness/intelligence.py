from __future__ import annotations

import hashlib
import json
import re
from typing import Any

from pydantic import ValidationError

from app.models.knowledge import KnowledgeChatResponse, KnowledgeSource
from app.services.harness.contracts import (
    AnswerValidationIssue, AnswerValidationResult, CapabilityScope, EvidenceCommit,
    EvidenceDecisionState, IntentEnvelope, TaskProgressCheckpoint,
)


def content_hash(value: Any) -> str:
    canonical = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def evidence_hash(commit: EvidenceCommit | None, sources: list[KnowledgeSource]) -> str:
    return content_hash({
        "commit": commit.model_dump(mode="json") if commit else None,
        "sources": [{**source.model_dump(mode="json"), "materialHash": content_hash(source.material)} for source in sources],
    })


def validate_answer(
    response: KnowledgeChatResponse, *, commit: EvidenceCommit | None,
    deterministic_issues: list[dict[str, str]] | None = None,
    prior_review: dict[str, Any] | None = None,
) -> AnswerValidationResult:
    if response.status != "answered" or not (response.answer or "").strip():
        return AnswerValidationResult()
    issues = {
        item["code"]: AnswerValidationIssue(issueId=item["code"])
        for item in deterministic_issues or []
    }
    accepted_citations = {
        str(decision.citationRef or decision.evidenceId).removeprefix("source:")
        for decision in commit.decisions
        if decision.decision in {EvidenceDecisionState.ACCEPTED, EvidenceDecisionState.DEGRADED}
    } if commit else set()
    for citation in re.findall(r"\[(\d+)\]", response.answer):
        if not 1 <= int(citation) <= len(response.sources) or (commit and citation not in accepted_citations):
            issues["citation_unresolved"] = AnswerValidationIssue(issueId="citation_unresolved", repairKind="retrieve")
    if commit and not commit.canCommit and response.sources:
        issues["evidence_not_committable"] = AnswerValidationIssue(issueId="evidence_not_committable", repairKind="retrieve")
    review = prior_review or {}
    unchecked_review_issues = set(review.get("issues") or []) - set(review.get("deterministicIssues") or [])
    status = "failed" if issues else "unknown" if (response.sources and commit is None) or unchecked_review_issues else "passed"
    return AnswerValidationResult(
        status=status, issues=tuple(issues.values())[:32], checkedAnswerHash=content_hash(response.answer),
        checkedEvidenceCommitId=commit.commitId if commit else None,
        checkedEvidenceHash=evidence_hash(commit, list(response.sources)),
    )


def _source_matches_item(source: KnowledgeSource, item: dict[str, Any]) -> bool:
    source_payload = source.model_dump(mode="json")
    field_aliases = {
        "projectId": ("projectId", "project_id"),
        "workId": ("workId", "work_id"),
        "generationId": ("generationId", "generation_id"),
        "chapterVersion": ("chapterVersion", "chapter_version"),
        "contentHash": ("contentHash", "content_hash"),
        "sourceType": ("sourceType", "source_type"),
    }

    def item_value_for(key: str) -> Any:
        return next((item[alias] for alias in field_aliases[key] if item.get(alias) is not None), None)

    for key in ("projectId", "workId"):
        item_value = item_value_for(key)
        source_value = source_payload.get(key)
        if item_value is not None and source_value is not None and str(item_value) != str(source_value):
            return False
    for key in ("generationId", "chapterVersion", "contentHash"):
        item_value = item_value_for(key)
        source_value = source_payload.get(key)
        if item_value is not None and source_value is not None and str(item_value) != str(source_value):
            return False
    source_type = str(source_payload.get("sourceType") or "").strip().casefold()
    item_type = str(item_value_for("sourceType") or "").strip().casefold()
    if source_type and item_type and source_type != item_type:
        return False
    identifiers = ("chunkId", "documentId", "sourceRefId", "chapterId", "snapshotId")
    return any(
        item.get(key) is not None
        and source_payload.get(key) is not None
        and str(item.get(key)) == str(source_payload.get(key))
        for key in identifiers
    )


def _accepted_source_indexes(commit: EvidenceCommit | None) -> set[int]:
    indexes: set[int] = set()
    if commit is None:
        return indexes
    for decision in commit.decisions:
        if decision.decision != EvidenceDecisionState.ACCEPTED:
            continue
        for raw_ref in (decision.citationRef, decision.evidenceId):
            normalized = str(raw_ref or "").strip().removeprefix("source:")
            if normalized.isdigit() and int(normalized) > 0:
                indexes.add(int(normalized))
    return indexes


def _task_run_items(run: dict[str, Any]) -> list[dict[str, Any]]:
    output = run.get("output") or {}
    if not isinstance(output, dict):
        return []
    for key in ("evidence", "sources", "items"):
        items = output.get(key)
        if isinstance(items, list):
            return [item for item in items if isinstance(item, dict)]
    return []


def _task_has_accepted_coverage(
    task_id: str,
    *,
    task_plan: dict[str, Any] | None,
    runs: list[dict[str, Any]],
    sources: list[KnowledgeSource],
    accepted_indexes: set[int],
) -> bool:
    covered_items: list[dict[str, Any]] = []
    for run in runs:
        if not isinstance(run, dict) or run.get("status") != "succeeded":
            continue
        if str((run.get("input") or {}).get("taskId") or "") != task_id:
            continue
        for item in _task_run_items(run):
            if any(
                index in accepted_indexes and _source_matches_item(source, item)
                for index, source in enumerate(sources, start=1)
            ):
                covered_items.append(item)
    if not covered_items:
        return False
    retrieval = task_plan.get("retrievalPlan") if isinstance(task_plan, dict) else None
    if not isinstance(retrieval, dict):
        return True
    chapter_from = retrieval.get("chapterFrom")
    chapter_to = retrieval.get("chapterTo")
    try:
        chapter_from = int(chapter_from) if chapter_from is not None else None
        chapter_to = int(chapter_to) if chapter_to is not None else None
    except (TypeError, ValueError):
        return False
    if chapter_from is None or chapter_to is None:
        return True
    if chapter_from < 1 or chapter_to < chapter_from or chapter_to - chapter_from > 1000:
        return False
    covered_chapters = {
        int(item["chapterNo"])
        for item in covered_items
        if item.get("chapterNo") is not None and str(item.get("chapterNo")).isdigit()
    }
    return all(chapter_number in covered_chapters for chapter_number in range(chapter_from, chapter_to + 1))


def build_task_checkpoint(state: dict[str, Any]) -> TaskProgressCheckpoint:
    request = state["request"]
    envelope = IntentEnvelope.model_validate(state["intent_envelope"])
    commit = EvidenceCommit.model_validate(state["evidence_commit"]) if state.get("evidence_commit") else None
    sources = [source if isinstance(source, KnowledgeSource) else KnowledgeSource.model_validate(source)
               for source in state.get("sources") or []]
    accepted = tuple(decision.evidenceId for decision in commit.decisions
                     if decision.decision == EvidenceDecisionState.ACCEPTED) if commit else ()
    rejected = tuple(decision.evidenceId for decision in commit.decisions
                     if decision.decision == EvidenceDecisionState.REJECTED) if commit else ()
    accepted_indexes = _accepted_source_indexes(commit)
    task_plans = {
        str(plan.get("taskId")): plan
        for plan in state.get("task_tool_plan") or []
        if isinstance(plan, dict) and plan.get("taskId")
    }
    completed: set[str] = set()
    task_ids = tuple(dict.fromkeys(str(task["id"]) for task in (state.get("task_graph") or {}).get("tasks", []) if task.get("id")))
    for task_id in task_ids:
        if _task_has_accepted_coverage(
            task_id,
            task_plan=task_plans.get(task_id),
            runs=list(state.get("tool_runs") or []),
            sources=sources,
            accepted_indexes=accepted_indexes,
        ):
            completed.add(task_id)
    pending = tuple(task_id for task_id in task_ids if task_id not in completed)
    return TaskProgressCheckpoint(
        runId=(state.get("tool_ledger_checkpoint") or {}).get("runId") or request.traceId or request.conversationId,
        scope=CapabilityScope(userId=request.userId, projectId=request.projectId, bookId=state.get("book_id")),
        requestFingerprint=content_hash({
            "question": request.question, "traceId": request.traceId, "conversationId": request.conversationId,
            "userId": request.userId, "projectId": request.projectId, "workId": request.workId,
            "referenceWorks": [work.model_dump(mode="json") for work in request.referenceWorks],
        }),
        intentEnvelopeHash=envelope.fingerprint, planRevision=min(1, int((state.get("retry_counts") or {}).get("evidence_repair") or 0)),
        evidenceCommitId=commit.commitId if commit else None, evidenceFingerprint=evidence_hash(commit, sources),
        goal=envelope.goal, constraints=envelope.constraints,
        completedTaskIds=tuple(task_id for task_id in task_ids if task_id in completed), pendingTaskIds=pending,
        acceptedEvidenceRefs=accepted, rejectedEvidenceRefs=rejected,
        nextAction="validate" if state.get("response") else "retrieve" if pending else "compose",
    )


def current_task_checkpoint(payload: Any, state: dict[str, Any]) -> TaskProgressCheckpoint | None:
    try:
        checkpoint = TaskProgressCheckpoint.model_validate(payload)
        current = build_task_checkpoint(state)
    except (ValidationError, KeyError, TypeError, ValueError):
        return None
    return checkpoint if checkpoint == current else None
