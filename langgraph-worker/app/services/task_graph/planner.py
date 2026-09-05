from __future__ import annotations

from app.models.agent_task import TaskGraph, TaskType, ToolPlan


class DomainToolPlanner:
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
