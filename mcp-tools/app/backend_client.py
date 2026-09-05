from __future__ import annotations

import hashlib
import hmac
import time
import uuid
from contextvars import ContextVar, Token
from typing import Any

import httpx

from app.config import settings


_GOVERNANCE_CONTEXT: ContextVar[tuple[str, frozenset[str]] | None] = ContextVar(
    "backend_governance_context",
    default=None,
)


class BackendClient:
    def __init__(self, *, async_client_factory: Any | None = None) -> None:
        self._async_client_factory = async_client_factory or httpx.AsyncClient

    async def post(self, path: str, payload: dict[str, Any]) -> Any:
        headers = {"Content-Type": "application/json"}
        if settings.backend_internal_api_key:
            headers["X-Internal-Service-Token"] = settings.backend_internal_api_key
        timeout = httpx.Timeout(max(1, settings.backend_timeout_millis / 1000))
        async with self._async_client_factory(base_url=settings.backend_base_url, timeout=timeout) as client:
            response = await client.post(path, json=payload, headers=headers)
            response.raise_for_status()
            return response.json()

    def bind_governance_context(self, *, route: str, permissions: set[str]) -> Token:
        return _GOVERNANCE_CONTEXT.set((str(route or "").strip(), frozenset(permissions)))

    def reset_governance_context(self, token: Token) -> None:
        _GOVERNANCE_CONTEXT.reset(token)

    async def post_governed_rank_refresh(self, payload: dict[str, Any]) -> Any:
        context = _GOVERNANCE_CONTEXT.get()
        if context is None:
            raise RuntimeError("FastMCP governance context is required for rank refresh")
        route, permissions = context
        permission = next(
            (item for item in ("rank.refresh", "tools:write", "admin:*") if item in permissions),
            None,
        )
        if not route or permission is None:
            raise RuntimeError("FastMCP supervisor permission is required for rank refresh")
        signing_key = str(settings.mcp_backend_attestation_key or "").strip()
        if len(signing_key) < 32 or signing_key.upper().startswith("CHANGE_ME"):
            raise RuntimeError("MCP_BACKEND_ATTESTATION_KEY is required for rank refresh")

        request_payload = dict(payload)
        attestation: dict[str, Any] = {
            "tool": "rank.refresh",
            "route": route,
            "permission": permission,
            "userId": str(request_payload.get("userId") or ""),
            "projectId": str(request_payload.get("projectId") or ""),
            "timestamp": int(time.time()),
            "nonce": uuid.uuid4().hex,
        }
        attestation["signature"] = hmac.new(
            signing_key.encode("utf-8"),
            self._rank_refresh_canonical(request_payload, attestation).encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        request_payload["supervisorAttestation"] = attestation
        return await self.post("/internal/knowledge/rank/refresh", request_payload)

    def _rank_refresh_canonical(
        self,
        payload: dict[str, Any],
        attestation: dict[str, Any],
    ) -> str:
        fields = (
            ("tool", attestation.get("tool")),
            ("route", attestation.get("route")),
            ("permission", attestation.get("permission")),
            ("userId", attestation.get("userId")),
            ("projectId", attestation.get("projectId")),
            ("platform", payload.get("platform")),
            ("channelCode", payload.get("channelCode")),
            ("boardCode", payload.get("boardCode")),
            ("category", payload.get("category")),
            ("refreshMode", payload.get("refreshMode")),
            ("forceReason", payload.get("forceReason")),
            ("rankFetchCount", payload.get("rankFetchCount")),
            ("idempotencyKey", payload.get("idempotencyKey")),
            ("timestamp", attestation.get("timestamp")),
            ("nonce", attestation.get("nonce")),
        )
        parts: list[str] = []
        for name, raw_value in fields:
            encoded_name = name.encode("utf-8")
            if raw_value is None:
                encoded_value = None
                value_text = ""
            else:
                value_text = str(raw_value)
                encoded_value = value_text.encode("utf-8")
            value_length = -1 if encoded_value is None else len(encoded_value)
            parts.append(f"{len(encoded_name)}:{name}={value_length}:{value_text};")
        return "".join(parts)

    async def health(self) -> bool:
        headers: dict[str, str] = {}
        if settings.backend_internal_api_key:
            headers["X-Internal-Service-Token"] = settings.backend_internal_api_key
        timeout_seconds = max(0.1, min(2.0, settings.backend_timeout_millis / 1000))
        try:
            async with self._async_client_factory(
                base_url=settings.backend_base_url,
                timeout=httpx.Timeout(timeout_seconds),
            ) as client:
                response = await client.get("/api/system/health", headers=headers)
                response.raise_for_status()
                payload = response.json()
        except (httpx.HTTPError, ValueError, TypeError):
            return False
        data = payload.get("data") if isinstance(payload, dict) else None
        return (
            payload.get("code") == 200
            and isinstance(data, dict)
            and str(data.get("status") or "").upper() == "UP"
        )
