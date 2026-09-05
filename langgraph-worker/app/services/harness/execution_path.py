from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any


class ExecutionPath(StrEnum):
    DIRECT = "DIRECT"
    RETRIEVE = "RETRIEVE"
    COMPLEX = "COMPLEX"


@dataclass(frozen=True, slots=True)
class ExecutionPathDecision:
    path: ExecutionPath
    reason: str

    def as_trace(self) -> dict[str, str]:
        return {"path": self.path.value, "reason": self.reason}


class ExecutionPathRouter:
    _DIRECT_INTENTS = {
        "creative_advice",
        "opening_strategy",
        "outline_building",
        "chapter_outline",
        "character_design",
        "worldbuilding",
        "revision_advice",
        "followup_context",
        "out_of_scope",
        "admin_skill_governance",
    }
    _NON_RETRIEVAL_STEPS = {"creative_generation", "skill.lookup"}
    _RETRIEVAL_LEGACY_INTENTS = {
        "single_book_research",
        "book_resolution",
        "trend_research",
        "answer_question",
        "rank_lookup",
    }

    def decide(
        self,
        *,
        intent: str | None,
        domain_intent: str | None,
        task_graph: dict[str, Any] | None,
        tool_plan: list[dict[str, Any]] | None,
        has_project_context: bool = True,
    ) -> ExecutionPathDecision:
        legacy_intent = str(intent or "").strip().lower()
        normalized_intent = str(domain_intent or intent or "").strip().lower()
        direct_intent = (
            legacy_intent in self._DIRECT_INTENTS
            or (
                legacy_intent not in self._RETRIEVAL_LEGACY_INTENTS
                and normalized_intent in self._DIRECT_INTENTS
            )
        )
        tasks = [item for item in list((task_graph or {}).get("tasks") or []) if isinstance(item, dict)]
        planned_tools = [item for item in list(tool_plan or []) if isinstance(item, dict)]
        task_tools = {
            str(tool).strip()
            for task in tasks
            for tool in list(task.get("tools") or [])
            if str(tool).strip()
        }
        tool_names = (task_tools | {
            str(item.get("name") or item.get("tool") or "").strip()
            for item in planned_tools
            if str(item.get("name") or item.get("tool") or "").strip()
        }) - self._NON_RETRIEVAL_STEPS
        if not has_project_context:
            tool_names.discard("memory.project_context")
        active_task_types = {
            str(task.get("type") or "").strip().lower()
            for task in tasks
            if str(task.get("type") or "").strip()
        }
        if len(active_task_types) >= 2 or normalized_intent == "mixed_creation_research":
            return ExecutionPathDecision(ExecutionPath.COMPLEX, "multi_task_or_mixed_intent")
        if direct_intent and not has_project_context:
            return ExecutionPathDecision(ExecutionPath.DIRECT, "creative_without_project_retrieval")
        if tool_names:
            return ExecutionPathDecision(ExecutionPath.RETRIEVE, "tool_or_retrieval_required")
        if direct_intent or not tasks:
            return ExecutionPathDecision(ExecutionPath.DIRECT, "creative_or_no_external_evidence")
        return ExecutionPathDecision(ExecutionPath.RETRIEVE, "single_research_task")
