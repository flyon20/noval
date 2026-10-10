from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

import httpx

from app.services.harness.agent_kernel import (
    AgentKernel,
    build_logical_cache_affinity,
    KernelMessage,
    KernelStopReason,
    KernelToolCall,
    KernelToolObservation,
    KernelTurnRequest,
)
from app.services.harness.capability_authorizer import CapabilityAuthorizer
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.cancellation import (
    CancellationToken,
    RunCancelledError,
    cancellation_scope,
)
from app.services.harness.provider_dispatch_scope import ProviderDispatch, provider_dispatch_scope
from app.services.harness.tool_ledger import RunToolLedger, run_tool_ledger_scope
from app.services.harness.contracts import DomainStatus, IntentEnvelope
from app.services.harness.context_compaction import (
    ContextCompactor,
    ModelContextCapability,
    ProviderEnvelopeCompactionError,
)
from app.models.agent_task import RunToolIdentity
from app.services.provider_client import OpenAICompatibleProviderClient, ProviderProfile


class FakeProvider:
    def __init__(self, scripted: list[dict]) -> None:
        self.scripted = list(scripted)
        self.calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.calls.append(kwargs)
        if not self.scripted:
            raise AssertionError("unexpected provider call")
        return self.scripted.pop(0)


class ProfileAwareProvider(FakeProvider):
    def __init__(self, scripted: list[dict], profile: ProviderProfile) -> None:
        super().__init__(scripted)
        self.profile = profile
        self.profile_resolve_calls: list[dict] = []

    def resolve_provider_profile(self, model: str, *, route_snapshot: dict | None = None) -> ProviderProfile:
        self.profile_resolve_calls.append({"model": model, "route_snapshot": route_snapshot})
        return self.profile

    async def invoke(self, **kwargs):
        result = await super().invoke(**kwargs)
        result["providerProfile"] = self.profile.snapshot()
        return result


class StreamingProvider:
    def __init__(self) -> None:
        self.stream_calls: list[dict] = []
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.invoke_calls.append(kwargs)
        raise AssertionError("streaming no-tool turn must not bypass AgentKernel.stream")

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        yield {"event": "delta", "delta": "first "}
        yield {"event": "delta", "delta": "second"}
        yield {
            "event": "done",
            "tokenUsed": 7,
            "usage": {
                "inputTokens": 5,
                "outputTokens": 2,
                "reasoningTokens": 1,
                "totalTokens": 7,
                "promptCacheHitTokens": 2,
            },
            "promptCacheHitTokens": 2,
            "promptCacheMissTokens": 5,
            "wireApi": "responses",
        }


class ProfileAwareStreamingProvider(StreamingProvider):
    def __init__(self, profile: ProviderProfile) -> None:
        super().__init__()
        self.profile = profile
        self.profile_resolve_calls: list[dict] = []

    def resolve_provider_profile(self, model: str, *, route_snapshot: dict | None = None) -> ProviderProfile:
        self.profile_resolve_calls.append({"model": model, "route_snapshot": route_snapshot})
        return self.profile


class RuntimeDispatchProvider(FakeProvider):
    def __init__(self, scripted: list[dict]) -> None:
        super().__init__(scripted)
        self.profile_resolve_calls: list[dict] = []

    def resolve_provider_profile(
        self,
        model: str,
        *,
        route_snapshot: dict | None = None,
        api_key: str | None = None,
    ) -> ProviderProfile:
        self.profile_resolve_calls.append({
            "model": model,
            "route_snapshot": route_snapshot,
            "api_key": api_key,
        })
        route = route_snapshot or {}
        return ProviderProfile(
            profile_key=str(route.get("profileKey") or "runtime"),
            profile_version=str(route.get("profileVersion") or "v1"),
            endpoint=str(route.get("endpoint") or "https://gateway.example/v1"),
            model=str(route.get("model") or model),
            protocol=str(route.get("protocol") or "responses"),
            api_key=api_key,
            provider_capabilities=route.get("providerCapabilities"),
        )


class EmptyStreamingProvider:
    def __init__(self) -> None:
        self.stream_calls: list[dict] = []
        self.invoke_calls: list[dict] = []

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        yield {"event": "done", "tokenUsed": 0, "usage": {}}

    async def invoke(self, **kwargs):
        self.invoke_calls.append(kwargs)
        return {
            "content": "fallback answer",
            "model_name": "m1",
            "token_used": 5,
            "tool_calls": [],
        }


def semantic_checkpoint_response(
    event_type: str,
    event_key: str,
    *,
    event_id: int,
    sequence: int,
) -> dict:
    envelope = {
        "schemaVersion": 1,
        "eventId": event_id,
        "runId": "run-source-correlation",
        "sequence": sequence,
        "eventType": event_type,
        "visibility": "internal",
        "eventIdempotencyKey": event_key,
    }
    return {
        "eventId": event_id,
        "runId": "run-source-correlation",
        "sequenceNo": sequence,
        "eventType": event_type,
        "eventIdempotencyKey": event_key,
        "payload": {
            "_event": envelope,
            "privateCallbackBody": "must not enter checkpoint or trace",
        },
    }


