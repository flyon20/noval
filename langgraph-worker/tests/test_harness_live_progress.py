from __future__ import annotations

import asyncio
from contextlib import aclosing
from copy import deepcopy
import unittest
from unittest.mock import AsyncMock, patch

from app.services.harness.agent_kernel import (
    AgentKernel, KernelMessage, KernelToolObservation, KernelTurnRequest,
)
from app.services.provider_client import OpenAICompatibleProviderClient, ProviderProfile
from app.services.harness.cancellation import CancellationToken, RunCancelledError, cancellation_scope
from test_agent_kernel import FakeProvider
from test_provider_client import FakeAsyncClientFactory, FakeStreamResponse


def tool_request() -> KernelTurnRequest:
    return KernelTurnRequest(
        messages=[KernelMessage(role="system", content="stable policy"),
                  KernelMessage(role="user", content="fixed question")],
        model="fixture-model", cache_affinity="fixture-affinity", request_family="answer",
        temperature=0.2, max_tokens=128, max_turns=3,
        tool_schemas=[{"type": "function", "function": {
            "name": "knowledge.search", "parameters": {
                "type": "object", "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        }}],
    )


AUTHORIZATION = {"grants": [{"toolName": "knowledge.search"}]}


def replies() -> list[dict]:
    return [{
        "content": "", "model_name": "fixture-model", "token_used": 4,
        "usage": {"inputTokens": 3, "outputTokens": 1, "promptCacheHitTokens": 2},
        "tool_calls": [{"id": "call-fixed", "type": "function", "function": {
            "name": "knowledge.search", "arguments": '{"query":"fixture"}',
        }}],
    }, {
        "content": "verified answer", "model_name": "fixture-model", "token_used": 5,
        "usage": {"inputTokens": 4, "outputTokens": 1, "promptCacheHitTokens": 3},
        "tool_calls": [],
    }]


async def observation(call) -> KernelToolObservation:
    return KernelToolObservation(call.id, call.name, "succeeded", "fixed evidence")


class WireRecordingProvider(FakeProvider):
    """Use the production dialect/cache compiler without making network calls."""

    def __init__(self, profile: ProviderProfile) -> None:
        super().__init__(replies())
        self.profile = profile
        self.wire: list[dict] = []
        self.continuity: list[dict] = []

    async def invoke(self, **kwargs):
        client = OpenAICompatibleProviderClient()
        payload = client._build_payload(
            messages=kwargs["messages"], model=kwargs["model"],
            temperature=kwargs["temperature"], max_tokens=kwargs["max_tokens"],
            require_json=kwargs["require_json"], stream=False, tools=kwargs.get("tools"),
            reasoning_mode=kwargs.get("reasoning_mode"),
            reasoning_effort=kwargs.get("reasoning_effort"),
            cache_affinity=kwargs.get("cache_affinity"),
            wire_api=self.profile.protocol, provider_profile=self.profile,
        )
        self.wire.append(deepcopy(payload))
        self.continuity.append(client._cache_continuity_snapshot(
            payload, self.profile.protocol, provider_profile=self.profile,
            cache_affinity=kwargs.get("cache_affinity"), request_family=kwargs.get("request_family"),
        ))
        return await super().invoke(**kwargs)


class KernelLiveProgressTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancellation_after_start_prevents_tool_dispatch(self) -> None:
        token = CancellationToken()
        called = []

        async def executor(call):
            called.append(call.id)
            return await observation(call)

        with cancellation_scope(token):
            async with aclosing(AgentKernel(FakeProvider(replies())).stream(
                tool_request(), authorization=AUTHORIZATION, tool_executor=executor,
            )) as stream:
                while (await anext(stream)).type != "tool.start":
                    pass
                token.cancel("fixture cancelled before dispatch")
                with self.assertRaises(RunCancelledError):
                    await anext(stream)
        self.assertEqual([], called)

    async def test_real_provider_transport_closes_on_disconnect_and_rejects_partial_eof(self) -> None:
        for close_early in (True, False):
            with self.subTest(close_early=close_early):
                cleaned = asyncio.Event()

                class Response(FakeStreamResponse):
                    async def __aexit__(self, *_args):
                        cleaned.set()
                        return False

                factory = FakeAsyncClientFactory(stream_effects=[Response([
                    'data: {"choices":[{"delta":{"content":"partial"}}]}',
                ])])
                profile = ProviderProfile(profile_key="fixture", profile_version="v1",
                    endpoint="https://fixture.example/v1", model="fixture", protocol="chat_completions",
                    api_key="fixture-only")
                client = OpenAICompatibleProviderClient()
                with patch("app.services.provider_client.httpx.AsyncClient", side_effect=factory), patch.object(
                    OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock(),
                ), patch.object(
                    client, "resolve_provider_profile", return_value=profile,
                ):
                    async with aclosing(AgentKernel(client).stream(KernelTurnRequest(
                        messages=[KernelMessage(role="user", content="fixture")], model="fixture",
                        provider_profile=profile.snapshot(),
                    ))) as stream:
                        self.assertEqual("message.start", (await anext(stream)).type)
                        self.assertEqual("message.delta", (await anext(stream)).type)
                        if not close_early:
                            with self.assertRaisesRegex(RuntimeError, "terminal"):
                                await anext(stream)
                self.assertTrue(cleaned.is_set())
                self.assertEqual(1, len(factory.stream_calls))

    async def test_native_stream_eof_does_not_retry_commit_or_complete(self) -> None:
        for partial in (False, True):
            with self.subTest(partial=partial):
                checkpoints, events, stream_calls = [], [], []

                class InterruptedProvider(FakeProvider):
                    async def stream(self, **kwargs):
                        stream_calls.append(kwargs)
                        if partial:
                            yield {"event": "delta", "delta": "partial"}

                async def writer(event_type, _key, _payload):
                    checkpoints.append(event_type)

                provider = InterruptedProvider([])
                with self.assertRaisesRegex(RuntimeError, "terminal"):
                    async for event in AgentKernel(provider, checkpoint_writer=writer).stream(
                        KernelTurnRequest(messages=[KernelMessage(role="user", content="fixture")], model="fixture"),
                    ):
                        events.append(event)
                self.assertEqual(["MODEL_PREPARED"], checkpoints)
                self.assertNotIn("result", [event.type for event in events])
                self.assertEqual([], provider.calls)
                self.assertEqual(1, len(stream_calls))

    async def test_real_provider_empty_eof_does_not_retry(self) -> None:
        for protocol in ("chat_completions", "responses"):
            with self.subTest(protocol=protocol):
                cleaned = asyncio.Event()

                class Response(FakeStreamResponse):
                    async def __aexit__(self, *_args):
                        cleaned.set()
                        return False

                factory = FakeAsyncClientFactory(stream_effects=[Response([])])
                profile = ProviderProfile(profile_key="fixture", profile_version="v1",
                    endpoint="https://fixture.example/v1", model="fixture", protocol=protocol,
                    api_key="fixture-only")
                client = OpenAICompatibleProviderClient()
                with patch("app.services.provider_client.httpx.AsyncClient", side_effect=factory), patch.object(
                    OpenAICompatibleProviderClient, "_assert_public_endpoint", new=AsyncMock(),
                ), patch.object(client, "resolve_provider_profile", return_value=profile):
                    with self.assertRaisesRegex(RuntimeError, "terminal"):
                        async for _event in AgentKernel(client).stream(KernelTurnRequest(
                            messages=[KernelMessage(role="user", content="fixture")], model="fixture",
                            provider_profile=profile.snapshot(),
                        )):
                            pass
                self.assertTrue(cleaned.is_set())
                self.assertEqual(1, len(factory.stream_calls))

    async def test_closing_native_delta_closes_provider_iterator_immediately(self) -> None:
        cleaned = asyncio.Event()

        class StreamingFixture(FakeProvider):
            async def stream(self, **_kwargs):
                try:
                    yield {"event": "delta", "delta": "partial"}
                    await asyncio.Event().wait()
                finally:
                    cleaned.set()

        async with aclosing(AgentKernel(StreamingFixture([])).stream(KernelTurnRequest(
            messages=[KernelMessage(role="user", content="fixture")], model="fixture",
        ))) as stream:
            self.assertEqual("message.start", (await anext(stream)).type)
            self.assertEqual("message.delta", (await anext(stream)).type)
        self.assertTrue(cleaned.is_set())

    async def test_sampling_freezes_schema_authorization_and_request_settings_across_yields(self) -> None:
        request = tool_request()
        authorization = deepcopy(AUTHORIZATION)
        provider = FakeProvider(replies())
        async with aclosing(AgentKernel(provider).stream(
            request, authorization=authorization, tool_executor=observation,
        )) as stream:
            self.assertEqual("message.start", (await anext(stream)).type)
            request.tool_schemas[0]["function"]["parameters"]["required"] = ["changed"]
            request.cache_affinity = "changed"
            request.max_tokens = 999
            authorization["grants"] = []
            events = [event async for event in stream]
        self.assertEqual("verified answer", events[-1].payload["content"])
        self.assertEqual([128, 128], [call["max_tokens"] for call in provider.calls])
        self.assertEqual(["fixture-affinity", "fixture-affinity"], [call["cache_affinity"] for call in provider.calls])
        self.assertEqual(["query"], provider.calls[0]["tools"][0]["function"]["parameters"]["required"])

    async def test_tool_start_is_consumable_before_gated_tool_finishes(self) -> None:
        release = asyncio.Event()
        entered = asyncio.Event()

        async def blocked(call):
            entered.set()
            await release.wait()
            return await observation(call)

        provider = FakeProvider(replies())
        async with aclosing(AgentKernel(provider).stream(
            tool_request(), authorization=AUTHORIZATION, tool_executor=blocked,
        )) as stream:
            seen = []
            while not seen or seen[-1].type != "tool.start":
                seen.append(await asyncio.wait_for(anext(stream), timeout=1))
            self.assertFalse(release.is_set())
            self.assertEqual(1, len(provider.calls))
            # No producer task runs ahead while the consumer is paused.
            self.assertFalse(entered.is_set())
            release.set()
            seen.extend([event async for event in stream])
        self.assertEqual(1, sum(event.type == "result" for event in seen))
        terminal = next(event for event in seen if event.type == "tool.end")
        self.assertEqual(seen[3].payload["id"], terminal.payload["id"])

    async def test_run_and_tool_stream_preserve_wire_cache_and_usage(self) -> None:
        for protocol, model, endpoint in (
            ("responses", "gpt-5.6", "https://api.openai.com/v1"),
            ("chat_completions", "deepseek-chat", "https://api.deepseek.com/v1"),
            ("responses", "unknown-model", "https://fixture.example/v1"),
        ):
            with self.subTest(protocol=protocol, model=model):
                profile = ProviderProfile(profile_key="fixture", profile_version="v1",
                                          endpoint=endpoint, model=model, protocol=protocol)
                run_provider, stream_provider = WireRecordingProvider(profile), WireRecordingProvider(profile)
                result = await AgentKernel(run_provider).run(
                    tool_request(), authorization=AUTHORIZATION, tool_executor=observation,
                )
                events = [event async for event in AgentKernel(stream_provider).stream(
                    tool_request(), authorization=AUTHORIZATION, tool_executor=observation,
                )]
                self.assertEqual(run_provider.wire, stream_provider.wire)
                self.assertEqual(run_provider.continuity, stream_provider.continuity)
                self.assertEqual(2, len(run_provider.wire))
                self.assertEqual(result.events, events[:-1])
                final = events[-1].payload
                self.assertEqual(result.content, final["content"])
                self.assertEqual(result.token_used, final["tokenUsed"])
                self.assertEqual(2, final["providerRequestCount"])
                self.assertEqual([call["usage"] for call in result.provider_calls],
                                 [call["usage"] for call in final["kernelProviderCalls"]])
                self.assertNotIn("itemId", str(stream_provider.wire))

    async def test_closing_at_tool_start_does_not_execute_or_leak_a_task(self) -> None:
        provider = FakeProvider(replies())
        executor_calls = []

        async def executor(call):
            executor_calls.append(call.id)
            return await observation(call)

        before = set(asyncio.all_tasks())
        async with aclosing(AgentKernel(provider).stream(
            tool_request(), authorization=AUTHORIZATION, tool_executor=executor,
        )) as stream:
            async for event in stream:
                if event.type == "tool.start":
                    break
        self.assertEqual([], executor_calls)
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(before, set(asyncio.all_tasks()))

    async def test_exception_after_tool_start_never_emits_completed_result(self) -> None:
        async def failed(_call):
            raise ValueError("fixture failure")

        events = []
        with self.assertRaisesRegex(ValueError, "fixture failure"):
            async for event in AgentKernel(FakeProvider(replies())).stream(
                tool_request(), authorization=AUTHORIZATION, tool_executor=failed,
            ):
                events.append(event)
        self.assertIn("tool.start", [event.type for event in events])
        self.assertNotIn("result", [event.type for event in events])
