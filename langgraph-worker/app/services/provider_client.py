from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import json
import re
import socket
from collections.abc import AsyncGenerator
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit

import httpx

from app.config import settings
from app.models.analysis import RunRequest
from app.services.harness.admission import llm_slot
from app.services.harness.budget import current_run_budget
from app.services.harness.cancellation import (
    cancellable_await,
    cancellation_checkpoint,
    current_cancellation_token,
)
from app.services.harness.provider_dispatch_scope import (
    PromptCacheCapabilities,
    ProviderCapabilities,
)
from app.services.provider_dialect import (
    REASONING_DEEPSEEK_THINKING,
    REASONING_KIMI_GLM_EFFORT,
    REASONING_OPENAI_EFFORT,
    REASONING_QWEN_ENABLE_THINKING,
    kimi_glm_effort,
    openai_effort,
    qwen_thinking_enabled,
    resolve_dialect,
)


_BLOCKED_PROVIDER_HOSTS = frozenset({
    "localhost",
    "localhost.localdomain",
    "metadata",
    "metadata.google.internal",
    "instance-data",
    "instance-data.ec2.internal",
})
_BLOCKED_PROVIDER_HOST_SUFFIXES = (".localhost", ".local", ".internal", ".home.arpa")


def _validate_provider_base_url(value: str | None) -> str:
    """Accept only an explicit HTTP(S) provider endpoint outside local/private networks."""
    candidate = str(value or "")
    if not candidate:
        return ""
    if any(char.isspace() for char in candidate):
        raise ValueError("provider base URL contains unsupported URL components")
    try:
        parsed = urlsplit(candidate)
        hostname = (parsed.hostname or "").rstrip(".").lower()
        # Accessing port validates malformed values such as ``:bad``.
        _ = parsed.port
    except ValueError as exc:
        raise ValueError("provider base URL is invalid") from exc
    if parsed.scheme.lower() not in {"http", "https"} or not hostname:
        raise ValueError("provider base URL must use http or https")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("provider base URL must not contain credentials")
    if parsed.query or parsed.fragment:
        raise ValueError("provider base URL contains unsupported URL components")
    if hostname in _BLOCKED_PROVIDER_HOSTS or hostname.endswith(_BLOCKED_PROVIDER_HOST_SUFFIXES):
        raise ValueError("provider base URL points to a local or metadata host")
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        address = None
    if address is not None and (
        address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_reserved
        or address.is_unspecified
        or address.is_multicast
    ):
        raise ValueError("provider base URL points to a non-public address")
    return candidate.rstrip("/")


async def _assert_public_provider_endpoint(value: str) -> None:
    validated = _validate_provider_base_url(value)
    parsed = urlsplit(validated)
    hostname = str(parsed.hostname or "")
    try:
        addresses = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(
                hostname,
                parsed.port or (443 if parsed.scheme.lower() == "https" else 80),
                type=socket.SOCK_STREAM,
            ),
            timeout=2.0,
        )
    except (OSError, UnicodeError) as exc:
        raise ValueError("provider base URL host cannot be resolved") from exc
    if not addresses:
        raise ValueError("provider base URL host cannot be resolved")
    for *_prefix, sockaddr in addresses:
        try:
            resolved_address = ipaddress.ip_address(sockaddr[0])
        except ValueError as exc:
            raise ValueError("provider base URL resolved to an invalid address") from exc
        if (
            resolved_address.is_private
            or resolved_address.is_loopback
            or resolved_address.is_link_local
            or resolved_address.is_reserved
            or resolved_address.is_unspecified
            or resolved_address.is_multicast
        ):
            raise ValueError("provider base URL resolves to a non-public address")


# 上游错误码只放行枚举形状的取值。provider 的自由文本 message 不进这条通路——
# 它会回显请求里的字段值，而这条串要一路透传到后端并落进 ai_chat_run.error_message。
_PROVIDER_ERROR_TOKEN = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")
_PROVIDER_ERROR_FIELDS = ("code", "type", "param")


def provider_error_diagnostic(error: BaseException) -> str | None:
    """把上游异常压成 ``upstream=<status> code=<code> type=<type> param=<param>``。

    非重试状态码也必须分类：gpt-5.6 那次故障是 400，而
    :meth:`OpenAICompatibleProviderClient.failover_failure_class` 只认可重试状态码，
    对 400 返回 ``None``，于是故障原因在链路上整条丢失。
    """
    if isinstance(error, httpx.TimeoutException):
        return "upstream=timeout"
    if isinstance(error, httpx.ConnectError):
        return "upstream=connect_error"
    if not isinstance(error, httpx.HTTPStatusError) or error.response is None:
        return None
    parts = [f"upstream={error.response.status_code}"]
    try:
        body = error.response.json()
    except Exception:  # noqa: BLE001 - 上游可能回非 JSON，拿不到就只报状态码
        body = None
    detail = body.get("error") if isinstance(body, dict) else None
    if not isinstance(detail, dict):
        detail = body if isinstance(body, dict) else {}
    for field_name in _PROVIDER_ERROR_FIELDS:
        value = detail.get(field_name)
        if not isinstance(value, str):
            continue
        normalized = value.strip()
        if _PROVIDER_ERROR_TOKEN.match(normalized):
            parts.append(f"{field_name}={normalized}")
    return " ".join(parts)


@dataclass(frozen=True, slots=True)
class ProviderProfile:
    """Immutable runtime route; the API key is intentionally runtime-only."""

    profile_key: str
    endpoint: str
    model: str
    protocol: str = "responses"
    profile_version: str = "1"
    api_key: str | None = field(default=None, repr=False, compare=False)
    provider_capabilities: ProviderCapabilities | dict[str, Any] | None = None
    # Registry-declared vendor, used to pick the request dialect. Absent on
    # legacy routes, where the dialect falls back to the model name.
    provider_type: str | None = None

    def __post_init__(self) -> None:
        profile_key = str(self.profile_key or "").strip()
        endpoint = _validate_provider_base_url(self.endpoint)
        model = str(self.model or "").strip()
        protocol = str(self.protocol or "").strip().lower().replace("-", "_")
        version = str(self.profile_version or "").strip()
        if not profile_key:
            raise ValueError("provider profile key is required")
        if not endpoint:
            raise ValueError("provider profile endpoint is required")
        if not model:
            raise ValueError("provider profile model is required")
        if protocol in {"chat", "chat_completion"}:
            protocol = "chat_completions"
        if protocol not in {"responses", "chat_completions"}:
            raise ValueError("provider profile protocol must be responses or chat_completions")
        if not version:
            raise ValueError("provider profile version is required")
        object.__setattr__(self, "profile_key", profile_key)
        object.__setattr__(self, "endpoint", endpoint)
        object.__setattr__(self, "model", model)
        object.__setattr__(self, "protocol", protocol)
        object.__setattr__(self, "profile_version", version)
        object.__setattr__(self, "api_key", str(self.api_key or "").strip() or None)
        object.__setattr__(self, "provider_type", str(self.provider_type or "").strip() or None)
        capabilities = ProviderCapabilities.from_payload(self.provider_capabilities)
        if capabilities is not None and capabilities.prompt_cache is not None:
            capabilities.prompt_cache.assert_responses_protocol(protocol)
        object.__setattr__(self, "provider_capabilities", capabilities)

    def snapshot(self) -> dict[str, Any]:
        """Return the durable/public route identity, never the runtime secret."""
        snapshot: dict[str, Any] = {
            "profileKey": self.profile_key,
            "profileVersion": self.profile_version,
            "endpoint": self.endpoint,
            "endpointFingerprint": hashlib.sha256(self.endpoint.encode("utf-8")).hexdigest(),
            "model": self.model,
            "protocol": self.protocol,
        }
        if self.provider_capabilities is not None:
            snapshot["providerCapabilities"] = self.provider_capabilities.snapshot()
        return snapshot

    def assert_supports_request(
        self,
        *,
        stream: bool = False,
        tools: bool = False,
        require_json: bool = False,
        reasoning_mode: str | None = None,
    ) -> None:
        capabilities = self.provider_capabilities
        if capabilities is None:
            return
        reasoning_requested = str(reasoning_mode or "").strip().lower() not in {
            "",
            "none",
            "off",
            "disabled",
        }
        requirements = (
            (stream, capabilities.supports_streaming, "streaming"),
            (tools, capabilities.supports_tools, "tools"),
            (require_json, capabilities.supports_json_object, "json_object"),
            (reasoning_requested, capabilities.supports_reasoning, "reasoning"),
        )
        for requested, supported, name in requirements:
            if requested and not supported:
                raise ValueError(f"provider profile does not support {name}")


