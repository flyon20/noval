from __future__ import annotations

import asyncio
import contextvars
import hashlib
import inspect
import json
import re
import time
from collections.abc import Awaitable, Callable, Iterator, Mapping
from concurrent.futures import Future as ConcurrentFuture, ThreadPoolExecutor
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any, Literal, TypeAlias

from app.models.agent_task import RunToolIdentity, ToolRun
from app.services.harness.contracts import ToolProgressAttempt
from app.services.harness.budget import BudgetExceededError, RunBudget, current_run_budget
from app.services.harness.cancellation import (
    CancellationToken,
    RunCancelledError,
    cancellable_await,
    cancellation_checkpoint,
    cancellation_scope,
    current_cancellation_token,
)


ToolAccess = Literal["read", "write", "idempotent"]
ToolOperation: TypeAlias = Callable[..., Any] | Awaitable[Any]
SemanticCheckpointWriter: TypeAlias = Callable[[str, str, dict[str, Any]], Awaitable[Any]]

_SENSITIVE_KEYS = {
    "apikey",
    "authorization",
    "accesstoken",
    "refreshtoken",
    "clientsecret",
    "cookie",
    "password",
    "privatekey",
    "idempotencykey",
    "secret",
    "setcookie",
    "token",
}
_SECRET_KEY_PATTERN = (
    r"authorization|api[\s_-]?key|access[\s_-]?token|refresh[\s_-]?token|"
    r"client[\s_-]?secret|password|private[\s_-]?key|secret|token"
)
_QUOTED_SECRET_TEXT = re.compile(
    rf"(?i)([\"']?(?:{_SECRET_KEY_PATTERN})[\"']?\s*[:=]\s*)([\"'])(.*?)(\2)"
)
_SECRET_TEXT = re.compile(
    rf"(?i)([\"']?(?:{_SECRET_KEY_PATTERN})[\"']?\s*[:=]\s*)(?![\"'])([^\s,;}}\]]+)"
)
_BEARER_TEXT = re.compile(r"(?i)\bBearer\s+[^\s,;]+")
_SYNC_TOOL_EXECUTOR = ThreadPoolExecutor(max_workers=2, thread_name_prefix="governed-tool")
_READ_CHECKPOINT_LIMIT = 64
_READ_CHECKPOINT_MAX_CHARS = 256_000


@dataclass(slots=True)
class _RunToolLedgerState:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    pending: dict[str, asyncio.Task[ToolRun]] = field(default_factory=dict)
    pending_tools: dict[str, str] = field(default_factory=dict)
    pending_waiters: dict[asyncio.Task[ToolRun], int] = field(default_factory=dict)
    pending_tokens: dict[asyncio.Task[ToolRun], CancellationToken] = field(default_factory=dict)
    completed: dict[str, ToolRun] = field(default_factory=dict)
    semantic_terminals: dict[str, ToolRun] = field(default_factory=dict)
    semantic_reuse_terminals: dict[str, ToolRun] = field(default_factory=dict)
    history: list[ToolRun] = field(default_factory=list)
    idempotency_fingerprints: dict[str, str] = field(default_factory=dict)
    call_fingerprints: dict[str, str] = field(default_factory=dict)
    tool_generations: dict[str, int] = field(default_factory=dict)
    write_sequence: int = 0
    progress_control_enabled: bool = False
    progress_attempts: dict[str, dict[str, int]] = field(default_factory=dict)
    evidence_repair_used: bool = False