class AgentKernelTests(unittest.IsolatedAsyncioTestCase):
    async def test_blocking_profile_failover_switches_on_credential_failure(self) -> None:
        capabilities = {
            "schemaVersion": 1,
            "supportsStreaming": True,
            "supportsTools": True,
            "supportsJsonObject": True,
            "supportsReasoning": True,
            "reportsUsage": True,
            "reportsCacheUsage": True,
        }
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup")
        ]
        resolved: list[str] = []
        outcomes: list[dict] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            resolved.append(profile_key)
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        class Provider(RuntimeDispatchProvider):
            def __init__(self) -> None:
                super().__init__([])
                self.classifier = OpenAICompatibleProviderClient()

            def failover_failure_class(self, error: BaseException) -> str | None:
                return self.classifier.failover_failure_class(error)

            async def invoke(self, **kwargs):
                self.calls.append(kwargs)
                profile = kwargs["provider_profile"]
                if profile.profile_key == "primary":
                    # A rejected credential can never recover on the same key, so the
                    # kernel must switch immediately instead of spending its budget.
                    request = httpx.Request("POST", "https://primary.example/v1/responses")
                    raise httpx.HTTPStatusError(
                        "unauthorized",
                        request=request,
                        response=httpx.Response(401, request=request),
                    )
                return {
                    "content": "backup answer",
                    "model_name": profile.model,
                    "token_used": 2,
                    "tool_calls": [],
                    "providerProfile": profile.snapshot(),
                }

        provider = Provider()
        policy = {
            "schemaVersion": 1,
            "enabled": True,
            "orderedProfileKeys": ["primary", "backup"],
            "maxFailovers": 1,
            "circuitStates": {},
        }
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy=policy,
            outcome_reporter=lambda payload: self._capture_outcome(outcomes, payload),
        ) as scope:
            result = await AgentKernel(provider).run(KernelTurnRequest(
                messages=[KernelMessage(role="user", content="answer")],
                model="primary-model",
                max_turns=1,
            ))
            self.assertEqual("backup", scope.current().profile_key if scope.current() else None)

        self.assertEqual("backup answer", result.content)
        self.assertEqual(["primary", "backup"], resolved)
        self.assertEqual(
            ["primary", "backup"],
            [call["provider_profile"].profile_key for call in provider.calls],
        )
        self.assertEqual([
            {
                "profileKey": "primary",
                "profileVersion": "v1",
                "outcome": "TRANSIENT_FAILURE",
                "failureClass": "HTTP_401",
                "switched": False,
            },
            {
                "profileKey": "backup",
                "profileVersion": "v1",
                "outcome": "SUCCEEDED",
                "switched": True,
            },
        ], outcomes)
        self.assertNotIn("secret", str(result.provider_calls))

    async def test_blocking_transient_failure_retries_same_key_before_switching(self) -> None:
        capabilities = {
            "schemaVersion": 1,
            "supportsStreaming": True,
            "supportsTools": True,
            "supportsJsonObject": True,
            "supportsReasoning": True,
            "reportsUsage": True,
            "reportsCacheUsage": True,
        }
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup")
        ]
        resolved: list[str] = []
        outcomes: list[dict] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            resolved.append(profile_key)
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        class Provider(RuntimeDispatchProvider):
            def __init__(self) -> None:
                super().__init__([])
                self.classifier = OpenAICompatibleProviderClient()

            def failover_failure_class(self, error: BaseException) -> str | None:
                return self.classifier.failover_failure_class(error)

            async def invoke(self, **kwargs):
                self.calls.append(kwargs)
                profile = kwargs["provider_profile"]
                if profile.profile_key == "primary":
                    raise httpx.ConnectError("primary unavailable")
                return {
                    "content": "backup answer",
                    "model_name": profile.model,
                    "token_used": 2,
                    "tool_calls": [],
                    "providerProfile": profile.snapshot(),
                }

        provider = Provider()
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy={
                "schemaVersion": 1,
                "enabled": True,
                "orderedProfileKeys": ["primary", "backup"],
                "maxFailovers": 1,
                "circuitStates": {},
            },
            outcome_reporter=lambda payload: self._capture_outcome(outcomes, payload),
        ):
            with patch("asyncio.sleep", new_callable=AsyncMock) as sleep_mock:
                result = await AgentKernel(provider).run(KernelTurnRequest(
                    messages=[KernelMessage(role="user", content="answer")],
                    model="primary-model",
                    max_turns=1,
                ))

        self.assertEqual("backup answer", result.content)
        # A connect error may clear on the same key, so the budget is spent there
        # first; the switch only happens once the last attempt is gone.
        self.assertEqual(
            ["primary"] * 5 + ["backup"],
            [call["provider_profile"].profile_key for call in provider.calls],
        )
        self.assertEqual(["primary", "backup"], resolved)
        self.assertEqual(4, sleep_mock.await_count)
        self.assertEqual(
            [("primary", "TRANSIENT_FAILURE")] * 5 + [("backup", "SUCCEEDED")],
            [(item["profileKey"], item["outcome"]) for item in outcomes],
        )
        self.assertEqual(
            {"CONNECT_ERROR"},
            {item["failureClass"] for item in outcomes if "failureClass" in item},
        )

    async def test_primary_profile_success_reports_and_does_not_resolve_backup(self) -> None:
        capabilities = {
            "schemaVersion": 1,
            "supportsStreaming": True,
            "supportsTools": True,
            "supportsJsonObject": True,
            "supportsReasoning": True,
            "reportsUsage": True,
            "reportsCacheUsage": True,
        }
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup")
        ]
        resolved: list[str] = []
        outcomes: list[dict] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            resolved.append(profile_key)
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        class Provider(RuntimeDispatchProvider):
            def __init__(self) -> None:
                super().__init__([])

            async def invoke(self, **kwargs):
                self.calls.append(kwargs)
                profile = kwargs["provider_profile"]
                return {
                    "content": "primary answer",
                    "model_name": profile.model,
                    "token_used": 2,
                    "tool_calls": [],
                    "providerProfile": profile.snapshot(),
                }

            async def stream(self, **kwargs):
                profile = kwargs["provider_profile"]
                yield {"event": "delta", "delta": "primary"}
                yield {
                    "event": "done",
                    "tokenUsed": 2,
                    "usage": {},
                    "providerProfile": profile.snapshot(),
                }

        policy = {
            "schemaVersion": 1,
            "enabled": True,
            "orderedProfileKeys": ["primary", "backup"],
            "maxFailovers": 1,
            "circuitStates": {},
        }
        provider = Provider()
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy=policy,
            outcome_reporter=lambda payload: self._capture_outcome(outcomes, payload),
        ):
            result = await AgentKernel(provider).run(KernelTurnRequest(
                messages=[KernelMessage(role="user", content="answer")],
                model="primary-model",
                max_turns=1,
            ))
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy=policy,
            outcome_reporter=lambda payload: self._capture_outcome(outcomes, payload),
        ):
            events = [event async for event in AgentKernel(provider).stream(KernelTurnRequest(
                messages=[KernelMessage(role="user", content="stream")],
                model="primary-model",
            ))]

        self.assertEqual("primary answer", result.content)
        self.assertTrue(any(event.type == "result" for event in events))
        self.assertEqual(["primary", "primary"], resolved)
        self.assertEqual([
            {
                "profileKey": "primary",
                "profileVersion": "v1",
                "outcome": "SUCCEEDED",
                "switched": False,
            },
            {
                "profileKey": "primary",
                "profileVersion": "v1",
                "outcome": "SUCCEEDED",
                "switched": False,
            },
        ], outcomes)

    async def test_blocking_profile_failover_is_fenced_after_external_tool_side_effect(self) -> None:
        capabilities = {
            "schemaVersion": 1,
            "supportsStreaming": True,
            "supportsTools": True,
            "supportsJsonObject": True,
            "supportsReasoning": True,
            "reportsUsage": True,
            "reportsCacheUsage": True,
        }
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup")
        ]
        resolved: list[str] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            resolved.append(profile_key)
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        class Provider(RuntimeDispatchProvider):
            def __init__(self) -> None:
                super().__init__([])
                self.classifier = OpenAICompatibleProviderClient()

            def failover_failure_class(self, error: BaseException) -> str | None:
                return self.classifier.failover_failure_class(error)

            async def invoke(self, **kwargs):
                self.calls.append(kwargs)
                raise httpx.ConnectError("primary unavailable after write")

        ledger = RunToolLedger(RunToolIdentity(
            runId="run-failover-fence",
            userId=7,
            projectId=91,
            route="market_scan",
        ))

        async def completed_write() -> dict:
            return {"saved": True}

        await ledger.execute("memory.write", {}, completed_write, access="write")
        provider = Provider()
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy={
                "schemaVersion": 1,
                "enabled": True,
                "orderedProfileKeys": ["primary", "backup"],
                "maxFailovers": 1,
                "circuitStates": {},
            },
        ):
            with run_tool_ledger_scope(ledger):
                with patch("asyncio.sleep", new_callable=AsyncMock):
                    with self.assertRaises(httpx.ConnectError):
                        await AgentKernel(provider).run(KernelTurnRequest(
                            messages=[KernelMessage(role="user", content="answer")],
                            model="primary-model",
                            max_turns=1,
                        ))

        self.assertEqual(["primary"], resolved)
        # The fence blocks the key switch, not the same-key retries: the budget is
        # spent on primary and the backup credential is never resolved.
        self.assertEqual(5, len(provider.calls))
        self.assertTrue(all(
            call["provider_profile"].profile_key == "primary" for call in provider.calls
        ))

    async def test_stream_does_not_switch_after_first_provider_delta(self) -> None:
        capabilities = {
            "schemaVersion": 1,
            "supportsStreaming": True,
            "supportsTools": True,
            "supportsJsonObject": True,
            "supportsReasoning": True,
            "reportsUsage": True,
            "reportsCacheUsage": True,
        }
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup")
        ]
        resolved: list[str] = []
        outcomes: list[dict] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            resolved.append(profile_key)
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        class Provider(RuntimeDispatchProvider):
            def __init__(self) -> None:
                super().__init__([])
                self.stream_calls: list[dict] = []
                self.classifier = OpenAICompatibleProviderClient()

            def failover_failure_class(self, error: BaseException) -> str | None:
                return self.classifier.failover_failure_class(error)

            async def stream(self, **kwargs):
                self.stream_calls.append(kwargs)
                yield {"event": "delta", "delta": "visible"}
                raise httpx.ConnectError("lost after visible output")

        provider = Provider()
        policy = {
            "schemaVersion": 1,
            "enabled": True,
            "orderedProfileKeys": ["primary", "backup"],
            "maxFailovers": 1,
            "circuitStates": {},
        }
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy=policy,
            outcome_reporter=lambda payload: self._capture_outcome(outcomes, payload),
        ):
            stream = AgentKernel(provider).stream(KernelTurnRequest(
                messages=[KernelMessage(role="user", content="stream")],
                model="primary-model",
            ))
            self.assertEqual("message.start", (await anext(stream)).type)
            self.assertEqual("message.delta", (await anext(stream)).type)
            with self.assertRaises(httpx.ConnectError):
                await anext(stream)

        self.assertEqual(["primary"], resolved)
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual([], outcomes)

    @staticmethod
    async def _capture_outcome(target: list[dict], payload: dict) -> None:
        target.append(dict(payload))

    async def test_declared_blocking_capability_rejections_precede_checkpoint_and_provider(self) -> None:
        cases = (
            (
                "tools",
                {"supportsTools": False},
                {
                    "tool_schemas": [{
                        "type": "function",
                        "function": {"name": "rank.lookup", "parameters": {"type": "object"}},
                    }],
                },
            ),
            ("json_object", {"supportsJsonObject": False}, {"require_json": True}),
            ("reasoning", {"supportsReasoning": False}, {"reasoning_mode": "deep"}),
        )

        for expected_capability, overrides, request_updates in cases:
            with self.subTest(capability=expected_capability):
                capabilities = {
                    "schemaVersion": 1,
                    "supportsStreaming": True,
                    "supportsTools": True,
                    "supportsJsonObject": True,
                    "supportsReasoning": True,
                    "reportsUsage": True,
                    "reportsCacheUsage": True,
                    **overrides,
                }
                profile = ProviderProfile(
                    profile_key=f"profile-{expected_capability}",
                    profile_version="v1",
                    endpoint="https://gateway.example/v1",
                    model="capability-model",
                    protocol="responses",
                    api_key="capability-secret",
                    provider_capabilities=capabilities,
                )
                provider = ProfileAwareProvider([{
                    "content": "must-not-run",
                    "model_name": "capability-model",
                    "token_used": 1,
                    "tool_calls": [],
                }], profile)
                checkpoints: list[tuple[str, str, dict]] = []

                async def writer(event_type: str, event_key: str, payload: dict):
                    checkpoints.append((event_type, event_key, payload))
                    return None

                async def executor(_call: KernelToolCall) -> KernelToolObservation:
                    raise AssertionError("unsupported tools must fail before execution")

                request = KernelTurnRequest(
                    messages=[KernelMessage(role="user", content="test")],
                    model="capability-model",
                    max_turns=1,
                    **request_updates,
                )
                with self.assertRaisesRegex(
                    ValueError,
                    f"provider profile does not support {expected_capability}",
                ):
                    await AgentKernel(provider, checkpoint_writer=writer).run(
                        request,
                        authorization=self._auth_for_market(),
                        tool_executor=executor,
                    )

                self.assertEqual([], checkpoints)
                self.assertEqual([], provider.calls)

    async def test_declared_streaming_capability_rejection_precedes_checkpoint_and_provider(self) -> None:
        profile = ProviderProfile(
            profile_key="profile-streaming",
            profile_version="v1",
            endpoint="https://gateway.example/v1",
            model="capability-model",
            protocol="responses",
            api_key="capability-secret",
            provider_capabilities={
                "schemaVersion": 1,
                "supportsStreaming": False,
                "supportsTools": True,
                "supportsJsonObject": True,
                "supportsReasoning": True,
                "reportsUsage": True,
                "reportsCacheUsage": True,
            },
        )
        provider = ProfileAwareStreamingProvider(profile)
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict):
            checkpoints.append((event_type, event_key, payload))
            return None

        with self.assertRaisesRegex(ValueError, "provider profile does not support streaming"):
            _ = [
                event
                async for event in AgentKernel(provider, checkpoint_writer=writer).stream(
                    KernelTurnRequest(
                        messages=[KernelMessage(role="user", content="test")],
                        model="capability-model",
                        max_turns=1,
                    )
                )
            ]

        self.assertEqual([], checkpoints)
        self.assertEqual([], provider.stream_calls)
        self.assertEqual([], provider.invoke_calls)
    def test_semantic_source_event_rejects_mismatched_envelope(self) -> None:
        value = semantic_checkpoint_response(
            "MODEL_COMMITTED",
            "wrong-key",
            event_id=1,
            sequence=2,
        )
        self.assertIsNone(AgentKernel._semantic_source_event(
            value,
            expected_event_type="MODEL_PREPARED",
            expected_event_key="expected-key",
        ))

    def test_cache_continuity_rejects_non_fingerprint_payload_values(self) -> None:
        self.assertIsNone(AgentKernel._sanitize_cache_continuity({
            "schemaVersion": 1,
            "inputCount": 1,
            "inputFingerprint": "private prompt disguised as a fingerprint",
            "bodyRedacted": True,
        }))
        valid = {
            "schemaVersion": 1,
            "inputCount": 1,
            "inputFingerprint": "a" * 64,
            "requestSettingsFingerprint": "b" * 64,
            "promptCacheStrategy": "openai_gpt_5_6",
            "bodyRedacted": True,
        }
        self.assertEqual(valid, AgentKernel._sanitize_cache_continuity(valid))
        self.assertIsNone(AgentKernel._sanitize_cache_continuity({
            **valid,
            "requestSettingsFingerprint": "private request settings",
        }))
        self.assertIsNone(AgentKernel._sanitize_cache_continuity({
            **valid,
            "promptCacheStrategy": "unknown_provider_strategy",
        }))

    def test_semantic_source_event_requires_strict_persisted_identity(self) -> None:
        valid = semantic_checkpoint_response(
            "MODEL_PREPARED",
            "harness:model_prepared:model-1",
            event_id=101,
            sequence=7,
        )
        self.assertEqual(
            {
                "schemaVersion": 1,
                "eventId": 101,
                "sequence": 7,
                "eventType": "MODEL_PREPARED",
                "bodyRedacted": True,
            },
            AgentKernel._semantic_source_event(
                valid,
                expected_event_type="MODEL_PREPARED",
                expected_event_key="harness:model_prepared:model-1",
            ),
        )

        malformed_cases = []
        string_schema = semantic_checkpoint_response(
            "MODEL_PREPARED",
            "harness:model_prepared:model-1",
            event_id=101,
            sequence=7,
        )
        string_schema["payload"]["_event"]["schemaVersion"] = "1"
        malformed_cases.append(string_schema)

        boolean_id = semantic_checkpoint_response(
            "MODEL_PREPARED",
            "harness:model_prepared:model-1",
            event_id=101,
            sequence=7,
        )
        boolean_id["payload"]["_event"]["eventId"] = True
        malformed_cases.append(boolean_id)

        missing_outer = semantic_checkpoint_response(
            "MODEL_PREPARED",
            "harness:model_prepared:model-1",
            event_id=101,
            sequence=7,
        )
        missing_outer.pop("sequenceNo")
        malformed_cases.append(missing_outer)

        mismatched_outer = semantic_checkpoint_response(
            "MODEL_PREPARED",
            "harness:model_prepared:model-1",
            event_id=101,
            sequence=7,
        )
        mismatched_outer["eventType"] = "MODEL_COMMITTED"
        malformed_cases.append(mismatched_outer)

        for malformed in malformed_cases:
            self.assertIsNone(
                AgentKernel._semantic_source_event(
                    malformed,
                    expected_event_type="MODEL_PREPARED",
                    expected_event_key="harness:model_prepared:model-1",
                )
            )

    async def test_malformed_checkpoint_response_keeps_invoke_compatible_and_redacted(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 1,
            "tool_calls": [],
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> dict:
            checkpoints.append((event_type, event_key, payload))
            return {
                "eventId": "101",
                "payload": {
                    "_event": {
                        "schemaVersion": "1",
                        "eventId": "101",
                        "runId": "run-source-correlation",
                        "sequence": "7",
                        "eventType": event_type,
                        "visibility": "internal",
                        "eventIdempotencyKey": event_key,
                    },
                    "authorization": "must not enter trace",
                },
            }

        result = await AgentKernel(
            provider,
            checkpoint_writer=writer,
            context_compactor=ContextCompactor(),
        ).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="private prompt")],
                model="m1",
            )
        )

        self.assertEqual("final answer", result.content)
        self.assertEqual(1, len(provider.calls))
        self.assertNotIn("sourceEvent", str(checkpoints))
        self.assertNotIn("authorization", str(result.provider_calls))

    def test_cache_affinity_reuses_trusted_scope_across_conversations(self) -> None:
        project_first = build_logical_cache_affinity(
            conversation_id="conversation-a",
            trace_id="trace-a",
            user_id=7,
            project_id=91,
        )
        project_second = build_logical_cache_affinity(
            conversation_id="conversation-b",
            trace_id="trace-b",
            user_id=7,
            project_id=91,
        )
        user_only = build_logical_cache_affinity(
            conversation_id="conversation-a",
            trace_id="trace-a",
            user_id=7,
        )
        self.assertEqual(project_first, project_second)
        self.assertNotEqual(project_first, user_only)
        self.assertRegex(str(project_first), r"^noval-cache-v1:[0-9a-f]{64}$")

    def _auth_for_market(self):
        plan = CapabilityCompiler().compile(
            IntentEnvelope(
                domainStatus=DomainStatus.IN_SCOPE,
                goal="market_scan",
                operations=("market_scan",),
                confidence=0.9,
                classificationSource="rules",
            )
        )
        return CapabilityAuthorizer().authorize(plan)

    async def test_no_tool_reply_completes_in_one_turn(self) -> None:
        provider = FakeProvider([
            {
                "content": "final answer",
                "model_name": "m1",
                "token_used": 3,
                "tool_calls": [],
                "wire_api": "responses",
                "providerTransportFallback": {
                    "from": "responses",
                    "to": "chat_completions",
                    "reason": "model_not_responses_capable",
                    "model": "m1",
                },
                "usage": {"inputTokens": 11, "outputTokens": 3, "totalTokens": 14},
                "kernelUsed": False,
                "kernelStopReason": "spoofed",
                "kernelTurns": 99,
                "providerRequestCount": 99,
                "kernelProviderCalls": [{"kernelTurn": 99}],
            },
        ])
        kernel = AgentKernel(provider)
        result = await kernel.run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="hi")],
                model="m1",
                max_turns=3,
            )
        )
        self.assertEqual("final answer", result.content)
        self.assertEqual(KernelStopReason.COMPLETED, result.stop_reason)
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(["message.start", "message.delta", "message.end", "turn.end"], [e.type for e in result.events])
        projection = result.to_provider_result()
        self.assertTrue(projection["kernelUsed"])
        self.assertEqual("completed", projection["kernelStopReason"])
        self.assertEqual(1, projection["kernelTurns"])
        self.assertEqual(1, projection["providerRequestCount"])
        self.assertEqual(1, len(projection["kernelProviderCalls"]))
        provider_call = projection["kernelProviderCalls"][0]
        self.assertEqual(1, provider_call["kernelTurn"])
        self.assertEqual("responses", provider_call["wireApi"])
        self.assertEqual(11, provider_call["usage"]["inputTokens"])
        self.assertEqual("chat_completions", provider_call["providerTransportFallback"]["to"])

    async def test_model_checkpoint_wraps_invoke_without_persisting_prompt_content(self) -> None:
        cache_continuity = {
            "schemaVersion": 1,
            "provider": "openai_compatible",
            "wireApi": "responses",
            "model": "m1",
            "inputCount": 1,
            "inputFingerprint": "a" * 64,
            "prefixChainFingerprints": ["a" * 64],
            "bodyRedacted": True,
        }
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
            "wire_api": "responses",
            "prompt_cache_hit_tokens": 2,
            "prompt_cache_miss_tokens": 5,
            "cacheContinuity": cache_continuity,
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        result = await AgentKernel(
            provider,
            checkpoint_writer=writer,
            context_compactor=ContextCompactor(),
        ).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="private prompt")],
                model="m1",
            )
        )

        self.assertEqual("final answer", result.content)
        self.assertEqual(["MODEL_PREPARED", "MODEL_COMMITTED"], [item[0] for item in checkpoints])
        self.assertNotIn("private prompt", str(checkpoints))
        self.assertEqual(checkpoints[0][2]["semanticKey"], checkpoints[1][2]["semanticKey"])
        self.assertNotIn("cacheContinuity", checkpoints[0][2])
        self.assertEqual(cache_continuity, checkpoints[1][2]["cacheContinuity"])
        prepared_compaction = checkpoints[0][2]["requestSummary"]["contextCompaction"]
        committed_compaction = checkpoints[1][2]["requestSummary"]["contextCompaction"]
        self.assertEqual("not_needed", prepared_compaction["status"])
        self.assertEqual(
            prepared_compaction["beforeSurfaceFingerprint"],
            committed_compaction["afterSurfaceFingerprint"],
        )
        self.assertTrue(committed_compaction["bodyRedacted"])
        self.assertEqual("responses", checkpoints[1][2]["wireApi"])
        self.assertEqual(2, checkpoints[1][2]["cacheReadTokens"])
        self.assertEqual(5, checkpoints[1][2]["cacheMissTokens"])
        self.assertNotIn(
            "prefixChainFingerprints",
            result.provider_calls[0]["cacheContinuity"],
        )

    async def test_model_committed_checkpoint_records_reporting_flags_and_routed_model(self) -> None:
        # 选中 m1，provider profile 落到默认档实际发出去的是 m9。影子投影按模型
        # 分桶，只记 m1 会把两个模型的前缀链混成一条，命中判据随之失效。
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
            "wire_api": "responses",
            "usage": {
                "promptTokens": 1200,
                "promptCacheHitTokens": 0,
                "promptCacheMissTokens": 1200,
                "usageReported": True,
                "cacheUsageReported": True,
            },
            "cacheContinuity": {
                "schemaVersion": 1,
                "provider": "openai_compatible",
                "wireApi": "responses",
                "model": "m9",
                "inputCount": 1,
                "inputFingerprint": "a" * 64,
                "prefixChainFingerprints": ["a" * 64],
                "bodyRedacted": True,
            },
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        await AgentKernel(provider, checkpoint_writer=writer).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="private prompt")],
                model="m1",
            )
        )

        committed = checkpoints[1][2]
        self.assertTrue(committed["usageReported"])
        self.assertTrue(committed["cacheUsageReported"])
        self.assertEqual("m9", committed["routedModel"])
        # 上游确实回报了缓存用量，0 命中是结论而不是"不知道"。
        self.assertEqual(0, committed["cacheReadTokens"])
        self.assertEqual(1200, committed["cacheMissTokens"])

    async def test_model_committed_checkpoint_marks_usage_as_unreported(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 0,
            "tool_calls": [],
            "wire_api": "responses",
            "usage": {"usageReported": False, "cacheUsageReported": False},
            "providerProfile": {"model": "m7"},
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        await AgentKernel(provider, checkpoint_writer=writer).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="private prompt")],
                model="m1",
            )
        )

        committed = checkpoints[1][2]
        self.assertFalse(committed["usageReported"])
        self.assertFalse(committed["cacheUsageReported"])
        # 没有 cacheContinuity 时退回 providerProfile，仍然不能记成选中的 m1。
        self.assertEqual("m7", committed["routedModel"])

    async def test_explicit_zero_cache_usage_wins_over_legacy_aliases(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
            # The normalized usage is authoritative even when the value is zero.
            "usage": {
                "promptCacheHitTokens": 0,
                "promptCacheMissTokens": 0,
                "promptCacheWriteTokens": 0,
                "promptCacheMissTokensDerived": False,
                "usageReported": True,
                "cacheUsageReported": True,
            },
            # Simulate a legacy adapter leaving stale snake/camel aliases behind.
            "prompt_cache_hit_tokens": 11,
            "prompt_cache_miss_tokens": 22,
            "prompt_cache_write_tokens": 33,
            "promptCacheHitTokens": 44,
            "promptCacheMissTokens": 55,
            "promptCacheWriteTokens": 66,
            "promptCacheMissTokensDerived": True,
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        result = await AgentKernel(provider, checkpoint_writer=writer).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="private prompt")],
                model="m1",
                max_turns=1,
            )
        )

        call = result.provider_calls[0]
        self.assertEqual(0, call["promptCacheHitTokens"])
        self.assertEqual(0, call["promptCacheMissTokens"])
        self.assertEqual(0, call["promptCacheWriteTokens"])
        self.assertFalse(call["promptCacheMissTokensDerived"])
        committed = checkpoints[1][2]
        self.assertEqual(0, committed["cacheReadTokens"])
        self.assertEqual(0, committed["cacheMissTokens"])
        self.assertEqual(0, committed["cacheWriteTokens"])
        self.assertFalse(committed["cacheMissTokensDerived"])

    async def test_stream_explicit_zero_cache_usage_wins_over_legacy_aliases(self) -> None:
        provider = StreamingProvider()

        async def stream_with_conflicting_usage(**_kwargs):
            yield {"event": "delta", "delta": "answer"}
            yield {
                "event": "done",
                "tokenUsed": 1,
                "usage": {
                    "promptCacheHitTokens": 0,
                    "promptCacheMissTokens": 0,
                    "promptCacheWriteTokens": 0,
                    "promptCacheMissTokensDerived": False,
                    "usageReported": True,
                    "cacheUsageReported": True,
                },
                "promptCacheHitTokens": 0,
                "promptCacheMissTokens": 0,
                "promptCacheWriteTokens": 0,
                "promptCacheMissTokensDerived": False,
                "prompt_cache_hit_tokens": 11,
                "prompt_cache_miss_tokens": 22,
                "prompt_cache_write_tokens": 33,
                "prompt_cache_miss_tokens_derived": True,
                "wireApi": "responses",
            }

        provider.stream = stream_with_conflicting_usage
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        events = [
            event
            async for event in AgentKernel(provider, checkpoint_writer=writer).stream(
                KernelTurnRequest(messages=[KernelMessage(role="user", content="stream")], model="m1")
            )
        ]

        result = events[-1].payload
        self.assertEqual(0, result["promptCacheHitTokens"])
        self.assertEqual(0, result["promptCacheMissTokens"])
        self.assertEqual(0, result["promptCacheWriteTokens"])
        self.assertFalse(result["promptCacheMissTokensDerived"])
        committed = checkpoints[1][2]
        self.assertEqual(0, committed["cacheMissTokens"])
        self.assertFalse(committed["cacheMissTokensDerived"])

    async def test_model_prepared_checkpoint_failure_prevents_provider_dispatch(self) -> None:
        provider = FakeProvider([{
            "content": "must not execute",
            "model_name": "m1",
            "token_used": 1,
            "tool_calls": [],
        }])

        async def writer(_event_type: str, _event_key: str, _payload: dict) -> None:
            raise RuntimeError("checkpoint unavailable")

        with self.assertRaisesRegex(RuntimeError, "checkpoint unavailable"):
            await AgentKernel(provider, checkpoint_writer=writer).run(
                KernelTurnRequest(messages=[KernelMessage(role="user", content="hi")], model="m1")
            )

        self.assertEqual([], provider.calls)

    async def test_model_checkpoint_correlates_redacted_prepared_source_event(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> dict:
            checkpoints.append((event_type, event_key, payload))
            return semantic_checkpoint_response(
                event_type,
                event_key,
                event_id=101 if event_type == "MODEL_PREPARED" else 102,
                sequence=7 if event_type == "MODEL_PREPARED" else 8,
            )

        result = await AgentKernel(
            provider,
            checkpoint_writer=writer,
            context_compactor=ContextCompactor(),
        ).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="private prompt")],
                model="m1",
            )
        )

        prepared = checkpoints[0][2]["requestSummary"]["contextCompaction"]
        committed = checkpoints[1][2]["requestSummary"]["contextCompaction"]
        expected_source = {
            "schemaVersion": 1,
            "eventId": 101,
            "sequence": 7,
            "eventType": "MODEL_PREPARED",
            "bodyRedacted": True,
        }
        self.assertNotIn("sourceEvent", prepared)
        self.assertEqual(expected_source, committed["sourceEvent"])
        self.assertEqual(
            expected_source,
            result.provider_calls[0]["requestSummary"]["contextCompaction"]["sourceEvent"],
        )
        self.assertNotIn("privateCallbackBody", str(checkpoints))
        self.assertNotIn("privateCallbackBody", str(result.provider_calls))
        self.assertNotIn("sourceEvent", str(provider.calls))

    async def test_final_provider_envelope_is_compacted_before_invoke_dispatch(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
        }])
        compactor = ContextCompactor(ModelContextCapability(
            context_window_tokens=12_000,
            max_output_tokens=2_000,
            reserved_output_tokens=300,
            safety_margin_tokens=200,
            target_ratio=0.72,
            minimum_recent_turns=2,
            max_summary_tokens=900,
        ))
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        result = await AgentKernel(
            provider,
            checkpoint_writer=writer,
            context_compactor=compactor,
        ).run(
            KernelTurnRequest(
                messages=[
                    KernelMessage(role="system", content="stable prefix"),
                    KernelMessage(role="user", content="old request"),
                    KernelMessage(
                        role="assistant",
                        tool_calls=[{"id": "call-1", "type": "function"}],
                    ),
                    KernelMessage(
                        role="tool",
                        tool_call_id="call-1",
                        content="large-result " * 5000,
                    ),
                    KernelMessage(role="user", content="recent request"),
                ],
                model="m1",
                max_tokens=512,
            )
        )

        sent_messages = provider.calls[0]["messages"]
        sent_tool = next(message for message in sent_messages if message.get("role") == "tool")
        sent_assistant = next(
            message for message in sent_messages if message.get("role") == "assistant"
        )
        summary = result.provider_calls[0]["requestSummary"]["contextCompaction"]
        self.assertEqual("compacted", summary["status"])
        self.assertLess(summary["afterInputTokens"], summary["beforeInputTokens"])
        self.assertEqual(["MODEL_PREPARED", "MODEL_COMMITTED"], [item[0] for item in checkpoints])
        prepared = checkpoints[0][2]["requestSummary"]["contextCompaction"]
        committed = checkpoints[1][2]["requestSummary"]["contextCompaction"]
        self.assertEqual(prepared, committed)
        self.assertNotEqual(prepared["beforeSurfaceFingerprint"], prepared["afterSurfaceFingerprint"])
        self.assertEqual(summary, prepared)
        self.assertTrue(prepared["bodyRedacted"])
        self.assertNotIn("large-result", str(checkpoints))
        self.assertEqual("call-1", sent_tool["tool_call_id"])
        self.assertEqual("call-1", sent_assistant["tool_calls"][0]["id"])

    async def test_compacted_invoke_checkpoint_keeps_source_event_reference(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> dict:
            checkpoints.append((event_type, event_key, payload))
            return semantic_checkpoint_response(
                event_type,
                event_key,
                event_id=301 if event_type == "MODEL_PREPARED" else 302,
                sequence=21 if event_type == "MODEL_PREPARED" else 22,
            )

        compactor = ContextCompactor(ModelContextCapability(
            context_window_tokens=12_000,
            max_output_tokens=2_000,
            reserved_output_tokens=300,
            safety_margin_tokens=200,
            target_ratio=0.72,
            minimum_recent_turns=1,
            max_summary_tokens=900,
        ))
        result = await AgentKernel(
            provider,
            checkpoint_writer=writer,
            context_compactor=compactor,
        ).run(
            KernelTurnRequest(
                messages=[
                    KernelMessage(role="system", content="stable prefix"),
                    KernelMessage(role="user", content="old request " * 3000),
                    KernelMessage(role="assistant", content="old answer " * 3000),
                    KernelMessage(role="user", content="recent request"),
                ],
                model="m1",
                max_tokens=512,
            )
        )

        prepared = checkpoints[0][2]["requestSummary"]["contextCompaction"]
        committed = checkpoints[1][2]["requestSummary"]["contextCompaction"]
        self.assertEqual("compacted", prepared["status"])
        self.assertNotIn("sourceEvent", prepared)
        self.assertEqual(301, committed["sourceEvent"]["eventId"])
        self.assertEqual(21, committed["sourceEvent"]["sequence"])
        self.assertEqual("MODEL_PREPARED", committed["sourceEvent"]["eventType"])
        self.assertEqual(
            committed["sourceEvent"],
            result.provider_calls[0]["requestSummary"]["contextCompaction"]["sourceEvent"],
        )
        self.assertNotIn("old request", str(checkpoints))
        self.assertNotIn("old answer", str(checkpoints))
        self.assertNotIn("sourceEvent", str(provider.calls))

    async def test_non_convergent_final_envelope_blocks_invoke_and_stream_dispatch(self) -> None:
        compactor = ContextCompactor(ModelContextCapability(
            context_window_tokens=8_000,
            max_output_tokens=2_000,
            reserved_output_tokens=300,
            safety_margin_tokens=200,
            target_ratio=0.62,
            minimum_recent_turns=1,
            max_summary_tokens=900,
        ))
        request = KernelTurnRequest(
            messages=[
                KernelMessage(role="system", content="immutable " * 5000),
                KernelMessage(role="user", content="current " * 5000),
            ],
            model="m1",
            max_tokens=512,
        )

        invoke_provider = FakeProvider([])
        with self.assertRaises(ProviderEnvelopeCompactionError):
            await AgentKernel(invoke_provider, context_compactor=compactor).run(request)
        self.assertEqual([], invoke_provider.calls)

        stream_provider = StreamingProvider()
        with self.assertRaises(ProviderEnvelopeCompactionError):
            _ = [
                event
                async for event in AgentKernel(
                    stream_provider,
                    context_compactor=compactor,
                ).stream(request)
            ]
        self.assertEqual([], stream_provider.stream_calls)
        self.assertEqual([], stream_provider.invoke_calls)

    async def test_cache_affinity_and_tool_schemas_are_canonical_and_redacted(self) -> None:
        provider = FakeProvider([{
            "content": "final answer",
            "model_name": "m1",
            "token_used": 3,
            "tool_calls": [],
        }])

        async def executor(_call: KernelToolCall) -> KernelToolObservation:
            raise AssertionError("tool executor should not run")

        result = await AgentKernel(provider).run(
            KernelTurnRequest(
                messages=[
                    KernelMessage(role="system", content="stable system prefix"),
                    KernelMessage(role="user", content="dynamic question"),
                ],
                model="m1",
                cache_affinity="cache-affinity-secret",
                request_family="answer",
                tool_schemas=[
                    {
                        "function": {
                            "parameters": {
                                "required": ["value"],
                                "type": "object",
                                "properties": {"value": {"type": "string"}},
                            },
                            "name": "z.tool",
                        },
                        "type": "function",
                    },
                    {
                        "type": "function",
                        "function": {
                            "name": "a.tool",
                            "parameters": {
                                "type": "object",
                                "properties": {"query": {"type": "string"}},
                                "required": ["query"],
                            },
                        },
                    },
                ],
                max_turns=1,
            ),
            authorization={"grants": [{"toolName": "z.tool"}, {"toolName": "a.tool"}]},
            tool_executor=executor,
        )

        self.assertEqual("cache-affinity-secret", provider.calls[0]["cache_affinity"])
        self.assertEqual(
            ["a.tool", "z.tool"],
            [tool["function"]["name"] for tool in provider.calls[0]["tools"]],
        )
        self.assertEqual(
            ["properties", "required", "type"],
            list(provider.calls[0]["tools"][1]["function"]["parameters"]),
        )
        summary = result.provider_calls[0]["requestSummary"]
        self.assertTrue(summary["cacheAffinityPresent"])
        self.assertEqual("answer", summary["requestFamily"])
        self.assertEqual(len("stable system prefix"), summary["cachePrefixChars"])
        self.assertEqual(64, len(summary["cachePrefixFingerprint"]))
        self.assertNotIn("cache-affinity-secret", str(result.provider_calls))

    async def test_streaming_no_tool_turn_is_owned_by_kernel(self) -> None:
        provider = StreamingProvider()
        events = [
            event
            async for event in AgentKernel(provider).stream(
                KernelTurnRequest(
                    messages=[KernelMessage(role="user", content="stream")],
                    model="m1",
                    cache_affinity="stream-cache-affinity",
                    max_turns=1,
                )
            )
        ]

        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual([], provider.invoke_calls)
        self.assertEqual("stream-cache-affinity", provider.stream_calls[0]["cache_affinity"])
        self.assertEqual(
            ["message.start", "message.delta", "message.delta", "message.end", "turn.end", "result"],
            [event.type for event in events],
        )
        self.assertEqual("first second", events[-1].payload["content"])
        self.assertEqual("completed", events[-1].payload["stopReason"])
        self.assertEqual(7, events[-1].payload["tokenUsed"])
        self.assertEqual(2, events[-1].payload["promptCacheHitTokens"])
        self.assertEqual(1, events[-1].payload["providerRequestCount"])
        provider_call = events[-1].payload["kernelProviderCalls"][0]
        self.assertEqual("stream", provider_call["transport"])
        self.assertEqual("responses", provider_call["wireApi"])
        self.assertEqual(5, provider_call["usage"]["inputTokens"])
        self.assertEqual(1, provider_call["usage"]["reasoningTokens"])
        self.assertEqual({"user": 1}, provider_call["requestSummary"]["roleCounts"])
        self.assertEqual(1, provider_call["requestSummary"]["messageCount"])
        self.assertEqual(len("stream"), provider_call["requestSummary"]["messageChars"])
        self.assertEqual(0, provider_call["requestSummary"]["toolSchemaCount"])
        self.assertFalse(provider_call["requestSummary"]["reasoningRequested"])
        self.assertTrue(provider_call["requestSummary"]["bodyRedacted"])
        self.assertEqual(len("first second"), provider_call["responseSummary"]["outputChars"])
        self.assertEqual(0, provider_call["responseSummary"]["toolCallCount"])
        self.assertFalse(provider_call["responseSummary"]["emptyResponse"])
        self.assertTrue(provider_call["responseSummary"]["bodyRedacted"])

    async def test_blocking_and_streaming_share_canonical_request_identity(self) -> None:
        request = KernelTurnRequest(
            messages=[
                KernelMessage(role="system", content="stable prefix"),
                KernelMessage(role="user", content="same request"),
            ],
            model="m1",
            temperature=0.2,
            max_tokens=128,
            reasoning_mode="fast",
            cache_affinity="same-cache-scope",
            max_turns=1,
        )
        blocking_checkpoints: list[tuple[str, str, dict]] = []
        streaming_checkpoints: list[tuple[str, str, dict]] = []

        async def blocking_writer(event_type: str, event_key: str, payload: dict) -> None:
            blocking_checkpoints.append((event_type, event_key, payload))

        async def streaming_writer(event_type: str, event_key: str, payload: dict) -> None:
            streaming_checkpoints.append((event_type, event_key, payload))

        await AgentKernel(
            FakeProvider([{
                "content": "blocking answer",
                "model_name": "m1",
                "token_used": 3,
                "tool_calls": [],
            }]),
            checkpoint_writer=blocking_writer,
        ).run(request)
        _ = [
            event
            async for event in AgentKernel(
                StreamingProvider(),
                checkpoint_writer=streaming_writer,
            ).stream(request)
        ]

        blocking_prepared = blocking_checkpoints[0][2]
        streaming_prepared = streaming_checkpoints[0][2]
        self.assertEqual("MODEL_PREPARED", blocking_checkpoints[0][0])
        self.assertEqual("MODEL_PREPARED", streaming_checkpoints[0][0])
        self.assertEqual(
            blocking_prepared["requestFingerprint"],
            streaming_prepared["requestFingerprint"],
        )
        self.assertEqual(
            blocking_prepared["requestSummary"],
            streaming_prepared["requestSummary"],
        )
        self.assertEqual("invoke", blocking_prepared["transport"])
        self.assertEqual("stream", streaming_prepared["transport"])
        self.assertNotEqual(
            blocking_prepared["semanticKey"],
            streaming_prepared["semanticKey"],
        )

    async def test_provider_profile_is_frozen_across_blocking_tool_turns_and_redacted(self) -> None:
        profile = ProviderProfile(
            profile_key="profile-blocking",
            profile_version="v1",
            endpoint="https://gateway.example/v1",
            model="frozen-model",
            protocol="responses",
            api_key="blocking-profile-secret",
        )
        provider = ProfileAwareProvider(
            [
                {
                    "content": "",
                    "model_name": "frozen-model",
                    "token_used": 1,
                    "tool_calls": [{"id": "c1", "name": "rank.lookup", "arguments": {}}],
                },
                {
                    "content": "final",
                    "model_name": "frozen-model",
                    "token_used": 2,
                    "tool_calls": [],
                },
            ],
            profile,
        )

        async def executor(call: KernelToolCall) -> KernelToolObservation:
            return KernelToolObservation(
                tool_call_id=call.id,
                name=call.name,
                status="succeeded",
                content="{}",
            )

        route_snapshot = {
            "profileKey": "profile-blocking",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "frozen-model",
            "protocol": "responses",
        }
        result = await AgentKernel(provider).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="question")],
                model="request-model",
                provider_profile=route_snapshot,
                tool_schemas=[{
                    "type": "function",
                    "function": {"name": "rank.lookup", "parameters": {"type": "object"}},
                }],
                max_turns=2,
            ),
            authorization=self._auth_for_market(),
            tool_executor=executor,
        )

        self.assertEqual("final", result.content)
        self.assertEqual(1, len(provider.profile_resolve_calls))
        self.assertEqual("request-model", provider.profile_resolve_calls[0]["model"])
        self.assertEqual(route_snapshot, provider.profile_resolve_calls[0]["route_snapshot"])
        self.assertEqual(2, len(provider.calls))
        self.assertIs(profile, provider.calls[0]["provider_profile"])
        self.assertIs(profile, provider.calls[1]["provider_profile"])
        self.assertEqual(
            [profile.snapshot(), profile.snapshot()],
            [call["providerProfile"] for call in result.provider_calls],
        )
        self.assertNotIn("blocking-profile-secret", str(result.provider_calls))

    async def test_run_scope_resolves_transient_profile_before_provider_dispatch(self) -> None:
        provider = RuntimeDispatchProvider([{
            "content": "resolved",
            "model_name": "intent-model",
            "token_used": 2,
            "tool_calls": [],
        }])
        secret = "runtime-dispatch-secret"
        capabilities = {
            "schemaVersion": 1,
            "supportsStreaming": True,
            "supportsTools": True,
            "supportsJsonObject": True,
            "supportsReasoning": True,
            "reportsUsage": True,
            "reportsCacheUsage": True,
        }

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint="https://gateway.example/v1",
                model="intent-model",
                provider_type="openai-compatible",
                protocol="responses",
                api_key=secret,
                provider_capabilities=capabilities,
            )

        routes = [{
            "profileKey": "intent-profile",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "intent-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "providerCapabilities": capabilities,
            "isDefault": True,
        }]
        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="intent-model"):
            result = await AgentKernel(provider).run(KernelTurnRequest(
                messages=[KernelMessage(role="user", content="classify")],
                model="intent-model",
                max_turns=1,
            ))

        self.assertEqual("resolved", result.content)
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(secret, provider.profile_resolve_calls[0]["api_key"])
        self.assertEqual(
            capabilities,
            result.provider_calls[0]["requestSummary"]["providerProfile"]["providerCapabilities"],
        )
        self.assertNotIn(secret, str(result.provider_calls))

    async def test_dispatch_resolution_failure_precedes_checkpoint_and_provider_call(self) -> None:
        provider = RuntimeDispatchProvider([{
            "content": "must-not-run",
            "model_name": "intent-model",
            "token_used": 1,
            "tool_calls": [],
        }])
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        async def resolver(_profile_key: str, _profile_version: str) -> ProviderDispatch:
            raise RuntimeError("credential unavailable")

        routes = [{
            "profileKey": "intent-profile",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "intent-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "isDefault": True,
        }]
        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="intent-model"):
            with self.assertRaisesRegex(RuntimeError, "credential unavailable"):
                await AgentKernel(provider, checkpoint_writer=writer).run(KernelTurnRequest(
                    messages=[KernelMessage(role="user", content="classify")],
                    model="intent-model",
                    max_turns=1,
                ))

        self.assertEqual([], provider.calls)
        self.assertEqual([], provider.profile_resolve_calls)
        self.assertEqual([], checkpoints)

    async def test_provider_profile_is_frozen_for_direct_stream_and_redacted(self) -> None:
        profile = ProviderProfile(
            profile_key="profile-stream",
            profile_version="v2",
            endpoint="https://gateway.example/v1",
            model="stream-model",
            protocol="responses",
            api_key="stream-profile-secret",
        )
        provider = ProfileAwareStreamingProvider(profile)
        route_snapshot = {
            "profileKey": "profile-stream",
            "profileVersion": "v2",
            "endpoint": "https://gateway.example/v1",
            "model": "stream-model",
            "protocol": "responses",
        }
        events = [
            event
            async for event in AgentKernel(provider).stream(
                KernelTurnRequest(
                    messages=[KernelMessage(role="user", content="stream")],
                    model="request-model",
                    provider_profile=route_snapshot,
                )
            )
        ]

        self.assertEqual(1, len(provider.profile_resolve_calls))
        self.assertEqual(route_snapshot, provider.profile_resolve_calls[0]["route_snapshot"])
        self.assertEqual(1, len(provider.stream_calls))
        self.assertIs(profile, provider.stream_calls[0]["provider_profile"])
        provider_call = events[-1].payload["kernelProviderCalls"][0]
        self.assertEqual(profile.snapshot(), provider_call["providerProfile"])
        self.assertNotIn("stream-profile-secret", str(events))

    async def test_model_checkpoint_wraps_direct_stream_dispatch(self) -> None:
        provider = StreamingProvider()
        cache_continuity = {
            "schemaVersion": 1,
            "provider": "openai_compatible",
            "wireApi": "responses",
            "model": "m1",
            "inputCount": 1,
            "inputFingerprint": "b" * 64,
            "prefixChainFingerprints": ["b" * 64],
            "bodyRedacted": True,
        }
        checkpoints: list[tuple[str, str, dict]] = []

        original_stream = provider.stream

        async def stream_with_cache(**kwargs):
            async for event in original_stream(**kwargs):
                if event.get("event") == "done":
                    event["cacheContinuity"] = cache_continuity
                yield event

        provider.stream = stream_with_cache

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        _ = [
            event
            async for event in AgentKernel(
                provider,
                checkpoint_writer=writer,
                context_compactor=ContextCompactor(),
            ).stream(
                KernelTurnRequest(messages=[KernelMessage(role="user", content="stream")], model="m1")
            )
        ]

        self.assertEqual(["MODEL_PREPARED", "MODEL_COMMITTED"], [item[0] for item in checkpoints])
        self.assertEqual(1, len(provider.stream_calls))
        prepared_compaction = checkpoints[0][2]["requestSummary"]["contextCompaction"]
        committed_compaction = checkpoints[1][2]["requestSummary"]["contextCompaction"]
        self.assertEqual("not_needed", prepared_compaction["status"])
        self.assertEqual(
            prepared_compaction["beforeSurfaceFingerprint"],
            committed_compaction["afterSurfaceFingerprint"],
        )
        self.assertTrue(committed_compaction["bodyRedacted"])
        self.assertEqual(cache_continuity, checkpoints[1][2]["cacheContinuity"])
        self.assertEqual("responses", checkpoints[1][2]["wireApi"])
        self.assertEqual(2, checkpoints[1][2]["cacheReadTokens"])
        self.assertEqual(5, checkpoints[1][2]["cacheMissTokens"])

    async def test_stream_cancellation_after_done_prevents_model_commit_and_terminal_events(self) -> None:
        provider = StreamingProvider()
        token = CancellationToken()
        checkpoints: list[tuple[str, str, dict]] = []
        original_stream = provider.stream

        async def stream_then_cancel(**kwargs):
            async for event in original_stream(**kwargs):
                yield event
            token.cancel("cancelled_after_provider_done")

        provider.stream = stream_then_cancel

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        events = []
        with cancellation_scope(token):
            with self.assertRaisesRegex(RunCancelledError, "cancelled_after_provider_done"):
                async for event in AgentKernel(
                    provider,
                    checkpoint_writer=writer,
                    context_compactor=ContextCompactor(),
                ).stream(
                    KernelTurnRequest(
                        messages=[KernelMessage(role="user", content="stream")],
                        model="m1",
                    )
                ):
                    events.append(event)

        self.assertEqual(["MODEL_PREPARED"], [item[0] for item in checkpoints])
        self.assertNotIn("MODEL_COMMITTED", [item[0] for item in checkpoints])
        self.assertEqual(
            ["message.start", "message.delta", "message.delta"],
            [event.type for event in events],
        )
        self.assertNotIn("message.end", [event.type for event in events])
        self.assertNotIn("turn.end", [event.type for event in events])
        self.assertNotIn("result", [event.type for event in events])

    async def test_empty_stream_fallback_reports_both_provider_requests(self) -> None:
        provider = EmptyStreamingProvider()
        events = [
            event
            async for event in AgentKernel(provider).stream(
                KernelTurnRequest(
                    messages=[KernelMessage(role="user", content="stream")],
                    model="m1",
                    max_turns=1,
                )
            )
        ]

        result = events[-1].payload
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertEqual("fallback answer", result["content"])
        self.assertEqual(
            [
                "message.start",
                "message.end",
                "turn.end",
                "message.start",
                "message.delta",
                "message.end",
                "turn.end",
                "result",
            ],
            [event.type for event in events],
        )
        self.assertEqual(2, result["kernelTurns"])
        self.assertEqual(2, result["providerRequestCount"])
        self.assertEqual(["stream", "invoke"], [call["transport"] for call in result["kernelProviderCalls"]])
        self.assertTrue(result["kernelProviderCalls"][0]["emptyResponse"])
        self.assertTrue(result["kernelProviderCalls"][0]["responseSummary"]["emptyResponse"])
        self.assertEqual(
            len("fallback answer"),
            result["kernelProviderCalls"][1]["responseSummary"]["outputChars"],
        )

    async def test_compacted_stream_checkpoint_keeps_replacement_metadata(self) -> None:
        provider = StreamingProvider()
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> dict:
            checkpoints.append((event_type, event_key, payload))
            return semantic_checkpoint_response(
                event_type,
                event_key,
                event_id=201 if event_type == "MODEL_PREPARED" else 202,
                sequence=11 if event_type == "MODEL_PREPARED" else 12,
            )

        compactor = ContextCompactor(ModelContextCapability(
            context_window_tokens=12_000,
            max_output_tokens=2_000,
            reserved_output_tokens=300,
            safety_margin_tokens=200,
            target_ratio=0.72,
            minimum_recent_turns=1,
            max_summary_tokens=900,
        ))
        _ = [
            event
            async for event in AgentKernel(
                provider,
                checkpoint_writer=writer,
                context_compactor=compactor,
            ).stream(
                KernelTurnRequest(
                    messages=[
                        KernelMessage(role="system", content="stable prefix"),
                        KernelMessage(role="user", content="old request " * 3000),
                        KernelMessage(role="assistant", content="old answer " * 3000),
                        KernelMessage(role="user", content="recent request"),
                    ],
                    model="m1",
                    max_tokens=512,
                )
            )
        ]

        self.assertEqual(["MODEL_PREPARED", "MODEL_COMMITTED"], [item[0] for item in checkpoints])
        prepared = checkpoints[0][2]["requestSummary"]["contextCompaction"]
        committed = checkpoints[1][2]["requestSummary"]["contextCompaction"]
        self.assertEqual("compacted", prepared["status"])
        self.assertNotIn("sourceEvent", prepared)
        self.assertEqual(
            {
                "schemaVersion": 1,
                "eventId": 201,
                "sequence": 11,
                "eventType": "MODEL_PREPARED",
                "bodyRedacted": True,
            },
            committed["sourceEvent"],
        )
        self.assertNotEqual(prepared["beforeSurfaceFingerprint"], prepared["afterSurfaceFingerprint"])
        self.assertTrue(prepared["bodyRedacted"])
        self.assertNotIn("old request", str(checkpoints))
        self.assertNotIn("old answer", str(checkpoints))
        self.assertNotIn("privateCallbackBody", str(checkpoints))

    async def test_authorized_tool_then_final_answer(self) -> None:
        provider = FakeProvider([
            {
                "content": "",
                "model_name": "m1",
                "token_used": 2,
                "tool_calls": [{"id": "c1", "name": "rank.lookup", "arguments": {"limit": 3}}],
            },
            {"content": "based on ranks", "model_name": "m1", "token_used": 4, "tool_calls": []},
        ])
        observations: list[str] = []

        async def executor(call: KernelToolCall) -> KernelToolObservation:
            observations.append(call.name)
            return KernelToolObservation(
                tool_call_id=call.id,
                name=call.name,
                status="succeeded",
                content='{"ok": true}',
            )

        kernel = AgentKernel(provider)
        result = await kernel.run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="trend?")],
                model="m1",
                tool_schemas=[{
                    "type": "function",
                    "function": {"name": "rank.lookup", "parameters": {"type": "object"}},
                }],
                max_turns=4,
            ),
            authorization=self._auth_for_market(),
            tool_executor=executor,
        )
        self.assertEqual(["rank.lookup"], observations)
        self.assertEqual("based on ranks", result.content)
        self.assertEqual(KernelStopReason.COMPLETED, result.stop_reason)
        self.assertEqual(1, len(result.tool_runs))
        self.assertEqual(2, len(provider.calls))
        projection = result.to_provider_result()
        self.assertEqual(2, projection["providerRequestCount"])
        self.assertEqual(2, projection["kernelTurns"])
        self.assertEqual(
            ["tool_calls", "completed"],
            [call["kernelStopReason"] for call in projection["kernelProviderCalls"]],
        )
        self.assertEqual(1, projection["kernelProviderCalls"][0]["requestSummary"]["toolSchemaCount"])
        self.assertEqual(1, projection["kernelProviderCalls"][0]["responseSummary"]["toolCallCount"])
        self.assertEqual(3, projection["kernelProviderCalls"][1]["requestSummary"]["messageCount"])
        self.assertNotIn("trend?", str(projection["kernelProviderCalls"]))
        self.assertNotIn("based on ranks", str(projection["kernelProviderCalls"]))
        assistant = next(
            message
            for message in provider.calls[1]["messages"]
            if message.get("role") == "assistant"
        )
        self.assertEqual("function", assistant["tool_calls"][0]["type"])
        self.assertEqual("rank.lookup", assistant["tool_calls"][0]["function"]["name"])
        self.assertIsInstance(assistant["tool_calls"][0]["function"]["arguments"], str)

    async def test_unauthorized_tool_is_blocked_before_executor(self) -> None:
        provider = FakeProvider([
            {
                "content": "",
                "model_name": "m1",
                "token_used": 1,
                "tool_calls": [{"id": "c1", "name": "rank.refresh", "arguments": {}}],
            },
        ])
        executed = []

        async def executor(call: KernelToolCall) -> KernelToolObservation:
            executed.append(call.name)
            return KernelToolObservation(tool_call_id=call.id, name=call.name, status="succeeded", content="should not run")

        kernel = AgentKernel(provider)
        result = await kernel.run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="refresh")],
                model="m1",
                tool_schemas=[
                    {"type": "function", "function": {"name": "rank.lookup", "parameters": {"type": "object"}}},
                    {"type": "function", "function": {"name": "rank.refresh", "parameters": {"type": "object"}}},
                ],
                max_turns=3,
            ),
            authorization=self._auth_for_market(),
            tool_executor=executor,
        )
        self.assertEqual([], executed)
        self.assertEqual(KernelStopReason.UNAUTHORIZED_TOOL, result.stop_reason)
        self.assertEqual(1, len(provider.calls))
        projection = result.to_provider_result()
        self.assertEqual(1, projection["providerRequestCount"])
        self.assertEqual(1, len(projection["kernelProviderCalls"]))
        self.assertEqual(
            "unauthorized_tool",
            projection["kernelProviderCalls"][0]["kernelStopReason"],
        )
        # unauthorized tool must not be offered in schema either
        tools = provider.calls[0].get("tools") or []
        names = [
            (item.get("function") or {}).get("name")
            for item in tools
            if isinstance(item, dict)
        ]
        self.assertEqual(["rank.lookup"], names)

    async def test_missing_authorization_exposes_no_schema_and_blocks_execution(self) -> None:
        provider = FakeProvider([
            {
                "content": "",
                "model_name": "m1",
                "tool_calls": [
                    {"id": "call-1", "name": "rank.lookup", "arguments": {}},
                ],
            },
        ])
        executed: list[str] = []

        async def executor(call: KernelToolCall) -> KernelToolObservation:
            executed.append(call.name)
            return KernelToolObservation(
                tool_call_id=call.id,
                name=call.name,
                status="succeeded",
                content="should not execute",
            )

        result = await AgentKernel(provider).run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="trend?")],
                model="m1",
                tool_schemas=[{
                    "type": "function",
                    "function": {"name": "rank.lookup", "parameters": {"type": "object"}},
                }],
            ),
            tool_executor=executor,
        )

        self.assertEqual([], executed)
        self.assertEqual(KernelStopReason.UNAUTHORIZED_TOOL, result.stop_reason)
        self.assertFalse(provider.calls[0].get("tools"))

    async def test_max_turns_stops_loop(self) -> None:
        provider = FakeProvider([
            {
                "content": "",
                "model_name": "m1",
                "token_used": 1,
                "tool_calls": [{"id": "c1", "name": "rank.lookup", "arguments": {}}],
            },
            {
                "content": "",
                "model_name": "m1",
                "token_used": 1,
                "tool_calls": [{"id": "c2", "name": "rank.lookup", "arguments": {}}],
            },
        ])

        async def executor(call: KernelToolCall) -> KernelToolObservation:
            return KernelToolObservation(tool_call_id=call.id, name=call.name, status="succeeded", content="{}")

        kernel = AgentKernel(provider)
        result = await kernel.run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="loop")],
                model="m1",
                tool_schemas=[{"type": "function", "function": {"name": "rank.lookup", "parameters": {"type": "object"}}}],
                max_turns=1,
                max_tool_calls=5,
            ),
            authorization=self._auth_for_market(),
            tool_executor=executor,
        )
        self.assertEqual(KernelStopReason.MAX_TURNS, result.stop_reason)
        self.assertEqual(1, len(provider.calls))

    async def test_before_tool_hook_can_block(self) -> None:
        provider = FakeProvider([
            {
                "content": "",
                "model_name": "m1",
                "token_used": 1,
                "tool_calls": [{"id": "c1", "name": "rank.lookup", "arguments": {}}],
            },
        ])

        async def executor(call: KernelToolCall) -> KernelToolObservation:
            raise AssertionError("executor should not run")

        async def before(call, observation):
            return None

        kernel = AgentKernel(provider, before_tool=before)
        result = await kernel.run(
            KernelTurnRequest(
                messages=[KernelMessage(role="user", content="x")],
                model="m1",
                tool_schemas=[{"type": "function", "function": {"name": "rank.lookup", "parameters": {"type": "object"}}}],
            ),
            authorization=self._auth_for_market(),
            tool_executor=executor,
        )
        self.assertEqual(KernelStopReason.HOOK_BLOCKED, result.stop_reason)



class KernelStopReasonProtocolTest(unittest.TestCase):
    def test_needs_user_input_protocol_slot_exists(self) -> None:
        self.assertEqual("needs_user_input", KernelStopReason.NEEDS_USER_INPUT.value)


if __name__ == "__main__":
    unittest.main()
