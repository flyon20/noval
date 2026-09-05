from __future__ import annotations

import asyncio
from typing import Any

from app.models.agent_task import TaskGraph, ToolPlan, ToolRun
from app.services.harness.tool_ledger import RunToolLedger, current_run_tool_ledger
from app.services.tools.registry import DomainToolRegistry


class DomainTaskToolExecutor:
    def __init__(self, registry: DomainToolRegistry, *, tool_ledger: RunToolLedger | None = None) -> None:
        self._registry = registry
        self._tool_ledger = tool_ledger

    async def execute(
        self,
        graph: TaskGraph,
        plans: list[ToolPlan],
        *,
        context: dict[str, Any] | None = None,
        allowed_tools: set[str] | list[str] | tuple[str, ...] | None = None,
        max_tool_calls: int | None = None,
        reserved_required_tools: set[str] | list[str] | tuple[str, ...] | None = None,
        tool_ledger: RunToolLedger | None = None,
    ) -> list[ToolRun]:
        task_by_id = {task.id: task for task in graph.tasks}
        execution_context = dict(context or {})
        allowed = {str(tool) for tool in allowed_tools or ()}
        reserved = {str(tool) for tool in reserved_required_tools or []}
        remaining_calls = None if max_tool_calls is None else max(0, int(max_tool_calls))
        runs: list[ToolRun] = []
        for plan in plans:
            task = task_by_id.get(plan.taskId)
            for tool_name in plan.tools:
                if tool_name not in allowed:
                    runs.append(self._policy_failure(tool_name, "ToolNotAllowed", f"tool {tool_name} is not allowed by runtime policy"))
                    continue
                if remaining_calls is not None and remaining_calls <= 0 and tool_name not in reserved:
                    runs.append(self._policy_failure(tool_name, "ToolBudgetExceeded", "tool call budget exhausted"))
                    continue
                if tool_name == "project.retrieve" and plan.retrievalPlan is None:
                    runs.append(self._policy_failure(
                        tool_name,
                        "RetrievalPlanRequired",
                        "project retrieval requires a typed retrieval plan",
                    ))
                    continue
                if tool_name.startswith("project.") and tool_name != "project.resolve" and not self._project_scope_ready(execution_context):
                    runs.append(self._skipped_project_tool(tool_name, "project scope was not resolved"))
                    continue
                payload = self._payload_for_tool(
                    tool_name,
                    plan,
                    context=execution_context,
                    task_goal=task.goal if task is not None else "",
                )
                if tool_name == "project.retrieve":
                    retrieval_payloads = self._project_retrieval_payloads(payload, execution_context)
                    for scope_index, retrieval_payload in enumerate(retrieval_payloads):
                        active_scope_is_reserved = scope_index == 0 and tool_name in reserved
                        if remaining_calls is not None and remaining_calls <= 0 and not active_scope_is_reserved:
                            runs.append(self._policy_failure(
                                tool_name,
                                "ToolBudgetExceeded",
                                "reference work retrieval budget exhausted",
                            ))
                            break
                        run = await self._dispatch_with_timeout(
                            tool_name,
                            retrieval_payload,
                            context=execution_context,
                            tool_ledger=tool_ledger or self._tool_ledger or current_run_tool_ledger(),
                        )
                        runs.append(run)
                        if remaining_calls is not None and remaining_calls > 0 and run.executed:
                            remaining_calls -= 1
                    continue
                run = await self._dispatch_with_timeout(
                    tool_name,
                    payload,
                    context=execution_context,
                    tool_ledger=tool_ledger or self._tool_ledger or current_run_tool_ledger(),
                )
                if tool_name == "project.resolve" and run.status == "succeeded":
                    resolution_status = str(run.output.get("status") or "").strip().lower()
                    if resolution_status == "resolved" and not self._apply_project_resolution(execution_context, run.output):
                        run = ToolRun(
                            name=run.name,
                            status="failed",
                            input=run.input,
                            output={"message": "project resolution returned an invalid or mismatched scope"},
                            errorType="ProjectScopeMismatch",
                            executed=run.executed,
                        )
                runs.append(run)
                if remaining_calls is not None and remaining_calls > 0 and run.executed:
                    remaining_calls -= 1
        return runs

    def _project_scope_ready(self, context: dict[str, Any]) -> bool:
        return all(self._positive_int(context.get(key)) for key in ("userId", "projectId", "workId"))

    def _positive_int(self, value: Any) -> int | None:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None

    def _skipped_project_tool(self, tool_name: str, message: str) -> ToolRun:
        return ToolRun(
            name=tool_name,
            status="skipped",
            output={"message": message},
            errorType="ProjectScopeUnresolved",
            executed=False,
        )

    def _apply_project_resolution(self, context: dict[str, Any], output: dict[str, Any]) -> bool:
        if str(output.get("status") or "").strip().lower() != "resolved":
            return False
        resolved_user = self._positive_int(output.get("userId"))
        resolved_project = self._positive_int(output.get("projectId"))
        resolved_work = self._positive_int(output.get("workId"))
        expected_user = self._positive_int(context.get("userId"))
        expected_project = self._positive_int(context.get("projectId"))
        if not resolved_project or not resolved_work:
            return False
        if resolved_user is not None and expected_user is not None and resolved_user != expected_user:
            return False
        if expected_project is not None and resolved_project != expected_project:
            return False
        context["projectId"] = resolved_project
        context["workId"] = resolved_work
        context["projectWorkTitle"] = str(output.get("title") or "").strip() or None
        context["_expectedProjectId"] = resolved_project
        context["_expectedUserId"] = expected_user or resolved_user
        return True

    def _policy_failure(self, tool_name: str, error_type: str, message: str) -> ToolRun:
        return ToolRun(
            name=tool_name,
            status="failed",
            input={},
            output={"message": message},
            errorType=error_type,
        )

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
            self._copy_policy_if_present(payload, source_policy, "snapshotStartDate")
            self._copy_policy_if_present(payload, source_policy, "snapshotEndDate")
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
        elif tool_name == "project.resolve":
            payload["query"] = context.get("projectQuery")
        elif tool_name == "project.retrieve":
            retrieval_plan = plan.retrievalPlan
            if retrieval_plan is None:
                raise ValueError("project retrieval requires a typed retrieval plan")
            payload["projectId"] = context.get("projectId")
            payload["workId"] = context.get("workId")
            payload.update(retrieval_plan.model_dump(
                exclude_none=True,
            ))
        elif tool_name.startswith("project."):
            payload["projectId"] = context.get("projectId")
            payload["workId"] = context.get("workId")
        return payload

    def _project_retrieval_payloads(
        self,
        payload: dict[str, Any],
        context: dict[str, Any],
    ) -> list[dict[str, Any]]:
        active_project = self._positive_int(payload.get("projectId"))
        active_work = self._positive_int(payload.get("workId"))
        if active_project is None or active_work is None:
            return [payload]
        scopes: list[tuple[int, int, str | None, str]] = [(
            active_project,
            active_work,
            str(payload.get("projectWorkTitle") or "").strip() or None,
            "active",
        )]
        seen = {(active_project, active_work)}
        raw_references = context.get("referenceWorks")
        if isinstance(raw_references, list):
            for item in raw_references[:8]:
                if not isinstance(item, dict):
                    continue
                project_id = self._positive_int(item.get("projectId"))
                work_id = self._positive_int(item.get("workId"))
                if project_id is None or work_id is None or (project_id, work_id) in seen:
                    continue
                seen.add((project_id, work_id))
                scopes.append((
                    project_id,
                    work_id,
                    str(item.get("title") or "").strip() or None,
                    "reference",
                ))
        expanded: list[dict[str, Any]] = []
        for project_id, work_id, title, role in scopes:
            scoped_payload = dict(payload)
            scoped_payload.pop("referenceWorks", None)
            scoped_payload["projectId"] = project_id
            scoped_payload["workId"] = work_id
            if scoped_payload.get("_expectedUserId") is not None:
                scoped_payload["_expectedProjectId"] = project_id
            scoped_payload["projectScopeRole"] = role
            if title:
                scoped_payload["projectWorkTitle"] = title
            else:
                scoped_payload.pop("projectWorkTitle", None)
            expanded.append(scoped_payload)
        return expanded

    def _copy_if_absent(self, payload: dict[str, Any], target: str, source: str) -> None:
        if target not in payload and source in payload:
            payload[target] = payload[source]

    async def _dispatch_with_timeout(
        self,
        tool_name: str,
        payload: dict[str, Any],
        *,
        context: dict[str, Any],
        tool_ledger: RunToolLedger | None,
    ) -> ToolRun:
        timeout_millis = self._timeout_millis(context, payload)
        if tool_ledger is not None:
            route = str(context.get("toolRoute") or tool_ledger.identity.route).strip()
            project_id = self._positive_int(payload.get("projectId")) if tool_name == "project.retrieve" else None
            if project_id is not None:
                routed_ledger = tool_ledger.for_project_scope(project_id, route=route or None)
            else:
                routed_ledger = tool_ledger.for_route(route) if route else tool_ledger
            return await self._registry.dispatch(
                tool_name,
                payload,
                tool_ledger=routed_ledger,
                timeout=None if timeout_millis is None else max(0.001, timeout_millis / 1000),
                identity_payload=self._identity_payload_for_tool(tool_name, payload),
            )
        return ToolRun(
            name=tool_name,
            status="failed",
            input=payload,
            output={"message": "run tool ledger is required"},
            errorType="ToolLedgerRequired",
        )

    def _timeout_millis(self, context: dict[str, Any], payload: dict[str, Any]) -> int | None:
        values: list[int] = []
        for raw in (context.get("toolTimeoutMillis"), payload.get("timeoutMillis")):
            if raw is None:
                continue
            try:
                values.append(max(1, int(raw)))
            except (TypeError, ValueError):
                continue
        return min(values) if values else None

    def _source_policy_for(self, *, context: dict[str, Any]) -> dict[str, Any]:
        source_policy = context.get("sourcePolicy")
        return dict(source_policy) if isinstance(source_policy, dict) and source_policy else {}

    def _copy_policy_if_present(self, payload: dict[str, Any], source_policy: dict[str, Any], key: str) -> None:
        if key in source_policy and source_policy.get(key) is not None:
            payload[key] = source_policy.get(key)

    def _identity_payload_for_tool(self, tool_name: str, payload: dict[str, Any]) -> dict[str, Any]:
        key_sets = {
            "rank.lookup": {
                "platform", "channelCode", "boardCode", "category", "rankNo", "limit",
                "freshness", "allowHistorical", "timeWindowDays", "requireSnapshotTime",
            },
            "rank.research_pack": {
                "platform", "channelCode", "boardCode", "category", "rankNo", "limit",
                "chapterLimitPerBook", "freshness", "allowHistorical", "timeWindowDays",
                "requireSnapshotTime", "userId", "projectId",
            },
            "book.research_pack": {
                "platform", "bookId", "bookName", "chapterLimit", "analysisLimit", "userId", "projectId",
            },
            "knowledge.vector_search": {
                "query", "bookId", "platform", "analysisType", "sourceType", "limit", "userId", "projectId",
            },
            "skill.lookup": {"query", "taskType", "intent", "eligibleSkillIds", "activatedSkillIds"},
            "memory.project_context": {
                "userId", "projectId", "bookId", "bookName", "contextSummary", "history",
            },
            "project.resolve": {"userId", "projectId", "workId", "query", "limit"},
            "project.retrieve": {
                "userId", "projectId", "workId", "query", "intent", "entities",
                "chapterFrom", "chapterTo", "channels", "filters", "weights", "limit", "deep",
                "graphBudgetMillis", "timeoutMillis", "rerankPolicy",
            },
            "project.foreshadowing.list": {"userId", "projectId", "workId", "status", "limit"},
            "project.foreshadowing.aggregate": {"userId", "projectId", "workId"},
            "project.timeline_lookup": {"userId", "projectId", "workId", "query", "limit"},
            "project.character_state_lookup": {"userId", "projectId", "workId", "query", "limit"},
            "project.world_rule_lookup": {"userId", "projectId", "workId", "query", "limit"},
        }
        keys = key_sets.get(tool_name)
        if keys is None:
            return {
                key: value
                for key, value in payload.items()
                if not str(key).startswith("_")
            }
        return {key: payload.get(key) for key in keys if key in payload}
