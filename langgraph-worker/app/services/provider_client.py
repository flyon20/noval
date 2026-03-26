from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncGenerator

import httpx

from app.config import settings


class OpenAICompatibleProviderClient:
    _MAX_TRANSPORT_ATTEMPTS = 3
    _RETRYABLE_ERRORS = (httpx.ConnectError, httpx.TimeoutException)

    async def invoke(self, *, messages: list[dict], model: str, temperature: float | None, max_tokens: int | None,
                     require_json: bool, base_url: str | None = None, api_key: str | None = None,
                     timeout_millis: int | None = None) -> dict:
        payload = self._build_payload(messages, model, temperature, max_tokens, require_json, stream=False)
        data = await self._invoke_with_retry(
            payload=payload,
            base_url=base_url,
            api_key=api_key,
            timeout_millis=timeout_millis,
        )
        choice = (data.get("choices") or [{}])[0]
        message = choice.get("message") or {}
        usage = data.get("usage") or {}
        return {
            "model_name": data.get("model", model),
            "content": message.get("content", "") or "",
            "token_used": int(usage.get("total_tokens") or 0),
        }

    async def stream(self, *, messages: list[dict], model: str, temperature: float | None,
                     max_tokens: int | None, require_json: bool, base_url: str | None = None,
                     api_key: str | None = None, timeout_millis: int | None = None) -> AsyncGenerator[dict, None]:
        payload = self._build_payload(messages, model, temperature, max_tokens, require_json, stream=True)
        for attempt in range(1, self._MAX_TRANSPORT_ATTEMPTS + 1):
            yielded_chunk = False
            try:
                async with httpx.AsyncClient(timeout=self._resolve_timeout_seconds(timeout_millis)) as client:
                    async with client.stream(
                        "POST",
                        f"{self._resolve_base_url(base_url)}/chat/completions",
                        headers=self._headers(api_key),
                        json=payload,
                    ) as response:
                        response.raise_for_status()
                        async for line in response.aiter_lines():
                            if not line or not line.startswith("data:"):
                                continue
                            data = line[len("data:"):].strip()
                            if data == "[DONE]":
                                break
                            payload = json.loads(data)
                            choice = (payload.get("choices") or [{}])[0]
                            delta = (choice.get("delta") or {}).get("content")
                            if delta:
                                yielded_chunk = True
                                yield {"event": "delta", "delta": delta}
                            if choice.get("finish_reason"):
                                yielded_chunk = True
                                usage = payload.get("usage") or {}
                                yield {"event": "done", "tokenUsed": int(usage.get("total_tokens") or 0)}
                return
            except self._RETRYABLE_ERRORS:
                if yielded_chunk or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await asyncio.sleep(self._retry_backoff_seconds(attempt))

    def _build_payload(self, messages: list[dict], model: str, temperature: float | None,
                       max_tokens: int | None, require_json: bool, stream: bool) -> dict:
        payload: dict = {
            "model": model or settings.default_model,
            "messages": messages,
            "stream": stream,
        }
        if temperature is not None:
            payload["temperature"] = temperature
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if require_json:
            payload["response_format"] = {"type": "json_object"}
        return payload

    def _headers(self, api_key: str | None = None) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        effective_api_key = api_key or settings.openai_api_key
        if effective_api_key:
            headers["Authorization"] = f"Bearer {effective_api_key}"
        return headers

    def _resolve_base_url(self, base_url: str | None = None) -> str:
        return (base_url or settings.openai_base_url).rstrip("/")

    def _resolve_timeout_seconds(self, timeout_millis: int | None) -> float:
        effective_timeout_millis = timeout_millis if timeout_millis and timeout_millis > 0 else settings.timeout_millis
        return effective_timeout_millis / 1000

    async def _invoke_with_retry(self,
                                 *,
                                 payload: dict,
                                 base_url: str | None,
                                 api_key: str | None,
                                 timeout_millis: int | None) -> dict:
        for attempt in range(1, self._MAX_TRANSPORT_ATTEMPTS + 1):
            try:
                async with httpx.AsyncClient(timeout=self._resolve_timeout_seconds(timeout_millis)) as client:
                    response = await client.post(
                        f"{self._resolve_base_url(base_url)}/chat/completions",
                        headers=self._headers(api_key),
                        json=payload,
                    )
                    response.raise_for_status()
                    return response.json()
            except self._RETRYABLE_ERRORS:
                if attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await asyncio.sleep(self._retry_backoff_seconds(attempt))
        raise RuntimeError("unreachable")

    def _retry_backoff_seconds(self, attempt: int) -> float:
        return 0.35 * attempt