class RunToolLedger:
    def __init__(
        self,
        identity: RunToolIdentity | Mapping[str, Any] | None = None,
        *,
        run_id: Any | None = None,
        user_id: Any | None = None,
        project_id: Any | None = None,
        route: Any | None = None,
        budget: RunBudget | None = None,
        cancellation_token: CancellationToken | None = None,
        checkpoint_writer: SemanticCheckpointWriter | None = None,
        _state: _RunToolLedgerState | None = None,
    ) -> None:
        if identity is None:
            identity = {
                "runId": run_id,
                "userId": user_id,
                "projectId": project_id,
                "route": route,
            }
        self.identity = (
            identity
            if isinstance(identity, RunToolIdentity)
            else RunToolIdentity.model_validate(identity)
        )
        self._budget = budget
        self._cancellation_token = cancellation_token
        self._checkpoint_writer = checkpoint_writer
        self._state = _state or _RunToolLedgerState()

    def for_route(self, route: Any) -> "RunToolLedger":
        identity = RunToolIdentity(
            runId=self.identity.runId,
            userId=self.identity.userId,
            projectId=self.identity.projectId,
            route=route,
        )
        if identity.route == self.identity.route:
            return self
        return RunToolLedger(
            identity,
            budget=self._budget,
            cancellation_token=self._cancellation_token,
            checkpoint_writer=self._checkpoint_writer,
            _state=self._state,
        )

    @property
    def progress_control_enabled(self) -> bool:
        return self._state.progress_control_enabled

    @progress_control_enabled.setter
    def progress_control_enabled(self, enabled: bool) -> None:
        self._state.progress_control_enabled = enabled is True

    @property
    def evidence_repair_used(self) -> bool:
        return self._state.evidence_repair_used

    async def claim_evidence_repair(self) -> bool:
        async with self._state.lock:
            cancellation_checkpoint(self._cancellation_token or current_cancellation_token())
            if self._state.evidence_repair_used:
                return False
            semantic_key = "repair_" + self._digest(self.identity.dedupe_scope_key)
            await self._write_checkpoint("HARNESS_REPAIR", semantic_key, {
                "schemaVersion": "harness-repair-slot-v1", "semanticKey": semantic_key,
                "runId": self.identity.runId, "userId": self.identity.userId,
                "projectId": self.identity.projectId, "used": True,
            })
            self._state.evidence_repair_used = True
            return True

    def for_project_scope(self, project_id: Any, *, route: Any | None = None) -> "RunToolLedger":
        identity = RunToolIdentity(
            runId=self.identity.runId,
            userId=self.identity.userId,
            projectId=project_id,
            route=self.identity.route if route is None else route,
        )
        if identity.projectId == self.identity.projectId and identity.route == self.identity.route:
            return self
        return RunToolLedger(
            identity,
            budget=self._budget,
            cancellation_token=self._cancellation_token,
            checkpoint_writer=self._checkpoint_writer,
            _state=self._state,
        )

    def call_id(
        self,
        name: str,
        arguments: Mapping[str, Any] | None = None,
        *,
        access: ToolAccess = "read",
        idempotency_key: str | None = None,
        nonce: int | str | None = None,
    ) -> str:
        normalized_access = self._normalize_access(access)
        payload = {
            "scope": self.identity.dedupe_scope_key,
            "tool": self._normalize_tool_name(name),
            "access": normalized_access,
            "arguments": self._json_safe(dict(arguments or {})),
            "idempotencyId": self.idempotency_id(name, idempotency_key),
            "nonce": None if nonce is None else str(nonce).strip(),
        }
        return f"tool_call_{self._digest(payload)}"

    def idempotency_id(self, name: str, idempotency_key: str | None) -> str | None:
        normalized_key = self._normalize_optional_id(idempotency_key)
        if normalized_key is None:
            return None
        payload = {
            "scope": self.identity.dedupe_scope_key,
            "tool": self._normalize_tool_name(name),
            "key": normalized_key,
        }
        return f"tool_idem_{self._digest(payload)}"

    async def execute(
        self,
        name: str,
        arguments: Mapping[str, Any] | None,
        operation: ToolOperation,
        *,
        access: ToolAccess = "read",
        idempotency_key: str | None = None,
        call_id: str | None = None,
        timeout: float | None = None,
        cancellation_token: CancellationToken | None = None,
        toolset: str | None = None,
        route: str | None = None,
        identity_arguments: Mapping[str, Any] | None = None,
        secret_input_keys: set[str] | tuple[str, ...] | list[str] | None = None,
        secret_output_keys: set[str] | tuple[str, ...] | list[str] | None = None,
        track_progress: bool = False,
    ) -> ToolRun:
        if route is not None:
            routed = self.for_route(route)
            if routed is not self:
                return await routed.execute(
                    name,
                    arguments,
                    operation,
                    access=access,
                    idempotency_key=idempotency_key,
                    call_id=call_id,
                    timeout=timeout,
                    cancellation_token=cancellation_token,
                    toolset=toolset,
                    identity_arguments=identity_arguments,
                    secret_input_keys=secret_input_keys,
                    secret_output_keys=secret_output_keys,
                    track_progress=track_progress,
                )
        normalized_access = self._normalize_access(access)
        normalized_tool_name = self._normalize_tool_name(name)
        raw_arguments = dict(arguments or {})
        canonical_arguments = self._canonical_identity_arguments(
            dict(identity_arguments) if identity_arguments is not None else raw_arguments
        )
        idempotency_id = self.idempotency_id(name, idempotency_key)
        reusable = normalized_access in {"read", "idempotent"} or idempotency_id is not None
        normalized_call_id = self._normalize_optional_id(call_id)
        supplied_call_id = normalized_call_id is not None
        active_cancellation_token = (
            cancellation_token
            or self._cancellation_token
            or current_cancellation_token()
        )

        async with self._state.lock:
            if normalized_call_id is None:
                nonce = None
                if not reusable:
                    self._state.write_sequence += 1
                    nonce = self._state.write_sequence
                normalized_call_id = self.call_id(
                    name,
                    canonical_arguments,
                    access=normalized_access,
                    idempotency_key=idempotency_key,
                    nonce=nonce,
                )
            canonical_fingerprint = self.call_id(
                name,
                canonical_arguments,
                access=normalized_access,
                idempotency_key=idempotency_key,
            )
            call_scope_key: str | None = None
            if supplied_call_id:
                call_scope_key = f"{self.identity.dedupe_scope_key}:{self._normalize_tool_name(name)}:{normalized_call_id}"
                existing_call_fingerprint = self._state.call_fingerprints.get(call_scope_key)
                if existing_call_fingerprint is not None and existing_call_fingerprint != canonical_fingerprint:
                    conflict = self._conflict_run(
                        name=name,
                        arguments=raw_arguments,
                        call_id=normalized_call_id,
                        idempotency_id=idempotency_id,
                        access=normalized_access,
                        error_type="CallIdentityConflict",
                        message="call id reused with different arguments",
                        secret_input_keys=secret_input_keys,
                    )
                    self._state.history.append(conflict)
                    return conflict.model_copy(deep=True)
                self._state.call_fingerprints[call_scope_key] = canonical_fingerprint
            if idempotency_id is not None:
                fingerprint = canonical_fingerprint
                existing_fingerprint = self._state.idempotency_fingerprints.get(idempotency_id)
                if existing_fingerprint is not None and existing_fingerprint != fingerprint:
                    conflict = self._conflict_run(
                        name=name,
                        arguments=raw_arguments,
                        call_id=normalized_call_id,
                        idempotency_id=idempotency_id,
                        access=normalized_access,
                        error_type="IdempotencyConflict",
                        message="idempotency key reused with different arguments",
                        secret_input_keys=secret_input_keys,
                    )
                    self._state.history.append(conflict)
                    return conflict.model_copy(deep=True)
                self._state.idempotency_fingerprints[idempotency_id] = fingerprint
            reuse_key = self._reuse_key(
                name,
                canonical_fingerprint,
                idempotency_id,
                reusable,
            )
            tool_generation = self._state.tool_generations.get(normalized_tool_name, 0)
            if track_progress:
                cancellation_checkpoint(active_cancellation_token)
                ordinal = await self._record_progress_attempt(
                    canonical_fingerprint, normalized_call_id, tool_generation, call_scope_key,
                )
                if ordinal >= 3:
                    denied = self._conflict_run(
                        name=name, arguments=raw_arguments, call_id=normalized_call_id,
                        idempotency_id=idempotency_id, access=normalized_access,
                        error_type="ToolNoProgress", message="unchanged request has made no progress; use existing evidence or stop",
                        secret_input_keys=secret_input_keys,
                    ).model_copy(update={"status": "denied"})
                    self._state.history.append(denied)
                    return denied.model_copy(deep=True)
            semantic_key = self._semantic_key(
                normalized_tool_name,
                normalized_call_id,
                canonical_fingerprint,
                tool_generation,
            )
            semantic_terminal = self._state.semantic_terminals.get(semantic_key)
            if semantic_terminal is not None:
                reused_run = self._reuse(semantic_terminal, joined=False)
                self._state.history.append(reused_run)
                return reused_run
            if reuse_key is not None and reuse_key in self._state.completed:
                reused_run = self._reuse(self._state.completed[reuse_key], joined=False)
                self._state.history.append(reused_run)
                return reused_run
            if reuse_key is not None and reuse_key in self._state.semantic_reuse_terminals:
                recovered_run = self._reuse(
                    self._state.semantic_reuse_terminals[reuse_key],
                    joined=False,
                )
                self._state.history.append(recovered_run)
                return recovered_run
            pending = self._state.pending.get(reuse_key) if reuse_key is not None else None
            if pending is None:
                shared_cancellation_token = CancellationToken()
                pending = asyncio.create_task(
                    self._execute_shared(
                        shared_cancellation_token=shared_cancellation_token,
                        name=name,
                        arguments=raw_arguments,
                        operation=operation,
                        access=normalized_access,
                        call_id=normalized_call_id,
                        idempotency_id=idempotency_id,
                        reuse_key=reuse_key,
                        semantic_key=semantic_key,
                        canonical_fingerprint=canonical_fingerprint,
                        call_scope_key=call_scope_key,
                        timeout=timeout,
                        budget=self._budget or current_run_budget(),
                        toolset=toolset,
                        normalized_tool_name=normalized_tool_name,
                        tool_generation=tool_generation,
                        secret_input_keys=secret_input_keys,
                        secret_output_keys=secret_output_keys,
                    )
                )
                self._state.pending_tokens[pending] = shared_cancellation_token
                if reuse_key is not None:
                    self._state.pending[reuse_key] = pending
                    self._state.pending_tools[reuse_key] = normalized_tool_name
                joined = False
            else:
                joined = True
            self._state.pending_waiters[pending] = self._state.pending_waiters.get(pending, 0) + 1

        try:
            try:
                run = await cancellable_await(
                    asyncio.shield(pending),
                    token=active_cancellation_token,
                )
            except RunCancelledError as exc:
                cancelled = ToolRun(
                    name=str(name).strip(),
                    status="cancelled",
                    input=self._redact(raw_arguments, extra_keys=secret_input_keys),
                    output={"message": self._redact_text(str(exc) or "run cancelled")},
                    errorType="RunCancelledError",
                    runId=self.identity.runId,
                    userId=self.identity.userId,
                    projectId=self.identity.projectId,
                    route=self.identity.route,
                    callId=normalized_call_id,
                    idempotencyId=idempotency_id,
                    access=normalized_access,
                    executed=False,
                    reused=joined,
                    joined=joined,
                )
                async with self._state.lock:
                    self._state.history.append(cancelled)
                return cancelled.model_copy(deep=True)
            if not joined:
                return run.model_copy(deep=True)
            joined_run = self._reuse(run, joined=True)
            async with self._state.lock:
                self._state.history.append(joined_run)
            return joined_run
        finally:
            await self._release_pending_waiter(pending)

    async def run(
        self,
        name: str,
        arguments: Mapping[str, Any] | None,
        operation: ToolOperation,
        **kwargs: Any,
    ) -> ToolRun:
        return await self.execute(name, arguments, operation, **kwargs)

    async def invalidate(self, *tool_names: str) -> None:
        normalized_names = {
            self._normalize_tool_name(name)
            for name in tool_names
            if str(name or "").strip()
        }
        if not normalized_names:
            return
        async with self._state.lock:
            generations = {
                name: self._state.tool_generations.get(name, 0) + 1
                for name in sorted(normalized_names)
            }
            invalidation_identity = {
                "scope": self.identity.dedupe_scope_key,
                "generations": generations,
            }
            invalidation_key = f"tool_invalidation_{self._digest(invalidation_identity)}"
            await self._write_checkpoint(
                "TOOL_INVALIDATED",
                invalidation_key,
                {
                    "semanticKey": invalidation_key,
                    "runId": self.identity.runId,
                    "userId": self.identity.userId,
                    "projectId": self.identity.projectId,
                    "generations": generations,
                },
            )
            for name, generation in generations.items():
                self._state.tool_generations[name] = generation
            stale_completed = [
                reuse_key
                for reuse_key, run in self._state.completed.items()
                if self._normalize_tool_name(run.name) in normalized_names
            ]
            for reuse_key in stale_completed:
                self._state.completed.pop(reuse_key, None)
            stale_semantic = [
                semantic_key
                for semantic_key, run in self._state.semantic_terminals.items()
                if self._normalize_tool_name(run.name) in normalized_names
            ]
            for semantic_key in stale_semantic:
                self._state.semantic_terminals.pop(semantic_key, None)
            stale_semantic_reuse = [
                reuse_key
                for reuse_key, run in self._state.semantic_reuse_terminals.items()
                if self._normalize_tool_name(run.name) in normalized_names
            ]
            for reuse_key in stale_semantic_reuse:
                self._state.semantic_reuse_terminals.pop(reuse_key, None)
            stale_pending = [
                reuse_key
                for reuse_key, name in self._state.pending_tools.items()
                if name in normalized_names
            ]
            for reuse_key in stale_pending:
                self._state.pending.pop(reuse_key, None)
                self._state.pending_tools.pop(reuse_key, None)

    @property
    def runs(self) -> tuple[ToolRun, ...]:
        return tuple(run.model_copy(deep=True) for run in self._state.history)

    def has_external_side_effect(self) -> bool:
        candidates = [
            *self._state.history,
            *self._state.completed.values(),
            *self._state.semantic_terminals.values(),
            *self._state.semantic_reuse_terminals.values(),
        ]
        return any(
            run.access in {"write", "idempotent"}
            and (run.executed is True or run.status == "unknown")
            for run in candidates
        )

    def snapshot(self) -> list[dict[str, Any]]:
        return [run.model_dump(mode="json", exclude_none=True) for run in self.runs]

    def checkpoint_snapshot(self) -> dict[str, Any]:
        durable_completed: list[dict[str, Any]] = []
        read_completed: list[dict[str, Any]] = []
        read_chars = 0
        for reuse_key, run in self._state.completed.items():
            item = {
                "reuseKey": reuse_key,
                "run": run.model_dump(mode="json", exclude_none=True),
            }
            if run.idempotencyId is not None or run.access in {"write", "idempotent"}:
                durable_completed.append(item)
                continue
            if run.access != "read" or run.status != "succeeded":
                continue
            item_chars = len(json.dumps(item, ensure_ascii=True, separators=(",", ":")))
            if item_chars > _READ_CHECKPOINT_MAX_CHARS:
                continue
            read_completed.append(item)
            read_chars += item_chars
            while len(read_completed) > _READ_CHECKPOINT_LIMIT or read_chars > _READ_CHECKPOINT_MAX_CHARS:
                removed = read_completed.pop(0)
                read_chars -= len(json.dumps(removed, ensure_ascii=True, separators=(",", ":")))
        return {
            "schemaVersion": "run-tool-ledger-v2",
            "runId": self.identity.runId,
            "userId": self.identity.userId,
            "projectId": self.identity.projectId,
            "completed": [*durable_completed, *read_completed],
            "semanticTerminals": [
                {
                    "semanticKey": semantic_key,
                    "run": run.model_dump(mode="json", exclude_none=True),
                }
                for semantic_key, run in self._state.semantic_terminals.items()
            ],
            "idempotencyFingerprints": dict(self._state.idempotency_fingerprints),
            "callFingerprints": dict(self._state.call_fingerprints),
            "toolGenerations": dict(self._state.tool_generations),
            "writeSequence": self._state.write_sequence,
            "evidenceRepairUsed": self._state.evidence_repair_used,
            "progressAttempts": [
                ToolProgressAttempt(requestKey=request_key, attemptId=attempt_id, ordinal=ordinal).model_dump()
                for request_key, attempts in self._state.progress_attempts.items()
                for attempt_id, ordinal in attempts.items()
            ],
        }

    def merge_checkpoint(self, snapshot: dict[str, Any] | None) -> None:
        if not isinstance(snapshot, dict):
            return
        expected_scope = (
            self.identity.runId,
            self.identity.userId,
            self.identity.projectId,
        )
        actual_scope = (
            str(snapshot.get("runId") or "").strip(),
            str(snapshot.get("userId") or "").strip(),
            str(snapshot.get("projectId") or "").strip() or None,
        )
        if actual_scope != expected_scope:
            raise ValueError(
                f"tool ledger checkpoint scope mismatch: expected={expected_scope}, actual={actual_scope}"
            )
        for payload in snapshot.get("progressAttempts") or []:
            self._merge_progress_attempt(payload)
        repair_used = snapshot.get("evidenceRepairUsed", False)
        if type(repair_used) is not bool:
            raise ValueError("invalid evidence repair slot")
        self._state.evidence_repair_used |= repair_used
        for item in snapshot.get("completed") or []:
            if not isinstance(item, dict) or not item.get("reuseKey") or not isinstance(item.get("run"), dict):
                continue
            run = ToolRun.model_validate(item["run"])
            if run.status == "succeeded":
                self._state.completed.setdefault(str(item["reuseKey"]), run)
        for item in snapshot.get("semanticTerminals") or []:
            if not isinstance(item, dict) or not item.get("semanticKey") or not isinstance(item.get("run"), dict):
                continue
            run = ToolRun.model_validate(item["run"])
            self._state.semantic_terminals.setdefault(str(item["semanticKey"]), run)
        for target, key in (
            (self._state.idempotency_fingerprints, "idempotencyFingerprints"),
            (self._state.call_fingerprints, "callFingerprints"),
            (self._state.tool_generations, "toolGenerations"),
        ):
            values = snapshot.get(key)
            if isinstance(values, dict):
                for item_key, value in values.items():
                    if target is self._state.tool_generations:
                        target[str(item_key)] = max(target.get(str(item_key), 0), self._safe_int(value))
                    else:
                        target.setdefault(str(item_key), str(value))
        try:
            write_sequence = int(snapshot.get("writeSequence") or 0)
        except (TypeError, ValueError):
            write_sequence = 0
        self._state.write_sequence = max(self._state.write_sequence, max(0, write_sequence))

    def merge_semantic_events(self, events: list[dict[str, Any]] | tuple[dict[str, Any], ...]) -> list[dict[str, Any]]:
        states: dict[str, dict[str, Any]] = {}
        for event in sorted(events, key=lambda item: self._safe_int(item.get("sequenceNo"))):
            event_type = str(event.get("eventType") or "").strip().upper()
            if event_type not in {
                "TOOL_PREPARED", "TOOL_COMMITTED", "TOOL_UNKNOWN", "TOOL_INVALIDATED", "TOOL_PROGRESS", "HARNESS_REPAIR"
            }:
                continue
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            semantic_key = str(payload.get("semanticKey") or "").strip()
            if not semantic_key or not self._semantic_scope_matches(payload):
                continue
            if event_type == "HARNESS_REPAIR":
                if payload.get("schemaVersion") != "harness-repair-slot-v1" or payload.get("used") is not True:
                    raise ValueError("invalid evidence repair checkpoint")
                self._state.evidence_repair_used = True
                continue
            if event_type == "TOOL_PROGRESS":
                if (str(payload.get("projectId") or "").strip() or None) != self.identity.projectId:
                    continue
                self._merge_progress_attempt(payload.get("progress"))
                self._restore_semantic_fingerprints(payload)
                continue
            if event_type == "TOOL_INVALIDATED":
                generations = payload.get("generations")
                if not isinstance(generations, dict):
                    continue
                normalized_generations = {
                    self._normalize_tool_name(name): self._safe_int(generation)
                    for name, generation in generations.items()
                    if str(name or "").strip()
                }
                for name, generation in normalized_generations.items():
                    self._state.tool_generations[name] = max(
                        self._state.tool_generations.get(name, 0),
                        generation,
                    )
                for stale_key, stale_state in list(states.items()):
                    prepared = stale_state.get("prepared")
                    if not isinstance(prepared, dict):
                        continue
                    name = self._normalize_tool_name(prepared.get("name") or "unknown")
                    if self._safe_int(prepared.get("toolGeneration")) < normalized_generations.get(name, 0):
                        states.pop(stale_key, None)
                continue
            state = states.setdefault(semantic_key, {})
            if event_type == "TOOL_PREPARED":
                state["prepared"] = dict(payload)
                name = self._normalize_tool_name(payload.get("name") or "unknown")
                self._state.tool_generations[name] = max(
                    self._state.tool_generations.get(name, 0),
                    self._safe_int(payload.get("toolGeneration")),
                )
            elif event_type == "TOOL_COMMITTED":
                state["committed"] = dict(payload)
            else:
                state["unknown"] = dict(payload)

        recovered_unknowns: list[dict[str, Any]] = []
        for semantic_key, state in states.items():
            committed = state.get("committed")
            unknown = state.get("unknown")
            prepared = state.get("prepared")
            terminal_payload = committed or unknown
            if isinstance(terminal_payload, dict) and isinstance(terminal_payload.get("run"), dict):
                run = ToolRun.model_validate(terminal_payload["run"])
            elif isinstance(prepared, dict):
                run = self._unknown_run(prepared)
                terminal_payload = {**prepared, "run": run.model_dump(mode="json", exclude_none=True)}
                recovered_unknowns.append(dict(terminal_payload))
            else:
                continue
            self._restore_semantic_fingerprints(terminal_payload)
            if run.status not in {"succeeded", "unknown"}:
                continue
            self._state.semantic_terminals.setdefault(semantic_key, run)
            reuse_key = str(terminal_payload.get("reuseKey") or "").strip()
            if reuse_key and run.status == "succeeded":
                self._state.completed.setdefault(reuse_key, run)
            elif reuse_key and run.status == "unknown":
                self._state.semantic_reuse_terminals.setdefault(reuse_key, run)
            self._state.write_sequence = max(
                self._state.write_sequence,
                self._safe_int(terminal_payload.get("writeSequence")),
            )
        return recovered_unknowns

    async def _record_progress_attempt(
        self, fingerprint: str, call_id: str, generation: int, call_scope_key: str | None,
    ) -> int:
        request_key = f"progress_request_{self._digest({'fingerprint': fingerprint, 'generation': generation})}"
        attempt_id = self._digest(call_id)
        attempts = self._state.progress_attempts.get(request_key, {})
        if attempt_id in attempts:
            return attempts[attempt_id]
        ordinal = max(attempts.values(), default=0) + 1
        if ordinal > 3:
            return 3
        if request_key not in self._state.progress_attempts and len(self._state.progress_attempts) >= 128:
            raise RuntimeError("tool progress capacity exhausted")
        progress = ToolProgressAttempt(requestKey=request_key, attemptId=attempt_id, ordinal=ordinal)
        semantic_key = f"progress_{self._digest(progress.model_dump())}"
        await self._write_checkpoint("TOOL_PROGRESS", semantic_key, {
            "semanticKey": semantic_key, "runId": self.identity.runId,
            "userId": self.identity.userId, "projectId": self.identity.projectId,
            "fingerprint": fingerprint, "callScopeKey": call_scope_key,
            "progress": progress.model_dump(),
        })
        self._merge_progress_attempt(progress.model_dump())
        return ordinal

    def _merge_progress_attempt(self, payload: Any) -> None:
        progress = ToolProgressAttempt.model_validate(payload)
        if progress.requestKey not in self._state.progress_attempts and len(self._state.progress_attempts) >= 128:
            raise ValueError("tool progress capacity exceeded")
        attempts = self._state.progress_attempts.setdefault(progress.requestKey, {})
        existing = attempts.get(progress.attemptId)
        if existing is not None and existing != progress.ordinal:
            raise ValueError("tool progress replay conflict")
        if existing is None and len(attempts) >= 3:
            raise ValueError("too many attempts for one request")
        attempts[progress.attemptId] = progress.ordinal

    async def _execute_and_record(
        self,
        *,
        name: str,
        arguments: dict[str, Any],
        operation: ToolOperation,
        access: ToolAccess,
        call_id: str,
        idempotency_id: str | None,
        reuse_key: str | None,
        semantic_key: str,
        canonical_fingerprint: str,
        call_scope_key: str | None,
        timeout: float | None,
        cancellation_token: CancellationToken | None,
        budget: RunBudget | None,
        toolset: str | None,
        normalized_tool_name: str,
        tool_generation: int,
        secret_input_keys: set[str] | tuple[str, ...] | list[str] | None,
        secret_output_keys: set[str] | tuple[str, ...] | list[str] | None,
    ) -> ToolRun:
        prepared_payload = {
            "semanticKey": semantic_key,
            "reuseKey": reuse_key,
            "fingerprint": canonical_fingerprint,
            "callScopeKey": call_scope_key,
            "name": str(name).strip(),
            "toolset": toolset,
            "runId": self.identity.runId,
            "userId": self.identity.userId,
            "projectId": self.identity.projectId,
            "route": self.identity.route,
            "callId": call_id,
            "idempotencyId": idempotency_id,
            "access": access,
            "writeSequence": self._state.write_sequence,
            "toolGeneration": tool_generation,
        }
        try:
            run = await self._execute_actual(
                name=name,
                arguments=arguments,
                operation=operation,
                access=access,
                call_id=call_id,
                idempotency_id=idempotency_id,
                timeout=timeout,
                cancellation_token=cancellation_token,
                budget=budget,
                toolset=toolset,
                prepared_payload=prepared_payload,
                secret_input_keys=secret_input_keys,
                secret_output_keys=secret_output_keys,
            )
        except BaseException:
            async with self._state.lock:
                if reuse_key is not None:
                    self._state.pending.pop(reuse_key, None)
                    self._state.pending_tools.pop(reuse_key, None)
            raise
        committed_payload = {
            **prepared_payload,
            "run": run.model_dump(mode="json", exclude_none=True),
        }
        try:
            await self._write_checkpoint("TOOL_COMMITTED", semantic_key, committed_payload)
        except BaseException:
            unknown = self._unknown_run(prepared_payload)
            async with self._state.lock:
                if reuse_key is not None:
                    self._state.pending.pop(reuse_key, None)
                    self._state.pending_tools.pop(reuse_key, None)
                self._state.semantic_terminals[semantic_key] = unknown
                if reuse_key is not None:
                    self._state.semantic_reuse_terminals[reuse_key] = unknown
                self._state.history.append(unknown.model_copy(deep=True))
            raise
        async with self._state.lock:
            generation_is_current = (
                self._state.tool_generations.get(normalized_tool_name, 0) == tool_generation
            )
            if reuse_key is not None and generation_is_current:
                self._state.pending.pop(reuse_key, None)
                self._state.pending_tools.pop(reuse_key, None)
                if run.status == "succeeded":
                    self._state.completed[reuse_key] = run.model_copy(deep=True)
            if reuse_key is not None and not generation_is_current and run.status == "succeeded":
                run = run.model_copy(update={
                    "status": "invalidated",
                    "errorType": "ToolResultInvalidated",
                })
            if run.status in {"succeeded", "unknown"}:
                self._state.semantic_terminals[semantic_key] = run.model_copy(deep=True)
            if reuse_key is not None and run.status == "unknown":
                self._state.semantic_reuse_terminals[reuse_key] = run.model_copy(deep=True)
            self._state.history.append(run.model_copy(deep=True))
        return run

    async def _execute_shared(
        self,
        *,
        shared_cancellation_token: CancellationToken,
        **kwargs: Any,
    ) -> ToolRun:
        with cancellation_scope(shared_cancellation_token):
            return await self._execute_and_record(
                cancellation_token=shared_cancellation_token,
                **kwargs,
            )

    async def _release_pending_waiter(self, pending: asyncio.Task[ToolRun]) -> None:
        shared_cancellation_token: CancellationToken | None = None
        async with self._state.lock:
            remaining = self._state.pending_waiters.get(pending, 1) - 1
            if remaining <= 0:
                self._state.pending_waiters.pop(pending, None)
                shared_cancellation_token = self._state.pending_tokens.pop(pending, None)
            else:
                self._state.pending_waiters[pending] = remaining
        if shared_cancellation_token is not None and not pending.done():
            shared_cancellation_token.cancel("tool_has_no_active_waiters")
            await asyncio.gather(pending, return_exceptions=True)

    async def _execute_actual(
        self,
        *,
        name: str,
        arguments: dict[str, Any],
        operation: ToolOperation,
        access: ToolAccess,
        call_id: str,
        idempotency_id: str | None,
        timeout: float | None,
        cancellation_token: CancellationToken | None,
        budget: RunBudget | None,
        toolset: str | None,
        prepared_payload: dict[str, Any],
        secret_input_keys: set[str] | tuple[str, ...] | list[str] | None,
        secret_output_keys: set[str] | tuple[str, ...] | list[str] | None,
    ) -> ToolRun:
        base = {
            "name": str(name).strip(),
            "toolset": toolset,
            "input": self._redact(arguments, extra_keys=secret_input_keys),
            "runId": self.identity.runId,
            "userId": self.identity.userId,
            "projectId": self.identity.projectId,
            "route": self.identity.route,
            "callId": call_id,
            "idempotencyId": idempotency_id,
            "access": access,
        }
        executed = False
        cancellation_checkpoint(cancellation_token)
        if access in {"write", "idempotent"} and not (
            inspect.isawaitable(operation) or inspect.iscoroutinefunction(operation)
        ):
            return ToolRun(
                **base,
                status="failed",
                output={"message": "write tools must use an async handler"},
                errorType="SyncWriteToolRejected",
                executed=False,
            )
        if budget is not None:
            try:
                budget.consume_tool_call()
            except BudgetExceededError as exc:
                return ToolRun(
                    **base,
                    status="failed",
                    output={"message": self._redact_text(str(exc))},
                    errorType="BudgetExceededError",
                    executed=False,
                )
        await self._write_checkpoint(
            "TOOL_PREPARED",
            str(prepared_payload["semanticKey"]),
            prepared_payload,
        )
        try:
            cancellation_checkpoint(cancellation_token)
            executed = True
            if inspect.isawaitable(operation):
                result = operation
            else:
                sync_started_at = time.monotonic()
                sync_completed_at: list[float | None] = [None]
                sync_future = _SYNC_TOOL_EXECUTOR.submit(
                    contextvars.copy_context().run,
                    self._invoke,
                    operation,
                    arguments,
                )
                sync_future.add_done_callback(
                    lambda _future: sync_completed_at.__setitem__(0, time.monotonic())
                )
                try:
                    result = await cancellable_await(
                        asyncio.wrap_future(sync_future),
                        token=cancellation_token,
                        timeout=timeout,
                    )
                    if (
                        timeout is not None
                        and sync_completed_at[0] is not None
                        and sync_completed_at[0] - sync_started_at >= timeout
                    ):
                        raise TimeoutError("synchronous tool exceeded its deadline")
                except BaseException:
                    sync_future.add_done_callback(self._close_abandoned_awaitable)
                    raise
            if inspect.isawaitable(result):
                result = await cancellable_await(result, token=cancellation_token, timeout=timeout)
            cancellation_checkpoint(cancellation_token)
            return ToolRun(
                **base,
                status="succeeded",
                output=self._normalize_output(self._redact(result, extra_keys=secret_output_keys)),
                resultCount=self._result_count(result),
                executed=executed,
            )
        except TimeoutError:
            return ToolRun(
                **base,
                status="timed_out",
                output={"message": "tool timed out"},
                errorType="ToolTimeout",
                executed=executed,
            )
        except RunCancelledError as exc:
            return ToolRun(
                **base,
                status="cancelled",
                output={"message": self._redact_text(str(exc) or "run cancelled")},
                errorType="RunCancelledError",
                executed=executed,
            )
        except asyncio.CancelledError as exc:
            return ToolRun(
                **base,
                status="cancelled",
                output={"message": self._redact_text(str(exc) or "tool cancelled")},
                errorType="CancelledError",
                executed=executed,
            )
        except Exception as exc:
            return ToolRun(
                **base,
                status="failed",
                output={"message": self._redact_text(
                    str(exc) or exc.__class__.__name__,
                    extra_keys={*(secret_input_keys or ()), *(secret_output_keys or ())},
                )},
                errorType=exc.__class__.__name__,
                executed=executed,
            )

    def _invoke(self, operation: ToolOperation, arguments: dict[str, Any]) -> Any:
        if inspect.isawaitable(operation):
            return operation
        try:
            signature = inspect.signature(operation)
        except (TypeError, ValueError):
            return operation()
        accepts_arguments = any(
            parameter.kind in {parameter.POSITIONAL_ONLY, parameter.POSITIONAL_OR_KEYWORD, parameter.VAR_POSITIONAL}
            for parameter in signature.parameters.values()
        )
        return operation(arguments) if accepts_arguments else operation()

    def _close_abandoned_awaitable(self, future: ConcurrentFuture[Any]) -> None:
        try:
            value = future.result()
        except BaseException:
            return
        if inspect.iscoroutine(value):
            value.close()

    def _reuse(self, run: ToolRun, *, joined: bool) -> ToolRun:
        return run.model_copy(
            deep=True,
            update={
                "route": self.identity.route,
                "executed": False,
                "reused": True,
                "joined": joined,
            },
        )

    def _reuse_key(
        self,
        name: str,
        canonical_fingerprint: str,
        idempotency_id: str | None,
        reusable: bool,
    ) -> str | None:
        if not reusable:
            return None
        if idempotency_id is not None:
            return idempotency_id
        reuse_identity = {
            'scope': self.identity.dedupe_scope_key,
            'tool': self._normalize_tool_name(name),
            'fingerprint': canonical_fingerprint,
        }
        return f"tool_reuse_{self._digest(reuse_identity)}"

    def _semantic_key(
        self,
        name: str,
        call_id: str,
        canonical_fingerprint: str,
        tool_generation: int,
    ) -> str:
        semantic_identity = {
            "scope": self.identity.dedupe_scope_key,
            "tool": self._normalize_tool_name(name),
            "callId": call_id,
            "fingerprint": canonical_fingerprint,
            "generation": max(0, int(tool_generation)),
        }
        return f"tool_semantic_{self._digest(semantic_identity)}"

    async def _write_checkpoint(
        self,
        event_type: str,
        semantic_key: str,
        payload: dict[str, Any],
    ) -> None:
        if self._checkpoint_writer is None:
            return
        event_key = f"harness:{event_type.casefold()}:{semantic_key}"
        await self._checkpoint_writer(event_type, event_key, self._json_safe(payload))

    def _semantic_scope_matches(self, payload: dict[str, Any]) -> bool:
        return (
            str(payload.get("runId") or "").strip() == self.identity.runId
            and str(payload.get("userId") or "").strip() == self.identity.userId
        )

    def _restore_semantic_fingerprints(self, payload: dict[str, Any]) -> None:
        fingerprint = str(payload.get("fingerprint") or "").strip()
        idempotency_id = str(payload.get("idempotencyId") or "").strip()
        call_scope_key = str(payload.get("callScopeKey") or "").strip()
        if fingerprint and idempotency_id:
            self._state.idempotency_fingerprints.setdefault(idempotency_id, fingerprint)
        if fingerprint and call_scope_key:
            self._state.call_fingerprints.setdefault(call_scope_key, fingerprint)

    def _unknown_run(self, payload: dict[str, Any]) -> ToolRun:
        return ToolRun(
            name=str(payload.get("name") or "unknown").strip() or "unknown",
            toolset=str(payload.get("toolset") or "").strip() or None,
            status="unknown",
            output={"message": "tool outcome is unknown after interrupted execution"},
            errorType="ToolOutcomeUnknown",
            runId=self.identity.runId,
            userId=self.identity.userId,
            projectId=str(payload.get("projectId") or "").strip() or None,
            route=str(payload.get("route") or "").strip() or self.identity.route,
            callId=str(payload.get("callId") or "").strip() or None,
            idempotencyId=str(payload.get("idempotencyId") or "").strip() or None,
            access=str(payload.get("access") or "").strip() or None,
            executed=False,
        )

    def _safe_int(self, value: Any) -> int:
        try:
            return max(0, int(value or 0))
        except (TypeError, ValueError):
            return 0

    def _conflict_run(
        self,
        *,
        name: str,
        arguments: dict[str, Any],
        call_id: str,
        idempotency_id: str | None,
        access: ToolAccess,
        error_type: str,
        message: str,
        secret_input_keys: set[str] | tuple[str, ...] | list[str] | None,
    ) -> ToolRun:
        return ToolRun(
            name=str(name).strip(),
            status="failed",
            input=self._redact(arguments, extra_keys=secret_input_keys),
            output={"message": message},
            errorType=error_type,
            runId=self.identity.runId,
            userId=self.identity.userId,
            projectId=self.identity.projectId,
            route=self.identity.route,
            callId=call_id,
            idempotencyId=idempotency_id,
            access=access,
            executed=False,
        )

    def redact(
        self,
        value: Any,
        *,
        secret_keys: set[str] | tuple[str, ...] | list[str] | None = None,
    ) -> Any:
        return self._redact(value, extra_keys=secret_keys)

    def _normalize_output(self, value: Any) -> dict[str, Any]:
        serialized = self._json_safe(value)
        if isinstance(serialized, dict):
            return serialized
        if isinstance(serialized, list):
            return {"items": serialized}
        return {"value": serialized}

    def _result_count(self, value: Any) -> int:
        if isinstance(value, (list, tuple)):
            return len(value)
        if isinstance(value, dict):
            for key in ("items", "ranks", "books", "chapters", "analyses"):
                items = value.get(key)
                if isinstance(items, list):
                    return len(items)
        return 0 if value is None else 1

    def _redact(
        self,
        value: Any,
        *,
        extra_keys: set[str] | tuple[str, ...] | list[str] | None = None,
    ) -> Any:
        serialized = self._json_safe(value)
        normalized_extra_keys = {
            re.sub(r"[^a-z0-9]", "", str(key).casefold())
            for key in extra_keys or []
        }
        if isinstance(serialized, dict):
            return {
                str(key): "[redacted]"
                if self._is_sensitive_key(key) or re.sub(r"[^a-z0-9]", "", str(key).casefold()) in normalized_extra_keys
                else self._redact(item, extra_keys=extra_keys)
                for key, item in serialized.items()
            }
        if isinstance(serialized, list):
            return [self._redact(item, extra_keys=extra_keys) for item in serialized]
        if isinstance(serialized, str):
            return self._redact_text(serialized, extra_keys=extra_keys)
        return serialized

    def _redact_text(
        self,
        value: str,
        *,
        extra_keys: set[str] | tuple[str, ...] | list[str] | None = None,
    ) -> str:
        try:
            structured = json.loads(value)
        except (TypeError, ValueError, json.JSONDecodeError):
            structured = None
        if isinstance(structured, (dict, list)):
            return json.dumps(
                self._redact(structured, extra_keys=extra_keys),
                ensure_ascii=False,
                separators=(",", ":"),
            )

        redacted = _QUOTED_SECRET_TEXT.sub(
            lambda match: f"{match.group(1)}{match.group(2)}[redacted]{match.group(4)}",
            value,
        )
        redacted = _SECRET_TEXT.sub(lambda match: f"{match.group(1)}[redacted]", redacted)
        redacted = _BEARER_TEXT.sub("Bearer [redacted]", redacted)
        for key in extra_keys or ():
            escaped = re.escape(str(key))
            quoted_pattern = re.compile(
                rf"(?i)([\"']?{escaped}[\"']?\s*[:=]\s*)([\"'])(.*?)(\2)"
            )
            redacted = quoted_pattern.sub(
                lambda match: f"{match.group(1)}{match.group(2)}[redacted]{match.group(4)}",
                redacted,
            )
            unquoted_pattern = re.compile(
                rf"(?i)([\"']?{escaped}[\"']?\s*[:=]\s*)(?![\"'])([^\s,;}}\]]+)"
            )
            redacted = unquoted_pattern.sub(lambda match: f"{match.group(1)}[redacted]", redacted)
        return redacted

    def _is_sensitive_key(self, key: Any) -> bool:
        normalized = re.sub(r"[^a-z0-9]", "", str(key).casefold())
        return normalized in _SENSITIVE_KEYS

    def _normalize_access(self, access: str) -> ToolAccess:
        normalized = str(access).strip().casefold()
        if normalized not in {"read", "write", "idempotent"}:
            raise ValueError(f"unsupported tool access: {access}")
        return normalized  # type: ignore[return-value]

    def _normalize_tool_name(self, name: str) -> str:
        normalized = str(name).strip().casefold()
        if not normalized:
            raise ValueError("tool name must be non-empty")
        return normalized

    def _normalize_optional_id(self, value: Any | None) -> str | None:
        normalized = str(value).strip() if value is not None else ""
        return normalized or None

    def _digest(self, value: Any) -> str:
        canonical = json.dumps(
            self._json_safe(value),
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]

    def _json_safe(self, value: Any) -> Any:
        if hasattr(value, "model_dump") and callable(value.model_dump):
            return self._json_safe(value.model_dump(mode="json", exclude_none=False))
        if isinstance(value, Mapping):
            return {str(key): self._json_safe(item) for key, item in value.items()}
        if isinstance(value, (list, tuple)):
            return [self._json_safe(item) for item in value]
        if isinstance(value, (set, frozenset)):
            return sorted((self._json_safe(item) for item in value), key=repr)
        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        return repr(value)

    def _canonical_identity_arguments(self, value: Any) -> Any:
        ignored = {
            "contextsummary",
            "conversationid",
            "history",
            "idempotencykey",
            "projectid",
            "required",
            "runtimeconfig",
            "taskid",
            "toolroute",
            "tooltimeoutmillis",
            "userid",
        }
        if isinstance(value, Mapping):
            normalized: dict[str, Any] = {}
            for key, item in value.items():
                normalized_key = re.sub(r"[^a-z0-9]", "", str(key).casefold())
                if not normalized_key or normalized_key.startswith("expected") or normalized_key in ignored:
                    continue
                normalized[normalized_key] = self._canonical_identity_arguments(item)
            return normalized
        if isinstance(value, (list, tuple)):
            return [self._canonical_identity_arguments(item) for item in value]
        return self._json_safe(value)


_CURRENT_RUN_TOOL_LEDGER: ContextVar[RunToolLedger | None] = ContextVar(
    "harness_run_tool_ledger",
    default=None,
)


def current_run_tool_ledger() -> RunToolLedger | None:
    return _CURRENT_RUN_TOOL_LEDGER.get()


@contextmanager
def run_tool_ledger_scope(
    identity_or_ledger: RunToolIdentity | Mapping[str, Any] | RunToolLedger,
) -> Iterator[RunToolLedger]:
    ledger = (
        identity_or_ledger
        if isinstance(identity_or_ledger, RunToolLedger)
        else RunToolLedger(identity_or_ledger)
    )
    reset_token = _CURRENT_RUN_TOOL_LEDGER.set(ledger)
    try:
        yield ledger
    finally:
        try:
            _CURRENT_RUN_TOOL_LEDGER.reset(reset_token)
        except ValueError:
            pass
