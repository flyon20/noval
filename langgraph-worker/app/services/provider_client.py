from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncGenerator
from typing import Any

import httpx

from app.config import settings
from app.models.analysis import RunRequest


class OpenAICompatibleProviderClient:
    _MAX_TRANSPORT_ATTEMPTS = 3
    _RETRYABLE_ERRORS = (httpx.ConnectError, httpx.TimeoutException)

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
    ) -> AsyncGenerator[dict, None]:
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
                            item = json.loads(data)
                            choice = (item.get("choices") or [{}])[0]
                            delta = (choice.get("delta") or {}).get("content")
                            if delta:
                                yielded_chunk = True
                                yield {"event": "delta", "delta": delta}
                            if choice.get("finish_reason"):
                                yielded_chunk = True
                                usage = item.get("usage") or {}
                                yield {"event": "done", "tokenUsed": int(usage.get("total_tokens") or 0)}
                return
            except self._RETRYABLE_ERRORS:
                if yielded_chunk or attempt >= self._MAX_TRANSPORT_ATTEMPTS:
                    raise
                await asyncio.sleep(self._retry_backoff_seconds(attempt))

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
    ) -> dict:
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

        payload = {
            "inputs": {
                "content": self._render_dify_content(messages),
                "analysisType": self._analysis_type(request),
                "workflowId": workflow_id,
            },
            "response_mode": "blocking",
            "user": "novel-analyzer",
        }
        async with httpx.AsyncClient(timeout=self._resolve_timeout_seconds(timeout_millis)) as client:
            response = await client.post(
                f"{base_url.rstrip('/')}/workflows/run",
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {api_key}",
                },
                json=payload,
            )
            response.raise_for_status()
            body = response.json() or {}

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
    ) -> dict:
        payload: dict[str, Any] = {
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

    async def _invoke_with_retry(
        self,
        *,
        payload: dict,
        base_url: str | None,
        api_key: str | None,
        timeout_millis: int | None,
    ) -> dict:
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
        return (candidate or "").strip()

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
