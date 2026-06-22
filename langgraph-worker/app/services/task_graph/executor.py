from __future__ import annotations

import asyncio
from typing import Any

from app.models.agent_task import TaskGraph, ToolPlan, ToolRun
from app.services.tools.registry import DomainToolRegistry


class DomainTaskToolExecutor:
    def __init__(self, registry: DomainToolRegistry) -> None:
        self._registry = registry

    async def execute(
        self,
        graph: TaskGraph,
        plans: list[ToolPlan],
        *,
        context: dict[str, Any] | None = None,
    ) -> list[ToolRun]:
        task_by_id = {task.id: task for task in graph.tasks}
        runs: list[ToolRun] = []
        for plan in plans:
            task = task_by_id.get(plan.taskId)
            for tool_name in plan.tools:
                payload = self._payload_for_tool(
                    tool_name,
                    plan,
                    context=context or {},
                    task_goal=task.goal if task is not None else "",
                )
                runs.append(await self._dispatch_with_timeout(tool_name, payload, context=context or {}))
        return runs

    def _payload_for_tool(
        self,
        tool_name: str,
        plan: ToolPlan,
        *,
        context: dict[str, Any],
        task_goal: str,
    ) -> dict[str, Any]:
        payload = dict(context)
        payload.setdefault("query", context.get("query") or task_goal)
        payload["taskId"] = plan.taskId
        payload["taskType"] = plan.taskType.value
        payload["required"] = plan.required
        source_policy = self._source_policy_for(context=context)
        if source_policy:
            payload["sourcePolicy"] = source_policy
            self._copy_policy_if_present(payload, source_policy, "freshness")
            self._copy_policy_if_present(payload, source_policy, "allowHistorical")
            self._copy_policy_if_present(payload, source_policy, "timeWindowDays")
            self._copy_policy_if_present(payload, source_policy, "requireSnapshotTime")
        if tool_name == "rank.lookup" or tool_name == "rank.research_pack":
            self._copy_if_absent(payload, "channelCode", "channel_code")
            self._copy_if_absent(payload, "boardCode", "board_code")
            self._copy_if_absent(payload, "rankNo", "rank_no")
            self._copy_if_absent(payload, "chapterLimitPerBook", "chapter_limit_per_book")
        elif tool_name == "book.research_pack":
            self._copy_if_absent(payload, "bookId", "book_id")
            self._copy_if_absent(payload, "bookName", "book_name")
            self._copy_if_absent(payload, "chapterLimit", "chapter_limit")
            self._copy_if_absent(payload, "analysisLimit", "analysis_limit")
        elif tool_name == "knowledge.vector_search":
            self._copy_if_absent(payload, "bookId", "book_id")
            self._copy_if_absent(payload, "analysisType", "analysis_type")
            self._copy_if_absent(payload, "sourceType", "source_type")
        return payload

    def _copy_if_absent(self, payload: dict[str, Any], target: str, source: str) -> None:
        if target not in payload and source in payload:
            payload[target] = payload[source]

    async def _dispatch_with_timeout(
        self,
        tool_name: str,
        payload: dict[str, Any],
        *,
        context: dict[str, Any],
    ) -> ToolRun:
        timeout_millis = self._timeout_millis(context)
        if timeout_millis is None:
            return await self._registry.dispatch(tool_name, payload)
        try:
            return await asyncio.wait_for(
                self._registry.dispatch(tool_name, payload),
                timeout=max(0.001, timeout_millis / 1000),
            )
        except TimeoutError:
            return ToolRun(
                name=tool_name,
                status="failed",
                input=payload,
                output={"message": "tool timed out"},
                errorType="ToolTimeout",
            )

    def _timeout_millis(self, context: dict[str, Any]) -> int | None:
        raw = context.get("toolTimeoutMillis")
        if raw is None:
            return None
        try:
            parsed = int(raw)
        except (TypeError, ValueError):
            return None
        return max(1, parsed)

    def _source_policy_for(self, *, context: dict[str, Any]) -> dict[str, Any]:
        source_policy = context.get("sourcePolicy")
        return dict(source_policy) if isinstance(source_policy, dict) and source_policy else {}

    def _copy_policy_if_present(self, payload: dict[str, Any], source_policy: dict[str, Any], key: str) -> None:
        if key in source_policy and source_policy.get(key) is not None:
            payload[key] = source_policy.get(key)
