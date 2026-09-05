from __future__ import annotations

import asyncio
from dataclasses import dataclass, field, replace
from enum import Enum
import hashlib
import httpx
import json
import random
import time
from typing import Any, Awaitable, Callable, Protocol
from uuid import uuid4

from app.services.harness.cancellation import cancellation_checkpoint, cancellable_await
from app.services.harness.contracts import AuthorizationDecision
from app.services.harness.context_compaction import (
    ContextCompactor,
    ProviderEnvelopeCompactionError,
    ProviderEnvelopeCompactionResult,
)
from app.services.harness.provider_dispatch_scope import current_provider_dispatch_scope
from app.services.harness.tool_ledger import current_run_tool_ledger


def build_logical_cache_affinity(
    *,
    conversation_id: str | None,
    trace_id: str | None,
    user_id: Any | None = None,
    project_id: Any | None = None,
) -> str | None:
    normalized_user = str(user_id or "").strip()
    normalized_project = str(project_id or "").strip()
    if normalized_project and normalized_user:
        scope = "project"
        identity = f"{normalized_user}:{normalized_project}"
    elif normalized_user:
        scope = "user"
        identity = normalized_user
    elif str(conversation_id or "").strip():
        scope = "conversation"
        identity = str(conversation_id).strip()
    else:
        scope = "trace"
        identity = str(trace_id or "").strip()
    if not identity:
        return None
    payload = json.dumps(
        {
            "scope": scope,
            "identity": identity,
            "userId": normalized_user or None,
            "projectId": normalized_project or None,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    return f"noval-cache-v1:{hashlib.sha256(payload.encode('utf-8')).hexdigest()}"


class KernelStopReason(str, Enum):
    COMPLETED = "completed"
    MAX_TURNS = "max_turns"
    TOOL_BUDGET = "tool_budget"
    TOKEN_BUDGET = "token_budget"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"
    UNAUTHORIZED_TOOL = "unauthorized_tool"
    HOOK_BLOCKED = "hook_blocked"
    ERROR = "error"
    # Protocol slot reserved for OPT-3 mid-run clarification; behavior not implemented in this wave.
    NEEDS_USER_INPUT = "needs_user_input"


class FailureUrgency(str, Enum):
    """How a provider failure should be handled by the retry/failover loop."""

    # Credential rejected: retrying the same key is pointless, switch immediately.
    INVALID_CREDENTIALS = "invalid_credentials"
    # Quota or rate limit hit: another key may still have headroom.
    INSUFFICIENT_QUOTA = "insufficient_quota"
    # Model missing on this upstream: another key may host it.
    MODEL_NOT_FOUND = "model_not_found"
    # Timeouts, connection resets, upstream 5xx: same key may recover.
    TRANSIENT = "transient"
    # Malformed request: neither retry nor failover can help.
    PERMANENT = "permanent"
    UNKNOWN = "unknown"


# Total attempts shared across same-key retries and cross-key failovers.
_DEFAULT_RETRY_BUDGET = 5
# Urgencies that mean "this key is unusable now" — switch key without spending budget.
_SWITCH_KEY_URGENCIES = frozenset(
    {
        FailureUrgency.INVALID_CREDENTIALS,
        FailureUrgency.INSUFFICIENT_QUOTA,
        FailureUrgency.MODEL_NOT_FOUND,
    }
)
_BACKOFF_BASE_SECONDS = 0.5
_BACKOFF_CAP_SECONDS = 60.0
_BACKOFF_JITTER_RATIO = 0.3
# Floor for the retry loop's wall-clock budget when no per-request timeout is known.
_MIN_RETRY_WINDOW_SECONDS = 10.0


@dataclass(slots=True)
class KernelMessage:
    role: str
    content: str = ""
    tool_call_id: str | None = None
    name: str | None = None
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    reasoning_content: str | None = None

    def to_openai(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"role": self.role, "content": self.content}
        if self.tool_call_id:
            payload["tool_call_id"] = self.tool_call_id
        if self.name:
            payload["name"] = self.name
        if self.tool_calls:
            payload["tool_calls"] = list(self.tool_calls)
        if self.reasoning_content:
            payload["reasoning_content"] = self.reasoning_content
        return payload


@dataclass(slots=True)
class KernelToolCall:
    id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class KernelToolObservation:
    tool_call_id: str
    name: str
    status: str
    content: str
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class KernelModelReply:
    content: str
    model_name: str | None = None
    tool_calls: list[KernelToolCall] = field(default_factory=list)
    token_used: int = 0
    reasoning_content: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class KernelEvent:
    type: str
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class ProviderAttemptTrace:
    """What the retry/failover loop had to do to land one provider response.

    ``failure_class`` is the last classified failure before the attempt that
    succeeded, so a run panel can show *why* the call moved off its first key.
    """

    attempt_index: int
    profile_key: str | None = None
    failure_class: str | None = None

    def trace_summary(self) -> dict[str, Any]:
        summary: dict[str, Any] = {"attemptIndex": max(1, self.attempt_index)}
        if self.profile_key:
            summary["profileKeyUsed"] = self.profile_key
        if self.failure_class:
            summary["failureClass"] = self.failure_class
        return summary


@dataclass(slots=True)
class KernelTurnRequest:
    messages: list[KernelMessage]
    model: str
    temperature: float | None = 0.2
    max_tokens: int | None = None
    reasoning_mode: str | None = None
    reasoning_effort: str | None = None
    require_json: bool = False
    timeout_millis: int | None = None
    cache_affinity: str | None = None
    request_family: str | None = None
    provider_profile: dict[str, Any] | None = None
    tool_schemas: list[dict[str, Any]] = field(default_factory=list)
    max_turns: int = 6
    max_tool_calls: int | None = None


@dataclass(slots=True)
class KernelResult:
    content: str
    stop_reason: KernelStopReason
    model_name: str | None = None
    token_used: int = 0
    tool_runs: list[dict[str, Any]] = field(default_factory=list)
    events: list[KernelEvent] = field(default_factory=list)
    messages: list[KernelMessage] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict)
    provider_calls: list[dict[str, Any]] = field(default_factory=list)

    def to_provider_result(self) -> dict[str, Any]:
        provider_calls = [dict(call) for call in self.provider_calls if isinstance(call, dict)]
        if provider_calls:
            provider_calls[-1]["kernelStopReason"] = self.stop_reason.value
        provider_request_count = len(provider_calls) or sum(
            1 for event in self.events if event.type == "message.start"
        )
        payload = {
            key: value
            for key, value in self.raw.items()
            if key not in {
                "content",
                "kernelStopReason",
                "kernelTurns",
                "kernelUsed",
                "model_name",
                "token_used",
                "toolRuns",
                "usage",
                "providerRequestCount",
                "kernelProviderCalls",
            }
        }
        payload.update({
            "model_name": self.model_name,
            "content": self.content,
            "token_used": self.token_used,
            "toolRuns": list(self.tool_runs),
            "kernelUsed": True,
            "kernelStopReason": self.stop_reason.value,
            "kernelTurns": provider_request_count,
            "providerRequestCount": provider_request_count,
            "kernelProviderCalls": provider_calls,
            "usage": self.raw.get("usage") if isinstance(self.raw.get("usage"), dict) else {},
        })
        return payload


ToolExecutor = Callable[[KernelToolCall], Awaitable[KernelToolObservation]]
ToolHook = Callable[[KernelToolCall, KernelToolObservation | None], Awaitable[KernelToolObservation | None] | KernelToolObservation | None]
ToolCallMessageFormatter = Callable[[KernelToolCall], dict[str, Any]]
SemanticCheckpointWriter = Callable[[str, str, dict[str, Any]], Awaitable[Any]]


class AgentKernel:
    """Minimal model-action-observation loop shared by main answer and specialists.

    Only AuthorizationDecision grants may appear as executable tools. Domain routers,
    skill registries and backend/MCP clients must stay outside this class.
    """

    def __init__(
        self,
        provider_client: Any,
        *,
        before_tool: ToolHook | None = None,
        after_tool: ToolHook | None = None,
        tool_call_message_formatter: ToolCallMessageFormatter | None = None,
        checkpoint_writer: SemanticCheckpointWriter | None = None,
        context_compactor: ContextCompactor | None = None,
    ) -> None:
        self.provider_client = provider_client
        self.before_tool = before_tool
        self.after_tool = after_tool
        self.tool_call_message_formatter = tool_call_message_formatter
        self.checkpoint_writer = checkpoint_writer
        self.context_compactor = context_compactor

    async def _resolve_provider_profile(
        self,
        model: str,
        route_snapshot: dict[str, Any] | None = None,
    ) -> Any | None:
        resolver = getattr(self.provider_client, "resolve_provider_profile", None)
        if not callable(resolver):
            if current_provider_dispatch_scope() is not None:
                raise RuntimeError("Provider client cannot resolve a frozen dispatch profile")
            return None
        scope = current_provider_dispatch_scope()
        if scope is not None:
            if scope.routing_enabled:
                dispatch = await scope.resolve_for_model(model)
            elif route_snapshot:
                dispatch = await scope.resolve(
                    str(route_snapshot.get("profileKey") or ""),
                    str(route_snapshot.get("profileVersion") or ""),
                    expected_route=route_snapshot,
                )
            else:
                dispatch = await scope.resolve_for_model(model)
            return resolver(
                model,
                route_snapshot=dispatch.route_snapshot(),
                api_key=dispatch.api_key,
            )
        if route_snapshot:
            return resolver(model, route_snapshot=route_snapshot)
        return resolver(model)

    def _failover_failure_class(self, error: BaseException) -> str | None:
        classifier = getattr(self.provider_client, "failover_failure_class", None)
        if not callable(classifier):
            return None
        value = classifier(error)
        return str(value or "").strip() or None

    @staticmethod
    def _classify_failure(error: BaseException) -> FailureUrgency:
        """Map a provider exception onto the retry/failover decision it warrants."""
        if isinstance(error, (httpx.ConnectError, httpx.TimeoutException)):
            return FailureUrgency.TRANSIENT
        if not isinstance(error, httpx.HTTPStatusError):
            return FailureUrgency.UNKNOWN
        response = getattr(error, "response", None)
        status = getattr(response, "status_code", None)
        if not isinstance(status, int):
            return FailureUrgency.UNKNOWN
        if status in {401, 403}:
            return FailureUrgency.INVALID_CREDENTIALS
        if status in {402, 429}:
            return FailureUrgency.INSUFFICIENT_QUOTA
        if status == 404:
            return FailureUrgency.MODEL_NOT_FOUND
        if status in {500, 502, 503, 504}:
            return FailureUrgency.TRANSIENT
        if status in {400, 422}:
            return FailureUrgency.PERMANENT
        return FailureUrgency.UNKNOWN

    @staticmethod
    def _backoff_seconds(attempt: int, error: BaseException) -> float:
        """Exponential backoff with jitter, floored by any Retry-After the upstream sent."""
        base = min(_BACKOFF_CAP_SECONDS, _BACKOFF_BASE_SECONDS * (2 ** max(0, attempt)))
        delay = base + base * random.uniform(0.0, _BACKOFF_JITTER_RATIO)
        if isinstance(error, httpx.HTTPStatusError):
            headers = getattr(getattr(error, "response", None), "headers", None)
            raw = None
            if headers is not None:
                try:
                    raw = headers.get("Retry-After")
                except AttributeError:
                    raw = None
            if isinstance(raw, str) and raw.strip().isdigit():
                return max(delay, float(int(raw.strip())))
        return delay

    @staticmethod
    def _profile_identity(profile: Any | None) -> tuple[str, str] | None:
        if profile is None:
            return None
        profile_key = str(getattr(profile, "profile_key", "") or "").strip()
        profile_version = str(getattr(profile, "profile_version", "") or "").strip()
        if not profile_key or not profile_version:
            return None
        return profile_key, profile_version

    @classmethod
    def _profile_key(cls, profile: Any | None) -> str | None:
        identity = cls._profile_identity(profile)
        return identity[0] if identity else None

    async def _report_provider_outcome(
        self,
        profile: Any | None,
        *,
        outcome: str,
        failure_class: str | None = None,
        switched: bool,
    ) -> None:
        scope = current_provider_dispatch_scope()
        identity = self._profile_identity(profile)
        if scope is None or identity is None:
            return
        dispatch = scope.current(*identity)
        if dispatch is None:
            return
        await scope.report_outcome(
            dispatch,
            outcome=outcome,
            failure_class=failure_class,
            switched=switched,
        )

    async def _claim_failover(
        self,
        profile: Any | None,
        error: BaseException,
    ) -> Any | None:
        """Claim the next profile in the ordered route list.

        Outcome reporting is the caller's job: the retry/failover loop knows the
        attempt index and whether a switch already happened, this method does not.
        """
        scope = current_provider_dispatch_scope()
        identity = self._profile_identity(profile)
        if scope is None or identity is None:
            return None
        if self._classify_failure(error) is FailureUrgency.PERMANENT:
            return None
        ledger = current_run_tool_ledger()
        if ledger is not None and ledger.has_external_side_effect():
            return None
        try:
            dispatch = await scope.claim_failover(*identity)
        except Exception:
            # Claiming resolves a fresh credential against the backend, which now
            # refuses OPEN profiles. Treat that as "no backup" so the caller re-raises
            # the real upstream error instead of a masking gate rejection.
            return None
        if dispatch is None:
            return None
        resolver = getattr(self.provider_client, "resolve_provider_profile", None)
        if not callable(resolver):
            raise RuntimeError("Provider client cannot resolve a frozen dispatch profile")
        return resolver(
            dispatch.model,
            route_snapshot=dispatch.route_snapshot(),
            api_key=dispatch.api_key,
        )

    async def _report_failure(
        self,
        profile: Any | None,
        error: BaseException,
        *,
        switched: bool,
    ) -> None:
        failure_class = self._failover_failure_class(error)
        if failure_class is None:
            return
        await self._report_provider_outcome(
            profile,
            outcome="TRANSIENT_FAILURE",
            failure_class=failure_class,
            switched=switched,
        )

    @staticmethod
    def _retry_window_seconds(kwargs: dict[str, Any]) -> float:
        """Wall-clock budget for the whole retry loop: one provider timeout.

        The caller (backend) applies its own HTTP timeout to this worker, and both
        default to the same value. Spending five full provider timeouts on retries
        would therefore burn work nobody is still waiting for. Capping cumulative
        retry time at one timeout keeps the budget useful for fast failures — refused
        connections, 401/403, 404, 429, quick 5xx — which is what relays actually emit.
        """
        raw = kwargs.get("timeout_millis")
        try:
            seconds = float(raw) / 1000.0 if raw else 0.0
        except (TypeError, ValueError):
            seconds = 0.0
        return max(_MIN_RETRY_WINDOW_SECONDS, seconds)

    async def _next_retry_step(
        self,
        profile: Any | None,
        error: BaseException,
        *,
        switched: bool,
        remaining: int,
        attempt: int,
        deadline: float,
    ) -> tuple[Any | None, int, bool] | None:
        """Decide what happens after a failed provider attempt.

        Returns the ``(profile, remaining, switched)`` to continue with, or None when
        the caller should give up and re-raise the original error.
        """
        urgency = self._classify_failure(error)
        await self._report_failure(profile, error, switched=switched)
        if urgency is FailureUrgency.PERMANENT:
            return None
        if time.monotonic() >= deadline:
            return None
        if urgency in _SWITCH_KEY_URGENCIES:
            backup = await self._claim_failover(profile, error)
            return None if backup is None else (backup, remaining, True)
        remaining -= 1
        if remaining <= 0:
            # Budget gone on this key; give the next one a single parting attempt.
            backup = await self._claim_failover(profile, error)
            return None if backup is None else (backup, 1, True)
        await cancellable_await(asyncio.sleep(self._backoff_seconds(attempt - 1, error)))
        return profile, remaining, switched

    async def _invoke_provider_with_retry_and_failover(
        self,
        provider_kwargs: dict[str, Any],
        provider_profile: Any | None,
        budget: int = _DEFAULT_RETRY_BUDGET,
    ) -> tuple[dict[str, Any], Any | None, ProviderAttemptTrace]:
        """Run one provider call under a shared retry + failover budget.

        The budget counts transient retries only. Credential/quota/model failures
        switch key immediately without spending it, so a long ordered key list is
        still fully traversed even when every key is misconfigured.
        """

        async def invoke(profile: Any | None) -> dict[str, Any]:
            if profile is not None:
                return await self.provider_client.invoke(
                    **provider_kwargs,
                    provider_profile=profile,
                )
            return await self.provider_client.invoke(**provider_kwargs)

        active_profile = provider_profile
        switched = False
        attempt = 0
        failure_class: str | None = None
        remaining = max(1, int(budget))
        deadline = time.monotonic() + self._retry_window_seconds(provider_kwargs)
        while True:
            attempt += 1
            cancellation_checkpoint()
            try:
                result = await invoke(active_profile)
            except Exception as error:
                failure_class = self._failover_failure_class(error) or failure_class
                step = await self._next_retry_step(
                    active_profile,
                    error,
                    switched=switched,
                    remaining=remaining,
                    attempt=attempt,
                    deadline=deadline,
                )
                if step is None:
                    raise
                active_profile, remaining, switched = step
                continue
            await self._report_provider_outcome(
                active_profile,
                outcome="SUCCEEDED",
                switched=switched,
            )
            return result, active_profile, ProviderAttemptTrace(
                attempt_index=attempt,
                profile_key=self._profile_key(active_profile),
                failure_class=failure_class,
            )

    async def _stream_provider_with_failover(
        self,
        stream_fn: Callable[..., Any],
        stream_kwargs: dict[str, Any],
        provider_profile: Any | None,
        budget: int = _DEFAULT_RETRY_BUDGET,
    ):
        """Stream under the same budget, but only until the first provider event.

        Once a delta has reached the caller the stream is no longer replayable, so a
        later failure must surface instead of silently restarting on another key.
        """
        active_profile = provider_profile
        switched = False
        attempt = 0
        failure_class: str | None = None
        remaining = max(1, int(budget))
        deadline = time.monotonic() + self._retry_window_seconds(stream_kwargs)
        while True:
            attempt += 1
            provider_event_seen = False
            cancellation_checkpoint()
            provider_stream = (
                stream_fn(**stream_kwargs, provider_profile=active_profile)
                if active_profile is not None
                else stream_fn(**stream_kwargs)
            )
            try:
                async for provider_event in provider_stream:
                    if not provider_event_seen:
                        # The first delta freezes the attempt: no further switch is
                        # possible, so the trace it carries is already final.
                        trace = ProviderAttemptTrace(
                            attempt_index=attempt,
                            profile_key=self._profile_key(active_profile),
                            failure_class=failure_class,
                        )
                    provider_event_seen = True
                    yield provider_event, active_profile, trace
            except Exception as error:
                if provider_event_seen:
                    raise
                failure_class = self._failover_failure_class(error) or failure_class
                step = await self._next_retry_step(
                    active_profile,
                    error,
                    switched=switched,
                    remaining=remaining,
                    attempt=attempt,
                    deadline=deadline,
                )
                if step is None:
                    raise
                active_profile, remaining, switched = step
                continue
            await self._report_provider_outcome(
                active_profile,
                outcome="SUCCEEDED",
                switched=switched,
            )
            return

    @staticmethod
    def _provider_profile_snapshot(profile: Any | None) -> dict[str, Any] | None:
        snapshot_fn = getattr(profile, "snapshot", None)
        if not callable(snapshot_fn):
            return None
        raw = snapshot_fn()
        if not isinstance(raw, dict):
            return None
        allowed = {
            "profileKey",
            "profileVersion",
            "endpoint",
            "endpointFingerprint",
            "model",
            "protocol",
        }
        snapshot: dict[str, Any] = {
            str(key): str(value)
            for key, value in raw.items()
            if key in allowed and value is not None
        }
        capabilities = raw.get("providerCapabilities")
        if isinstance(capabilities, dict):
            capability_keys = {
                "schemaVersion",
                "supportsStreaming",
                "supportsTools",
                "supportsJsonObject",
                "supportsReasoning",
                "reportsUsage",
                "reportsCacheUsage",
            }
            snapshot["providerCapabilities"] = {
                str(key): value
                for key, value in capabilities.items()
                if key in capability_keys and (type(value) is bool or key == "schemaVersion")
            }
        return snapshot

    @staticmethod
    def _assert_provider_profile_capabilities(
        profile: Any | None,
        *,
        stream: bool = False,
        tools: bool = False,
        require_json: bool = False,
        reasoning_mode: str | None = None,
    ) -> None:
        guard = getattr(profile, "assert_supports_request", None)
        if callable(guard):
            guard(
                stream=stream,
                tools=tools,
                require_json=require_json,
                reasoning_mode=reasoning_mode,
            )

    def allowed_tool_names(self, authorization: AuthorizationDecision | dict[str, Any] | None) -> set[str]:
        if isinstance(authorization, AuthorizationDecision):
            return {grant.toolName for grant in authorization.grants}
        if not isinstance(authorization, dict):
            return set()
        grants = authorization.get("grants") or []
        names: set[str] = set()
        for grant in grants:
            if isinstance(grant, dict):
                name = str(grant.get("toolName") or "").strip()
                if name:
                    names.add(name)
        return names

    async def run(
        self,
        request: KernelTurnRequest,
        *,
        authorization: AuthorizationDecision | dict[str, Any] | None = None,
        tool_executor: ToolExecutor | None = None,
        tool_call_message_formatter: ToolCallMessageFormatter | None = None,
    ) -> KernelResult:
        working = [
            KernelMessage(
                role=m.role,
                content=m.content,
                tool_call_id=m.tool_call_id,
                name=m.name,
                tool_calls=list(m.tool_calls),
                reasoning_content=m.reasoning_content,
            )
            for m in request.messages
        ]
        events: list[KernelEvent] = []
        tool_runs: list[dict[str, Any]] = []
        provider_calls: list[dict[str, Any]] = []
        allowed = self.allowed_tool_names(authorization)
        remaining_tools = None if request.max_tool_calls is None else max(0, int(request.max_tool_calls))
        total_tokens = 0
        last_raw: dict[str, Any] = {}
        model_name = request.model
        content = ""

        schemas = self._canonical_tool_schemas(
            schema
            for schema in list(request.tool_schemas or [])
            if self._schema_name(schema) in allowed
        )

        max_turns = max(1, int(request.max_turns or 1))
        provider_profile = await self._resolve_provider_profile(request.model, request.provider_profile)
        provider_profile_snapshot = self._provider_profile_snapshot(provider_profile)
        self._assert_provider_profile_capabilities(
            provider_profile,
            require_json=request.require_json,
            reasoning_mode=request.reasoning_mode,
        )
        for turn in range(max_turns):
            cancellation_checkpoint()
            tools_enabled = bool(
                schemas
                and tool_executor is not None
                and (remaining_tools is None or remaining_tools > 0)
            )
            self._assert_provider_profile_capabilities(
                provider_profile,
                tools=tools_enabled,
            )
            dispatch_schemas = schemas if tools_enabled else []
            working, provider_compaction = self._prepare_provider_messages(
                working,
                request=request,
                tool_schemas=dispatch_schemas,
            )
            provider_kwargs: dict[str, Any] = {
                "messages": [message.to_openai() for message in working],
                "model": request.model,
                "temperature": request.temperature,
                "max_tokens": request.max_tokens,
                "require_json": request.require_json,
                "reasoning_mode": request.reasoning_mode,
                "reasoning_effort": request.reasoning_effort,
            }
            if request.cache_affinity:
                provider_kwargs["cache_affinity"] = request.cache_affinity
            if request.request_family:
                provider_kwargs["request_family"] = request.request_family
            if request.timeout_millis is not None:
                provider_kwargs["timeout_millis"] = request.timeout_millis
            if tools_enabled:
                provider_kwargs["tools"] = schemas
            request_summary = self._request_summary(
                working,
                dispatch_schemas,
                reasoning_mode=request.reasoning_mode,
                reasoning_effort=request.reasoning_effort,
                cache_affinity=request.cache_affinity,
                request_family=request.request_family,
            )
            if provider_profile_snapshot:
                request_summary["providerProfile"] = provider_profile_snapshot
            if provider_compaction is not None:
                request_summary["contextCompaction"] = provider_compaction.trace_summary()
                if provider_compaction.compacted:
                    events.append(KernelEvent(
                        type="context.compacted",
                        payload=provider_compaction.trace_summary(),
                    ))
            events.append(KernelEvent(type="message.start", payload={"turn": turn}))
            model_checkpoint = self._model_checkpoint_payload(
                provider_kwargs,
                turn=turn,
                transport="invoke",
                request_summary=request_summary,
            )
            # Provider failures propagate so callers keep existing error handling.
            prepared_source_event = await self._write_model_checkpoint(
                "MODEL_PREPARED",
                model_checkpoint,
            )
            model_checkpoint = self._with_compaction_source_event(
                model_checkpoint,
                prepared_source_event,
            )
            request_summary = dict(model_checkpoint["requestSummary"])
            provider_started_at = time.perf_counter()
            raw, provider_profile, attempt_trace = await self._invoke_provider_with_retry_and_failover(
                provider_kwargs,
                provider_profile,
            )
            provider_profile_snapshot = self._provider_profile_snapshot(provider_profile)
            last_raw = dict(raw or {})
            await self._write_model_checkpoint(
                "MODEL_COMMITTED",
                self._model_committed_checkpoint_payload(model_checkpoint, last_raw),
            )

            reply = self._parse_model_reply(last_raw, fallback_model=request.model)
            usage = last_raw.get("usage") if isinstance(last_raw.get("usage"), dict) else {}
            provider_call = {
                "kernelTurn": turn + 1,
                "transport": "invoke",
                "status": "succeeded",
                "model": reply.model_name or request.model,
                "durationMs": max(1, int((time.perf_counter() - provider_started_at) * 1000)),
                "tokenUsed": max(0, int(reply.token_used or 0)),
                "toolCallCount": len(reply.tool_calls),
                "kernelStopReason": "tool_calls" if reply.tool_calls else KernelStopReason.COMPLETED.value,
                "emptyResponse": not bool(reply.content or reply.tool_calls),
                "usage": dict(usage),
                "promptCacheHitTokens": self._non_negative_int_from_sources(
                    (usage, last_raw),
                    "promptCacheHitTokens",
                    "prompt_cache_hit_tokens",
                ),
                "promptCacheMissTokens": self._non_negative_int_from_sources(
                    (usage, last_raw),
                    "promptCacheMissTokens",
                    "prompt_cache_miss_tokens",
                ),
                "promptCacheWriteTokens": self._non_negative_int_from_sources(
                    (usage, last_raw),
                    "promptCacheWriteTokens",
                    "prompt_cache_write_tokens",
                ),
                "promptCacheMissTokensDerived": self._first_bool_from_sources(
                    (usage, last_raw),
                    "promptCacheMissTokensDerived",
                    "prompt_cache_miss_tokens_derived",
                ),
                "requestSummary": request_summary,
                "responseSummary": self._response_summary(
                    reply.content,
                    reply.tool_calls,
                ),
                **attempt_trace.trace_summary(),
            }
            cache_continuity = self._sanitize_cache_continuity(
                last_raw.get("cacheContinuity")
            )
            if cache_continuity:
                provider_call["cacheContinuity"] = self._cache_continuity_trace_summary(
                    cache_continuity
                )
            wire_api = str(last_raw.get("wire_api") or "").strip()
            if wire_api:
                provider_call["wireApi"] = wire_api
            transport_fallback = last_raw.get("providerTransportFallback")
            if isinstance(transport_fallback, dict):
                provider_call["providerTransportFallback"] = dict(transport_fallback)
            raw_profile = last_raw.get("providerProfile")
            if isinstance(raw_profile, dict):
                provider_call["providerProfile"] = dict(raw_profile)
            provider_calls.append(provider_call)
            model_name = reply.model_name or model_name
            total_tokens += max(0, int(reply.token_used or 0))
            content = reply.content
            events.append(KernelEvent(type="message.delta", payload={"turn": turn, "content": content}))
            events.append(KernelEvent(type="message.end", payload={"turn": turn, "toolCalls": len(reply.tool_calls)}))

            if not reply.tool_calls:
                events.append(KernelEvent(type="turn.end", payload={"turn": turn, "stopReason": KernelStopReason.COMPLETED.value}))
                return KernelResult(
                    content=content,
                    stop_reason=KernelStopReason.COMPLETED,
                    model_name=model_name,
                    token_used=total_tokens,
                    tool_runs=tool_runs,
                    events=events,
                    messages=working,
                    raw=last_raw,
                    provider_calls=provider_calls,
                )

            if tool_executor is None:
                events.append(KernelEvent(type="turn.end", payload={"turn": turn, "stopReason": KernelStopReason.COMPLETED.value}))
                return KernelResult(
                    content=content,
                    stop_reason=KernelStopReason.COMPLETED,
                    model_name=model_name,
                    token_used=total_tokens,
                    tool_runs=tool_runs,
                    events=events,
                    messages=working,
                    raw=last_raw,
                    provider_calls=provider_calls,
                )

            working.append(
                KernelMessage(
                    role="assistant",
                    content=reply.content,
                    tool_calls=[
                        self._tool_call_message(
                            call,
                            formatter=tool_call_message_formatter,
                        )
                        for call in reply.tool_calls
                    ],
                    reasoning_content=reply.reasoning_content,
                )
            )

            for call in reply.tool_calls:
                if remaining_tools is not None and remaining_tools <= 0:
                    observation = KernelToolObservation(
                        tool_call_id=call.id,
                        name=call.name,
                        status="failed",
                        content="tool budget exhausted before execution",
                        raw={
                            "error": "tool budget exhausted before execution",
                            "errorType": "ToolBudgetExceeded",
                            "input": dict(call.arguments),
                        },
                    )
                    tool_runs.append(self._tool_run_dict(observation))
                    working.append(
                        KernelMessage(
                            role="tool",
                            content=observation.content,
                            tool_call_id=call.id,
                            name=call.name,
                        )
                    )
                    events.append(KernelEvent(type="tool.end", payload={"name": call.name, "status": "failed"}))
                    continue
                if call.name not in allowed:
                    observation = KernelToolObservation(
                        tool_call_id=call.id,
                        name=call.name,
                        status="denied",
                        content="tool not authorized by AuthorizationDecision",
                        raw={"error": "unauthorized_tool"},
                    )
                    tool_runs.append(self._tool_run_dict(observation))
                    working.append(
                        KernelMessage(
                            role="tool",
                            content=observation.content,
                            tool_call_id=call.id,
                            name=call.name,
                        )
                    )
                    events.append(KernelEvent(type="tool.end", payload={"name": call.name, "status": "denied"}))
                    events.append(KernelEvent(type="turn.end", payload={"turn": turn, "stopReason": KernelStopReason.UNAUTHORIZED_TOOL.value}))
                    return KernelResult(
                        content=content,
                        stop_reason=KernelStopReason.UNAUTHORIZED_TOOL,
                        model_name=model_name,
                        token_used=total_tokens,
                        tool_runs=tool_runs,
                        events=events,
                        messages=working,
                        raw=last_raw,
                        provider_calls=provider_calls,
                    )

                events.append(KernelEvent(type="tool.start", payload={"name": call.name, "id": call.id}))
                observation: KernelToolObservation | None = None
                if self.before_tool is not None:
                    hooked = await self._maybe_await(self.before_tool(call, None))
                    if hooked is None:
                        events.append(KernelEvent(type="turn.end", payload={"turn": turn, "stopReason": KernelStopReason.HOOK_BLOCKED.value}))
                        return KernelResult(
                            content=content,
                            stop_reason=KernelStopReason.HOOK_BLOCKED,
                            model_name=model_name,
                            token_used=total_tokens,
                            tool_runs=tool_runs,
                            events=events,
                            messages=working,
                            raw=last_raw,
                            provider_calls=provider_calls,
                        )
                    if isinstance(hooked, KernelToolObservation):
                        observation = hooked
                if observation is None:
                    observation = await tool_executor(call)
                if self.after_tool is not None:
                    hooked = await self._maybe_await(self.after_tool(call, observation))
                    if isinstance(hooked, KernelToolObservation):
                        observation = hooked
                assert observation is not None
                if remaining_tools is not None:
                    remaining_tools -= 1
                tool_runs.append(self._tool_run_dict(observation))
                working.append(
                    KernelMessage(
                        role="tool",
                        content=observation.content,
                        tool_call_id=observation.tool_call_id,
                        name=observation.name,
                    )
                )
                events.append(KernelEvent(type="tool.update", payload={"name": call.name, "status": observation.status}))
                events.append(KernelEvent(type="tool.end", payload={"name": call.name, "status": observation.status}))
                if observation.status == "cancelled":
                    events.append(KernelEvent(type="turn.end", payload={"turn": turn, "stopReason": KernelStopReason.CANCELLED.value}))
                    return KernelResult(
                        content=content,
                        stop_reason=KernelStopReason.CANCELLED,
                        model_name=model_name,
                        token_used=total_tokens,
                        tool_runs=tool_runs,
                        events=events,
                        messages=working,
                        raw=last_raw,
                        provider_calls=provider_calls,
                    )
                if str(observation.raw.get("errorType") or "") in {
                    "BudgetExceededError",
                    "ToolBudgetExceeded",
                }:
                    remaining_tools = 0

        events.append(KernelEvent(type="turn.end", payload={"stopReason": KernelStopReason.MAX_TURNS.value}))
        return KernelResult(
            content=content,
            stop_reason=KernelStopReason.MAX_TURNS,
            model_name=model_name,
            token_used=total_tokens,
            tool_runs=tool_runs,
            events=events,
            messages=working,
            raw=last_raw,
            provider_calls=provider_calls,
        )

    async def stream(
        self,
        request: KernelTurnRequest,
        *,
        authorization: AuthorizationDecision | dict[str, Any] | None = None,
        tool_executor: ToolExecutor | None = None,
    ):
        allowed = self.allowed_tool_names(authorization)
        schemas = self._canonical_tool_schemas(
            schema
            for schema in list(request.tool_schemas or [])
            if self._schema_name(schema) in allowed
        )
        stream_fn = getattr(self.provider_client, "stream", None)
        provider_profile = await self._resolve_provider_profile(request.model, request.provider_profile)
        provider_profile_snapshot = self._provider_profile_snapshot(provider_profile)
        stream_provider_call: dict[str, Any] | None = None
        if callable(stream_fn) and not (schemas and tool_executor is not None):
            self._assert_provider_profile_capabilities(
                provider_profile,
                stream=True,
                require_json=request.require_json,
                reasoning_mode=request.reasoning_mode,
            )
            effective_messages, provider_compaction = self._prepare_provider_messages(
                list(request.messages),
                request=request,
                tool_schemas=[],
            )
            if effective_messages != request.messages:
                request = replace(request, messages=effective_messages)
            chunks: list[str] = []
            token_used = 0
            usage: dict[str, Any] = {}
            cache_hit_tokens = 0
            cache_miss_tokens = 0
            cache_write_tokens = 0
            cache_miss_tokens_derived = False
            wire_api = ""
            transport_fallback: dict[str, Any] | None = None
            cache_continuity: dict[str, Any] | None = None
            turn = 0
            request_summary = self._request_summary(
                list(request.messages),
                [],
                reasoning_mode=request.reasoning_mode,
                reasoning_effort=request.reasoning_effort,
                cache_affinity=request.cache_affinity,
                request_family=request.request_family,
            )
            if provider_profile_snapshot:
                request_summary["providerProfile"] = provider_profile_snapshot
            if provider_compaction is not None:
                request_summary["contextCompaction"] = provider_compaction.trace_summary()
                if provider_compaction.compacted:
                    yield KernelEvent(
                        type="context.compacted",
                        payload=provider_compaction.trace_summary(),
                    )
            yield KernelEvent(type="message.start", payload={"turn": turn})
            stream_started_at = time.perf_counter()
            stream_kwargs: dict[str, Any] = {
                "messages": [message.to_openai() for message in request.messages],
                "model": request.model,
                "temperature": request.temperature,
                "max_tokens": request.max_tokens,
                "require_json": request.require_json,
                "reasoning_mode": request.reasoning_mode,
                "reasoning_effort": request.reasoning_effort,
            }
            if request.cache_affinity:
                stream_kwargs["cache_affinity"] = request.cache_affinity
            if request.request_family:
                stream_kwargs["request_family"] = request.request_family
            if request.timeout_millis is not None:
                stream_kwargs["timeout_millis"] = request.timeout_millis
            model_checkpoint = self._model_checkpoint_payload(
                stream_kwargs,
                turn=turn,
                transport="stream",
                request_summary=request_summary,
            )
            prepared_source_event = await self._write_model_checkpoint(
                "MODEL_PREPARED",
                model_checkpoint,
            )
            model_checkpoint = self._with_compaction_source_event(
                model_checkpoint,
                prepared_source_event,
            )
            request_summary = dict(model_checkpoint["requestSummary"])
            attempt_trace = ProviderAttemptTrace(attempt_index=1)
            async for provider_event, active_profile, event_trace in self._stream_provider_with_failover(
                stream_fn,
                stream_kwargs,
                provider_profile,
            ):
                attempt_trace = event_trace
                provider_profile = active_profile
                provider_profile_snapshot = self._provider_profile_snapshot(provider_profile)
                cancellation_checkpoint()
                event_type = str(provider_event.get("event") or "")
                if event_type == "delta":
                    content = str(provider_event.get("delta") or "")
                    if content:
                        chunks.append(content)
                        yield KernelEvent(
                            type="message.delta",
                            payload={"turn": turn, "content": content},
                        )
                elif event_type == "done":
                    token_used = max(0, int(provider_event.get("tokenUsed") or 0))
                    if isinstance(provider_event.get("usage"), dict):
                        usage = dict(provider_event["usage"])
                    cache_hit_tokens = max(
                        0,
                        self._non_negative_int_from_sources(
                            (provider_event, usage),
                            "promptCacheHitTokens",
                            "prompt_cache_hit_tokens",
                        ),
                    )
                    cache_miss_tokens = max(
                        0,
                        self._non_negative_int_from_sources(
                            (provider_event, usage),
                            "promptCacheMissTokens",
                            "prompt_cache_miss_tokens",
                        ),
                    )
                    cache_write_tokens = max(
                        0,
                        self._non_negative_int_from_sources(
                            (provider_event, usage),
                            "promptCacheWriteTokens",
                            "prompt_cache_write_tokens",
                        ),
                    )
                    cache_miss_tokens_derived = self._first_bool_from_sources(
                        (provider_event, usage),
                        "promptCacheMissTokensDerived",
                        "prompt_cache_miss_tokens_derived",
                    )
                    wire_api = str(provider_event.get("wireApi") or "").strip()
                    raw_transport_fallback = provider_event.get("providerTransportFallback")
                    if isinstance(raw_transport_fallback, dict):
                        transport_fallback = dict(raw_transport_fallback)
                    cache_continuity = self._sanitize_cache_continuity(
                        provider_event.get("cacheContinuity")
                    )
                    raw_profile = provider_event.get("providerProfile")
                    if isinstance(raw_profile, dict):
                        provider_profile_snapshot = dict(raw_profile)

            cancellation_checkpoint()
            await self._write_model_checkpoint(
                "MODEL_COMMITTED",
                self._model_committed_checkpoint_payload(
                    model_checkpoint,
                    {
                        "tokenUsed": token_used,
                        "usage": usage,
                        "promptCacheHitTokens": cache_hit_tokens,
                        "promptCacheMissTokens": cache_miss_tokens,
                        "promptCacheWriteTokens": cache_write_tokens,
                        "promptCacheMissTokensDerived": cache_miss_tokens_derived,
                        "wireApi": wire_api,
                        "cacheContinuity": cache_continuity,
                        "providerProfile": provider_profile_snapshot,
                    },
                ),
            )

            content = "".join(chunks).strip()
            stream_provider_call = {
                "kernelTurn": 1,
                "transport": "stream",
                "status": "succeeded",
                "model": request.model,
                "durationMs": max(1, int((time.perf_counter() - stream_started_at) * 1000)),
                "tokenUsed": token_used,
                "toolCallCount": 0,
                "kernelStopReason": (
                    KernelStopReason.COMPLETED.value if content else "empty_response"
                ),
                "emptyResponse": not bool(content),
                "usage": dict(usage),
                "promptCacheHitTokens": cache_hit_tokens,
                "promptCacheMissTokens": cache_miss_tokens,
                "promptCacheWriteTokens": cache_write_tokens,
                "promptCacheMissTokensDerived": cache_miss_tokens_derived,
                "requestSummary": request_summary,
                "responseSummary": self._response_summary(content, []),
                **attempt_trace.trace_summary(),
            }
            if wire_api:
                stream_provider_call["wireApi"] = wire_api
            if cache_continuity:
                stream_provider_call["cacheContinuity"] = self._cache_continuity_trace_summary(
                    cache_continuity
                )
            if transport_fallback:
                stream_provider_call["providerTransportFallback"] = transport_fallback
            if provider_profile_snapshot:
                stream_provider_call["providerProfile"] = provider_profile_snapshot
            if content:
                yield KernelEvent(
                    type="message.end",
                    payload={"turn": turn, "toolCalls": 0},
                )
                yield KernelEvent(
                    type="turn.end",
                    payload={"turn": turn, "stopReason": KernelStopReason.COMPLETED.value},
                )
                yield KernelEvent(
                    type="result",
                    payload={
                        "content": content,
                        "stopReason": KernelStopReason.COMPLETED.value,
                        "tokenUsed": token_used,
                        "modelName": request.model,
                        "kernelUsed": True,
                        "kernelTurns": 1,
                        "providerRequestCount": 1,
                        "kernelProviderCalls": [stream_provider_call],
                        "usage": usage,
                        "promptCacheHitTokens": cache_hit_tokens,
                        "promptCacheMissTokens": cache_miss_tokens,
                        "promptCacheWriteTokens": cache_write_tokens,
                        "promptCacheMissTokensDerived": cache_miss_tokens_derived,
                    },
                )
                return
            yield KernelEvent(
                type="message.end",
                payload={"turn": turn, "toolCalls": 0, "emptyResponse": True},
            )
            yield KernelEvent(
                type="turn.end",
                payload={"turn": turn, "stopReason": "empty_response"},
            )

        result = await self.run(request, authorization=authorization, tool_executor=tool_executor)
        projection = result.to_provider_result()
        provider_calls = []
        turn_offset = 0
        if stream_provider_call is not None:
            provider_calls.append(dict(stream_provider_call))
            turn_offset = 1
        for provider_call in list(projection.get("kernelProviderCalls") or []):
            if not isinstance(provider_call, dict):
                continue
            shifted = dict(provider_call)
            shifted["kernelTurn"] = max(1, int(shifted.get("kernelTurn") or 1)) + turn_offset
            provider_calls.append(shifted)
        for event in result.events:
            payload = dict(event.payload)
            if turn_offset and isinstance(payload.get("turn"), int):
                payload["turn"] = int(payload["turn"]) + turn_offset
            yield KernelEvent(type=event.type, payload=payload)
        stream_token_used = max(0, int((stream_provider_call or {}).get("tokenUsed") or 0))
        stream_cache_hit_tokens = max(
            0,
            int((stream_provider_call or {}).get("promptCacheHitTokens") or 0),
        )
        stream_cache_miss_tokens = max(
            0,
            int((stream_provider_call or {}).get("promptCacheMissTokens") or 0),
        )
        stream_cache_write_tokens = max(
            0,
            int((stream_provider_call or {}).get("promptCacheWriteTokens") or 0),
        )
        yield KernelEvent(
            type="result",
            payload={
                "content": projection.get("content") or "",
                "stopReason": projection.get("kernelStopReason") or result.stop_reason.value,
                "tokenUsed": stream_token_used + int(projection.get("token_used") or 0),
                "modelName": projection.get("model_name") or result.model_name,
                "kernelUsed": True,
                "kernelTurns": len(provider_calls),
                "providerRequestCount": len(provider_calls),
                "kernelProviderCalls": provider_calls,
                "usage": projection.get("usage") if isinstance(projection.get("usage"), dict) else {},
                "promptCacheHitTokens": stream_cache_hit_tokens + self._non_negative_int_from_sources(
                    (
                        result.raw.get("usage")
                        if isinstance(result.raw.get("usage"), dict)
                        else {},
                        result.raw,
                    ),
                    "promptCacheHitTokens",
                    "prompt_cache_hit_tokens",
                ),
                "promptCacheMissTokens": stream_cache_miss_tokens + self._non_negative_int_from_sources(
                    (
                        result.raw.get("usage")
                        if isinstance(result.raw.get("usage"), dict)
                        else {},
                        result.raw,
                    ),
                    "promptCacheMissTokens",
                    "prompt_cache_miss_tokens",
                ),
                "promptCacheWriteTokens": stream_cache_write_tokens + self._non_negative_int_from_sources(
                    (
                        result.raw.get("usage")
                        if isinstance(result.raw.get("usage"), dict)
                        else {},
                        result.raw,
                    ),
                    "promptCacheWriteTokens",
                    "prompt_cache_write_tokens",
                ),
                "promptCacheMissTokensDerived": self._first_bool_from_sources(
                    (
                        stream_provider_call or {},
                        result.raw.get("usage")
                        if isinstance(result.raw.get("usage"), dict)
                        else {},
                        result.raw,
                    ),
                    "promptCacheMissTokensDerived",
                    "prompt_cache_miss_tokens_derived",
                ),
            },
        )

    def _parse_model_reply(self, raw: dict[str, Any], *, fallback_model: str) -> KernelModelReply:
        tool_calls_raw = raw.get("tool_calls") or raw.get("raw_tool_calls") or []
        normalized: list[KernelToolCall] = []
        if isinstance(tool_calls_raw, list):
            for index, item in enumerate(tool_calls_raw):
                if not isinstance(item, dict):
                    continue
                # provider may already normalize to {name, arguments, id}
                name = str(item.get("name") or ((item.get("function") or {}).get("name") if isinstance(item.get("function"), dict) else "") or "").strip()
                if not name:
                    continue
                arguments = item.get("arguments")
                if isinstance(arguments, str):
                    import json
                    try:
                        arguments = json.loads(arguments or "{}")
                    except Exception:
                        arguments = {}
                if isinstance(item.get("function"), dict) and not isinstance(arguments, dict):
                    import json
                    raw_args = item["function"].get("arguments")
                    if isinstance(raw_args, str):
                        try:
                            arguments = json.loads(raw_args or "{}")
                        except Exception:
                            arguments = {}
                    elif isinstance(raw_args, dict):
                        arguments = raw_args
                if not isinstance(arguments, dict):
                    arguments = {}
                call_id = str(
                    item.get("call_id")
                    or item.get("id")
                    or item.get("toolCallId")
                    or f"call_{index}_{uuid4().hex[:8]}"
                )
                normalized.append(
                    KernelToolCall(
                        id=call_id,
                        name=name,
                        arguments=arguments,
                        raw=dict(item),
                    )
                )
        usage = raw.get("usage") if isinstance(raw.get("usage"), dict) else {}
        token_used = int(raw.get("token_used") or usage.get("totalTokens") or usage.get("total_tokens") or 0)
        return KernelModelReply(
            content=str(raw.get("content") or "").strip(),
            model_name=str(raw.get("model_name") or raw.get("model") or fallback_model),
            tool_calls=normalized,
            token_used=token_used,
            reasoning_content=str(raw.get("reasoning_content") or "").strip() or None,
            raw=raw,
        )

    def _prepare_provider_messages(
        self,
        messages: list[KernelMessage],
        *,
        request: KernelTurnRequest,
        tool_schemas: list[dict[str, Any]],
    ) -> tuple[list[KernelMessage], ProviderEnvelopeCompactionResult | None]:
        if self.context_compactor is None:
            return messages, None
        result = self.context_compactor.prepare_provider_envelope(
            [message.to_openai() for message in messages],
            model=request.model,
            tool_schemas=tool_schemas,
            max_output_tokens=request.max_tokens,
            reasoning_mode=request.reasoning_mode,
            reasoning_effort=request.reasoning_effort,
        )
        if result.status == "failed":
            raise ProviderEnvelopeCompactionError(result)
        if not result.compacted:
            return messages, result
        return [self._kernel_message(message) for message in result.messages], result

    @staticmethod
    def _kernel_message(message: dict[str, Any]) -> KernelMessage:
        raw_tool_calls = message.get("tool_calls")
        tool_calls = [
            dict(tool_call)
            for tool_call in raw_tool_calls
            if isinstance(tool_call, dict)
        ] if isinstance(raw_tool_calls, list) else []
        return KernelMessage(
            role=str(message.get("role") or "user"),
            content=str(message.get("content") or ""),
            tool_call_id=str(message.get("tool_call_id") or "").strip() or None,
            name=str(message.get("name") or "").strip() or None,
            tool_calls=tool_calls,
            reasoning_content=str(message.get("reasoning_content") or "").strip() or None,
        )

    @classmethod
    def _request_summary(
        cls,
        messages: list[KernelMessage],
        tool_schemas: list[dict[str, Any]],
        *,
        reasoning_mode: str | None,
        reasoning_effort: str | None,
        cache_affinity: str | None,
        request_family: str | None,
    ) -> dict[str, Any]:
        role_counts: dict[str, int] = {}
        message_chars = 0
        for message in messages:
            role = str(message.role or "unknown").strip().lower() or "unknown"
            role_counts[role] = role_counts.get(role, 0) + 1
            message_chars += len(message.content or "")
        prefix_messages: list[dict[str, Any]] = []
        cache_prefix_chars = 0
        for message in messages:
            role = str(message.role or "").strip().lower()
            if role not in {"system", "developer"}:
                break
            prefix_messages.append(message.to_openai())
            cache_prefix_chars += len(message.content or "")
        prefix_fingerprint = None
        if prefix_messages or tool_schemas:
            prefix_payload = json.dumps(
                {"messages": prefix_messages, "tools": tool_schemas},
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            prefix_fingerprint = hashlib.sha256(prefix_payload.encode("utf-8")).hexdigest()
        summary = {
            "messageCount": len(messages),
            "roleCounts": role_counts,
            "messageChars": message_chars,
            "toolSchemaCount": len(tool_schemas),
            "cacheAffinityPresent": bool(cache_affinity),
            "cachePrefixChars": cache_prefix_chars,
            "cachePrefixFingerprint": prefix_fingerprint,
            "reasoningRequested": bool(
                str(reasoning_mode or "").strip()
                or str(reasoning_effort or "").strip()
            ),
            "bodyRedacted": True,
        }
        normalized_family = cls._normalized_request_family(request_family)
        if normalized_family:
            summary["requestFamily"] = normalized_family
        return summary

    @staticmethod
    def _normalized_request_family(value: Any) -> str | None:
        normalized = str(value or "").strip().lower()
        if not normalized or len(normalized) > 64:
            return None
        if any(character not in "abcdefghijklmnopqrstuvwxyz0123456789_.:-" for character in normalized):
            return None
        return normalized

    @staticmethod
    def _response_summary(
        content: str,
        tool_calls: list[KernelToolCall],
    ) -> dict[str, Any]:
        return {
            "outputChars": len(content or ""),
            "toolCallCount": len(tool_calls),
            "emptyResponse": not bool(content or tool_calls),
            "bodyRedacted": True,
        }

    @classmethod
    def _model_checkpoint_payload(
        cls,
        provider_kwargs: dict[str, Any],
        *,
        turn: int,
        transport: str,
        request_summary: dict[str, Any],
    ) -> dict[str, Any]:
        canonical = json.dumps(
            cls._canonical_value(provider_kwargs),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        request_fingerprint = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        semantic_identity = f"{transport}:{turn}:{request_fingerprint}"
        semantic_digest = hashlib.sha256(semantic_identity.encode("utf-8")).hexdigest()
        semantic_key = f"model_semantic_{semantic_digest}"
        return {
            "semanticKey": semantic_key,
            "requestFingerprint": request_fingerprint,
            "transport": transport,
            "turn": turn + 1,
            "model": str(provider_kwargs.get("model") or "").strip(),
            "requestSummary": dict(request_summary),
            "bodyRedacted": True,
        }

    @classmethod
    def _model_committed_checkpoint_payload(
        cls,
        model_checkpoint: dict[str, Any],
        provider_result: dict[str, Any],
    ) -> dict[str, Any]:
        usage = provider_result.get("usage")
        usage = usage if isinstance(usage, dict) else {}
        committed = {
            **model_checkpoint,
            "responseReceived": True,
            "cacheReadTokens": cls._non_negative_int_from_sources(
                (usage, provider_result),
                "promptCacheHitTokens",
                "prompt_cache_hit_tokens",
            ),
            "cacheMissTokens": cls._non_negative_int_from_sources(
                (usage, provider_result),
                "promptCacheMissTokens",
                "prompt_cache_miss_tokens",
            ),
            "cacheWriteTokens": cls._non_negative_int_from_sources(
                (usage, provider_result),
                "promptCacheWriteTokens",
                "prompt_cache_write_tokens",
            ),
            "cacheMissTokensDerived": cls._first_bool_from_sources(
                (usage, provider_result),
                "promptCacheMissTokensDerived",
                "prompt_cache_miss_tokens_derived",
            ),
            # 0 命中和"上游没回报用量"在数字上无法区分，投影侧要靠这两个标志分辨。
            "usageReported": cls._first_bool_from_sources(
                (usage, provider_result), "usageReported", "usage_reported"
            ),
            "cacheUsageReported": cls._first_bool_from_sources(
                (usage, provider_result), "cacheUsageReported", "cache_usage_reported"
            ),
        }
        token_used = provider_result.get("token_used")
        if token_used is None:
            token_used = provider_result.get("tokenUsed")
        if token_used is not None:
            committed["tokenUsed"] = cls._non_negative_int(token_used)
        wire_api = str(
            provider_result.get("wire_api")
            or provider_result.get("wireApi")
            or ""
        ).strip()
        if wire_api:
            committed["wireApi"] = wire_api
        cache_continuity = cls._sanitize_cache_continuity(
            provider_result.get("cacheContinuity")
        )
        if cache_continuity:
            committed["cacheContinuity"] = cache_continuity
        # ``model`` 是请求时选中的模型；provider profile 落到默认档时，真正发出去
        # 的是另一个模型名。Redis 影子投影按 model 分桶，写错就会把两个模型的
        # 前缀链混成一条。payload 里的模型名是唯一可信来源。
        routed_model = str((cache_continuity or {}).get("model") or "").strip()
        if not routed_model:
            profile = provider_result.get("providerProfile")
            if isinstance(profile, dict):
                routed_model = str(profile.get("model") or "").strip()
        if routed_model:
            committed["routedModel"] = routed_model
        return committed

    @staticmethod
    def _non_negative_int(value: Any) -> int:
        try:
            return max(0, int(value or 0))
        except (TypeError, ValueError):
            return 0

    @classmethod
    def _non_negative_int_from_sources(
        cls,
        sources: tuple[Any, ...],
        *keys: str,
    ) -> int:
        """Read a metric without treating an explicit zero as a missing value."""
        for source in sources:
            if not isinstance(source, dict):
                continue
            for key in keys:
                if key in source:
                    return cls._non_negative_int(source.get(key))
        return 0

    @staticmethod
    def _first_bool_from_sources(sources: tuple[Any, ...], *keys: str) -> bool:
        """Read the first declared boolean so an explicit false remains authoritative."""
        for source in sources:
            if not isinstance(source, dict):
                continue
            for key in keys:
                if key in source:
                    return source.get(key) is True
        return False

    @staticmethod
    def _sanitize_cache_continuity(value: Any) -> dict[str, Any] | None:
        if not isinstance(value, dict) or value.get("bodyRedacted") is not True:
            return None
        sanitized: dict[str, Any] = {"bodyRedacted": True}
        try:
            schema_version = int(value.get("schemaVersion") or 0)
            input_count = max(0, int(value.get("inputCount") or 0))
        except (TypeError, ValueError):
            return None
        if schema_version != 1:
            return None
        sanitized["schemaVersion"] = schema_version
        sanitized["inputCount"] = input_count

        for key, max_length in (
            ("provider", 64),
            ("wireApi", 32),
            ("model", 128),
        ):
            if key not in value:
                continue
            normalized = str(value.get(key) or "").strip()
            if not normalized or len(normalized) > max_length:
                return None
            sanitized[key] = normalized

        for key in (
            "stablePrefixFingerprint",
            "toolsFingerprint",
            "requestSettingsFingerprint",
            "surfaceGeneration",
            "inputFingerprint",
            "routeFingerprint",
            "affinityFingerprint",
        ):
            if key not in value:
                continue
            fingerprint = str(value.get(key) or "").strip()
            if not AgentKernel._is_sha256_fingerprint(fingerprint):
                return None
            sanitized[key] = fingerprint

        if "requestFamily" in value:
            request_family = AgentKernel._normalized_request_family(value.get("requestFamily"))
            if not request_family:
                return None
            sanitized["requestFamily"] = request_family

        if "cacheIdentityMode" in value:
            cache_identity_mode = str(value.get("cacheIdentityMode") or "").strip().lower()
            if cache_identity_mode not in {"none", "prompt_cache_key", "provider_user"}:
                return None
            sanitized["cacheIdentityMode"] = cache_identity_mode

        if "promptCacheStrategy" in value:
            prompt_cache_strategy = str(value.get("promptCacheStrategy") or "").strip().lower()
            if prompt_cache_strategy not in {
                "legacy_model_policy",
                "none",
                "deepseek_automatic",
                "openai_legacy",
                "openai_gpt_5_6",
            }:
                return None
            sanitized["promptCacheStrategy"] = prompt_cache_strategy

        if "prefixChainFingerprints" in value:
            raw_chain = value.get("prefixChainFingerprints")
            if not isinstance(raw_chain, list) or len(raw_chain) > 64:
                return None
            chain = [str(item or "").strip() for item in raw_chain]
            if any(not AgentKernel._is_sha256_fingerprint(item) for item in chain):
                return None
            sanitized["prefixChainFingerprints"] = chain
        if "chainComplete" in value:
            sanitized["chainComplete"] = bool(value.get("chainComplete"))
        return sanitized

    @staticmethod
    def _is_sha256_fingerprint(value: str) -> bool:
        return len(value) == 64 and all(character in "0123456789abcdef" for character in value)

    @staticmethod
    def _cache_continuity_trace_summary(value: dict[str, Any]) -> dict[str, Any]:
        return {
            key: AgentKernel._canonical_value(raw)
            for key, raw in value.items()
            if key != "prefixChainFingerprints"
        }

    @staticmethod
    def _semantic_source_event(
        value: Any,
        *,
        expected_event_type: str,
        expected_event_key: str,
    ) -> dict[str, Any] | None:
        if not isinstance(value, dict):
            return None
        payload = value.get("payload")
        envelope = payload.get("_event") if isinstance(payload, dict) else None
        if not isinstance(envelope, dict):
            return None
        schema_version = envelope.get("schemaVersion")
        event_id = envelope.get("eventId")
        sequence = envelope.get("sequence")
        if (
            type(schema_version) is not int
            or schema_version != 1
            or type(event_id) is not int
            or not 1 <= event_id <= 9_223_372_036_854_775_807
            or type(sequence) is not int
            or not 1 <= sequence <= 9_223_372_036_854_775_807
        ):
            return None
        if not all(
            isinstance(envelope.get(key), str)
            for key in ("eventType", "eventIdempotencyKey", "runId", "visibility")
        ):
            return None
        event_type = envelope["eventType"].strip()
        event_key = envelope["eventIdempotencyKey"].strip()
        run_id = envelope["runId"].strip()
        if (
            envelope["visibility"].strip() != "internal"
            or event_type != expected_event_type
            or event_key != expected_event_key
            or not run_id
        ):
            return None
        if (
            type(value.get("eventId")) is not int
            or type(value.get("sequenceNo")) is not int
            or not isinstance(value.get("runId"), str)
            or not isinstance(value.get("eventType"), str)
            or not isinstance(value.get("eventIdempotencyKey"), str)
        ):
            return None
        outer_comparisons = (
            (value["eventId"], event_id),
            (value["runId"], run_id),
            (value["sequenceNo"], sequence),
            (value["eventType"], event_type),
            (value["eventIdempotencyKey"], event_key),
        )
        for actual, expected in outer_comparisons:
            if actual != expected:
                return None
        return {
            "schemaVersion": schema_version,
            "eventId": event_id,
            "sequence": sequence,
            "eventType": event_type,
            "bodyRedacted": True,
        }

    @staticmethod
    def _with_compaction_source_event(
        model_checkpoint: dict[str, Any],
        source_event: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if source_event is None:
            return model_checkpoint
        request_summary = model_checkpoint.get("requestSummary")
        if not isinstance(request_summary, dict):
            return model_checkpoint
        context_compaction = request_summary.get("contextCompaction")
        if (
            not isinstance(context_compaction, dict)
            or context_compaction.get("bodyRedacted") is not True
        ):
            return model_checkpoint
        return {
            **model_checkpoint,
            "requestSummary": {
                **request_summary,
                "contextCompaction": {
                    **context_compaction,
                    "sourceEvent": dict(source_event),
                },
            },
        }

    async def _write_model_checkpoint(
        self,
        event_type: str,
        payload: dict[str, Any],
    ) -> dict[str, Any] | None:
        if self.checkpoint_writer is None:
            return None
        semantic_key = str(payload["semanticKey"])
        event_key = f"harness:{event_type.casefold()}:{semantic_key}"
        result = await self.checkpoint_writer(event_type, event_key, dict(payload))
        return self._semantic_source_event(
            result,
            expected_event_type=event_type,
            expected_event_key=event_key,
        )

    @staticmethod
    def _schema_name(schema: dict[str, Any]) -> str:
        if schema.get("type") == "function" and isinstance(schema.get("function"), dict):
            return str(schema["function"].get("name") or "").strip()
        return str(schema.get("name") or "").strip()

    @classmethod
    def _canonical_tool_schemas(cls, schemas: Any) -> list[dict[str, Any]]:
        canonical = [
            cls._canonical_value(schema)
            for schema in schemas
            if isinstance(schema, dict)
        ]
        canonical.sort(key=lambda schema: (cls._schema_name(schema), json.dumps(
            schema,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )))
        return canonical

    @classmethod
    def _canonical_value(cls, value: Any) -> Any:
        if isinstance(value, dict):
            return {
                key: cls._canonical_value(value[key])
                for key in sorted(value, key=lambda item: str(item))
            }
        if isinstance(value, list):
            return [cls._canonical_value(item) for item in value]
        return value

    @staticmethod
    def _dump_arguments(arguments: dict[str, Any]) -> str:
        import json
        return json.dumps(arguments or {}, ensure_ascii=False)

    def _tool_call_message(
        self,
        call: KernelToolCall,
        *,
        formatter: ToolCallMessageFormatter | None = None,
    ) -> dict[str, Any]:
        effective_formatter = formatter or self.tool_call_message_formatter
        if effective_formatter is not None:
            return dict(effective_formatter(call))
        if call.raw and isinstance(call.raw.get("function"), dict):
            return dict(call.raw)
        return {
            "id": call.id,
            "type": "function",
            "function": {
                "name": call.name,
                "arguments": self._dump_arguments(call.arguments),
            },
        }

    @staticmethod
    def _tool_run_dict(observation: KernelToolObservation) -> dict[str, Any]:
        return {
            "name": observation.name,
            "status": observation.status,
            "toolCallId": observation.tool_call_id,
            "content": observation.content,
            **({k: v for k, v in observation.raw.items() if k not in {"content"}}),
        }

    @staticmethod
    async def _maybe_await(value: Any) -> Any:
        if hasattr(value, "__await__"):
            return await value
        return value
