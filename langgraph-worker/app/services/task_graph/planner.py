from __future__ import annotations

from app.models.agent_task import TaskGraph, TaskType, ToolPlan
from typing import Any

from app.services.harness.contracts import CapabilityPlan, CapabilityScope, IntentEnvelope, RepairProposal


class DomainToolPlanner:
    def propose_read_repair(
        self, *, graph: TaskGraph, plans: list[ToolPlan], envelope: IntentEnvelope,
        capability_plan: CapabilityPlan, scope: CapabilityScope, allowed_tools: set[str],
        runs: list[dict[str, Any]],
        work_id: int | None = None,
    ) -> tuple[RepairProposal, list[ToolPlan]] | None:
        if capability_plan.intentEnvelopeHash != envelope.fingerprint or scope.projectId is None:
            return None
        if any(run.get("status") in {"denied", "cancelled", "unknown"} or run.get("errorType") in {
            "ToolNotAllowed", "ProjectScopeMismatch", "ProjectScopeUnresolved", "McpPermissionDenied",
            "ToolBudgetExceeded", "BudgetExceededError",
        } for run in runs):
            return None
        task_ids = {task.id for task in graph.tasks}
        repair_plans: list[ToolPlan] = []
        for plan in plans:
            if plan.taskId not in task_ids or "project.retrieve" not in plan.tools or "project.retrieve" not in allowed_tools:
                continue
            retrieval = plan.retrievalPlan
            if retrieval is None or not plan.required:
                continue
            attempts = [run for run in runs if run.get("name") == "project.retrieve"
                        and (run.get("input") or {}).get("taskId") == plan.taskId]
            if not attempts or any(
                run.get("status") != "succeeded" or (
                    bool((run.get("output") or {}).get("evidence"))
                    if isinstance((run.get("output") or {}).get("evidence"), list)
                    else int(run.get("resultCount") or 0) > 0
                ) for run in attempts
            ):
                continue
            query = " ".join(retrieval.entities).strip()
            if not query or query == retrieval.query or len(query) > 2000:
                continue
            repair_plans.append(plan.model_copy(update={
                "tools": ["project.retrieve"], "retrievalPlan": retrieval.model_copy(update={"query": query}),
                "reason": "empty_result_query_refinement",
            }))
        if not repair_plans:
            return None
        repair_plans = repair_plans[:12]
        return RepairProposal(
            intentEnvelopeHash=envelope.fingerprint, scope=scope, workId=work_id, planRevision=1,
            missingRequirementIds=tuple(plan.taskId for plan in repair_plans),
            action="refine_query", reasonCode="empty_result",
        ), repair_plans

    def plan(self, graph: TaskGraph) -> list[ToolPlan]:
        plans: list[ToolPlan] = []
        for task in graph.tasks:
            plans.append(
                ToolPlan(
                    taskId=task.id,
                    taskType=task.type,
                    tools=list(task.tools),
                    required=task.type in {
                        TaskType.market_scan,
                        TaskType.book_breakdown,
                        TaskType.project_knowledge_qa,
                        TaskType.foreshadowing_audit,
                        TaskType.continuity_check,
                    },
                    reason=f"Mapped from task type {task.type.value}",
                )
            )
        return plans
