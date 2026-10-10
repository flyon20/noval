from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable, Iterable, Mapping
from contextlib import asynccontextmanager, suppress
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True, slots=True)
class PromptCacheCapabilities:
    """Provider-specific Responses prompt-cache request compiler contract."""

    strategy: str
    mode: str
    retention: str
    breakpoint: str

    @classmethod
    def from_payload(cls, payload: Any) -> "PromptCacheCapabilities | None":
        if payload is None:
            return None
        if isinstance(payload, cls):
            return payload
        if not isinstance(payload, Mapping):
            raise ValueError("provider prompt cache capabilities must be an object")
        values: dict[str, str] = {}
        for key in ("strategy", "mode", "retention", "breakpoint"):
            value = payload.get(key)
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"provider prompt cache capabilities {key} must be a string")
            values[key] = value.strip().lower().replace("-", "_")
        strategy = values["strategy"]
        valid = (
            strategy == "none"
            and values == {
                "strategy": "none",
                "mode": "disabled",
                "retention": "provider_default",
                "breakpoint": "none",
            }
        ) or (
            strategy == "deepseek_automatic"
            and values == {
                "strategy": "deepseek_automatic",
                "mode": "provider_managed",
                "retention": "provider_default",
                "breakpoint": "none",
            }
        ) or (
            strategy == "openai_legacy"
            and values["mode"] == "implicit"
            and values["retention"] in {"provider_default", "in_memory", "24h"}
            and values["breakpoint"] == "none"
        ) or (
            strategy == "openai_gpt_5_6"
            and values["mode"] in {"implicit", "explicit"}
            and values["retention"] in {"provider_default", "30m"}
            and values["breakpoint"] in {"none", "stable_prefix"}
        )
        if not valid:
            raise ValueError(f"invalid prompt cache capability combination for {strategy or 'unknown'}")
        return cls(**values)

    @property
    def signature(self) -> tuple[str, str, str, str]:
        return self.strategy, self.mode, self.retention, self.breakpoint

    def snapshot(self) -> dict[str, str]:
        return {
            "strategy": self.strategy,
            "mode": self.mode,
            "retention": self.retention,
            "breakpoint": self.breakpoint,
        }

    def assert_responses_protocol(self, protocol: str) -> None:
        if self.strategy != "none" and protocol != "responses":
            raise ValueError("prompt cache capabilities require Responses protocol")


@dataclass(frozen=True, slots=True)
class ProviderCapabilities:
    """Declared Provider behavior; absence remains a legacy-unknown contract."""

    schema_version: int
    supports_streaming: bool
    supports_tools: bool
    supports_json_object: bool
    supports_reasoning: bool
    reports_usage: bool
    reports_cache_usage: bool
    prompt_cache: PromptCacheCapabilities | None = None

    @classmethod
    def from_payload(cls, payload: Any) -> "ProviderCapabilities | None":
        if payload is None:
            return None
        if isinstance(payload, cls):
            return payload
        if not isinstance(payload, Mapping):
            raise ValueError("provider capabilities must be an object")
        if payload.get("schemaVersion") != 1:
            raise ValueError("provider capabilities schemaVersion must be 1")
        fields = {
            "supports_streaming": "supportsStreaming",
            "supports_tools": "supportsTools",
            "supports_json_object": "supportsJsonObject",
            "supports_reasoning": "supportsReasoning",
            "reports_usage": "reportsUsage",
            "reports_cache_usage": "reportsCacheUsage",
        }
        values: dict[str, bool] = {}
        for attribute, key in fields.items():
            value = payload.get(key)
            if type(value) is not bool:
                raise ValueError(f"provider capabilities {key} must be boolean")
            values[attribute] = value
        return cls(
            schema_version=1,
            prompt_cache=PromptCacheCapabilities.from_payload(payload.get("promptCache")),
            **values,
        )

    @property
    def signature(self) -> tuple[Any, ...]:
        return (
            self.schema_version,
            self.supports_streaming,
            self.supports_tools,
            self.supports_json_object,
            self.supports_reasoning,
            self.reports_usage,
            self.reports_cache_usage,
            self.prompt_cache.signature if self.prompt_cache is not None else ("legacy_model_policy",),
        )

    def snapshot(self) -> dict[str, Any]:
        snapshot: dict[str, Any] = {
            "schemaVersion": self.schema_version,
            "supportsStreaming": self.supports_streaming,
            "supportsTools": self.supports_tools,
            "supportsJsonObject": self.supports_json_object,
            "supportsReasoning": self.supports_reasoning,
            "reportsUsage": self.reports_usage,
            "reportsCacheUsage": self.reports_cache_usage,
        }
        if self.prompt_cache is not None:
            snapshot["promptCache"] = self.prompt_cache.snapshot()
        return snapshot