class OpenAICompatibleProviderClient:
    # Transport-level retries are deliberately disabled: the agent kernel owns a
    # single shared retry+failover budget, so retrying here would multiply it.
    _MAX_TRANSPORT_ATTEMPTS = 1
    _MAX_CACHE_PREFIX_CHAIN_ITEMS = 64
    _RETRYABLE_ERRORS = (httpx.ConnectError, httpx.TimeoutException)
    _RETRYABLE_STATUS_CODES = frozenset({429, 500, 502, 503, 504})
    _CHAT_COMPLETIONS_WIRE = "chat_completions"
    _RESPONSES_WIRE = "responses"
    _GPT_MODEL_VERSION = re.compile(r"^gpt-(\d+)(?:\.(\d+))?")

    def resolve_provider_profile(
        self,
        model: str,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        protocol: str | None = None,
        route_snapshot: dict[str, Any] | None = None,
    ) -> ProviderProfile:
        """Freeze one explicit route for an Agent run before its first dispatch."""
        normalized_protocol = str(
            protocol or getattr(settings, "openai_wire_api", self._RESPONSES_WIRE) or self._RESPONSES_WIRE
        ).strip().lower().replace("-", "_")
        if normalized_protocol in {"chat", "chat_completion"}:
            normalized_protocol = self._CHAT_COMPLETIONS_WIRE
        if normalized_protocol not in {self._RESPONSES_WIRE, self._CHAT_COMPLETIONS_WIRE}:
            raise ValueError(f"unsupported Provider wire API: {normalized_protocol}")
        route = route_snapshot if isinstance(route_snapshot, dict) else {}
        configured_endpoint = route.get("endpoint") or base_url
        if not configured_endpoint:
            configured_endpoint = (
                settings.openai_responses_base_url
                if normalized_protocol == self._RESPONSES_WIRE
                else settings.openai_base_url
            )
        return ProviderProfile(
            profile_key=str(route.get("profileKey") or getattr(settings, "openai_provider_profile", "default") or "default"),
            profile_version=str(route.get("profileVersion") or getattr(settings, "openai_provider_profile_version", "1") or "1"),
            endpoint=configured_endpoint,
            model=str(route.get("model") or model or settings.default_model or "").strip(),
            protocol=str(route.get("protocol") or normalized_protocol),
            api_key=(api_key if api_key is not None else None) if route else settings.openai_api_key,
            provider_capabilities=route.get("providerCapabilities"),
            provider_type=route.get("providerType") or getattr(settings, "provider_type", None),
        )

    async def invoke(
        self,
        *,
        messages: list[dict],
        model: str,
        temperature: float | None,
        max_tokens: int | None,
        require_json: bool,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_millis: int | None = None,
        request: RunRequest | None = None,
        tools: list[dict[str, Any]] | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
        cache_affinity: str | None = None,
        request_family: str | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> dict:
        token = current_cancellation_token()
        self._require_run_token_capacity()
        max_tokens = self._bounded_max_tokens(max_tokens)
        async with llm_slot(token):
            cancellation_checkpoint(token)
            result = await self._invoke_admitted(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                require_json=require_json,
                base_url=base_url,
                api_key=api_key,
                timeout_millis=timeout_millis,
                request=request,
                tools=tools,
                reasoning_mode=reasoning_mode,
                reasoning_effort=reasoning_effort,
                cache_affinity=cache_affinity,
                request_family=request_family,
                provider_profile=provider_profile,
            )
            cancellation_checkpoint(token)
            self._record_run_usage(result)
            return result

    async def _invoke_admitted(
        self,
        *,
        messages: list[dict],
        model: str,
        temperature: float | None,
        max_tokens: int | None,
        require_json: bool,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_millis: int | None = None,
        request: RunRequest | None = None,
        tools: list[dict[str, Any]] | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
        cache_affinity: str | None = None,
        request_family: str | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> dict:
        if request is None:
            return await self._invoke_openai_compatible(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                require_json=require_json,
                base_url=base_url,
                api_key=api_key,
                timeout_millis=timeout_millis,
                tools=tools,
                reasoning_mode=reasoning_mode,
                reasoning_effort=reasoning_effort,
                cache_affinity=cache_affinity,
                request_family=request_family,
                provider_profile=provider_profile,
            )

        failures: list[dict[str, str]] = []
        for provider in self._resolve_provider_order(request):
            try:
                if provider == "dify":
                    return await self._invoke_dify_blocking(
                        request=request,
                        messages=messages,
                        model=model,
                        timeout_millis=timeout_millis,
                    )
                return await self._invoke_openai_compatible(
                    messages=messages,
                    model=model,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    require_json=require_json,
                    base_url=base_url,
                    api_key=api_key,
                    timeout_millis=timeout_millis,
                    tools=tools,
                    reasoning_mode=reasoning_mode,
                    reasoning_effort=reasoning_effort,
                    cache_affinity=cache_affinity,
                    request_family=request_family,
                    provider_profile=provider_profile,
                )
            except Exception as exc:
                failures.append({"provider": provider, "reason": str(exc) or exc.__class__.__name__})

        return self._build_final_fallback_result(
            request=request,
            model=model,
            messages=messages,
            failures=failures,
        )

    async def stream(
        self,
        *,
        messages: list[dict],
        model: str,
        temperature: float | None,
        max_tokens: int | None,
        require_json: bool,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_millis: int | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
        cache_affinity: str | None = None,
        request_family: str | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> AsyncGenerator[dict, None]:
        token = current_cancellation_token()
        self._require_run_token_capacity()
        max_tokens = self._bounded_max_tokens(max_tokens)
        async with llm_slot(token):
            cancellation_checkpoint(token)
            async for event in self._stream_admitted(
                messages=messages,
                model=model,
                temperature=temperature,
                max_tokens=max_tokens,
                require_json=require_json,
                base_url=base_url,
                api_key=api_key,
                timeout_millis=timeout_millis,
                reasoning_mode=reasoning_mode,
                reasoning_effort=reasoning_effort,
                cache_affinity=cache_affinity,
                request_family=request_family,
                provider_profile=provider_profile,
            ):
                cancellation_checkpoint(token)
                if event.get("event") == "done":
                    self._record_run_usage(event)
                yield event

    async def _stream_admitted(
        self,
        *,
        messages: list[dict],
        model: str,
        temperature: float | None,
        max_tokens: int | None,
        require_json: bool,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_millis: int | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
        cache_affinity: str | None = None,
        request_family: str | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> AsyncGenerator[dict, None]:
        effective_profile = provider_profile
        if effective_profile is not None:
            effective_profile.assert_supports_request(
                stream=True,
                require_json=require_json,
                reasoning_mode=reasoning_mode,
            )
            model = effective_profile.model
            wire_api = effective_profile.protocol
            transport_fallback = None
            base_url = effective_profile.endpoint
            api_key = self._require_provider_profile_api_key(effective_profile)
        else:
            wire_api, transport_fallback = self._resolve_wire_api(model)
        payload = self._build_payload(
            messages,
            model,
            temperature,
            max_tokens,
            require_json,
            stream=True,
            reasoning_mode=reasoning_mode,
            reasoning_effort=reasoning_effort,
            cache_affinity=cache_affinity,
            wire_api=wire_api,
            provider_type=None if effective_profile is None else effective_profile.provider_type,
            provider_profile=effective_profile,
        )
        cache_continuity = self._cache_continuity_snapshot(
            payload,
            wire_api,
            cache_affinity=cache_affinity,
            provider_profile=effective_profile,
            request_family=request_family,
        )
        endpoint = self._provider_endpoint(wire_api, base_url=base_url)
        await self._assert_public_endpoint(endpoint)
        for attempt in range(1, self._MAX_TRANSPORT_ATTEMPTS + 1):
            yielded_chunk = False
            try:
                cancellation_checkpoint()
                async with httpx.AsyncClient(
                    timeout=self._resolve_timeout_seconds(timeout_millis),
                    trust_env=False,
                ) as client:
                    stream_context = client.stream(
                        "POST",
                        endpoint,
                        headers=self._headers(api_key),
                        json=payload,
                    )
                    response = await cancellable_await(stream_context.__aenter__())
                    try:
                        response.raise_for_status()
                        line_iterator = response.aiter_lines().__aiter__()
                        if wire_api == self._RESPONSES_WIRE:
                            terminal_seen = False
                            last_sequence = -1
                            while True:
                                try:
                                    line = await cancellable_await(anext(line_iterator))
                                except StopAsyncIteration:
                                    break
                                cancellation_checkpoint()
                                if not line or not line.startswith("data:"):
                                    continue
                                item = json.loads(line[len("data:"):].strip())
                                sequence = item.get("sequence_number")
                                if isinstance(sequence, int):
                                    if sequence <= last_sequence:
                                        raise RuntimeError("responses stream sequence is not increasing")
                                    last_sequence = sequence
                                event_type = str(item.get("type") or "")
                                if event_type == "response.output_text.delta":
                                    delta = item.get("delta")
                                    if delta:
                                        yielded_chunk = True
                                        yield {"event": "delta", "delta": str(delta)}
                                    continue
                                if event_type == "response.completed":
                                    terminal_seen = True
                                    response_body = item.get("response") if isinstance(item.get("response"), dict) else {}
                                    usage_summary = self._usage_summary(response_body.get("usage") or {})
                                    done = self._done_event(
                                        usage_summary,
                                        wire_api,
                                        transport_fallback,
                                        cache_continuity,
                                        effective_profile,
                                    )
                                    yield done
                                    break
                                if event_type in {"response.incomplete", "response.failed"}:
                                    terminal_seen = True
                                    raise RuntimeError(self._responses_terminal_error(event_type, item))
                            if not terminal_seen:
                                raise RuntimeError("responses stream ended without a terminal event")
                            return
                        finish_seen = False
                        done_emitted = False
                        usage_summary: dict[str, int] = {}
                        while True:
                            try:
                                line = await cancellable_await(anext(line_iterator))
                            except StopAsyncIteration:
                                break
                            cancellation_checkpoint()
                            if not line or not line.startswith("data:"):
                                continue
                            data = line[len("data:"):].strip()
                            if data == "[DONE]":
                                break
                            item = json.loads(data)
                            choice = (item.get("choices") or [{}])[0]
                            delta = (choice.get("delta") or {}).get("content")
                            if delta:
                                yielded_chunk = True
                                yield {"event": "delta", "delta": delta}
                            if choice.get("finish_reason"):
                                yielded_chunk = True
                                finish_seen = True
                            usage = item.get("usage") or {}
                            if usage:
                                usage_summary = self._usage_summary(usage)
                            if finish_seen and usage_summary and not done_emitted:
                                done_emitted = True
                                yield self._done_event(
                                    usage_summary,
                                    wire_api,
                                    transport_fallback,
                                    cache_continuity,
                                    effective_profile,
                                )
                        if finish_seen and not done_emitted:
                            yield self._done_event(
                                usage_summary,
                                wire_api,
                                transport_fallback,
                                cache_continuity,
                                effective_profile,
                            )
                    finally:
                        await stream_context.__aexit__(None, None, None)
                return
            except self._RETRYABLE_ERRORS:
                if yielded_chunk or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await cancellable_await(asyncio.sleep(self._retry_backoff_seconds(attempt)))
            except httpx.HTTPStatusError as exc:
                if yielded_chunk or not self._is_retryable_status(exc) or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await cancellable_await(asyncio.sleep(self._retry_backoff_seconds(attempt)))

    async def _invoke_openai_compatible(
        self,
        *,
        messages: list[dict],
        model: str,
        temperature: float | None,
        max_tokens: int | None,
        require_json: bool,
        base_url: str | None = None,
        api_key: str | None = None,
        timeout_millis: int | None = None,
        tools: list[dict[str, Any]] | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
        cache_affinity: str | None = None,
        request_family: str | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> dict:
        effective_profile = provider_profile
        if effective_profile is not None:
            effective_profile.assert_supports_request(
                tools=bool(tools),
                require_json=require_json,
                reasoning_mode=reasoning_mode,
            )
        model_chain = [effective_profile.model] if effective_profile is not None else self._resolve_model_order(model)
        failures: list[dict[str, str]] = []
        for index, candidate in enumerate(model_chain):
            self._require_run_token_capacity()
            cancellation_checkpoint()
            try:
                if effective_profile is not None:
                    wire_api, transport_fallback = effective_profile.protocol, None
                    request_base_url = effective_profile.endpoint
                    request_api_key = self._require_provider_profile_api_key(effective_profile)
                else:
                    wire_api, transport_fallback = self._resolve_wire_api(candidate)
                    request_base_url, request_api_key = base_url, api_key
                payload = self._build_payload(
                    messages,
                    candidate,
                    temperature,
                    max_tokens,
                    require_json,
                    stream=False,
                    tools=tools,
                    reasoning_mode=reasoning_mode,
                    reasoning_effort=reasoning_effort,
                    cache_affinity=cache_affinity,
                    wire_api=wire_api,
                    provider_type=(
                        None if effective_profile is None else effective_profile.provider_type
                    ),
                    provider_profile=effective_profile,
                )
                cache_continuity = self._cache_continuity_snapshot(
                    payload,
                    wire_api,
                    cache_affinity=cache_affinity,
                    provider_profile=effective_profile,
                    request_family=request_family,
                )
                if wire_api == self._RESPONSES_WIRE:
                    data = await self._invoke_responses_with_retry(
                        payload=payload,
                        base_url=request_base_url,
                        api_key=request_api_key,
                        timeout_millis=timeout_millis,
                    )
                    result = self._normalize_responses_result(data, candidate)
                else:
                    data = await self._invoke_with_retry(
                        payload=payload,
                        base_url=request_base_url,
                        api_key=request_api_key,
                        timeout_millis=timeout_millis,
                    )
                    result = self._normalize_chat_result(data, candidate)
            except Exception as exc:
                failures.append({
                    "model": candidate,
                    "reason": str(exc) or exc.__class__.__name__,
                })
                if not self.is_failover_eligible(exc) or index >= len(model_chain) - 1:
                    raise
                continue
            result["wire_api"] = wire_api
            result["cacheContinuity"] = cache_continuity
            if effective_profile is not None:
                result["providerProfile"] = effective_profile.snapshot()
            if transport_fallback:
                result["providerTransportFallback"] = transport_fallback
            if index > 0:
                result["providerFailover"] = {
                    "from": model_chain[0],
                    "to": candidate,
                    "reason": failures[-1]["reason"] if failures else "primary_model_failed",
                }
            return result
        raise RuntimeError("model failover exhausted")

    def _resolve_model_order(self, model: str) -> list[str]:
        primary = str(model or settings.default_model or "").strip() or settings.default_model
        fallback = str(getattr(settings, "provider_fallback_model", "") or "").strip()
        if not fallback or fallback == primary:
            return [primary]
        return [primary, fallback]

    def _resolve_wire_api(self, model: str) -> tuple[str, dict[str, str] | None]:
        preferred = str(getattr(settings, "openai_wire_api", self._RESPONSES_WIRE) or "").strip().lower()
        preferred = preferred.replace("-", "_")
        if preferred in {"chat", "chat_completion", self._CHAT_COMPLETIONS_WIRE}:
            return self._CHAT_COMPLETIONS_WIRE, None
        if preferred != self._RESPONSES_WIRE:
            raise ValueError(f"unsupported Provider wire API: {preferred}")

        normalized_model = str(model or "").strip().lower()
        if normalized_model in self._responses_model_names():
            return self._RESPONSES_WIRE, None
        if bool(getattr(settings, "openai_responses_chat_fallback_enabled", True)):
            return self._CHAT_COMPLETIONS_WIRE, {
                "from": self._RESPONSES_WIRE,
                "to": self._CHAT_COMPLETIONS_WIRE,
                "reason": "model_not_responses_capable",
                "model": str(model or ""),
            }
        raise ValueError(f"model {model} does not support Responses")

    def _responses_model_names(self) -> set[str]:
        configured = str(getattr(settings, "openai_responses_models", "") or "")
        return {
            model.strip().lower()
            for model in configured.replace(";", ",").split(",")
            if model.strip()
        }

    def _prompt_cache_key_model_names(self) -> set[str]:
        configured = str(getattr(settings, "openai_prompt_cache_key_models", "") or "")
        return {
            model.strip().lower()
            for model in configured.replace(";", ",").split(",")
            if model.strip()
        }

    def _provider_user_model_names(self) -> set[str]:
        configured = str(getattr(settings, "openai_provider_user_models", "") or "")
        return {
            model.strip().lower()
            for model in configured.replace(";", ",").split(",")
            if model.strip()
        }

    def _should_send_prompt_cache_key(
        self,
        model: str,
        provider_profile: ProviderProfile | None = None,
    ) -> bool:
        """Return the cache-key policy for the model actually dispatched.

        The selected Profile model wins over the caller's requested alias. A
        model must match the explicit model policy; unknown models do not get
        vendor-specific fields by inference.
        """
        prompt_cache = self._profile_prompt_cache(provider_profile)
        if prompt_cache is not None:
            return (
                provider_profile is not None
                and provider_profile.protocol == self._RESPONSES_WIRE
                and prompt_cache.strategy in {"openai_legacy", "openai_gpt_5_6"}
            )
        selected_model = self._dispatch_model_name(model, provider_profile)
        return self._model_matches_policy(
            selected_model,
            self._prompt_cache_key_model_names(),
        )

    @staticmethod
    def _profile_prompt_cache(provider_profile: ProviderProfile | None):
        if provider_profile is None or provider_profile.provider_capabilities is None:
            return None
        return provider_profile.provider_capabilities.prompt_cache

    @staticmethod
    def _responses_prompt_cache_key(cache_affinity: str) -> str:
        if len(cache_affinity) <= 64:
            return cache_affinity
        return hashlib.sha256(
            f"noval-responses-prompt-cache-key-v1:{cache_affinity}".encode("utf-8"),
        ).hexdigest()

    def _effective_responses_prompt_cache(
        self,
        model: str,
        provider_profile: ProviderProfile | None,
    ) -> PromptCacheCapabilities | None:
        explicit = self._profile_prompt_cache(provider_profile)
        if explicit is not None:
            return explicit
        selected_model = self._dispatch_model_name(model, provider_profile)
        provider_user_enabled = self._model_matches_policy(
            selected_model,
            self._provider_user_model_names(),
        )
        prompt_cache_key_enabled = self._model_matches_policy(
            selected_model,
            self._prompt_cache_key_model_names(),
        )
        if provider_user_enabled and prompt_cache_key_enabled:
            raise ValueError("Responses cache model policies overlap")
        if provider_user_enabled:
            return PromptCacheCapabilities(
                strategy="deepseek_automatic",
                mode="provider_managed",
                retention="provider_default",
                breakpoint="none",
            )
        if not prompt_cache_key_enabled:
            return None
        version = self._GPT_MODEL_VERSION.match(selected_model)
        # A compatible gateway may expose this model without the new cache API.
        # Explicit profile capabilities above remain authoritative for such routes.
        official_endpoint = (
            provider_profile is not None
            and urlsplit(provider_profile.endpoint).hostname == "api.openai.com"
        )
        if version is not None and official_endpoint:
            major = int(version.group(1))
            minor = int(version.group(2) or 0)
            if major > 5 or (major == 5 and minor >= 6):
                return PromptCacheCapabilities(
                    strategy="openai_gpt_5_6",
                    mode="implicit",
                    retention="30m",
                    breakpoint="stable_prefix",
                )
        return PromptCacheCapabilities(
            strategy="openai_legacy",
            mode="implicit",
            retention="provider_default",
            breakpoint="none",
        )

    @staticmethod
    def _dispatch_model_name(
        model: str,
        provider_profile: ProviderProfile | None = None,
    ) -> str:
        if provider_profile is not None and provider_profile.model:
            return str(provider_profile.model).strip().lower()
        return str(model or "").strip().lower()

    @staticmethod
    def _model_matches_policy(model: str, policy: set[str]) -> bool:
        if not model or not policy:
            return False
        for rule in policy:
            if rule == "*":
                return True
            if rule.endswith("*") and model.startswith(rule[:-1]):
                return True
            if model == rule:
                return True
        return False

    def _provider_endpoint(self, wire_api: str, *, base_url: str | None = None) -> str:
        if wire_api == self._RESPONSES_WIRE:
            return f"{self._resolve_responses_base_url(base_url)}/responses"
        return f"{self._resolve_base_url(base_url)}/chat/completions"

    def _build_responses_input(
        self,
        messages: list[dict],
        *,
        stable_prefix_breakpoint: bool = False,
    ) -> tuple[str | None, list[dict[str, Any]]]:
        instructions: str | None = None
        response_input: list[dict[str, Any]] = []
        for index, message in enumerate(messages):
            if not isinstance(message, dict):
                continue
            role = str(message.get("role") or "user").strip().lower() or "user"
            content = self._string_content(message.get("content"))
            raw_tool_calls = message.get("tool_calls") if isinstance(message.get("tool_calls"), list) else []

            if index == 0 and role in {"system", "developer"} and content:
                if stable_prefix_breakpoint:
                    response_input.append({
                        "role": "developer",
                        "content": [{
                            "type": "input_text",
                            "text": content,
                            "prompt_cache_breakpoint": {"mode": "explicit"},
                        }],
                    })
                else:
                    instructions = content
            elif role == "tool":
                call_id = str(message.get("tool_call_id") or "").strip()
                if not call_id:
                    raise ValueError("Responses function_call_output requires tool_call_id")
                response_input.append({
                    "type": "function_call_output",
                    "call_id": call_id,
                    "output": content,
                })
            else:
                reasoning_content = self._string_content(message.get("reasoning_content"))
                if role == "assistant" and reasoning_content:
                    response_input.append({
                        "type": "reasoning",
                        "content": [{"type": "reasoning_text", "text": reasoning_content}],
                    })
                if content or not raw_tool_calls:
                    response_input.append({"role": role, "content": content})

            for raw_call in raw_tool_calls:
                if not isinstance(raw_call, dict):
                    continue
                function = raw_call.get("function") if isinstance(raw_call.get("function"), dict) else {}
                name = str(raw_call.get("name") or function.get("name") or "").strip()
                call_id = str(raw_call.get("call_id") or raw_call.get("id") or name).strip()
                if not name or not call_id:
                    raise ValueError("Responses function_call requires name and call_id")
                response_input.append({
                    "type": "function_call",
                    "call_id": call_id,
                    "name": name,
                    "arguments": self._json_arguments(raw_call.get("arguments", function.get("arguments"))),
                })
        return instructions, response_input

    def _build_responses_tools(self, tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
        normalized: list[dict[str, Any]] = []
        for schema in tools:
            if not isinstance(schema, dict) or schema.get("type") != "function":
                continue
            function = schema.get("function") if isinstance(schema.get("function"), dict) else schema
            name = str(function.get("name") or "").strip()
            if not name:
                continue
            item: dict[str, Any] = {
                "type": "function",
                "name": name,
                "parameters": function.get("parameters") if isinstance(function.get("parameters"), dict) else {},
            }
            description = str(function.get("description") or "").strip()
            if description:
                item["description"] = description
            if "strict" in function:
                item["strict"] = bool(function.get("strict"))
            normalized.append(item)
        return normalized

    def _normalize_chat_result(self, data: dict[str, Any], fallback_model: str) -> dict[str, Any]:
        choice = (data.get("choices") or [{}])[0]
        message = choice.get("message") or {}
        usage_summary = self._usage_summary(data.get("usage") or {})
        raw_tool_calls = message.get("tool_calls") if isinstance(message.get("tool_calls"), list) else []
        return {
            "model_name": data.get("model", fallback_model),
            "content": message.get("content", "") or "",
            "reasoning_content": message.get("reasoning_content"),
            "raw_tool_calls": raw_tool_calls,
            "tool_calls": self._normalize_tool_calls(raw_tool_calls),
            "token_used": int(usage_summary.get("totalTokens") or 0),
            "prompt_cache_hit_tokens": usage_summary.get("promptCacheHitTokens", 0),
            "prompt_cache_miss_tokens": usage_summary.get("promptCacheMissTokens", 0),
            "prompt_cache_write_tokens": usage_summary.get("promptCacheWriteTokens", 0),
            "usage": usage_summary,
        }

    def _normalize_responses_result(self, data: dict[str, Any], fallback_model: str) -> dict[str, Any]:
        status = str(data.get("status") or "").strip().lower()
        if status != "completed":
            raise RuntimeError(self._responses_status_error("response", status or "missing_status", data))

        content_parts: list[str] = []
        reasoning_parts: list[str] = []
        raw_tool_calls: list[dict[str, Any]] = []
        for item in data.get("output") or []:
            if not isinstance(item, dict):
                continue
            item_type = str(item.get("type") or "")
            if item_type == "message":
                content_parts.extend(self._response_content_text(item.get("content"), {"output_text"}))
            elif item_type == "reasoning":
                reasoning_parts.extend(self._response_content_text(item.get("content"), {"reasoning_text"}))
            elif item_type == "function_call":
                raw_tool_calls.append(dict(item))

        usage_summary = self._usage_summary(data.get("usage") or {})
        return {
            "model_name": data.get("model", fallback_model),
            "content": "".join(content_parts),
            "reasoning_content": "".join(reasoning_parts) or None,
            "raw_tool_calls": raw_tool_calls,
            "tool_calls": self._normalize_tool_calls(raw_tool_calls),
            "token_used": int(usage_summary.get("totalTokens") or 0),
            "prompt_cache_hit_tokens": usage_summary.get("promptCacheHitTokens", 0),
            "prompt_cache_miss_tokens": usage_summary.get("promptCacheMissTokens", 0),
            "prompt_cache_write_tokens": usage_summary.get("promptCacheWriteTokens", 0),
            "usage": usage_summary,
        }

    def _done_event(
        self,
        usage_summary: dict[str, Any],
        wire_api: str,
        transport_fallback: dict[str, str] | None,
        cache_continuity: dict[str, Any] | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> dict[str, Any]:
        done: dict[str, Any] = {
            "event": "done",
            "tokenUsed": int(usage_summary.get("totalTokens") or 0),
            "promptCacheHitTokens": int(usage_summary.get("promptCacheHitTokens") or 0),
            "promptCacheMissTokens": int(usage_summary.get("promptCacheMissTokens") or 0),
            "promptCacheWriteTokens": int(usage_summary.get("promptCacheWriteTokens") or 0),
            "promptCacheMissTokensDerived": bool(
                usage_summary.get("promptCacheMissTokensDerived")
            ),
            "usage": usage_summary,
            "wireApi": wire_api,
        }
        if cache_continuity:
            done["cacheContinuity"] = dict(cache_continuity)
        if transport_fallback:
            done["providerTransportFallback"] = dict(transport_fallback)
        if provider_profile is not None:
            done["providerProfile"] = provider_profile.snapshot()
        return done

    def _responses_terminal_error(self, event_type: str, item: dict[str, Any]) -> str:
        response = item.get("response") if isinstance(item.get("response"), dict) else {}
        return self._responses_status_error(event_type, event_type.removeprefix("response."), response)

    def _responses_status_error(self, source: str, status: str, body: dict[str, Any]) -> str:
        error = body.get("error") if isinstance(body.get("error"), dict) else {}
        incomplete = body.get("incomplete_details") if isinstance(body.get("incomplete_details"), dict) else {}
        reason = str(error.get("code") or incomplete.get("reason") or status or "unknown").strip()
        return f"responses {source} {status}: {reason}"

    @staticmethod
    def _response_content_text(content: Any, allowed_types: set[str]) -> list[str]:
        if isinstance(content, str):
            return [content]
        if not isinstance(content, list):
            return []
        parts: list[str] = []
        for part in content:
            if not isinstance(part, dict) or str(part.get("type") or "") not in allowed_types:
                continue
            text = part.get("text")
            if text:
                parts.append(str(text))
        return parts

    @staticmethod
    def _string_content(value: Any) -> str:
        if value is None:
            return ""
        if isinstance(value, str):
            return value
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _json_arguments(value: Any) -> str:
        if isinstance(value, str):
            return value
        if isinstance(value, dict):
            return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        return "{}"

    async def _invoke_dify_blocking(
        self,
        *,
        request: RunRequest,
        messages: list[dict],
        model: str,
        timeout_millis: int | None = None,
    ) -> dict:
        workflow_id = self._resolve_dify_workflow_id(request, model)
        if not workflow_id:
            raise ValueError("missing dify workflow id")

        api_key = self._resolve_dify_api_key(request)
        if not api_key:
            raise ValueError("missing dify api key")

        base_url = self._resolve_dify_base_url(request)
        if not base_url:
            raise ValueError("missing dify base url")
        endpoint = f"{base_url}/workflows/run"
        await self._assert_public_endpoint(endpoint)

        payload = {
            "inputs": {
                "content": self._render_dify_content(messages),
                "analysisType": self._analysis_type(request),
                "workflowId": workflow_id,
            },
            "response_mode": "blocking",
            "user": "novel-analyzer",
        }
        cancellation_checkpoint()
        async with httpx.AsyncClient(
            timeout=self._resolve_timeout_seconds(timeout_millis),
            trust_env=False,
        ) as client:
            response = await cancellable_await(client.post(
                    endpoint,
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {api_key}",
                    },
                    json=payload,
                ))
            response.raise_for_status()
            body = response.json() or {}
        cancellation_checkpoint()

        data = body.get("data") or {}
        outputs = data.get("outputs") or {}
        content = (
            outputs.get("text")
            or outputs.get("result")
            or outputs.get("answer")
            or data.get("answer")
            or ""
        )
        if not content:
            raise ValueError("empty dify response content")

        token_used_raw = data.get("total_tokens")
        try:
            token_used = int(token_used_raw or 0)
        except (TypeError, ValueError):
            token_used = 0
        if token_used <= 0:
            token_used = max(120, len(payload["inputs"]["content"]) // 2)

        return {
            "model_name": f"dify:{workflow_id}",
            "content": content,
            "token_used": token_used,
        }

    def _build_payload(
        self,
        messages: list[dict],
        model: str,
        temperature: float | None,
        max_tokens: int | None,
        require_json: bool,
        stream: bool,
        tools: list[dict[str, Any]] | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
        cache_affinity: str | None = None,
        wire_api: str = _CHAT_COMPLETIONS_WIRE,
        provider_type: str | None = None,
        provider_profile: ProviderProfile | None = None,
    ) -> dict:
        if provider_profile is not None:
            model = provider_profile.model
            provider_type = provider_profile.provider_type or provider_type
        dialect = resolve_dialect(provider_type, model)
        if wire_api == self._RESPONSES_WIRE:
            prompt_cache = self._effective_responses_prompt_cache(model, provider_profile)
            instructions, response_input = self._build_responses_input(
                messages,
                stable_prefix_breakpoint=(
                    prompt_cache is not None
                    and prompt_cache.strategy == "openai_gpt_5_6"
                    and prompt_cache.breakpoint == "stable_prefix"
                ),
            )
            payload: dict[str, Any] = {
                "model": model or settings.default_model,
                "input": response_input,
                "stream": stream,
            }
            if instructions:
                payload["instructions"] = instructions
            normalized_cache_affinity = str(cache_affinity or "").strip()
            if (
                normalized_cache_affinity
                and prompt_cache is not None
                and prompt_cache.strategy in {"openai_legacy", "openai_gpt_5_6"}
            ):
                payload["prompt_cache_key"] = self._responses_prompt_cache_key(
                    normalized_cache_affinity,
                )
            if prompt_cache is not None and prompt_cache.strategy == "openai_gpt_5_6":
                cache_options: dict[str, str] = {"mode": prompt_cache.mode}
                if prompt_cache.retention == "30m":
                    cache_options["ttl"] = "30m"
                payload["prompt_cache_options"] = cache_options
            elif (
                prompt_cache is not None
                and prompt_cache.strategy == "openai_legacy"
                and prompt_cache.retention != "provider_default"
            ):
                payload["prompt_cache_retention"] = prompt_cache.retention
            provider_user = (
                self._provider_user_id(model, normalized_cache_affinity, provider_profile)
                if prompt_cache is not None and prompt_cache.strategy == "deepseek_automatic"
                else None
            )
            if provider_user:
                payload["user"] = provider_user
            normalized_reasoning_mode = self._normalize_reasoning_mode(reasoning_mode)
            # 只有声明过 Responses 推理档位的族才发 reasoning；契约未核实的族
            # 直接省略，避免未知枚举被 400 拒掉。
            if dialect.emits_reasoning:
                # 显式档位优先，和 chat/completions 分支同一套语义：前端选了哪档就发哪档。
                # 这条线之前只看 reasoning_mode，fast 一律压成族内下限，用户在选择器里
                # 选的 high/xhigh 到了 Responses 上被整个丢掉，上游只会看到 effort=none
                # ——也就是"没传思考强度"。deep 的行为一个字没动。
                requested_tier = self._normalize_reasoning_effort(reasoning_effort)
                explicit_tier = requested_tier is not None and requested_tier not in {"none", "off"}
                if normalized_reasoning_mode == "deep" or (
                    normalized_reasoning_mode == "fast" and explicit_tier
                ):
                    # Responses 统一用 reasoning.effort
                    if dialect.reasoning_style == REASONING_DEEPSEEK_THINKING:
                        effort = self._normalize_responses_reasoning_effort(reasoning_effort)
                    elif dialect.reasoning_style == REASONING_KIMI_GLM_EFFORT:
                        effort = kimi_glm_effort(reasoning_effort)
                    elif dialect.reasoning_style == REASONING_QWEN_ENABLE_THINKING:
                        # Qwen 在 Responses 上也映射到 reasoning.effort
                        effort = kimi_glm_effort(reasoning_effort, default="high")
                    else:
                        effort = openai_effort(
                            reasoning_effort, extended=dialect.extended_openai_effort
                        )
                    payload["reasoning"] = {"effort": effort}
                elif normalized_reasoning_mode == "fast":
                    payload["reasoning"] = {"effort": dialect.responses_fast_effort}
            if temperature is not None:
                # OpenAI o 系列：任何推理模式都不接受 temperature (accepts_temperature=False)
                # DeepSeek：只在 deep 模式不接受 temperature
                # Kimi/GLM/Qwen：所有模式都接受 temperature
                should_send = dialect.accepts_temperature and not (
                    dialect.reasoning_style == REASONING_DEEPSEEK_THINKING
                    and normalized_reasoning_mode == "deep"
                )
                if should_send:
                    payload["temperature"] = temperature
            if max_tokens is not None:
                payload["max_output_tokens"] = max_tokens
            if require_json:
                payload["text"] = {"format": {"type": "json_object"}}
                # Responses validates JSON instructions in input separately from instructions.
                response_input.append({"role": "developer", "content": "Return a valid JSON object."})
            response_tools = self._build_responses_tools(tools or [])
            if response_tools:
                payload["tools"] = response_tools
                payload["tool_choice"] = "auto"
            return payload

        payload: dict[str, Any] = {
            "model": model or settings.default_model,
            "messages": messages,
            "stream": stream,
        }
        if stream:
            payload["stream_options"] = {"include_usage": True}
        normalized_reasoning_mode = self._normalize_reasoning_mode(reasoning_mode)
        # 每个供应商族只接受自己声明过的推理参数：未知字段会被 400 拒掉，
        # 契约未核实的族一律不发推理参数，降级成普通请求而不是冒险。
        if normalized_reasoning_mode in {"deep", "fast"} and dialect.emits_reasoning:
            # 显式档位优先：前端选了哪档就发哪档。没传档位时才回退到
            # reasoning_mode 的两端，保持旧调用方行为不变。
            requested_tier = self._normalize_reasoning_effort(reasoning_effort)
            deep = normalized_reasoning_mode == "deep"
            if dialect.reasoning_style == REASONING_DEEPSEEK_THINKING:
                if deep or (requested_tier and requested_tier not in {"none", "off"}):
                    payload["thinking"] = {"type": "enabled"}
                    payload["reasoning_effort"] = requested_tier or "max"
                else:
                    payload["thinking"] = {"type": "disabled"}
            elif dialect.reasoning_style == REASONING_KIMI_GLM_EFFORT:
                # Kimi/GLM 用 reasoning_effort 但枚举是 low|high|max
                payload["reasoning_effort"] = (
                    kimi_glm_effort(requested_tier)
                    if requested_tier
                    else (kimi_glm_effort(None) if deep else "low")
                )
            elif dialect.reasoning_style == REASONING_QWEN_ENABLE_THINKING:
                # Qwen 无 effort 枚举，只有 enable_thinking 布尔开关。
                payload["enable_thinking"] = (
                    qwen_thinking_enabled(requested_tier) if requested_tier else deep
                )
            elif dialect.reasoning_style == REASONING_OPENAI_EFFORT:
                # 快速档不能直接写字面量 "minimal"：gpt-5.6 在 chat/completions 上
                # 用 400 拒掉它，只有 none/low 是全代通行的下限。
                extended = dialect.extended_openai_effort
                payload["reasoning_effort"] = openai_effort(
                    requested_tier if requested_tier else (None if deep else "minimal"),
                    extended=extended,
                )

        if temperature is not None:
            # OpenAI o 系列：任何推理模式都不接受 temperature (accepts_temperature=False)
            # DeepSeek：只在 deep 模式不接受 temperature
            # Kimi/GLM/Qwen：所有模式都接受 temperature
            should_send = dialect.accepts_temperature and not (
                dialect.reasoning_style == REASONING_DEEPSEEK_THINKING
                and normalized_reasoning_mode == "deep"
            )
            if should_send:
                payload["temperature"] = temperature
        if max_tokens is not None:
            # OpenAI 推理模型已废弃 max_tokens，只认 max_completion_tokens。
            payload[dialect.output_cap_field] = max_tokens
        if require_json:
            payload["response_format"] = {"type": "json_object"}
        if tools:
            payload["tools"] = tools
        normalized_cache_affinity = str(cache_affinity or "").strip()
        if normalized_cache_affinity and self._should_send_prompt_cache_key(model, provider_profile):
            payload["prompt_cache_key"] = normalized_cache_affinity
        provider_user = self._provider_user_id(model, normalized_cache_affinity, provider_profile)
        if provider_user:
            payload["user_id"] = provider_user
        return payload

    def _cache_continuity_snapshot(
        self,
        payload: dict[str, Any],
        wire_api: str,
        *,
        cache_affinity: str | None = None,
        provider_profile: ProviderProfile | None = None,
        request_family: str | None = None,
    ) -> dict[str, Any]:
        normalized_wire_api = str(wire_api or "").strip().lower()
        if normalized_wire_api == self._RESPONSES_WIRE:
            input_items = payload.get("input") if isinstance(payload.get("input"), list) else []
            stable_prefix: Any = payload.get("instructions") or []
            if not stable_prefix:
                stable_prefix = []
                for item in input_items:
                    if not isinstance(item, dict):
                        continue
                    if str(item.get("role") or "").strip().lower() not in {"system", "developer"}:
                        break
                    stable_prefix.append(item)
        else:
            messages = payload.get("messages") if isinstance(payload.get("messages"), list) else []
            stable_prefix = []
            for message in messages:
                if not isinstance(message, dict):
                    continue
                role = str(message.get("role") or "").strip().lower()
                if role not in {"system", "developer"}:
                    break
                stable_prefix.append(message)
            input_items = messages

        tools = payload.get("tools") if isinstance(payload.get("tools"), list) else []
        stable_prefix_fingerprint = self._cache_fingerprint(stable_prefix)
        tools_fingerprint = self._cache_fingerprint(tools)
        request_settings = {
            key: payload[key]
            for key in (
                "reasoning",
                "text",
                "parallel_tool_calls",
                "context_management",
                "prompt_cache_options",
                "prompt_cache_retention",
            )
            if key in payload
        }
        request_settings_fingerprint = self._cache_fingerprint(request_settings)
        chain_state = self._cache_fingerprint("noval-wire-prefix-v1")
        prefix_chain: list[str] = []
        for index, item in enumerate(input_items):
            chain_state = self._cache_fingerprint({
                "previous": chain_state,
                "item": item,
            })
            if index < self._MAX_CACHE_PREFIX_CHAIN_ITEMS:
                prefix_chain.append(chain_state)
        model = str(payload.get("model") or "").strip()
        normalized_family = self._cache_dimension(request_family)
        affinity_fingerprint = (
            self._cache_fingerprint(str(cache_affinity).strip())
            if str(cache_affinity or "").strip()
            else None
        )
        route_fingerprint = self._provider_route_fingerprint(provider_profile)
        prompt_cache = (
            self._effective_responses_prompt_cache(model, provider_profile)
            if normalized_wire_api == self._RESPONSES_WIRE
            else self._profile_prompt_cache(provider_profile)
        )
        prompt_cache_strategy = (
            prompt_cache.strategy if prompt_cache is not None else "none"
        )
        cache_identity_mode = (
            "prompt_cache_key"
            if "prompt_cache_key" in payload
            else "provider_user"
            if "user" in payload or "user_id" in payload
            else "none"
        )
        surface_identity: dict[str, Any] = {
            "wireApi": normalized_wire_api,
            "model": model,
            "stablePrefixFingerprint": stable_prefix_fingerprint,
            "toolsFingerprint": tools_fingerprint,
            "requestSettingsFingerprint": request_settings_fingerprint,
            "cacheIdentityMode": cache_identity_mode,
            "promptCacheStrategy": prompt_cache_strategy,
        }
        for key, value in (
            ("requestFamily", normalized_family),
            ("routeFingerprint", route_fingerprint),
            ("affinityFingerprint", affinity_fingerprint),
        ):
            if value:
                surface_identity[key] = value
        surface_generation = self._cache_fingerprint(surface_identity)
        snapshot: dict[str, Any] = {
            "schemaVersion": 1,
            "provider": "openai_compatible",
            "wireApi": normalized_wire_api,
            "model": model,
            "stablePrefixFingerprint": stable_prefix_fingerprint,
            "toolsFingerprint": tools_fingerprint,
            "requestSettingsFingerprint": request_settings_fingerprint,
            "surfaceGeneration": surface_generation,
            "inputCount": len(input_items),
            "inputFingerprint": chain_state,
            "prefixChainFingerprints": prefix_chain,
            "chainComplete": len(prefix_chain) == len(input_items),
            "cacheIdentityMode": cache_identity_mode,
            "promptCacheStrategy": prompt_cache_strategy,
            "bodyRedacted": True,
        }
        if normalized_family:
            snapshot["requestFamily"] = normalized_family
        if route_fingerprint:
            snapshot["routeFingerprint"] = route_fingerprint
        if affinity_fingerprint:
            snapshot["affinityFingerprint"] = affinity_fingerprint
        return snapshot

    @staticmethod
    def _cache_fingerprint(value: Any) -> str:
        canonical = json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @staticmethod
    def _cache_dimension(value: Any, *, max_length: int = 64) -> str | None:
        normalized = str(value or "").strip().lower()
        if not normalized or len(normalized) > max_length:
            return None
        if re.fullmatch(r"[a-z0-9_.:-]+", normalized) is None:
            return None
        return normalized

    def _provider_route_fingerprint(self, profile: ProviderProfile | None) -> str | None:
        if profile is None:
            return None
        return self._cache_fingerprint({
            "profileKey": profile.profile_key,
            "profileVersion": profile.profile_version,
            "endpoint": profile.endpoint,
            "model": profile.model,
            "providerType": profile.provider_type,
            "protocol": profile.protocol,
        })

    def _normalize_reasoning_mode(self, reasoning_mode: str | None) -> str | None:
        value = (reasoning_mode or "").strip().lower()
        if value in {"deep", "reasoning", "think", "thinking", "max"}:
            return "deep"
        if value in {"fast", "quick", "normal", "disabled", "none"}:
            return "fast"
        return None

    def _normalize_reasoning_effort(self, reasoning_effort: str | None) -> str | None:
        # 整条规范标度都要放行：只认 high/max 会让 minimal/low/medium 退回两端默认值。
        # 各族自己的 clamp 负责把档位收敛到它接受的枚举。
        value = (reasoning_effort or "").strip().lower()
        if value in {"minimal", "low", "medium", "high", "max", "xhigh", "none", "off"}:
            return value
        return None

    def _normalize_responses_reasoning_effort(self, reasoning_effort: str | None) -> str:
        value = (reasoning_effort or "").strip().lower()
        if value == "low":
            return "low"
        if value in {"medium", "high", "xhigh"}:
            return "high"
        return "max"

    def _provider_user_id(
        self,
        model: str,
        cache_affinity: str,
        provider_profile: ProviderProfile | None = None,
    ) -> str | None:
        normalized_affinity = str(cache_affinity or "").strip()
        prompt_cache = self._profile_prompt_cache(provider_profile)
        if prompt_cache is not None:
            enabled = (
                provider_profile is not None
                and provider_profile.protocol == self._RESPONSES_WIRE
                and prompt_cache.strategy == "deepseek_automatic"
            )
            if not normalized_affinity or not enabled:
                return None
            digest = hashlib.sha256(
                f"noval-provider-user-v1:{normalized_affinity}".encode("utf-8"),
            ).hexdigest()
            return f"noval-{digest}"
        selected_model = self._dispatch_model_name(model, provider_profile)
        policy = self._provider_user_model_names()
        if not normalized_affinity or not self._model_matches_policy(selected_model, policy):
            return None
        digest = hashlib.sha256(
            f"noval-provider-user-v1:{normalized_affinity}".encode("utf-8"),
        ).hexdigest()
        return f"noval-{digest}"

    def _normalize_tool_calls(self, raw_tool_calls: Any) -> list[dict[str, Any]]:
        if not isinstance(raw_tool_calls, list):
            return []
        normalized: list[dict[str, Any]] = []
        for item in raw_tool_calls:
            if not isinstance(item, dict):
                continue
            function = item.get("function") if isinstance(item.get("function"), dict) else {}
            name = item.get("name") or function.get("name")
            arguments = item.get("arguments") if "arguments" in item else function.get("arguments")
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError:
                    arguments = {}
            if not isinstance(arguments, dict):
                arguments = {}
            normalized.append({
                "id": item.get("call_id") or item.get("id") or name,
                "name": name,
                "arguments": arguments,
            })
        return normalized

    def _usage_summary(self, usage: dict[str, Any]) -> dict[str, Any]:
        def as_int(value: Any) -> int:
            try:
                return max(0, int(value or 0))
            except (TypeError, ValueError):
                return 0

        def first_present(*sources: tuple[dict[str, Any], tuple[str, ...]]) -> tuple[bool, int]:
            for source, keys in sources:
                for key in keys:
                    if key in source:
                        return True, as_int(source.get(key))
            return False, 0

        details = usage.get("prompt_tokens_details") if isinstance(usage.get("prompt_tokens_details"), dict) else {}
        input_details = usage.get("input_tokens_details") if isinstance(usage.get("input_tokens_details"), dict) else {}
        output_details = usage.get("output_tokens_details") if isinstance(usage.get("output_tokens_details"), dict) else {}
        token_fields = {
            "prompt_tokens",
            "input_tokens",
            "completion_tokens",
            "output_tokens",
            "total_tokens",
        }
        cache_hit_present, cache_hit = first_present(
            (usage, (
                "prompt_cache_hit_tokens",
                "cache_read_input_tokens",
                "cached_tokens",
            )),
            (details, ("cached_tokens", "cache_read_input_tokens")),
            (input_details, ("cached_tokens", "cache_read_input_tokens")),
        )
        cache_write_present, cache_write = first_present(
            (usage, (
                "prompt_cache_write_tokens",
                "cache_write_input_tokens",
                "cache_creation_input_tokens",
                "cache_write_tokens",
            )),
            (details, ("cache_write_tokens", "cache_creation_input_tokens")),
            (input_details, ("cache_write_tokens", "cache_creation_input_tokens")),
        )
        cache_miss_present, cache_miss = first_present(
            (usage, (
                "prompt_cache_miss_tokens",
                "cache_miss_input_tokens",
                "cache_miss_tokens",
            )),
            (details, ("cache_miss_tokens",)),
            (input_details, ("cache_miss_tokens",)),
        )
        cache_usage_reported = cache_hit_present or cache_write_present or cache_miss_present
        usage_reported = bool(token_fields.intersection(usage)) or cache_usage_reported
        prompt_tokens = as_int(usage.get("prompt_tokens")) or as_int(usage.get("input_tokens"))
        completion_tokens = as_int(usage.get("completion_tokens")) or as_int(usage.get("output_tokens"))
        total_tokens = as_int(usage.get("total_tokens")) or prompt_tokens + completion_tokens
        miss_derived = False
        if not cache_miss_present and (
            prompt_tokens > 0 or cache_hit_present or cache_write_present
        ):
            # Anthropic-compatible gateways expose input_tokens as the uncached
            # portion when they also expose separate read/write fields. OpenAI-
            # style payloads expose inclusive input_tokens, so subtract the
            # reported read/write portions instead. In both cases this remains a
            # derived diagnostic, never a Provider-reported miss fact.
            if "input_tokens" in usage and (
                "cache_read_input_tokens" in usage
                or "cache_creation_input_tokens" in usage
                or "cache_write_input_tokens" in usage
            ):
                cache_miss = prompt_tokens
            elif prompt_tokens >= cache_hit:
                cache_miss = max(0, prompt_tokens - cache_hit - cache_write)
            else:
                cache_miss = 0
            miss_derived = True
        summary = {
            "promptTokens": prompt_tokens,
            "completionTokens": completion_tokens,
            "totalTokens": total_tokens,
            "promptCacheHitTokens": cache_hit,
            "promptCacheMissTokens": cache_miss,
            "promptCacheWriteTokens": cache_write,
            "promptCacheMissTokensDerived": miss_derived,
            "usageReported": usage_reported,
            "cacheUsageReported": cache_usage_reported,
        }
        if "input_tokens" in usage or "output_tokens" in usage:
            summary.update({
                "inputTokens": prompt_tokens,
                "outputTokens": completion_tokens,
                "cachedInputTokens": cache_hit,
                "cacheWriteInputTokens": cache_write,
                "reasoningTokens": as_int(output_details.get("reasoning_tokens")),
            })
        return summary

    def _headers(self, api_key: str | None = None) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        effective_api_key = api_key or settings.openai_api_key
        if effective_api_key:
            headers["Authorization"] = f"Bearer {effective_api_key}"
        return headers

    @staticmethod
    def _require_provider_profile_api_key(profile: ProviderProfile) -> str:
        api_key = str(profile.api_key or "").strip()
        if not api_key:
            raise ValueError("provider profile credential is required")
        return api_key

    def _resolve_base_url(self, base_url: str | None = None) -> str:
        return _validate_provider_base_url(base_url or settings.openai_base_url)

    def _resolve_responses_base_url(self, base_url: str | None = None) -> str:
        return _validate_provider_base_url(base_url or settings.openai_responses_base_url)

    async def _assert_public_endpoint(self, endpoint: str) -> None:
        await _assert_public_provider_endpoint(endpoint)

    def _resolve_timeout_seconds(self, timeout_millis: int | None) -> float:
        effective_timeout_millis = timeout_millis if timeout_millis and timeout_millis > 0 else settings.timeout_millis
        return effective_timeout_millis / 1000

    async def _invoke_with_retry(
        self,
        *,
        payload: dict,
        base_url: str | None,
        api_key: str | None,
        timeout_millis: int | None,
    ) -> dict:
        endpoint = f"{self._resolve_base_url(base_url)}/chat/completions"
        await self._assert_public_endpoint(endpoint)
        for attempt in range(1, self._MAX_TRANSPORT_ATTEMPTS + 1):
            try:
                cancellation_checkpoint()
                async with httpx.AsyncClient(
                    timeout=self._resolve_timeout_seconds(timeout_millis),
                    trust_env=False,
                ) as client:
                    response = await cancellable_await(client.post(
                            endpoint,
                            headers=self._headers(api_key),
                            json=payload,
                        ))
                    response.raise_for_status()
                    data = response.json()
                cancellation_checkpoint()
                return data
            except self._RETRYABLE_ERRORS:
                if attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await cancellable_await(asyncio.sleep(self._retry_backoff_seconds(attempt)))
            except httpx.HTTPStatusError as exc:
                if not self._is_retryable_status(exc) or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await cancellable_await(asyncio.sleep(self._retry_backoff_seconds(attempt)))
        raise RuntimeError("unreachable")

    async def _invoke_responses_with_retry(
        self,
        *,
        payload: dict,
        base_url: str | None,
        api_key: str | None,
        timeout_millis: int | None,
    ) -> dict:
        return await self._post_with_retry(
            endpoint=self._provider_endpoint(self._RESPONSES_WIRE, base_url=base_url),
            payload=payload,
            api_key=api_key,
            timeout_millis=timeout_millis,
        )

    async def _post_with_retry(
        self,
        *,
        endpoint: str,
        payload: dict,
        api_key: str | None,
        timeout_millis: int | None,
    ) -> dict:
        await self._assert_public_endpoint(endpoint)
        for attempt in range(1, self._MAX_TRANSPORT_ATTEMPTS + 1):
            try:
                cancellation_checkpoint()
                async with httpx.AsyncClient(
                    timeout=self._resolve_timeout_seconds(timeout_millis),
                    trust_env=False,
                ) as client:
                    response = await cancellable_await(client.post(
                        endpoint,
                        headers=self._headers(api_key),
                        json=payload,
                    ))
                    response.raise_for_status()
                    data = response.json()
                cancellation_checkpoint()
                return data
            except self._RETRYABLE_ERRORS:
                if attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await cancellable_await(asyncio.sleep(self._retry_backoff_seconds(attempt)))
            except httpx.HTTPStatusError as exc:
                if not self._is_retryable_status(exc) or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await cancellable_await(asyncio.sleep(self._retry_backoff_seconds(attempt)))
        raise RuntimeError("unreachable")

    def _is_retryable_status(self, error: httpx.HTTPStatusError) -> bool:
        response = error.response
        return response is not None and response.status_code in self._RETRYABLE_STATUS_CODES

    # Statuses that make the current key unusable but leave other keys viable.
    _FAILOVER_STATUS_CODES = frozenset({401, 402, 403, 404})

    def failover_failure_class(self, error: BaseException) -> str | None:
        if isinstance(error, httpx.ConnectError):
            return "CONNECT_ERROR"
        if isinstance(error, httpx.TimeoutException):
            return "TIMEOUT"
        if isinstance(error, httpx.HTTPStatusError):
            response = error.response
            status = getattr(response, "status_code", None) if response is not None else None
            if isinstance(status, int) and (
                status in self._RETRYABLE_STATUS_CODES or status in self._FAILOVER_STATUS_CODES
            ):
                return f"HTTP_{status}"
        return None

    def is_failover_eligible(self, error: BaseException) -> bool:
        return self.failover_failure_class(error) is not None

    def _record_run_usage(self, result: dict[str, Any]) -> None:
        budget = current_run_budget()
        if budget is None:
            return
        raw = result.get("token_used") or result.get("tokenUsed")
        if not raw and isinstance(result.get("usage"), dict):
            raw = result["usage"].get("totalTokens") or result["usage"].get("total_tokens")
        try:
            token_count = max(0, int(raw or 0))
        except (TypeError, ValueError):
            token_count = 0
        budget.record_tokens(token_count)

    def _require_run_token_capacity(self) -> None:
        budget = current_run_budget()
        if budget is not None:
            budget.require_token_capacity()

    def _bounded_max_tokens(self, max_tokens: int | None) -> int | None:
        budget = current_run_budget()
        if budget is None:
            return max_tokens
        remaining_tokens = budget.remaining[0]
        if remaining_tokens <= 0:
            budget.require_token_capacity()
        if max_tokens is None:
            return None
        return max(1, min(int(max_tokens), remaining_tokens))

    def _resolve_provider_order(self, request: RunRequest) -> list[str]:
        provider_type = (
            (request.promptConfig.providerType or "").strip().lower()
            or settings.provider_type.strip().lower()
        )
        if provider_type == "dify":
            return ["dify", "openai"]
        return ["openai", "dify"]

    def _resolve_dify_workflow_id(self, request: RunRequest, model: str) -> str:
        return (
            (request.contextMeta.get("difyWorkflowId") or "").strip()
            or (request.promptConfig.modelKey or "").strip()
            or (request.promptConfig.modelName or "").strip()
            or (model or "").strip()
        )

    def _resolve_dify_api_key(self, request: RunRequest) -> str:
        return (request.promptConfig.apiKey or settings.dify_api_key).strip()

    def _resolve_dify_base_url(self, request: RunRequest) -> str:
        candidate = settings.dify_base_url
        if request.promptConfig.providerType and request.promptConfig.providerType.strip().lower() == "dify":
            candidate = request.promptConfig.baseUrl or candidate
        return _validate_provider_base_url(candidate)

    def _render_dify_content(self, messages: list[dict]) -> str:
        rendered_parts: list[str] = []
        for message in messages:
            content = str(message.get("content") or "").strip()
            if not content:
                continue
            role = str(message.get("role") or "user").strip()
            rendered_parts.append(f"[{role}]\n{content}")
        return "\n\n".join(rendered_parts).strip()

    def _analysis_type(self, request: RunRequest) -> str:
        return "theme" if request.agentType == "trend_theme" else request.agentType

    def _build_final_fallback_result(
        self,
        *,
        request: RunRequest,
        model: str,
        messages: list[dict],
        failures: list[dict[str, str]],
    ) -> dict:
        analysis_type = self._analysis_type(request)
        model_name = (
            (request.promptConfig.modelName or "").strip()
            or (model or "").strip()
            or settings.fallback_model
        )
        summary_source = (
            str(request.sourcePayload.get("inputText") or "").strip()
            or self._render_dify_content(messages)
            or str(request.promptConfig.promptContent or "").strip()
        )
        summary = self._short_text(summary_source, 200)
        content = f"{analysis_type} analysis result\nmodel: {model_name}\nsummary: {summary}"
        result_json: dict[str, Any] = {
            "analysisType": analysis_type,
            "summary": summary,
        }
        return {
            "model_name": model_name,
            "content": content,
            "token_used": max(120, len(summary_source) // 2),
            "result_json": result_json,
        }

    def _retry_backoff_seconds(self, attempt: int) -> float:
        return 0.35 * attempt

    def _short_text(self, input_text: str, max_length: int) -> str:
        compact = " ".join((input_text or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."
