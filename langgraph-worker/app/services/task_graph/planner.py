from __future__ import annotations

from app.models.agent_task import TaskGraph, TaskType, ToolPlan


class DomainToolPlanner:
    def plan(self, graph: TaskGraph) -> list[ToolPlan]:
        plans: list[ToolPlan] = []
        for task in graph.tasks:
            tools = self._tools_for(task.type, admin_operation_requested=graph.adminOperationRequested)
            plans.append(
                ToolPlan(
                    taskId=task.id,
                    taskType=task.type,
                    tools=tools,
                    required=task.type in {TaskType.market_scan, TaskType.book_breakdown},
                    reason=f"Mapped from task type {task.type.value}",
                )
            )
        return plans

    def _tools_for(self, task_type: TaskType, *, admin_operation_requested: bool) -> list[str]:
        if task_type is TaskType.skill_governance:
            return [] if admin_operation_requested else ["skill.lookup"]
        return {
            TaskType.market_scan: ["rank.lookup", "rank.research_pack"],
            TaskType.book_breakdown: ["book.research_pack", "knowledge.vector_search"],
            TaskType.topic_strategy: ["knowledge.vector_search", "skill.lookup"],
            TaskType.outline_building: ["skill.lookup", "memory.project_context"],
            TaskType.chapter_outline: ["skill.lookup", "memory.project_context"],
            TaskType.character_design: ["skill.lookup", "memory.project_context"],
            TaskType.worldbuilding: ["skill.lookup", "memory.project_context"],
            TaskType.revision_advice: ["knowledge.vector_search", "editor.risk_check"],
            TaskType.reader_risk: ["reader.simulate_feedback"],
            TaskType.editor_risk: ["editor.risk_check"],
            TaskType.followup_context: ["memory.project_context"],
        }.get(task_type, [])