@dataclass(frozen=True, slots=True)
class ProviderDispatch:
    """Runtime-only Provider route whose credential must never enter durable state."""

    profile_key: str
    profile_version: str
    endpoint: str
    model: str
    provider_type: str
    protocol: str
    api_key: str = field(repr=False, compare=False)
    provider_capabilities: ProviderCapabilities | Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        normalized = {
            "profile_key": str(self.profile_key or "").strip(),
            "profile_version": str(self.profile_version or "").strip(),
            "endpoint": str(self.endpoint or "").strip(),
            "model": str(self.model or "").strip(),
            "provider_type": str(self.provider_type or "").strip(),
            "protocol": str(self.protocol or "").strip().lower().replace("-", "_"),
            "api_key": str(self.api_key or "").strip(),
        }
        for name in ("profile_key", "profile_version", "endpoint", "model", "provider_type", "api_key"):
            if not normalized[name]:
                raise ValueError(f"provider dispatch {name.replace('_', ' ')} is required")
        if normalized["protocol"] not in {"responses", "chat_completions"}:
            raise ValueError("provider dispatch protocol must be responses or chat_completions")
        for name, value in normalized.items():
            object.__setattr__(self, name, value)
        capabilities = ProviderCapabilities.from_payload(self.provider_capabilities)
        if capabilities is not None and capabilities.prompt_cache is not None:
            capabilities.prompt_cache.assert_responses_protocol(normalized["protocol"])
        object.__setattr__(self, "provider_capabilities", capabilities)

    @property
    def identity(self) -> tuple[str, str]:
        return self.profile_key, self.profile_version

    def route_snapshot(self) -> dict[str, Any]:
        snapshot: dict[str, Any] = {
            "profileKey": self.profile_key,
            "profileVersion": self.profile_version,
            "endpoint": self.endpoint,
            "model": self.model,
            "providerType": self.provider_type,
            "protocol": self.protocol,
        }
        if self.provider_capabilities is not None:
            snapshot["providerCapabilities"] = self.provider_capabilities.snapshot()
        return snapshot

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
        *,
        expected_profile_key: str,
        expected_profile_version: str,
    ) -> "ProviderDispatch":
        if not isinstance(payload, Mapping):
            raise ValueError("provider dispatch response must be an object")
        dispatch = cls(
            profile_key=str(payload.get("profileKey") or ""),
            profile_version=str(payload.get("profileVersion") or ""),
            endpoint=str(payload.get("endpoint") or ""),
            model=str(payload.get("model") or ""),
            provider_type=str(payload.get("providerType") or ""),
            protocol=str(payload.get("protocol") or ""),
            api_key=str(payload.get("apiKey") or ""),
            provider_capabilities=payload.get("providerCapabilities"),
        )
        expected = (
            str(expected_profile_key or "").strip(),
            str(expected_profile_version or "").strip(),
        )
        if dispatch.identity != expected:
            raise ValueError("provider dispatch response identity mismatch")
        return dispatch


ProviderDispatchResolver = Callable[[str, str], Awaitable[ProviderDispatch]]
ProviderOutcomeReporter = Callable[[dict[str, Any]], Awaitable[Any]]


