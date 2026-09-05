from __future__ import annotations

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx

from app.config import settings
from app.models.analysis import PromptConfigPayload, RunRequest
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.harness.cancellation import CancellationToken, RunCancelledError, cancellation_scope
from app.services.harness.agent_kernel import (
    AgentKernel,
    KernelMessage,
    KernelToolObservation,
    KernelTurnRequest,
)
from app.services.provider_client import OpenAICompatibleProviderClient, ProviderProfile


CHINESE_PROMPT = "\u8bf7\u53ea\u56de\u590dok"


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload


class FakeStreamResponse:
    def __init__(self, lines: list[str]) -> None:
        self._lines = lines

    async def __aenter__(self) -> FakeStreamResponse:
        return self

    async def __aexit__(self, exc_type, exc, tb) -> bool:
        return False

    def raise_for_status(self) -> None:
        return None

    async def aiter_lines(self):
        for line in self._lines:
            yield line


class StatusStreamResponse(FakeStreamResponse):
    def __init__(self, status_code: int, request: httpx.Request) -> None:
        super().__init__([])
        self._response = httpx.Response(status_code, request=request)

    def raise_for_status(self) -> None:
        self._response.raise_for_status()


class BlockingStreamResponse(FakeStreamResponse):
    def __init__(self) -> None:
        super().__init__([])
        self.started = asyncio.Event()
        self.cleaned_up = asyncio.Event()

    async def __aexit__(self, exc_type, exc, tb) -> bool:
        self.cleaned_up.set()
        return False

    async def aiter_lines(self):
        self.started.set()
        await asyncio.Event().wait()
        if False:
            yield ""


class RaisingStreamResponse:
    def __init__(self, error: Exception) -> None:
        self._error = error

    async def __aenter__(self):
        raise self._error

    async def __aexit__(self, exc_type, exc, tb) -> bool:
        return False


class FakeAsyncClient:
    def __init__(self, factory: "FakeAsyncClientFactory") -> None:
        self.factory = factory

    async def __aenter__(self) -> "FakeAsyncClient":
        return self

    async def __aexit__(self, exc_type, exc, tb) -> bool:
        return False

    async def post(self, *args, **kwargs):
        self.factory.post_calls.append({"args": args, "kwargs": kwargs})
        effect = self.factory.post_effects.pop(0)
        if isinstance(effect, Exception):
            raise effect
        return effect

    def stream(self, *args, **kwargs):
        self.factory.stream_calls.append({"args": args, "kwargs": kwargs})
        effect = self.factory.stream_effects.pop(0)
        if isinstance(effect, Exception):
            return RaisingStreamResponse(effect)
        return effect


class FakeAsyncClientFactory:
    def __init__(self, *, post_effects: list[object] | None = None, stream_effects: list[object] | None = None) -> None:
        self.post_effects = list(post_effects or [])
        self.stream_effects = list(stream_effects or [])
        self.post_calls: list[dict] = []
        self.stream_calls: list[dict] = []
        self.constructor_calls: list[dict] = []

    def __call__(self, *args, **kwargs) -> FakeAsyncClient:
        self.constructor_calls.append({"args": args, "kwargs": kwargs})
        return FakeAsyncClient(self)


class ProviderClientRetryTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.request = httpx.Request("POST", "https://api.deepseek.com/v1/chat/completions")

    def test_usage_summary_preserves_provider_field_presence(self) -> None:
        client = OpenAICompatibleProviderClient()

        absent = client._usage_summary({})
        inferred_miss = client._usage_summary({"input_tokens": 12, "output_tokens": 3})
        explicit_zero_cache = client._usage_summary({
            "input_tokens": 12,
            "output_tokens": 3,
            "input_tokens_details": {"cached_tokens": 0},
            "prompt_cache_miss_tokens": 0,
        })

        self.assertFalse(absent["usageReported"])
        self.assertFalse(absent["cacheUsageReported"])
        self.assertFalse(absent["promptCacheMissTokensDerived"])
        self.assertTrue(inferred_miss["usageReported"])
        self.assertFalse(inferred_miss["cacheUsageReported"])
        self.assertEqual(12, inferred_miss["promptCacheMissTokens"])
        self.assertTrue(explicit_zero_cache["usageReported"])
        self.assertTrue(explicit_zero_cache["cacheUsageReported"])
        self.assertEqual(0, explicit_zero_cache["promptCacheHitTokens"])
        # Provider 明确回报 miss=0 时，不能再用 prompt-hit 反推一遍。
        self.assertEqual(0, explicit_zero_cache["promptCacheMissTokens"])
        self.assertFalse(explicit_zero_cache["promptCacheMissTokensDerived"])

        write_usage = client._usage_summary({
            "input_tokens": 100,
            "output_tokens": 5,
            "input_tokens_details": {
                "cached_tokens": 80,
                "cache_write_tokens": 10,
            },
        })
        self.assertEqual(80, write_usage["promptCacheHitTokens"])
        self.assertEqual(10, write_usage["promptCacheWriteTokens"])
        self.assertEqual(10, write_usage["promptCacheMissTokens"])
        self.assertTrue(write_usage["promptCacheMissTokensDerived"])

    async def test_explicit_provider_profile_freezes_responses_route_without_secret_projection(self) -> None:
        factory = FakeAsyncClientFactory(post_effects=[FakeResponse({
            "id": "resp_profile",
            "status": "completed",
            "model": "gateway-model",
            "output": [{"type": "message", "content": [{"type": "output_text", "text": "ok"}]}],
            "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
        })])
        profile = ProviderProfile(
            profile_key="gateway-profile",
            profile_version="profile-v1",
            endpoint="https://gateway.example/v1",
            model="gateway-model",
            protocol="responses",
            api_key="third-party-secret-never-trace",
        )
        client = OpenAICompatibleProviderClient()
        with patch("app.services.provider_client.httpx.AsyncClient", side_effect=factory), \
             patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
            result = await client.invoke(
                messages=[{"role": "user", "content": "hello"}],
                model="unlisted-model",
                temperature=0.2,
                max_tokens=32,
                require_json=False,
                request_family="answer",
                provider_profile=profile,
            )

        call = factory.post_calls[0]
        self.assertTrue(call["args"][0].endswith("/responses"))
        self.assertEqual("gateway-model", call["kwargs"]["json"]["model"])
        self.assertEqual(profile.snapshot(), result["providerProfile"])
        self.assertEqual("answer", result["cacheContinuity"]["requestFamily"])
        self.assertRegex(result["cacheContinuity"]["routeFingerprint"], r"^[0-9a-f]{64}$")
        self.assertNotIn("third-party-secret-never-trace", json.dumps(result, ensure_ascii=False))
        self.assertNotIn("third-party-secret-never-trace", json.dumps(call["kwargs"]["json"], ensure_ascii=False))

    async def test_explicit_provider_profile_without_credential_never_uses_global_key(self) -> None:
        client = OpenAICompatibleProviderClient()
        route_snapshot = {
            "profileKey": "gateway-profile",
            "profileVersion": "profile-v1",
            "endpoint": "https://gateway.example/v1",
            "model": "gateway-model",
            "protocol": "responses",
        }
        factory = FakeAsyncClientFactory()
        with patch.dict(settings.__dict__, {"openai_api_key": "global-key-must-not-leak"}), \
             patch("app.services.provider_client.httpx.AsyncClient", side_effect=factory):
            profile = client.resolve_provider_profile("request-model", route_snapshot=route_snapshot)
            self.assertIsNone(profile.api_key)

            with self.assertRaisesRegex(ValueError, "provider profile credential is required"):
                await client.invoke(
                    messages=[{"role": "user", "content": "hello"}],
                    model="request-model",
                    temperature=0.2,
                    max_tokens=32,
                    require_json=False,
                    provider_profile=profile,
                )

            with self.assertRaisesRegex(ValueError, "provider profile credential is required"):
                _ = [
                    event
                    async for event in client.stream(
                        messages=[{"role": "user", "content": "hello"}],
                        model="request-model",
                        temperature=0.2,
                        max_tokens=32,
                        require_json=False,
                        provider_profile=profile,
                    )
                ]

        self.assertEqual([], factory.post_calls)
        self.assertEqual([], factory.stream_calls)

    def test_provider_base_url_rejects_ssrf_targets_and_credentials(self) -> None:
        client = OpenAICompatibleProviderClient()
        for value in (
            "http://127.0.0.1:8080",
            "http://10.0.0.8",
            "http://[::1]:8080",
            "http://169.254.169.254/latest/meta-data",
            "http://metadata.google.internal",
            "http://localhost:8001",
            "https://user:password@example.com/v1",
            "https://example.com/v1?target=http://127.0.0.1",
            "https://example.com/v1#fragment",
            " https://example.com/v1",
            "https://example.com/v1 ",
            "file:///etc/passwd",
        ):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "provider base URL"):
                client._resolve_base_url(value)

    def test_provider_base_url_accepts_public_https_endpoint(self) -> None:
        client = OpenAICompatibleProviderClient()
        self.assertEqual(
            "https://example.com/v1",
            client._resolve_base_url("https://example.com/v1/"),
        )

    @patch(
        "app.services.provider_client.socket.getaddrinfo",
        return_value=[(2, 1, 6, "", ("127.0.0.1", 443))],
    )
    async def test_provider_base_url_rejects_hostname_resolving_to_private_address(self, _resolve) -> None:
        client = OpenAICompatibleProviderClient()
        with self.assertRaisesRegex(ValueError, "resolves to a non-public address"):
            await client._assert_public_endpoint("https://provider.example/v1")

    @patch(
        "app.services.provider_client.socket.getaddrinfo",
        return_value=[(2, 1, 6, "", ("93.184.216.34", 443))],
    )
    async def test_provider_base_url_accepts_hostname_resolving_to_public_address(self, _resolve) -> None:
        client = OpenAICompatibleProviderClient()
        await client._assert_public_endpoint("https://provider.example/v1")

    def test_dify_base_url_uses_the_same_ssrf_validation(self) -> None:
        request = RunRequest(
            taskId="task-provider-security",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptType="theme",
                promptContent="JSON ONLY {{content}}",
                providerType="dify",
                baseUrl="http://127.0.0.1:8001",
                modelName="workflow-model",
            ),
            sourcePayload={"inputText": "content"},
            limits={},
        )
        client = OpenAICompatibleProviderClient()
        with self.assertRaisesRegex(ValueError, "provider base URL"):
            client._resolve_dify_base_url(request)

    def test_wire_cache_continuity_snapshot_is_prefix_comparable_and_redacted(self) -> None:
        client = OpenAICompatibleProviderClient()
        messages = [
            {"role": "system", "content": "private stable instructions"},
            {"role": "user", "content": "private first question"},
        ]
        tools = [{
            "type": "function",
            "function": {
                "name": "project.search",
                "description": "private tool description",
                "parameters": {"type": "object"},
            },
        }]
        payload = client._build_payload(
            messages,
            "deepseek-v4-pro",
            0.3,
            512,
            False,
            False,
            tools,
            "deep",
            "high",
            "private-cache-affinity",
            "responses",
        )
        first = client._cache_continuity_snapshot(
            payload,
            "responses",
            cache_affinity="private-cache-affinity",
            request_family="answer",
        )
        extended_payload = client._build_payload(
            [*messages, {"role": "assistant", "content": "private answer"}],
            "deepseek-v4-pro",
            0.3,
            512,
            False,
            False,
            tools,
            "deep",
            "high",
            "private-cache-affinity",
            "responses",
        )
        extended = client._cache_continuity_snapshot(
            extended_payload,
            "responses",
            cache_affinity="private-cache-affinity",
            request_family="answer",
        )

        self.assertEqual(1, first["schemaVersion"])
        self.assertEqual("openai_compatible", first["provider"])
        self.assertEqual("responses", first["wireApi"])
        self.assertTrue(first["bodyRedacted"])
        self.assertEqual(64, len(first["stablePrefixFingerprint"]))
        self.assertEqual(64, len(first["toolsFingerprint"]))
        self.assertEqual(
            first["stablePrefixFingerprint"],
            extended["stablePrefixFingerprint"],
        )
        self.assertEqual(first["toolsFingerprint"], extended["toolsFingerprint"])
        self.assertEqual(first["surfaceGeneration"], extended["surfaceGeneration"])
        self.assertEqual("answer", first["requestFamily"])
        self.assertEqual("provider_user", first["cacheIdentityMode"])
        self.assertRegex(first["affinityFingerprint"], r"^[0-9a-f]{64}$")
        self.assertGreater(extended["inputCount"], first["inputCount"])
        self.assertTrue(first["chainComplete"])
        self.assertTrue(extended["chainComplete"])
        self.assertEqual(
            first["inputFingerprint"],
            extended["prefixChainFingerprints"][first["inputCount"] - 1],
        )
        serialized = json.dumps(first, ensure_ascii=False)
        for secret in (
            "private stable instructions",
            "private first question",
            "private tool description",
            "private-cache-affinity",
        ):
            self.assertNotIn(secret, serialized)

    def test_wire_cache_continuity_separates_family_route_and_affinity(self) -> None:
        client = OpenAICompatibleProviderClient()
        payload = client._build_payload(
            [{"role": "system", "content": "stable"}, {"role": "user", "content": "question"}],
            "gateway-model",
            0.2,
            128,
            False,
            False,
            None,
            None,
            None,
            "affinity-a",
            "responses",
        )
        profile_a = ProviderProfile(
            profile_key="gateway-a",
            profile_version="v1",
            endpoint="https://gateway-a.example/v1",
            model="gateway-model",
            protocol="responses",
        )
        profile_b = ProviderProfile(
            profile_key="gateway-b",
            profile_version="v1",
            endpoint="https://gateway-b.example/v1",
            model="gateway-model",
            protocol="responses",
        )

        answer = client._cache_continuity_snapshot(
            payload,
            "responses",
            cache_affinity="affinity-a",
            request_family="answer",
            provider_profile=profile_a,
        )
        review = client._cache_continuity_snapshot(
            payload,
            "responses",
            cache_affinity="affinity-a",
            request_family="review",
            provider_profile=profile_a,
        )
        other_affinity = client._cache_continuity_snapshot(
            payload,
            "responses",
            cache_affinity="affinity-b",
            request_family="answer",
            provider_profile=profile_a,
        )
        other_route = client._cache_continuity_snapshot(
            payload,
            "responses",
            cache_affinity="affinity-a",
            request_family="answer",
            provider_profile=profile_b,
        )

        self.assertEqual("answer", answer["requestFamily"])
        self.assertNotEqual(answer["surfaceGeneration"], review["surfaceGeneration"])
        self.assertNotEqual(answer["surfaceGeneration"], other_affinity["surfaceGeneration"])
        self.assertNotEqual(answer["surfaceGeneration"], other_route["surfaceGeneration"])
        self.assertNotEqual(answer["routeFingerprint"], other_route["routeFingerprint"])
        self.assertNotEqual(answer["affinityFingerprint"], other_affinity["affinityFingerprint"])
        self.assertNotIn("affinity-a", json.dumps(answer, ensure_ascii=False))
        self.assertNotIn("gateway-a.example", json.dumps(answer, ensure_ascii=False))

        invalid_family = client._cache_continuity_snapshot(
            payload,
            "responses",
            request_family="answer/with-invalid-character",
        )
        self.assertNotIn("requestFamily", invalid_family)

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_not_retry_connect_error_at_transport_level(self, async_client_mock, sleep_mock) -> None:
        # The agent kernel owns the single shared retry+failover budget; retrying
        # here as well would silently multiply it by the transport attempt count.
        factory = FakeAsyncClientFactory(post_effects=[
            httpx.ConnectError("first attempt failed", request=self.request),
            FakeResponse({
                "model": "deepseek-chat",
                "choices": [{"message": {"content": "retry success"}}],
                "usage": {"total_tokens": 18},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self.assertRaises(httpx.ConnectError):
            await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-chat",
                temperature=0.3,
                max_tokens=16,
                require_json=False,
            )

        self.assertEqual(1, len(factory.constructor_calls))
        self.assertTrue(all(call["kwargs"]["trust_env"] is False for call in factory.constructor_calls))
        sleep_mock.assert_not_awaited()

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_surface_transient_http_statuses_without_retrying(self, async_client_mock, sleep_mock) -> None:
        for status_code in (429, 500, 502, 503, 504):
            with self.subTest(status_code=status_code):
                factory = FakeAsyncClientFactory(post_effects=[
                    httpx.Response(status_code, request=self.request),
                    FakeResponse({
                        "model": "deepseek-chat",
                        "choices": [{"message": {"content": f"status {status_code} recovered"}}],
                        "usage": {"total_tokens": 18},
                    }),
                ])
                async_client_mock.side_effect = factory
                client = OpenAICompatibleProviderClient()

                with self.assertRaises(httpx.HTTPStatusError) as caught:
                    await client.invoke(
                        messages=[{"role": "user", "content": CHINESE_PROMPT}],
                        model="deepseek-chat",
                        temperature=0.3,
                        max_tokens=16,
                        require_json=False,
                    )

                self.assertEqual(status_code, caught.exception.response.status_code)
                # Still classified as failover-eligible so the kernel can act on it.
                self.assertEqual(
                    f"HTTP_{status_code}",
                    client.failover_failure_class(caught.exception),
                )
                self.assertEqual(1, len(factory.constructor_calls))

        sleep_mock.assert_not_awaited()

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_not_retry_non_transient_http_status(self, async_client_mock, sleep_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            httpx.Response(400, request=self.request),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self.assertRaises(httpx.HTTPStatusError):
            await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-chat",
                temperature=0.3,
                max_tokens=16,
                require_json=False,
            )

        self.assertEqual(1, len(factory.constructor_calls))
        sleep_mock.assert_not_awaited()

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_should_not_retry_connect_error_at_transport_level(self, async_client_mock, sleep_mock) -> None:
        factory = FakeAsyncClientFactory(stream_effects=[
            httpx.ConnectError("stream attempt failed", request=self.request),
            FakeStreamResponse([
                'data: {"choices":[{"delta":{"content":"hello"}}]}',
                'data: {"choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":9,"total_tokens":21}}',
                "data: [DONE]",
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self.assertRaises(httpx.ConnectError):
            async for _event in client.stream(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-chat",
                temperature=0.3,
                max_tokens=16,
                require_json=False,
            ):
                pass

        self.assertEqual(1, len(factory.constructor_calls))
        sleep_mock.assert_not_awaited()

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_should_surface_transient_http_status_without_retrying(self, async_client_mock, sleep_mock) -> None:
        factory = FakeAsyncClientFactory(stream_effects=[
            StatusStreamResponse(503, self.request),
            FakeStreamResponse([
                'data: {"choices":[{"delta":{"content":"recovered"}}]}',
                'data: {"choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":9,"total_tokens":21}}',
                "data: [DONE]",
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self.assertRaises(httpx.HTTPStatusError) as caught:
            async for _event in client.stream(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-chat",
                temperature=0.3,
                max_tokens=16,
                require_json=False,
            ):
                pass

        self.assertEqual(503, caught.exception.response.status_code)
        self.assertEqual(1, len(factory.constructor_calls))
        sleep_mock.assert_not_awaited()

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_should_read_usage_only_tail_and_charge_consecutive_streams(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(stream_effects=[
            FakeStreamResponse([
                'data: {"choices":[{"delta":{"content":"first"}}]}',
                'data: {"choices":[{"finish_reason":"stop"}]}',
                'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}',
                "data: [DONE]",
            ]),
            FakeStreamResponse([
                'data: {"choices":[{"delta":{"content":"second"}}]}',
                'data: {"choices":[{"finish_reason":"stop"}]}',
                'data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":7,"total_tokens":27}}',
                "data: [DONE]",
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with run_budget_scope("fast") as budget:
            first = [event async for event in client.stream(
                messages=[{"role": "user", "content": "first"}],
                model="deepseek-chat",
                temperature=0.2,
                max_tokens=None,
                require_json=False,
            )]
            second = [event async for event in client.stream(
                messages=[{"role": "user", "content": "second"}],
                model="deepseek-chat",
                temperature=0.2,
                max_tokens=None,
                require_json=False,
            )]

        self.assertEqual(15, first[-1]["tokenUsed"])
        self.assertEqual(27, second[-1]["tokenUsed"])
        self.assertEqual(42, budget.used_total_tokens)

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_idle_read_is_cancelled_promptly_and_closes_response(self, async_client_mock) -> None:
        response = BlockingStreamResponse()
        factory = FakeAsyncClientFactory(stream_effects=[response])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()
        token = CancellationToken()

        async def consume() -> None:
            with cancellation_scope(token):
                async for _ in client.stream(
                    messages=[{"role": "user", "content": "wait"}],
                    model="deepseek-chat",
                    temperature=0.2,
                    max_tokens=None,
                    require_json=False,
                ):
                    pass

        task = asyncio.create_task(consume())
        await response.started.wait()
        token.cancel("stream_cancelled")

        with self.assertRaisesRegex(RunCancelledError, "stream_cancelled"):
            await asyncio.wait_for(task, timeout=0.2)
        self.assertTrue(response.cleaned_up.is_set())

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_enable_deepseek_thinking_mode_with_max_effort(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-v4-pro",
                "choices": [{"message": {"content": "deep answer", "reasoning_content": "private reasoning"}}],
                "usage": {"total_tokens": 42},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with patch.object(settings, "openai_wire_api", "chat_completions"):
            result = await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-pro",
                temperature=0.3,
                max_tokens=1024,
                require_json=False,
                reasoning_mode="deep",
            )

        payload = factory.post_calls[0]["kwargs"]["json"]
        self.assertNotIn("temperature", payload)
        self.assertEqual({"type": "enabled"}, payload["thinking"])
        self.assertEqual("max", payload["reasoning_effort"])
        self.assertEqual("private reasoning", result["reasoning_content"])

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_use_responses_fast_reasoning_mode(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-v4-flash",
                "status": "completed",
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "fast answer"}],
                }],
                "usage": {"total_tokens": 21},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
            await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-flash",
                temperature=0.3,
                max_tokens=1024,
                require_json=False,
                reasoning_mode="fast",
            )

        payload = factory.post_calls[0]["kwargs"]["json"]
        self.assertEqual({"effort": "none"}, payload["reasoning"])
        self.assertEqual(0.3, payload["temperature"])
        self.assertNotIn("thinking", payload)

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_allow_deepseek_high_effort_for_selected_agents(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-v4-pro",
                "choices": [{"message": {"content": "high effort answer"}}],
                "usage": {"total_tokens": 18},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with patch.object(settings, "openai_wire_api", "chat_completions"):
            await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-pro",
                temperature=0.3,
                max_tokens=1024,
                require_json=False,
                reasoning_mode="deep",
                reasoning_effort="high",
            )

        payload = factory.post_calls[0]["kwargs"]["json"]
        self.assertEqual({"type": "enabled"}, payload["thinking"])
        self.assertEqual("high", payload["reasoning_effort"])
        self.assertNotIn("temperature", payload)

    def test_build_payload_kimi_glm_use_reasoning_effort_with_max(self) -> None:
        # Kimi/GLM 用 reasoning_effort，枚举是 low|high|max
        client = OpenAICompatibleProviderClient()

        for provider_type, model in (
            ("moonshot", "kimi-k3"),
            ("zhipu", "glm-5.3"),
        ):
            with self.subTest(provider_type=provider_type, model=model):
                payload = client._build_payload(
                    messages=[{"role": "user", "content": CHINESE_PROMPT}],
                    model=model,
                    temperature=0.3,
                    max_tokens=1024,
                    require_json=False,
                    stream=False,
                    reasoning_mode="deep",
                    reasoning_effort="max",
                    cache_affinity=None,
                    wire_api="chat_completions",
                    provider_type=provider_type,
                )

                self.assertEqual("max", payload["reasoning_effort"])
                self.assertEqual(0.3, payload["temperature"])
                self.assertEqual(1024, payload["max_tokens"])
                self.assertNotIn("thinking", payload)
                self.assertNotIn("enable_thinking", payload)

        # 测试 fast 模式映射到 low
        for provider_type, model in (
            ("moonshot", "kimi-k3"),
            ("zhipu", "glm-5.3"),
        ):
            with self.subTest(provider_type=provider_type, model=model, mode="fast"):
                payload = client._build_payload(
                    messages=[{"role": "user", "content": CHINESE_PROMPT}],
                    model=model,
                    temperature=0.3,
                    max_tokens=1024,
                    require_json=False,
                    stream=False,
                    reasoning_mode="fast",
                    reasoning_effort=None,
                    cache_affinity=None,
                    wire_api="chat_completions",
                    provider_type=provider_type,
                )

                self.assertEqual("low", payload["reasoning_effort"])

    def test_build_payload_qwen_uses_enable_thinking(self) -> None:
        # Qwen 用 enable_thinking 布尔参数
        client = OpenAICompatibleProviderClient()

        # deep 模式开启 enable_thinking
        payload = client._build_payload(
            messages=[{"role": "user", "content": CHINESE_PROMPT}],
            model="qwen3-max",
            temperature=0.3,
            max_tokens=1024,
            require_json=False,
            stream=False,
            reasoning_mode="deep",
            reasoning_effort=None,
            cache_affinity=None,
            wire_api="chat_completions",
            provider_type="qwen",
        )

        self.assertTrue(payload["enable_thinking"])
        self.assertEqual(0.3, payload["temperature"])
        self.assertNotIn("reasoning_effort", payload)
        self.assertNotIn("thinking", payload)

        # fast 模式关闭 enable_thinking
        payload_fast = client._build_payload(
            messages=[{"role": "user", "content": CHINESE_PROMPT}],
            model="qwen3-max",
            temperature=0.3,
            max_tokens=1024,
            require_json=False,
            stream=False,
            reasoning_mode="fast",
            reasoning_effort=None,
            cache_affinity=None,
            wire_api="chat_completions",
            provider_type="qwen",
        )

        self.assertFalse(payload_fast["enable_thinking"])

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_return_deepseek_prompt_cache_usage(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-v4-pro",
                "choices": [{"message": {"content": "cached answer"}}],
                "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 30,
                    "total_tokens": 150,
                    "prompt_cache_hit_tokens": 90,
                    "prompt_cache_miss_tokens": 30,
                },
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with patch.object(settings, "openai_wire_api", "chat_completions"), \
             patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
            result = await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-pro",
                temperature=0.3,
                max_tokens=1024,
                require_json=False,
                reasoning_mode="deep",
                reasoning_effort="max",
            )

        self.assertEqual(150, result["token_used"])
        self.assertEqual(90, result["prompt_cache_hit_tokens"])
        self.assertEqual(30, result["prompt_cache_miss_tokens"])
        self.assertEqual({
            "promptTokens": 120,
            "completionTokens": 30,
            "totalTokens": 150,
            "promptCacheHitTokens": 90,
            "promptCacheMissTokens": 30,
            "promptCacheWriteTokens": 0,
            "promptCacheMissTokensDerived": False,
            "usageReported": True,
            "cacheUsageReported": True,
        }, result["usage"])

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_caps_provider_max_tokens_to_remaining_run_budget(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-chat",
                "choices": [{"message": {"content": "within remaining budget"}}],
                "usage": {"total_tokens": 10},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()
        budget = RunBudget(
            mode="fast",
            max_total_tokens=100,
            max_tool_calls=6,
            max_delegations=1,
        )
        budget.consume_tokens(90)

        with run_budget_scope(budget), patch.object(
            client,
            "_assert_public_endpoint",
            new=AsyncMock(),
        ):
            await client.invoke(
                messages=[{"role": "user", "content": "finish"}],
                model="deepseek-chat",
                temperature=0.2,
                max_tokens=1_000,
                require_json=False,
            )

        self.assertEqual(10, factory.post_calls[0]["kwargs"]["json"]["max_tokens"])
        self.assertEqual(100, budget.used_total_tokens)

    @patch.object(OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock())
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_preserves_explicit_no_local_output_cap(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-chat",
                "choices": [{"message": {"content": "provider governed output"}}],
                "usage": {"total_tokens": 5},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()
        budget = RunBudget(
            mode="fast",
            max_total_tokens=100,
            max_tool_calls=6,
            max_delegations=1,
        )
        budget.consume_tokens(90)

        with run_budget_scope(budget):
            await client.invoke(
                messages=[{"role": "user", "content": "finish"}],
                model="deepseek-chat",
                temperature=0.2,
                max_tokens=None,
                require_json=False,
            )

        self.assertNotIn("max_tokens", factory.post_calls[0]["kwargs"]["json"])


class ProviderResponsesApiTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        endpoint_patcher = patch.object(
            OpenAICompatibleProviderClient,
            "_assert_public_endpoint",
            new_callable=AsyncMock,
        )
        endpoint_patcher.start()
        self.addCleanup(endpoint_patcher.stop)

    @staticmethod
    def _responses_settings(
        *,
        chat_fallback: bool = True,
        models: str = "deepseek-v4-flash",
        cache_key_models: str = "gpt-*",
        provider_user_models: str = "deepseek-*",
    ):
        return patch.dict(settings.__dict__, {
            "openai_wire_api": "responses",
            "openai_responses_base_url": "https://api.deepseek.com",
            "openai_responses_models": models,
            "openai_responses_chat_fallback_enabled": chat_fallback,
            "openai_prompt_cache_key_models": cache_key_models,
            "openai_provider_user_models": provider_user_models,
        })

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_uses_native_responses_payload_and_normalizes_output(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "id": "resp_1",
                "status": "completed",
                "model": "deepseek-v4-flash",
                "output": [
                    {
                        "id": "reasoning_1",
                        "type": "reasoning",
                        "content": [{"type": "reasoning_text", "text": "private reasoning"}],
                    },
                    {
                        "id": "message_1",
                        "type": "message",
                        "role": "assistant",
                        "content": [{"type": "output_text", "text": "draft ready"}],
                    },
                    {
                        "id": "function_1",
                        "type": "function_call",
                        "call_id": "call_new",
                        "name": "project_search",
                        "arguments": '{"query":"next clue"}',
                    },
                ],
                "usage": {
                    "input_tokens": 120,
                    "output_tokens": 30,
                    "total_tokens": 150,
                    "input_tokens_details": {"cached_tokens": 90},
                    "output_tokens_details": {"reasoning_tokens": 8},
                },
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()
        messages = [
            {"role": "system", "content": "follow project canon"},
            {"role": "user", "content": "find the clue"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "id": "call_old",
                    "type": "function",
                    "function": {"name": "project_search", "arguments": '{"query":"old clue"}'},
                }],
            },
            {"role": "tool", "tool_call_id": "call_old", "name": "project_search", "content": "chapter 12"},
            {"role": "user", "content": "continue from that result"},
        ]
        tools = [{
            "type": "function",
            "function": {
                "name": "project_search",
                "description": "Search authorized project chunks",
                "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
        }]

        with self._responses_settings():
            result = await client.invoke(
                messages=messages,
                model="deepseek-v4-flash",
                temperature=0.3,
                max_tokens=1024,
                require_json=True,
                tools=tools,
                reasoning_mode="deep",
                reasoning_effort="max",
                cache_affinity="deepseek-affinity-must-not-be-sent",
            )

        self.assertEqual("https://api.deepseek.com/responses", factory.post_calls[0]["args"][0])
        payload = factory.post_calls[0]["kwargs"]["json"]
        self.assertNotIn("messages", payload)
        self.assertNotIn("max_tokens", payload)
        self.assertNotIn("response_format", payload)
        self.assertNotIn("thinking", payload)
        self.assertNotIn("stream_options", payload)
        self.assertNotIn("prompt_cache_key", payload)
        self.assertEqual("follow project canon", payload["instructions"])
        self.assertEqual(1024, payload["max_output_tokens"])
        self.assertEqual({"effort": "max"}, payload["reasoning"])
        self.assertRegex(payload["user"], r"^noval-[0-9a-f]{64}$")
        self.assertNotIn("deepseek-affinity-must-not-be-sent", payload["user"])
        self.assertEqual({"format": {"type": "json_object"}}, payload["text"])
        self.assertEqual("auto", payload["tool_choice"])
        self.assertEqual({
            "type": "function",
            "name": "project_search",
            "description": "Search authorized project chunks",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        }, payload["tools"][0])
        self.assertEqual({
            "type": "function_call",
            "call_id": "call_old",
            "name": "project_search",
            "arguments": '{"query":"old clue"}',
        }, payload["input"][1])
        self.assertEqual({
            "type": "function_call_output",
            "call_id": "call_old",
            "output": "chapter 12",
        }, payload["input"][2])
        self.assertEqual("draft ready", result["content"])
        self.assertEqual("private reasoning", result["reasoning_content"])
        self.assertEqual([{"id": "call_new", "name": "project_search", "arguments": {"query": "next clue"}}], result["tool_calls"])
        self.assertEqual("responses", result["wire_api"])
        self.assertEqual({
            "promptTokens": 120,
            "completionTokens": 30,
            "totalTokens": 150,
            "promptCacheHitTokens": 90,
            "promptCacheMissTokens": 30,
            "promptCacheWriteTokens": 0,
            "promptCacheMissTokensDerived": True,
            "usageReported": True,
            "cacheUsageReported": True,
            "inputTokens": 120,
            "outputTokens": 30,
            "cachedInputTokens": 90,
            "cacheWriteInputTokens": 0,
            "reasoningTokens": 8,
        }, result["usage"])

    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_responses_surfaces_transient_http_statuses_without_retrying(self, async_client_mock, sleep_mock) -> None:
        request = httpx.Request("POST", "https://api.deepseek.com/responses")
        client = OpenAICompatibleProviderClient()

        with patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
            for status_code in (429, 500, 502, 503, 504):
                with self.subTest(status_code=status_code):
                    factory = FakeAsyncClientFactory(post_effects=[
                        httpx.Response(status_code, request=request),
                        FakeResponse({"status": "completed"}),
                    ])
                    async_client_mock.side_effect = factory

                    with self.assertRaises(httpx.HTTPStatusError) as caught:
                        await client._invoke_responses_with_retry(
                            payload={"model": "deepseek-v4-flash", "input": [], "stream": False},
                            base_url="https://api.deepseek.com",
                            api_key=None,
                            timeout_millis=None,
                        )

                    self.assertEqual(status_code, caught.exception.response.status_code)
                    self.assertEqual(1, len(factory.post_calls))

        sleep_mock.assert_not_awaited()

    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_responses_does_not_retry_permanent_http_statuses(self, async_client_mock, sleep_mock) -> None:
        request = httpx.Request("POST", "https://api.deepseek.com/responses")
        client = OpenAICompatibleProviderClient()

        with patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
            for status_code in (400, 401, 402, 422):
                with self.subTest(status_code=status_code):
                    factory = FakeAsyncClientFactory(post_effects=[
                        httpx.Response(status_code, request=request),
                    ])
                    async_client_mock.side_effect = factory

                    with self.assertRaises(httpx.HTTPStatusError):
                        await client._invoke_responses_with_retry(
                            payload={"model": "deepseek-v4-flash", "input": [], "stream": False},
                            base_url="https://api.deepseek.com",
                            api_key=None,
                            timeout_millis=None,
                        )

                    self.assertEqual(1, len(factory.post_calls))

        sleep_mock.assert_not_awaited()

    def test_responses_reasoning_effort_uses_deepseek_mapping(self) -> None:
        client = OpenAICompatibleProviderClient()

        for requested, expected in (
            ("low", "low"),
            ("medium", "high"),
            ("high", "high"),
            ("xhigh", "high"),
            ("max", "max"),
        ):
            with self.subTest(requested=requested):
                payload = client._build_payload(
                    [{"role": "user", "content": "reason"}],
                    "deepseek-v4-pro",
                    temperature=0.2,
                    max_tokens=64,
                    require_json=False,
                    stream=False,
                    reasoning_mode="deep",
                    reasoning_effort=requested,
                    cache_affinity="stable-affinity",
                    wire_api="responses",
                )
                self.assertEqual({"effort": expected}, payload["reasoning"])
                self.assertNotIn("temperature", payload)

        compatible_payload = client._build_payload(
            [{"role": "user", "content": "reason"}],
            "gpt-5.6",
            temperature=0.2,
            max_tokens=64,
            require_json=False,
            stream=False,
            reasoning_mode="deep",
            reasoning_effort="max",
            cache_affinity="stable-affinity",
            wire_api="responses",
        )
        # gpt-5.6 起 max 是真档位，不再压到 high（2026-09-02 真机核实）。
        self.assertEqual({"effort": "max"}, compatible_payload["reasoning"])

    def test_chat_completions_keeps_deepseek_dialect_off_openai_models(self) -> None:
        client = OpenAICompatibleProviderClient()

        openai_payload = client._build_payload(
            [{"role": "user", "content": "reason"}],
            "gpt-5.6-sol",
            temperature=0.3,
            max_tokens=8192,
            require_json=False,
            stream=False,
            reasoning_mode="deep",
            reasoning_effort="max",
            cache_affinity="stable-affinity",
            wire_api="chat_completions",
        )

        self.assertNotIn("thinking", openai_payload)
        self.assertNotIn("max_tokens", openai_payload)
        self.assertNotIn("temperature", openai_payload)
        self.assertEqual(8192, openai_payload["max_completion_tokens"])
        self.assertEqual("max", openai_payload["reasoning_effort"])

        deepseek_payload = client._build_payload(
            [{"role": "user", "content": "reason"}],
            "deepseek-chat",
            temperature=0.3,
            max_tokens=8192,
            require_json=False,
            stream=False,
            reasoning_mode="deep",
            reasoning_effort="max",
            cache_affinity="stable-affinity",
            wire_api="chat_completions",
        )

        self.assertEqual({"type": "enabled"}, deepseek_payload["thinking"])
        self.assertEqual("max", deepseek_payload["reasoning_effort"])
        self.assertEqual(8192, deepseek_payload["max_tokens"])
        self.assertNotIn("max_completion_tokens", deepseek_payload)

    def test_responses_uses_kimi_glm_effort_for_kimi_glm_qwen(self) -> None:
        client = OpenAICompatibleProviderClient()

        # Kimi/GLM 用 reasoning_effort 但枚举是 low|high|max
        for provider_type, model in (
            ("moonshot", "kimi-k3"),
            ("zhipu", "glm-5.3"),
        ):
            # 显式档位优先于 fast/deep，两条 wire 同一套语义；没选档位时 fast 才
            # 退回族内下限（下面单独断言）。见
            # test_fast_mode_still_honours_an_explicitly_selected_tier_on_responses。
            for mode, expected in (("deep", "max"), ("fast", "max")):
                with self.subTest(provider_type=provider_type, mode=mode):
                    payload = client._build_payload(
                        [{"role": "user", "content": "reason"}],
                        model,
                        temperature=0.3,
                        max_tokens=512,
                        require_json=False,
                        stream=False,
                        reasoning_mode=mode,
                        reasoning_effort="max",
                        cache_affinity=None,
                        wire_api="responses",
                        provider_type=provider_type,
                    )
                    # "max" 直接保留，不映射成 "high"
                    self.assertEqual({"effort": expected}, payload["reasoning"])
                    self.assertEqual(512, payload["max_output_tokens"])
                    self.assertNotIn("thinking", payload)

            with self.subTest(provider_type=provider_type, mode="fast-no-tier"):
                payload = client._build_payload(
                    [{"role": "user", "content": "reason"}],
                    model,
                    temperature=0.3,
                    max_tokens=512,
                    require_json=False,
                    stream=False,
                    reasoning_mode="fast",
                    reasoning_effort=None,
                    cache_affinity=None,
                    wire_api="responses",
                    provider_type=provider_type,
                )
                self.assertEqual({"effort": "low"}, payload["reasoning"])

        # Qwen 在 Responses 上也映射到 reasoning.effort
        for mode, expected in (("deep", "high"), ("fast", "low")):
            with self.subTest(provider_type="qwen", mode=mode):
                payload = client._build_payload(
                    [{"role": "user", "content": "reason"}],
                    "qwen3-max",
                    temperature=0.3,
                    max_tokens=512,
                    require_json=False,
                    stream=False,
                    reasoning_mode=mode,
                    reasoning_effort=None,
                    cache_affinity=None,
                    wire_api="responses",
                    provider_type="qwen",
                )
                self.assertEqual({"effort": expected}, payload["reasoning"])
                self.assertNotIn("enable_thinking", payload)

    def test_responses_omits_reasoning_for_anthropic(self) -> None:
        client = OpenAICompatibleProviderClient()

        for mode in ("deep", "fast"):
            with self.subTest(mode=mode):
                payload = client._build_payload(
                    [{"role": "user", "content": "reason"}],
                    "claude-opus-5",
                    temperature=0.3,
                    max_tokens=512,
                    require_json=False,
                    stream=False,
                    reasoning_mode=mode,
                    reasoning_effort="max",
                    cache_affinity=None,
                    wire_api="responses",
                    provider_type="anthropic",
                )
                self.assertNotIn("reasoning", payload)
                self.assertNotIn("thinking", payload)
                self.assertEqual(512, payload["max_output_tokens"])

    def test_provider_type_overrides_model_name_for_dialect_choice(self) -> None:
        client = OpenAICompatibleProviderClient()

        # A gateway may serve DeepSeek under a house alias; providerType must win
        # so the thinking contract still applies.
        aliased = client._build_payload(
            [{"role": "user", "content": "reason"}],
            "house-alias-v1",
            temperature=0.3,
            max_tokens=512,
            require_json=False,
            stream=False,
            reasoning_mode="deep",
            reasoning_effort="max",
            cache_affinity=None,
            wire_api="chat_completions",
            provider_type="deepseek",
        )
        self.assertEqual({"type": "enabled"}, aliased["thinking"])
        self.assertEqual("max", aliased["reasoning_effort"])
        self.assertEqual(512, aliased["max_tokens"])

    def test_fast_mode_uses_each_dialect_lowest_reasoning_setting(self) -> None:
        client = OpenAICompatibleProviderClient()

        openai_chat = client._build_payload(
            [{"role": "user", "content": "quick"}],
            "gpt-5.6-sol",
            temperature=0.3,
            max_tokens=512,
            require_json=False,
            stream=False,
            reasoning_mode="fast",
            reasoning_effort=None,
            cache_affinity=None,
            wire_api="chat_completions",
        )
        # gpt-5.6 的下限是 none 而不是 minimal：真机上 minimal 会被 400 拒掉
        # （2026-09-02 核实，报文点名 Supported values 里没有 minimal）。
        self.assertEqual("none", openai_chat["reasoning_effort"])
        self.assertNotIn("thinking", openai_chat)

        openai_responses = client._build_payload(
            [{"role": "user", "content": "quick"}],
            "gpt-5.6-sol",
            temperature=0.3,
            max_tokens=512,
            require_json=False,
            stream=False,
            reasoning_mode="fast",
            reasoning_effort=None,
            cache_affinity=None,
            wire_api="responses",
        )
        self.assertEqual({"effort": "none"}, openai_responses["reasoning"])
        self.assertNotIn("temperature", openai_responses)

        legacy_openai_chat = client._build_payload(
            [{"role": "user", "content": "quick"}],
            "gpt-5",
            temperature=0.3,
            max_tokens=512,
            require_json=False,
            stream=False,
            reasoning_mode="fast",
            reasoning_effort=None,
            cache_affinity=None,
            wire_api="chat_completions",
        )
        # 老一代仍然只认 minimal，不能一并改掉。
        self.assertEqual("minimal", legacy_openai_chat["reasoning_effort"])

        deepseek_responses = client._build_payload(
            [{"role": "user", "content": "quick"}],
            "deepseek-v4-pro",
            temperature=0.3,
            max_tokens=512,
            require_json=False,
            stream=False,
            reasoning_mode="fast",
            reasoning_effort=None,
            cache_affinity=None,
            wire_api="responses",
        )
        self.assertEqual({"effort": "none"}, deepseek_responses["reasoning"])
        self.assertEqual(0.3, deepseek_responses["temperature"])

    def test_fast_mode_still_honours_an_explicitly_selected_tier_on_responses(self) -> None:
        """选择器里选了档位，fast 模式也不能把它压回族内下限。

        Responses 这条线原先只看 reasoning_mode：fast 一律发 responses_fast_effort，
        用户选的 high/xhigh 被整个丢掉，上游收到的永远是 effort=none——在供应商后台
        看就是"没传思考强度"。chat/completions 那条线一直是显式档位优先，两条 wire
        对同一次请求给出不同的思考强度，这里把它们钉成同一套语义。
        """
        client = OpenAICompatibleProviderClient()

        def payload(model, tier, wire="responses"):
            return client._build_payload(
                [{"role": "user", "content": "quick"}],
                model,
                temperature=0.3,
                max_tokens=512,
                require_json=False,
                stream=False,
                reasoning_mode="fast",
                reasoning_effort=tier,
                cache_affinity=None,
                wire_api=wire,
            )

        # gpt-5.6 的宽枚举：选了就原样落地。
        self.assertEqual({"effort": "xhigh"}, payload("gpt-5.6-sol", "xhigh")["reasoning"])
        self.assertEqual({"effort": "high"}, payload("gpt-5.6-sol", "high")["reasoning"])
        # 同一次请求，两条 wire 现在给同一个档位。
        self.assertEqual(
            "xhigh",
            payload("gpt-5.6-sol", "xhigh", "chat_completions")["reasoning_effort"],
        )
        # 老一代没有 xhigh，收敛到 high，不能原样发出去换一个 400。
        self.assertEqual({"effort": "high"}, payload("gpt-5", "xhigh")["reasoning"])
        # 明确选"不思考"仍然走族内下限，不能被误当成显式高档位。
        self.assertEqual({"effort": "none"}, payload("gpt-5.6-sol", "none")["reasoning"])
        self.assertEqual({"effort": "none"}, payload("gpt-5.6-sol", "off")["reasoning"])
        # DeepSeek 的 Responses clamp 只有 low/high/max。
        self.assertEqual({"effort": "high"}, payload("deepseek-v4-pro", "xhigh")["reasoning"])
        self.assertEqual({"effort": "low"}, payload("deepseek-v4-pro", "low")["reasoning"])
        # 契约未核实的族仍然一个推理字段都不发。
        self.assertNotIn("reasoning", payload("claude-4.7-opus", "xhigh"))

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_deepseek_user_isolation_is_stable_across_responses_and_chat(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "status": "completed",
                "model": "deepseek-v4-pro",
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "responses"}],
                }],
                "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
            }),
            FakeResponse({
                "model": "deepseek-chat",
                "choices": [{"message": {"content": "chat"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings(models="deepseek-v4-pro"):
            await client.invoke(
                messages=[{"role": "user", "content": "one"}],
                model="deepseek-v4-pro",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="noval-cache-v1:real-scope-is-not-forwarded",
            )
            await client.invoke(
                messages=[{"role": "user", "content": "two"}],
                model="deepseek-chat",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="noval-cache-v1:real-scope-is-not-forwarded",
            )

        responses_payload = factory.post_calls[0]["kwargs"]["json"]
        chat_payload = factory.post_calls[1]["kwargs"]["json"]
        self.assertRegex(responses_payload["user"], r"^noval-[0-9a-f]{64}$")
        self.assertEqual(responses_payload["user"], chat_payload["user_id"])
        self.assertNotIn("real-scope-is-not-forwarded", responses_payload["user"])
        self.assertNotIn("prompt_cache_key", responses_payload)

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_non_deepseek_responses_model_keeps_user_isolation_out_of_payload(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "status": "completed",
                "model": "gpt-5.6",
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "answer"}],
                }],
                "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings(models="gpt-5.6"):
            await client.invoke(
                messages=[{"role": "user", "content": "one"}],
                model="gpt-5.6",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="stable-affinity",
            )

        payload = factory.post_calls[0]["kwargs"]["json"]
        self.assertNotIn("user", payload)
        self.assertNotIn("user_id", payload)

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_prompt_cache_key_is_emitted_only_for_allowlisted_responses_models(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "status": "completed",
                "model": "gpt-5.6",
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "gpt answer"}],
                }],
                "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
            }),
            FakeResponse({
                "status": "completed",
                "model": "deepseek-v4-flash",
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "deepseek answer"}],
                }],
                "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
            }),
            FakeResponse({
                "model": "deepseek-v4-pro",
                "choices": [{"message": {"content": "chat answer"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings(
            models="gpt-5.6,deepseek-v4-flash",
            cache_key_models="gpt-*",
            provider_user_models="deepseek-*",
        ):
            await client.invoke(
                messages=[{"role": "user", "content": "first"}],
                model="gpt-5.6",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="conversation-affinity",
            )
            await client.invoke(
                messages=[{"role": "user", "content": "second"}],
                model="deepseek-v4-flash",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="deepseek-affinity",
            )
            await client.invoke(
                messages=[{"role": "user", "content": "third"}],
                model="deepseek-v4-pro",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="chat-affinity",
            )

        self.assertEqual(
            "conversation-affinity",
            factory.post_calls[0]["kwargs"]["json"]["prompt_cache_key"],
        )
        self.assertNotIn("prompt_cache_key", factory.post_calls[1]["kwargs"]["json"])
        self.assertNotIn("prompt_cache_key", factory.post_calls[2]["kwargs"]["json"])
        self.assertIn("user", factory.post_calls[1]["kwargs"]["json"])
        self.assertIn("user_id", factory.post_calls[2]["kwargs"]["json"])

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_selected_model_policy_controls_gpt_deepseek_and_unknown(self, async_client_mock) -> None:
        def responses_ok(model: str) -> FakeResponse:
            return FakeResponse({
                "status": "completed",
                "model": model,
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "answer"}],
                }],
                "usage": {"input_tokens": 10, "output_tokens": 2, "total_tokens": 12},
            })

        def chat_ok(model: str) -> FakeResponse:
            return FakeResponse({
                "model": model,
                "choices": [{"message": {"content": "answer"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12},
            })

        factory = FakeAsyncClientFactory(post_effects=[
            responses_ok("gpt-5.6"),
            chat_ok("qwen-max"),
            chat_ok("deepseek-v4-pro"),
            responses_ok("gpt-5.6"),
            responses_ok("gpt-5.6"),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        async def invoke(model: str) -> None:
            await client.invoke(
                messages=[{"role": "user", "content": "hello"}],
                model=model,
                temperature=0.2,
                max_tokens=16,
                require_json=False,
                cache_affinity="conversation-affinity",
            )

        with self._responses_settings(
            models="gpt-5.6",
            cache_key_models="gpt-*",
            provider_user_models="deepseek-*",
        ):
            await invoke("gpt-5.6")
            await invoke("qwen-max")
            await invoke("deepseek-v4-pro")
        with self._responses_settings(models="gpt-5.6", cache_key_models="gpt-5.6"):
            await invoke("gpt-5.6")
        with self._responses_settings(models="gpt-5.6", cache_key_models="glm-5"):
            await invoke("gpt-5.6")

        self.assertEqual(
            "conversation-affinity",
            factory.post_calls[0]["kwargs"]["json"]["prompt_cache_key"],
        )
        # 未声明的兼容模型不接收任何厂商专属缓存字段。
        self.assertNotIn("prompt_cache_key", factory.post_calls[1]["kwargs"]["json"])
        self.assertNotIn("user_id", factory.post_calls[1]["kwargs"]["json"])
        # DeepSeek 自动缓存，不发 key；匿名 user_id 只负责隔离。
        self.assertNotIn("prompt_cache_key", factory.post_calls[2]["kwargs"]["json"])
        self.assertIn("user_id", factory.post_calls[2]["kwargs"]["json"])
        self.assertEqual(
            "conversation-affinity",
            factory.post_calls[3]["kwargs"]["json"]["prompt_cache_key"],
        )
        self.assertNotIn("prompt_cache_key", factory.post_calls[4]["kwargs"]["json"])

    def test_cache_policy_uses_actual_provider_profile_model(self) -> None:
        client = OpenAICompatibleProviderClient()
        gpt_profile = ProviderProfile(
            profile_key="selected-gpt",
            profile_version="v1",
            endpoint="https://gpt-gateway.example/v1",
            model="gpt-5.6-sol",
            protocol="responses",
        )
        deepseek_profile = ProviderProfile(
            profile_key="selected-deepseek",
            profile_version="v1",
            endpoint="https://deepseek-gateway.example/v1",
            model="deepseek-v4-pro",
            protocol="responses",
        )
        gpt_chat_profile = ProviderProfile(
            profile_key="selected-gpt-chat",
            profile_version="v1",
            endpoint="https://gpt-gateway.example/v1",
            model="gpt-5.6-sol",
            protocol="chat_completions",
        )
        with self._responses_settings(
            models="gpt-5.6-sol,deepseek-v4-pro",
            cache_key_models="gpt-*",
            provider_user_models="deepseek-*",
        ):
            gpt_payload = client._build_payload(
                [
                    {"role": "system", "content": "stable instructions"},
                    {"role": "user", "content": "question"},
                ],
                "deepseek-request-alias",
                0.2,
                16,
                False,
                False,
                cache_affinity="stable-affinity",
                wire_api="responses",
                provider_profile=gpt_profile,
            )
            deepseek_payload = client._build_payload(
                [{"role": "user", "content": "question"}],
                "gpt-request-alias",
                0.2,
                16,
                False,
                False,
                cache_affinity="stable-affinity",
                wire_api="responses",
                provider_profile=deepseek_profile,
            )
            gpt_chat_payload = client._build_payload(
                [{"role": "user", "content": "question"}],
                "request-alias",
                0.2,
                16,
                False,
                False,
                cache_affinity="stable-affinity",
                wire_api="chat_completions",
                provider_profile=gpt_chat_profile,
            )
            unknown_payload = client._build_payload(
                [{"role": "user", "content": "question"}],
                "unknown-model",
                0.2,
                16,
                False,
                False,
                cache_affinity="stable-affinity",
                wire_api="responses",
            )

        self.assertEqual("gpt-5.6-sol", gpt_payload["model"])
        self.assertEqual("stable-affinity", gpt_payload["prompt_cache_key"])
        self.assertEqual({"mode": "implicit", "ttl": "30m"}, gpt_payload["prompt_cache_options"])
        self.assertNotIn("instructions", gpt_payload)
        self.assertEqual(
            {"mode": "explicit"},
            gpt_payload["input"][0]["content"][0]["prompt_cache_breakpoint"],
        )
        self.assertNotIn("user", gpt_payload)
        self.assertEqual("deepseek-v4-pro", deepseek_payload["model"])
        self.assertNotIn("prompt_cache_key", deepseek_payload)
        self.assertRegex(deepseek_payload["user"], r"^noval-[0-9a-f]{64}$")
        self.assertEqual("gpt-5.6-sol", gpt_chat_payload["model"])
        self.assertEqual("stable-affinity", gpt_chat_payload["prompt_cache_key"])
        self.assertNotIn("user_id", gpt_chat_payload)
        self.assertNotIn("prompt_cache_key", unknown_payload)
        self.assertNotIn("user", unknown_payload)

        gpt_snapshot = client._cache_continuity_snapshot(gpt_payload, "responses")
        deepseek_snapshot = client._cache_continuity_snapshot(deepseek_payload, "responses")
        unknown_snapshot = client._cache_continuity_snapshot(unknown_payload, "responses")
        self.assertEqual("prompt_cache_key", gpt_snapshot["cacheIdentityMode"])
        self.assertEqual("provider_user", deepseek_snapshot["cacheIdentityMode"])
        self.assertEqual("none", unknown_snapshot["cacheIdentityMode"])
        self.assertEqual("openai_gpt_5_6", gpt_snapshot["promptCacheStrategy"])
        self.assertEqual("deepseek_automatic", deepseek_snapshot["promptCacheStrategy"])
        self.assertEqual("none", unknown_snapshot["promptCacheStrategy"])

    def test_responses_prompt_cache_capability_compiles_gpt56_legacy_deepseek_and_none(self) -> None:
        client = OpenAICompatibleProviderClient()

        def capabilities(prompt_cache: dict[str, str]) -> dict[str, Any]:
            return {
                "schemaVersion": 1,
                "supportsStreaming": True,
                "supportsTools": True,
                "supportsJsonObject": True,
                "supportsReasoning": True,
                "reportsUsage": True,
                "reportsCacheUsage": True,
                "promptCache": prompt_cache,
            }

        def profile(profile_key: str, model: str, prompt_cache: dict[str, str]) -> ProviderProfile:
            return ProviderProfile(
                profile_key=profile_key,
                profile_version="v1",
                endpoint=f"https://{profile_key}.example/v1",
                model=model,
                provider_type="openai-compatible",
                protocol="responses",
                provider_capabilities=capabilities(prompt_cache),
            )

        gpt56_profile = profile("selected-gpt56", "gateway-gpt-current", {
            "strategy": "openai_gpt_5_6",
            "mode": "implicit",
            "retention": "30m",
            "breakpoint": "stable_prefix",
        })
        gpt56_explicit_profile = profile("selected-gpt56-explicit", "gateway-gpt-explicit", {
            "strategy": "openai_gpt_5_6",
            "mode": "explicit",
            "retention": "provider_default",
            "breakpoint": "none",
        })
        legacy_profile = profile("selected-gpt-legacy", "gateway-gpt-legacy", {
            "strategy": "openai_legacy",
            "mode": "implicit",
            "retention": "24h",
            "breakpoint": "none",
        })
        legacy_memory_profile = profile("selected-gpt-memory", "gateway-gpt-memory", {
            "strategy": "openai_legacy",
            "mode": "implicit",
            "retention": "in_memory",
            "breakpoint": "none",
        })
        deepseek_profile = profile("selected-deepseek", "gateway-deepseek-current", {
            "strategy": "deepseek_automatic",
            "mode": "provider_managed",
            "retention": "provider_default",
            "breakpoint": "none",
        })
        disabled_profile = profile("selected-none", "gpt-5.6-sol", {
            "strategy": "none",
            "mode": "disabled",
            "retention": "provider_default",
            "breakpoint": "none",
        })
        messages = [
            {"role": "system", "content": "stable instructions"},
            {"role": "user", "content": "changing question"},
        ]

        with self._responses_settings(cache_key_models="gpt-*", provider_user_models="deepseek-*"):
            gpt56 = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity="stable-affinity", wire_api="responses", provider_profile=gpt56_profile,
            )
            gpt56_explicit = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity="stable-affinity", wire_api="responses", provider_profile=gpt56_explicit_profile,
            )
            legacy = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity="stable-affinity", wire_api="responses", provider_profile=legacy_profile,
            )
            legacy_memory = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity="stable-affinity", wire_api="responses", provider_profile=legacy_memory_profile,
            )
            deepseek = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity="stable-affinity", wire_api="responses", provider_profile=deepseek_profile,
            )
            disabled = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity="stable-affinity", wire_api="responses", provider_profile=disabled_profile,
            )
            long_affinity = client._build_payload(
                messages, "request-alias", 0.2, 64, False, False,
                cache_affinity=f"noval-cache-v1:{'a' * 64}",
                wire_api="responses",
                provider_profile=gpt56_profile,
            )

        self.assertNotIn("instructions", gpt56)
        self.assertEqual("stable-affinity", gpt56["prompt_cache_key"])
        self.assertEqual({"mode": "implicit", "ttl": "30m"}, gpt56["prompt_cache_options"])
        self.assertEqual("developer", gpt56["input"][0]["role"])
        self.assertEqual(
            {"mode": "explicit"},
            gpt56["input"][0]["content"][0]["prompt_cache_breakpoint"],
        )
        self.assertNotIn("prompt_cache_retention", gpt56)
        self.assertEqual({"mode": "explicit"}, gpt56_explicit["prompt_cache_options"])
        self.assertEqual("stable instructions", gpt56_explicit["instructions"])
        self.assertNotIn("prompt_cache_breakpoint", json.dumps(gpt56_explicit))

        self.assertEqual("stable-affinity", legacy["prompt_cache_key"])
        self.assertEqual("24h", legacy["prompt_cache_retention"])
        self.assertNotIn("prompt_cache_options", legacy)
        self.assertNotIn("prompt_cache_breakpoint", json.dumps(legacy))
        self.assertEqual("in_memory", legacy_memory["prompt_cache_retention"])

        self.assertNotIn("prompt_cache_key", deepseek)
        self.assertNotIn("prompt_cache_options", deepseek)
        self.assertNotIn("prompt_cache_retention", deepseek)
        self.assertRegex(deepseek["user"], r"^noval-[0-9a-f]{64}$")

        self.assertNotIn("prompt_cache_key", disabled)
        self.assertNotIn("prompt_cache_options", disabled)
        self.assertNotIn("prompt_cache_retention", disabled)
        self.assertNotIn("user", disabled)
        self.assertRegex(long_affinity["prompt_cache_key"], r"^[0-9a-f]{64}$")

        baseline = client._cache_continuity_snapshot(
            gpt56,
            "responses",
            cache_affinity="stable-affinity",
            provider_profile=gpt56_profile,
        )
        changed_payload = {**gpt56, "reasoning": {"effort": "high"}}
        changed = client._cache_continuity_snapshot(
            changed_payload,
            "responses",
            cache_affinity="stable-affinity",
            provider_profile=gpt56_profile,
        )
        self.assertEqual("openai_gpt_5_6", baseline["promptCacheStrategy"])
        self.assertIn("requestSettingsFingerprint", baseline)
        self.assertEqual(
            client._cache_fingerprint(gpt56["input"][:1]),
            baseline["stablePrefixFingerprint"],
        )
        self.assertNotEqual(baseline["surfaceGeneration"], changed["surfaceGeneration"])

        with self._responses_settings(cache_key_models="*", provider_user_models="*"):
            with self.assertRaisesRegex(ValueError, "cache model policies overlap"):
                client._build_payload(
                    messages,
                    "gpt-5.6-sol",
                    0.2,
                    64,
                    False,
                    False,
                    cache_affinity="stable-affinity",
                    wire_api="responses",
                )

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_uses_responses_events_and_requires_completed_terminal(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(stream_effects=[
            FakeStreamResponse([
                'event: response.created',
                'data: {"type":"response.created","sequence_number":0,"response":{"status":"in_progress"}}',
                'event: response.output_text.delta',
                'data: {"type":"response.output_text.delta","sequence_number":1,"delta":"hello"}',
                'event: response.completed',
                'data: {"type":"response.completed","sequence_number":2,"response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":2}}}}',
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings():
            events = [event async for event in client.stream(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-flash",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
            )]

        self.assertEqual("https://api.deepseek.com/responses", factory.stream_calls[0]["args"][1])
        self.assertNotIn("stream_options", factory.stream_calls[0]["kwargs"]["json"])
        self.assertEqual("hello", events[0]["delta"])
        self.assertEqual(15, events[-1]["tokenUsed"])
        self.assertEqual(4, events[-1]["promptCacheHitTokens"])
        self.assertEqual(2, events[-1]["usage"]["reasoningTokens"])

    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_responses_stream_does_not_retry_after_output_delta(self, async_client_mock, sleep_mock) -> None:
        request = httpx.Request("POST", "https://api.deepseek.com/responses")

        class DeltaThenConnectErrorResponse(FakeStreamResponse):
            async def aiter_lines(self):
                yield 'data: {"type":"response.output_text.delta","sequence_number":0,"delta":"partial"}'
                raise httpx.ConnectError("connection lost after delta", request=request)

        factory = FakeAsyncClientFactory(stream_effects=[
            DeltaThenConnectErrorResponse([]),
            FakeStreamResponse([
                'data: {"type":"response.completed","sequence_number":0,"response":{"status":"completed"}}',
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings(), patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
            stream = client.stream(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-flash",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
            )
            self.assertEqual({"event": "delta", "delta": "partial"}, await anext(stream))
            with self.assertRaises(httpx.ConnectError):
                await anext(stream)

        self.assertEqual(1, len(factory.stream_calls))
        sleep_mock.assert_not_awaited()

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_fails_closed_on_incomplete_or_missing_terminal(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(stream_effects=[
            FakeStreamResponse([
                'data: {"type":"response.incomplete","sequence_number":0,"response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}',
            ]),
            FakeStreamResponse([
                'data: {"type":"response.output_text.delta","sequence_number":0,"delta":"partial"}',
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings():
            with self.assertRaisesRegex(RuntimeError, "incomplete"):
                _ = [event async for event in client.stream(
                    messages=[{"role": "user", "content": "first"}],
                    model="deepseek-v4-flash",
                    temperature=0.2,
                    max_tokens=16,
                    require_json=False,
                )]
            with self.assertRaisesRegex(RuntimeError, "terminal"):
                _ = [event async for event in client.stream(
                    messages=[{"role": "user", "content": "second"}],
                    model="deepseek-v4-flash",
                    temperature=0.2,
                    max_tokens=16,
                    require_json=False,
                )]

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_unsupported_responses_model_uses_observable_chat_fallback(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "model": "deepseek-v4-pro",
                "choices": [{"message": {"content": "chat fallback"}}],
                "usage": {"total_tokens": 9},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self._responses_settings(chat_fallback=True):
            result = await client.invoke(
                messages=[{"role": "user", "content": CHINESE_PROMPT}],
                model="deepseek-v4-pro",
                temperature=0.2,
                max_tokens=16,
                require_json=False,
            )

        self.assertEqual("https://api.deepseek.com/v1/chat/completions", factory.post_calls[0]["args"][0])
        self.assertEqual("chat_completions", result["wire_api"])
        self.assertEqual({
            "from": "responses",
            "to": "chat_completions",
            "reason": "model_not_responses_capable",
            "model": "deepseek-v4-pro",
        }, result["providerTransportFallback"])

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_unsupported_responses_model_fails_before_network_when_chat_fallback_is_disabled(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory()
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()
        original_fallback_model = settings.provider_fallback_model
        settings.provider_fallback_model = ""
        try:
            with self._responses_settings(chat_fallback=False):
                with self.assertRaisesRegex(ValueError, "does not support Responses"):
                    await client.invoke(
                        messages=[{"role": "user", "content": CHINESE_PROMPT}],
                        model="deepseek-v4-pro",
                        temperature=0.2,
                        max_tokens=16,
                        require_json=False,
                    )
        finally:
            settings.provider_fallback_model = original_fallback_model

        self.assertEqual([], factory.post_calls)

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_explicit_responses_profile_never_implicitly_falls_back_to_chat(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "status": "completed",
                "model": "gateway-responses-model",
                "output": [{
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "responses answer"}],
                }],
                "usage": {"input_tokens": 3, "output_tokens": 2, "total_tokens": 5},
            }),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()
        profile = ProviderProfile(
            profile_key="gateway-responses",
            profile_version="v3",
            endpoint="https://gateway.example/v1",
            model="gateway-responses-model",
            protocol="responses",
            api_key="explicit-profile-secret",
        )

        with self._responses_settings(chat_fallback=True, models=""):
            with patch.object(client, "_assert_public_endpoint", new=AsyncMock()):
                result = await client.invoke(
                    messages=[{"role": "user", "content": CHINESE_PROMPT}],
                    model="unlisted-request-model",
                    temperature=0.2,
                    max_tokens=16,
                    require_json=False,
                    provider_profile=profile,
                )

        self.assertEqual(
            "https://gateway.example/v1/responses",
            factory.post_calls[0]["args"][0],
        )
        self.assertEqual("responses", result["wire_api"])
        self.assertNotIn("providerTransportFallback", result)
        self.assertEqual(profile.snapshot(), result["providerProfile"])
        self.assertNotIn("explicit-profile-secret", str(result))

    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_agent_kernel_round_trips_authorized_function_output_through_responses(self, async_client_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            FakeResponse({
                "id": "resp_tool",
                "status": "completed",
                "model": "deepseek-v4-flash",
                "output": [{
                    "id": "function_1",
                    "type": "function_call",
                    "call_id": "call_project_search",
                    "name": "project_search",
                    "arguments": '{"query":"chapter clue"}',
                }],
                "usage": {"input_tokens": 20, "output_tokens": 5, "total_tokens": 25},
            }),
            FakeResponse({
                "id": "resp_answer",
                "status": "completed",
                "model": "deepseek-v4-flash",
                "output": [{
                    "id": "message_1",
                    "type": "message",
                    "role": "assistant",
                    "content": [{"type": "output_text", "text": "chapter 12 contains the clue"}],
                }],
                "usage": {"input_tokens": 32, "output_tokens": 8, "total_tokens": 40},
            }),
        ])
        async_client_mock.side_effect = factory
        executed: list[dict] = []

        async def execute(call):
            executed.append({"name": call.name, "arguments": call.arguments})
            return KernelToolObservation(
                tool_call_id=call.id,
                name=call.name,
                status="succeeded",
                content="chapter 12",
            )

        provider_client = OpenAICompatibleProviderClient()
        kernel = AgentKernel(provider_client)
        with self._responses_settings():
            result = await kernel.run(
                KernelTurnRequest(
                    messages=[
                        KernelMessage(role="system", content="use authorized project evidence"),
                        KernelMessage(role="user", content="find the clue"),
                    ],
                    model="deepseek-v4-flash",
                    tool_schemas=[{
                        "type": "function",
                        "function": {
                            "name": "project_search",
                            "parameters": {"type": "object"},
                        },
                    }],
                    max_turns=3,
                    max_tool_calls=2,
                ),
                authorization={"grants": [{"toolName": "project_search"}]},
                tool_executor=execute,
            )

        self.assertEqual([{"name": "project_search", "arguments": {"query": "chapter clue"}}], executed)
        self.assertEqual("chapter 12 contains the clue", result.content)
        self.assertEqual(2, len(factory.post_calls))
        first_payload = factory.post_calls[0]["kwargs"]["json"]
        second_payload = factory.post_calls[1]["kwargs"]["json"]
        first_input = first_payload["input"]
        second_input = second_payload["input"]
        function_call = next(item for item in second_input if item.get("type") == "function_call")
        self.assertEqual("call_project_search", function_call["call_id"])
        self.assertEqual("project_search", function_call["name"])
        self.assertEqual({"query": "chapter clue"}, json.loads(function_call["arguments"]))
        self.assertIn({
            "type": "function_call_output",
            "call_id": "call_project_search",
            "output": "chapter 12",
        }, second_input)
        self.assertEqual(first_input, second_input[:len(first_input)])
        self.assertEqual(
            ["function_call", "function_call_output"],
            [item.get("type") for item in second_input[len(first_input):]],
        )
        self.assertEqual(first_payload.get("instructions"), second_payload.get("instructions"))
        self.assertEqual(first_payload.get("tools"), second_payload.get("tools"))
        first_cache = provider_client._cache_continuity_snapshot(first_payload, "responses")
        second_cache = provider_client._cache_continuity_snapshot(second_payload, "responses")
        self.assertEqual(
            first_cache["stablePrefixFingerprint"],
            second_cache["stablePrefixFingerprint"],
        )
        self.assertEqual(first_cache["toolsFingerprint"], second_cache["toolsFingerprint"])
        self.assertEqual(first_cache["surfaceGeneration"], second_cache["surfaceGeneration"])
        self.assertEqual(
            first_cache["inputFingerprint"],
            second_cache["prefixChainFingerprints"][first_cache["inputCount"] - 1],
        )
        self.assertTrue(all(call["wireApi"] == "responses" for call in result.provider_calls))


class RoutingProviderClient(OpenAICompatibleProviderClient):
    def __init__(self) -> None:
        super().__init__()
        self.call_order: list[str] = []
        self.openai_effects: list[object] = []
        self.dify_effects: list[object] = []

    async def _invoke_openai_compatible(self, **kwargs) -> dict:
        self.call_order.append("openai")
        effect = self.openai_effects.pop(0)
        if isinstance(effect, Exception):
            raise effect
        return effect

    async def _invoke_dify_blocking(self, **kwargs) -> dict:
        self.call_order.append("dify")
        effect = self.dify_effects.pop(0)
        if isinstance(effect, Exception):
            raise effect
        return effect


class ProviderClientRoutingTest(unittest.IsolatedAsyncioTestCase):
    def _build_request(self, provider_type: str | None = None) -> RunRequest:
        return RunRequest(
            taskId="task-provider-routing",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptType="theme",
                promptContent="JSON ONLY {{content}}",
                providerType=provider_type,
                modelName="deepseek-chat",
            ),
            sourcePayload={"inputText": "trend content", "snapshots": [{"snapshotId": 1}]},
            limits={},
        )

    async def test_invoke_should_try_dify_before_openai_when_primary_provider_is_dify(self) -> None:
        client = RoutingProviderClient()
        client.dify_effects = [RuntimeError("dify unavailable")]
        client.openai_effects = [{"model_name": "deepseek-chat", "content": "openai fallback", "token_used": 21}]

        result = await client.invoke(
            request=self._build_request("dify"),
            messages=[{"role": "user", "content": "hello"}],
            model="deepseek-chat",
            temperature=0.3,
            max_tokens=32,
            require_json=False,
        )

        self.assertEqual(["dify", "openai"], client.call_order)
        self.assertEqual("openai fallback", result["content"])

    async def test_invoke_should_try_openai_before_dify_when_primary_provider_is_not_dify(self) -> None:
        client = RoutingProviderClient()
        client.openai_effects = [RuntimeError("openai unavailable")]
        client.dify_effects = [{"model_name": "dify:workflow-1", "content": "dify fallback", "token_used": 13}]

        result = await client.invoke(
            request=self._build_request("openai-compatible"),
            messages=[{"role": "user", "content": "hello"}],
            model="deepseek-chat",
            temperature=0.3,
            max_tokens=32,
            require_json=False,
        )

        self.assertEqual(["openai", "dify"], client.call_order)
        self.assertEqual("dify fallback", result["content"])

    async def test_invoke_should_return_final_fallback_payload_when_all_providers_fail(self) -> None:
        client = RoutingProviderClient()
        client.openai_effects = [RuntimeError("openai unavailable")]
        client.dify_effects = [RuntimeError("dify unavailable")]

        result = await client.invoke(
            request=self._build_request("openai-compatible"),
            messages=[{"role": "system", "content": "prompt"}, {"role": "user", "content": "trend content"}],
            model="deepseek-chat",
            temperature=0.3,
            max_tokens=32,
            require_json=False,
        )

        self.assertEqual(["openai", "dify"], client.call_order)
        self.assertEqual("deepseek-chat", result["model_name"])
        self.assertIn("theme analysis result", result["content"])
        self.assertIn("summary", result["result_json"])
        self.assertEqual("theme", result["result_json"]["analysisType"])
        self.assertNotIn("modelName", result["result_json"])
        self.assertNotIn("content", result["result_json"])
        self.assertNotIn("meta", result["result_json"])


class AdmissionProbeProvider(OpenAICompatibleProviderClient):
    active = 0
    max_active = 0
    entered: asyncio.Event
    release: asyncio.Event

    async def _invoke_admitted(self, **kwargs) -> dict:
        type(self).active += 1
        type(self).max_active = max(type(self).max_active, type(self).active)
        type(self).entered.set()
        try:
            await type(self).release.wait()
            return {"model_name": kwargs.get("model"), "content": "ok", "token_used": 11}
        finally:
            type(self).active -= 1


class ProviderAdmissionIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        AdmissionProbeProvider.active = 0
        AdmissionProbeProvider.max_active = 0
        AdmissionProbeProvider.entered = asyncio.Event()
        AdmissionProbeProvider.release = asyncio.Event()

    async def test_provider_admission_is_process_wide_across_client_instances(self) -> None:
        original = settings.max_active_llm_calls
        settings.max_active_llm_calls = 1
        try:
            first = asyncio.create_task(self._invoke(AdmissionProbeProvider()))
            await AdmissionProbeProvider.entered.wait()
            second = asyncio.create_task(self._invoke(AdmissionProbeProvider()))
            await asyncio.sleep(0.05)

            self.assertEqual(1, AdmissionProbeProvider.max_active)
            AdmissionProbeProvider.release.set()
            await asyncio.gather(first, second)
            self.assertEqual(1, AdmissionProbeProvider.max_active)
        finally:
            settings.max_active_llm_calls = original

    async def test_provider_consumes_run_tokens_and_honors_pre_cancelled_scope(self) -> None:
        client = AdmissionProbeProvider()
        AdmissionProbeProvider.release.set()
        with run_budget_scope("fast") as budget:
            await self._invoke(client)
            self.assertEqual(11, budget.used_total_tokens)

        token = CancellationToken()
        token.cancel("user_cancelled")
        with cancellation_scope(token):
            with self.assertRaisesRegex(RunCancelledError, "user_cancelled"):
                await self._invoke(AdmissionProbeProvider())

    async def _invoke(self, client: OpenAICompatibleProviderClient) -> dict:
        return await client.invoke(
            messages=[{"role": "user", "content": "hello"}],
            model="deepseek-chat",
            temperature=0.2,
            max_tokens=None,
            require_json=False,
        )


if __name__ == "__main__":
    unittest.main()


class ProviderUsageSummaryTests(unittest.TestCase):
    def test_usage_summary_reads_cached_token_aliases(self) -> None:
        from app.services.provider_client import OpenAICompatibleProviderClient

        client = OpenAICompatibleProviderClient.__new__(OpenAICompatibleProviderClient)
        summary = client._usage_summary({
            "prompt_tokens": 100,
            "completion_tokens": 20,
            "total_tokens": 120,
            "prompt_tokens_details": {"cached_tokens": 40},
        })
        self.assertEqual(40, summary["promptCacheHitTokens"])
        self.assertEqual(100, summary["promptTokens"])


class ProviderErrorDiagnosticTests(unittest.TestCase):
    """上游故障必须留下可复盘的码位，而且只能留码位。"""

    def _status_error(self, status: int, payload: dict | None = None, *, text: str | None = None):
        request = httpx.Request("POST", "https://provider.example/v1/responses")
        if text is not None:
            response = httpx.Response(status, request=request, text=text)
        else:
            response = httpx.Response(status, request=request, json=payload or {})
        return httpx.HTTPStatusError("upstream rejected", request=request, response=response)

    def test_non_retryable_400_is_classified_even_though_failover_ignores_it(self) -> None:
        from app.services.provider_client import (
            OpenAICompatibleProviderClient,
            provider_error_diagnostic,
        )

        # 这就是 gpt-5.6 那次真机故障的报文形状。
        error = self._status_error(400, {
            "error": {
                "message": (
                    "Unsupported value: 'minimal' is not supported with the "
                    "'gpt-5.6-sol' model."
                ),
                "type": "invalid_request_error",
                "param": "reasoning.effort",
                "code": "unsupported_value",
            }
        })
        client = OpenAICompatibleProviderClient()
        # failover 分类器对 400 返回 None——所以必须有第二条通路，否则线索归零。
        self.assertIsNone(client.failover_failure_class(error))
        self.assertEqual(
            "upstream=400 code=unsupported_value type=invalid_request_error param=reasoning.effort",
            provider_error_diagnostic(error),
        )

    def test_diagnostic_never_carries_the_provider_free_text(self) -> None:
        from app.services.provider_client import provider_error_diagnostic

        # message 会回显请求里的字段值，整串要落进 ai_chat_run.error_message，
        # 所以只放行枚举形状的取值：带空格/引号的一律丢掉。
        diagnostic = provider_error_diagnostic(self._status_error(400, {
            "error": {
                "message": "secret prompt leaked here",
                "type": "invalid request error",
                "code": "rate limit reached",
            }
        }))
        self.assertEqual("upstream=400", diagnostic)

    def test_non_json_and_transport_errors_still_report_something(self) -> None:
        from app.services.provider_client import provider_error_diagnostic

        request = httpx.Request("POST", "https://provider.example/v1/responses")
        self.assertEqual(
            "upstream=502",
            provider_error_diagnostic(self._status_error(502, text="<html>bad gateway</html>")),
        )
        self.assertEqual(
            "upstream=timeout",
            provider_error_diagnostic(httpx.ReadTimeout("slow", request=request)),
        )
        self.assertEqual(
            "upstream=connect_error",
            provider_error_diagnostic(httpx.ConnectError("down", request=request)),
        )
        self.assertIsNone(provider_error_diagnostic(ValueError("policy")))


class ProviderModelFailoverTests(unittest.IsolatedAsyncioTestCase):
    def test_failover_classifier_covers_transient_and_credential_failures(self) -> None:
        client = OpenAICompatibleProviderClient()
        request = httpx.Request("POST", "https://provider.example/responses")
        eligible = [
            httpx.ConnectError("connect", request=request),
            httpx.ReadTimeout("timeout", request=request),
            *[
                httpx.HTTPStatusError(
                    "eligible",
                    request=request,
                    response=httpx.Response(status, request=request),
                )
                # 5xx and 429 may recover on the same key; 401/402/403/404 mean this
                # key is unusable and only another key can help.
                for status in (429, 500, 502, 503, 504, 401, 402, 403, 404)
            ],
        ]
        ineligible = [
            ValueError("policy"),
            RuntimeError("malformed response"),
            *[
                httpx.HTTPStatusError(
                    "ineligible",
                    request=request,
                    response=httpx.Response(status, request=request),
                )
                for status in (400, 422)
            ],
        ]

        self.assertTrue(all(client.is_failover_eligible(error) for error in eligible))
        self.assertTrue(all(not client.is_failover_eligible(error) for error in ineligible))

    async def test_model_failover_uses_fallback_once(self) -> None:
        from app.config import settings
        from app.services.provider_client import OpenAICompatibleProviderClient

        class Flaky(OpenAICompatibleProviderClient):
            def __init__(self) -> None:
                self.models: list[str] = []

            async def _invoke_with_retry(self, *, payload, base_url=None, api_key=None, timeout_millis=None):
                model = str(payload.get("model") or "")
                self.models.append(model)
                if model == "primary-model":
                    raise httpx.ConnectError("primary down")
                return {
                    "model": model,
                    "choices": [{"message": {"content": "ok from fallback"}}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                }

        original = settings.provider_fallback_model
        settings.provider_fallback_model = "fallback-model"
        client = Flaky()
        try:
            result = await client.invoke(
                messages=[{"role": "user", "content": "hi"}],
                model="primary-model",
                temperature=0,
                max_tokens=16,
                require_json=False,
            )
        finally:
            settings.provider_fallback_model = original

        self.assertEqual(["primary-model", "fallback-model"], client.models)
        self.assertEqual("ok from fallback", result["content"])
        self.assertEqual(
            {"from": "primary-model", "to": "fallback-model", "reason": "primary down"},
            result.get("providerFailover"),
        )

    async def test_model_failover_skipped_when_unconfigured(self) -> None:
        from app.config import settings
        from app.services.provider_client import OpenAICompatibleProviderClient

        class AlwaysFail(OpenAICompatibleProviderClient):
            def __init__(self) -> None:
                self.calls = 0

            async def _invoke_with_retry(self, *, payload, base_url=None, api_key=None, timeout_millis=None):
                self.calls += 1
                raise httpx.ConnectError("down")

        original = settings.provider_fallback_model
        settings.provider_fallback_model = ""
        client = AlwaysFail()
        try:
            with self.assertRaises(httpx.ConnectError):
                await client.invoke(
                    messages=[{"role": "user", "content": "hi"}],
                    model="only-model",
                    temperature=0,
                    max_tokens=8,
                    require_json=False,
                )
        finally:
            settings.provider_fallback_model = original
        self.assertEqual(1, client.calls)
