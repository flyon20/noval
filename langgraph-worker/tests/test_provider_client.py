from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

import httpx

from app.services.provider_client import OpenAICompatibleProviderClient


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

    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_retry_connect_error_before_succeeding(self, async_client_mock, sleep_mock) -> None:
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

        result = await client.invoke(
            messages=[{"role": "user", "content": "请只回复ok"}],
            model="deepseek-chat",
            temperature=0.3,
            max_tokens=16,
            require_json=False,
        )

        self.assertEqual("retry success", result["content"])
        self.assertEqual(2, len(factory.constructor_calls))
        self.assertEqual(1, sleep_mock.await_count)

    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_invoke_should_raise_after_transport_retries_are_exhausted(self, async_client_mock, sleep_mock) -> None:
        factory = FakeAsyncClientFactory(post_effects=[
            httpx.ConnectError("attempt-1", request=self.request),
            httpx.ConnectError("attempt-2", request=self.request),
            httpx.ConnectError("attempt-3", request=self.request),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        with self.assertRaises(httpx.ConnectError):
            await client.invoke(
                messages=[{"role": "user", "content": "请只回复ok"}],
                model="deepseek-chat",
                temperature=0.3,
                max_tokens=16,
                require_json=False,
            )

        self.assertEqual(3, len(factory.constructor_calls))
        self.assertEqual(2, sleep_mock.await_count)

    @patch("asyncio.sleep", new_callable=AsyncMock)
    @patch("app.services.provider_client.httpx.AsyncClient")
    async def test_stream_should_retry_connect_error_before_first_chunk(self, async_client_mock, sleep_mock) -> None:
        factory = FakeAsyncClientFactory(stream_effects=[
            httpx.ConnectError("stream attempt failed", request=self.request),
            FakeStreamResponse([
                'data: {"choices":[{"delta":{"content":"hello"}}]}',
                'data: {"choices":[{"finish_reason":"stop"}],"usage":{"total_tokens":21}}',
                "data: [DONE]",
            ]),
        ])
        async_client_mock.side_effect = factory
        client = OpenAICompatibleProviderClient()

        events = [
            event async for event in client.stream(
                messages=[{"role": "user", "content": "请只回复ok"}],
                model="deepseek-chat",
                temperature=0.3,
                max_tokens=16,
                require_json=False,
            )
        ]

        self.assertEqual(
            [{"event": "delta", "delta": "hello"}, {"event": "done", "tokenUsed": 21}],
            events,
        )
        self.assertEqual(2, len(factory.constructor_calls))
        self.assertEqual(1, sleep_mock.await_count)


if __name__ == "__main__":
    unittest.main()