@dataclass(frozen=True, slots=True)
class ProviderRoute:
    """Secret-free route frozen from the Backend runtime-config projection."""

    profile_key: str
    profile_version: str
    endpoint: str
    model: str
    provider_type: str
    protocol: str
    provider_capabilities: ProviderCapabilities | None = None
    is_default: bool = False

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "ProviderRoute":
        if not isinstance(payload, Mapping):
            raise ValueError("provider route must be an object")
        route = cls(
            profile_key=str(payload.get("profileKey") or "").strip(),
            profile_version=str(payload.get("profileVersion") or "").strip(),
            endpoint=str(payload.get("endpoint") or "").strip().rstrip("/"),
            model=str(payload.get("model") or "").strip(),
            provider_type=str(payload.get("providerType") or "openai-compatible").strip(),
            protocol=str(payload.get("protocol") or "").strip().lower().replace("-", "_"),
            provider_capabilities=ProviderCapabilities.from_payload(payload.get("providerCapabilities")),
            is_default=payload.get("isDefault") is True,
        )
        for name in ("profile_key", "profile_version", "endpoint", "model", "provider_type"):
            if not getattr(route, name):
                raise ValueError(f"provider route {name.replace('_', ' ')} is required")
        if route.protocol not in {"responses", "chat_completions"}:
            raise ValueError("provider route protocol must be responses or chat_completions")
        if route.provider_capabilities is not None and route.provider_capabilities.prompt_cache is not None:
            route.provider_capabilities.prompt_cache.assert_responses_protocol(route.protocol)
        return route

    @property
    def identity(self) -> tuple[str, str]:
        return self.profile_key, self.profile_version

    @property
    def capability_set(self) -> tuple[Any, ...]:
        capabilities = (
            self.provider_capabilities.signature
            if self.provider_capabilities is not None
            else ("legacy_unknown",)
        )
        return self.endpoint, self.provider_type, self.protocol, capabilities

    @property
    def failover_compatibility_signature(self) -> tuple[Any, ...]:
        capabilities = (
            self.provider_capabilities.signature
            if self.provider_capabilities is not None
            else ("legacy_unknown",)
        )
        return self.provider_type, self.protocol, capabilities

    def snapshot(self) -> dict[str, Any]:
        snapshot: dict[str, Any] = {
            "profileKey": self.profile_key,
            "profileVersion": self.profile_version,
            "endpoint": self.endpoint,
            "model": self.model,
            "providerType": self.provider_type,
            "protocol": self.protocol,
        }
        if self.provider_capabilities is not None:
            snapshot["providerCapabilities"] = self.provider_capabilities.snapshot()
        return snapshot


@dataclass(frozen=True, slots=True)
class ProviderRoutingPolicy:
    enabled: bool = False
    ordered_profile_keys: tuple[str, ...] = ()
    max_failovers: int = 0
    circuit_states: tuple[tuple[str, str], ...] = ()

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any] | None) -> "ProviderRoutingPolicy":
        if payload is None:
            return cls()
        if not isinstance(payload, Mapping) or payload.get("schemaVersion") != 1:
            raise ValueError("provider routing policy schemaVersion must be 1")
        enabled = payload.get("enabled")
        if type(enabled) is not bool:
            raise ValueError("provider routing policy enabled must be boolean")
        raw_keys = payload.get("orderedProfileKeys")
        if not isinstance(raw_keys, list):
            raise ValueError("provider routing policy orderedProfileKeys must be an array")
        ordered_keys: list[str] = []
        for raw_key in raw_keys:
            key = str(raw_key or "").strip()
            if not key or key in ordered_keys:
                raise ValueError("provider routing policy orderedProfileKeys must be unique and non-empty")
            ordered_keys.append(key)
        max_failovers = payload.get("maxFailovers")
        if type(max_failovers) is not int or max_failovers < 0 or max_failovers > len(ordered_keys):
            raise ValueError(
                "provider routing policy maxFailovers must be between 0 and the "
                "orderedProfileKeys length"
            )
        raw_states = payload.get("circuitStates")
        if not isinstance(raw_states, Mapping):
            raise ValueError("provider routing policy circuitStates must be an object")
        circuit_states: list[tuple[str, str]] = []
        for raw_key, raw_state in raw_states.items():
            key = str(raw_key or "").strip()
            if isinstance(raw_state, Mapping):
                raw_state = raw_state.get("state")
            state = str(raw_state or "").strip().lower().replace("-", "_")
            if not key or state not in {"closed", "open", "half_open"}:
                raise ValueError("provider routing policy circuitStates is invalid")
            circuit_states.append((key, state))
        return cls(
            enabled=enabled,
            ordered_profile_keys=tuple(ordered_keys),
            max_failovers=max_failovers,
            circuit_states=tuple(circuit_states),
        )

    @property
    def active(self) -> bool:
        return self.enabled and self.max_failovers >= 1 and len(self.ordered_profile_keys) >= 2

    def circuit_state(self, profile_key: str) -> str:
        return dict(self.circuit_states).get(profile_key, "closed")


