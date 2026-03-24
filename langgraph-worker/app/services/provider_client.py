from __future__ import annotations

import json
from collections.abc import AsyncGenerator

import httpx

from app.config import settings


class OpenAICompatibleProviderClient:
    async def invoke(self, *, messages: list[dict], model: str, temperature: float | None, max_tokens: int | None,
                     require_json: bool) -> dict:
        payload = self._build_payload(messages, model, temperature, max_tokens, require_json, stream=False)
        async with httpx.AsyncClient(timeout=settings.timeout_millis / 1000) as client:
            response = await client.post(
                f"{settings.openai_base_url}/chat/completions",
                headers=self._headers(),
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
        choice = (data.get("choices") or [{}])[0]
        message = choice.get("message") or {}
        usage = data.get("usage") or {}
        return {
            "model_name": data.get("model", model),
            "content": message.get("content", "") or "",
            "token_used": int(usage.get("total_tokens") or 0),
        }

    async def stream(self, *, messages: list[dict], model: str, temperature: float | None,
                     max_tokens: int | None, require_json: bool) -> AsyncGenerator[dict, None]:
        payload = self._build_payload(messages, model, temperature, max_tokens, require_json, stream=True)
        async with httpx.AsyncClient(timeout=settings.timeout_millis / 1000) as client:
            async with client.stream(
                "POST",
                f"{settings.openai_base_url}/chat/completions",
                headers=self._headers(),
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
                        yield {"event": "delta", "delta": delta}
                    if choice.get("finish_reason"):
                        usage = payload.get("usage") or {}
                        yield {"event": "done", "tokenUsed": int(usage.get("total_tokens") or 0)}

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

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if settings.openai_api_key:
            headers["Authorization"] = f"Bearer {settings.openai_api_key}"
        return headers