class ProviderDispatchScope:
    """Run-local credential resolver over one frozen Provider capability set."""

    def __init__(
        self,
        resolver: ProviderDispatchResolver,
        *,
        routes: Iterable[Mapping[str, Any]] | None = None,
        preferred_model: str | None = None,
        preferred_profile_key: str | None = None,
        routing_policy: Mapping[str, Any] | None = None,
        outcome_reporter: ProviderOutcomeReporter | None = None,
    ) -> None:
        if not callable(resolver):
            raise TypeError("provider dispatch resolver must be callable")
        self._resolver = resolver
        self._lock = asyncio.Lock()
        self._routing_policy = ProviderRoutingPolicy.from_payload(routing_policy)
        self._routes = self._freeze_routes(
            routes,
            preferred_model=preferred_model,
            preferred_profile_key=preferred_profile_key,
            routing_policy=self._routing_policy,
        )
        self._primary_route = next(iter(self._routes.values()), None)
        self._active_identity: tuple[str, str] | None = None
        self._failovers_used = 0
        self._outcome_reporter = outcome_reporter
        self._identity: tuple[str, str] | None = None
        self._dispatches: dict[tuple[str, str], ProviderDispatch] = {}
        self._inflight: dict[tuple[str, str], asyncio.Task[ProviderDispatch]] = {}
        self._closed = False

    def current(self, profile_key: str | None = None, profile_version: str | None = None) -> ProviderDispatch | None:
        if self._closed:
            return None
        if profile_key is not None or profile_version is not None:
            identity = self._normalize_identity(profile_key or "", profile_version or "")
            return self._dispatches.get(identity)
        if self._active_identity is not None:
            return self._dispatches.get(self._active_identity)
        if self._primary_route is not None:
            return self._dispatches.get(self._primary_route.identity)
        return next(iter(self._dispatches.values()), None)

    def route_for_model(self, model: str) -> ProviderRoute:
        if not self._routes:
            raise RuntimeError("provider route catalog is not active")
        if self._routing_policy.active:
            if self._active_identity is not None:
                active = self._routes.get(self._active_identity)
                if active is not None:
                    return active
            candidates = self._first_closed_route()
            return candidates if candidates is not None else self._primary_route
        normalized_model = str(model or "").strip()
        return next(
            (route for route in self._routes.values() if route.model == normalized_model),
            self._primary_route,
        )

    async def resolve_for_model(self, model: str) -> ProviderDispatch:
        route = self.route_for_model(model)
        dispatch = await self.resolve(*route.identity, expected_route=route.snapshot())
        if self._routing_policy.active:
            async with self._lock:
                if self._active_identity is None:
                    self._active_identity = route.identity
        return dispatch

    @property
    def routing_enabled(self) -> bool:
        return self._routing_policy.active

    async def claim_failover(
        self,
        profile_key: str,
        profile_version: str,
    ) -> ProviderDispatch | None:
        current_identity = self._normalize_identity(profile_key, profile_version)
        async with self._lock:
            if (
                self._closed
                or not self._routing_policy.active
                or self._failovers_used >= self._routing_policy.max_failovers
            ):
                return None
            active_identity = self._active_identity or (
                self._primary_route.identity if self._primary_route is not None else None
            )
            if active_identity != current_identity:
                return None
            candidates = list(self._routes.values())
            try:
                current_index = next(
                    index for index, route in enumerate(candidates) if route.identity == current_identity
                )
            except StopIteration:
                return None
            next_route = self._next_closed_route(candidates, current_index)
            if next_route is None:
                return None
            self._failovers_used += 1
            self._active_identity = next_route.identity
        return await self.resolve(*next_route.identity, expected_route=next_route.snapshot())

    async def report_outcome(
        self,
        dispatch: ProviderDispatch,
        *,
        outcome: str,
        failure_class: str | None = None,
        switched: bool,
    ) -> None:
        if self._outcome_reporter is None or not self._routing_policy.active:
            return
        payload: dict[str, Any] = {
            "profileKey": dispatch.profile_key,
            "profileVersion": dispatch.profile_version,
            "outcome": outcome,
            "switched": bool(switched),
        }
        if failure_class:
            payload["failureClass"] = failure_class
        try:
            await self._outcome_reporter(payload)
        except Exception:
            return

    async def resolve(
        self,
        profile_key: str,
        profile_version: str,
        *,
        expected_route: Mapping[str, Any] | None = None,
    ) -> ProviderDispatch:
        identity = self._normalize_identity(profile_key, profile_version)
        frozen_route = self._routes.get(identity)
        if self._routes and frozen_route is None:
            raise ValueError("provider dispatch route is outside the frozen catalog")
        if frozen_route is not None and expected_route is not None:
            self._assert_route_matches(frozen_route, expected_route)
        async with self._lock:
            if self._closed:
                raise RuntimeError("provider dispatch scope is closed")
            if not self._routes and self._identity is not None and self._identity != identity:
                raise ValueError("provider dispatch scope route mismatch")
            cached = self._dispatches.get(identity)
            if cached is not None:
                return cached
            if identity not in self._inflight:
                self._identity = identity
                self._inflight[identity] = asyncio.create_task(self._resolve_once(identity, frozen_route))
            inflight = self._inflight[identity]

        try:
            dispatch = await asyncio.shield(inflight)
        except asyncio.CancelledError:
            if inflight.cancelled():
                await self._clear_failed(inflight)
            raise
        except BaseException:
            await self._clear_failed(inflight)
            raise

        async with self._lock:
            if self._closed:
                raise RuntimeError("provider dispatch scope is closed")
            if self._inflight.get(identity) is inflight:
                self._dispatches[identity] = dispatch
                self._inflight.pop(identity, None)
            cached = self._dispatches.get(identity)
            if cached is None:
                raise RuntimeError("provider dispatch scope lost resolved credential")
            return cached

    async def aclose(self) -> None:
        async with self._lock:
            if self._closed:
                return
            self._closed = True
            inflight = list(self._inflight.values())
            self._inflight.clear()
            self._dispatches.clear()
            self._identity = None
            self._active_identity = None
        for task in inflight:
            if not task.done():
                task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    async def _resolve_once(
        self,
        identity: tuple[str, str],
        frozen_route: ProviderRoute | None,
    ) -> ProviderDispatch:
        dispatch = await self._resolver(*identity)
        if not isinstance(dispatch, ProviderDispatch):
            raise TypeError("provider dispatch resolver returned an invalid value")
        if dispatch.identity != identity:
            raise ValueError("provider dispatch resolver identity mismatch")
        if frozen_route is not None:
            self._assert_route_matches(frozen_route, dispatch.route_snapshot())
        return dispatch

    async def _clear_failed(self, inflight: asyncio.Task[ProviderDispatch]) -> None:
        async with self._lock:
            identity = next(
                (key for key, candidate in self._inflight.items() if candidate is inflight),
                None,
            )
            if identity is not None:
                self._inflight.pop(identity, None)
                self._dispatches.pop(identity, None)

    @classmethod
    def _freeze_routes(
        cls,
        routes: Iterable[Mapping[str, Any]] | None,
        *,
        preferred_model: str | None,
        preferred_profile_key: str | None,
        routing_policy: ProviderRoutingPolicy,
    ) -> dict[tuple[str, str], ProviderRoute]:
        if routes is None:
            return {}
        normalized = [ProviderRoute.from_payload(route) for route in routes]
        if routing_policy.active:
            by_key: dict[str, ProviderRoute] = {}
            for route in normalized:
                if route.profile_key in by_key:
                    raise ValueError("provider routing policy profile key is ambiguous")
                by_key[route.profile_key] = route
            try:
                ordered = [by_key[key] for key in routing_policy.ordered_profile_keys]
            except KeyError as exc:
                raise ValueError("provider routing policy references an unknown profile") from exc
            primary_signature = ordered[0].failover_compatibility_signature
            if primary_signature[-1] == ("legacy_unknown",) or any(
                route.failover_compatibility_signature != primary_signature
                for route in ordered[1:]
            ):
                raise ValueError("provider routing policy candidates must be capability compatible")
            return {route.identity: route for route in ordered}

        preferred = str(preferred_model or "").strip()
        preferred_key = str(preferred_profile_key or "").strip()
        primary = next(
            (
                route
                for route in normalized
                if route.profile_key == preferred_key
                and (not preferred or route.model == preferred)
            ),
            next(
                (route for route in normalized if route.model == preferred),
                next((route for route in normalized if route.is_default), normalized[0] if normalized else None),
            ),
        )
        if primary is None:
            return {}
        frozen: dict[tuple[str, str], ProviderRoute] = {}
        for route in normalized:
            if primary.provider_capabilities is None and route.identity != primary.identity:
                continue
            if route.capability_set != primary.capability_set:
                continue
            existing = frozen.get(route.identity)
            if existing is not None and existing != route:
                raise ValueError("provider route identity is ambiguous")
            frozen[route.identity] = route
        ordered = sorted(
            frozen.values(),
            key=lambda route: (route.identity != primary.identity, route.profile_key, route.profile_version),
        )
        return {route.identity: route for route in ordered}

    def _first_closed_route(self) -> ProviderRoute | None:
        """Highest-priority route whose breaker is not OPEN, or None if all are."""
        for route in self._routes.values():
            if self._routing_policy.circuit_state(route.profile_key) != "open":
                return route
        return None

    def _next_closed_route(
        self,
        routes: list[ProviderRoute],
        current_index: int,
    ) -> ProviderRoute | None:
        """First route after ``current_index`` whose breaker is not OPEN.

        The current route stays in ``routes`` so its position can be located even
        when its own breaker just tripped; only forward candidates are gated.
        """
        for route in routes[current_index + 1 :]:
            if self._routing_policy.circuit_state(route.profile_key) != "open":
                return route
        return None

    @staticmethod
    def _assert_route_matches(route: ProviderRoute, payload: Mapping[str, Any]) -> None:
        candidate = ProviderRoute.from_payload(payload)
        if candidate.identity != route.identity or candidate.capability_set != route.capability_set or candidate.model != route.model:
            raise ValueError("provider dispatch route does not match the frozen catalog")

    @staticmethod
    def _normalize_identity(profile_key: str, profile_version: str) -> tuple[str, str]:
        identity = (
            str(profile_key or "").strip(),
            str(profile_version or "").strip(),
        )
        if not identity[0]:
            raise ValueError("provider profile key is required")
        if not identity[1]:
            raise ValueError("provider profile version is required")
        return identity


_CURRENT_PROVIDER_DISPATCH_SCOPE: ContextVar[ProviderDispatchScope | None] = ContextVar(
    "harness_provider_dispatch_scope",
    default=None,
)


def current_provider_dispatch_scope() -> ProviderDispatchScope | None:
    return _CURRENT_PROVIDER_DISPATCH_SCOPE.get()


def current_provider_dispatch() -> ProviderDispatch | None:
    scope = current_provider_dispatch_scope()
    return scope.current() if scope is not None else None


async def resolve_provider_dispatch(profile_key: str, profile_version: str) -> ProviderDispatch:
    scope = current_provider_dispatch_scope()
    if scope is None:
        raise RuntimeError("provider dispatch scope is not active")
    return await scope.resolve(profile_key, profile_version)


async def resolve_provider_dispatch_for_model(model: str) -> ProviderDispatch:
    scope = current_provider_dispatch_scope()
    if scope is None:
        raise RuntimeError("provider dispatch scope is not active")
    return await scope.resolve_for_model(model)


@asynccontextmanager
async def provider_dispatch_scope(
    resolver: ProviderDispatchResolver,
    *,
    routes: Iterable[Mapping[str, Any]] | None = None,
    preferred_model: str | None = None,
    preferred_profile_key: str | None = None,
    routing_policy: Mapping[str, Any] | None = None,
    outcome_reporter: ProviderOutcomeReporter | None = None,
) -> AsyncIterator[ProviderDispatchScope]:
    scope = ProviderDispatchScope(
        resolver,
        routes=routes,
        preferred_model=preferred_model,
        preferred_profile_key=preferred_profile_key,
        routing_policy=routing_policy,
        outcome_reporter=outcome_reporter,
    )
    reset_token = _CURRENT_PROVIDER_DISPATCH_SCOPE.set(scope)
    try:
        yield scope
    finally:
        try:
            await scope.aclose()
        finally:
            try:
                _CURRENT_PROVIDER_DISPATCH_SCOPE.reset(reset_token)
            except ValueError:
                pass
