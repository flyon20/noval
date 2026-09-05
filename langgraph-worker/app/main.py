import asyncio
import time
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.api.analysis import router as analysis_router
from app.api.knowledge import research_agent
from app.api.knowledge import router as knowledge_router
from app.config import settings
from app.security import validate_internal_api_key_config


@asynccontextmanager
async def lifespan(_: FastAPI):
    validate_internal_api_key_config()
    try:
        yield
    finally:
        await research_agent.aclose()


app = FastAPI(title=settings.app_name, lifespan=lifespan)
app.include_router(analysis_router)
app.include_router(knowledge_router)

_READINESS_TTL_SECONDS = 5.0
_READINESS_CACHE: tuple[float, int, dict[str, Any]] | None = None
_READINESS_LOCK = asyncio.Lock()


def _configured_secret(value: str, *, minimum_length: int = 1) -> bool:
    normalized = str(value or "").strip()
    return len(normalized) >= minimum_length and not normalized.upper().startswith("CHANGE_ME")


def _configuration_readiness() -> tuple[bool, list[str]]:
    missing: list[str] = []
    if len(settings.internal_api_key.strip()) < 8:
        missing.append("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY")
    if len(settings.backend_internal_api_key.strip()) < 8:
        missing.append("AI_BACKEND_INTERNAL_API_KEY")
    if not settings.mcp_base_url.strip().lower().startswith(("http://", "https://")):
        missing.append("AI_MCP_BASE_URL")
    if not _configured_secret(settings.mcp_internal_api_key):
        missing.append("MCP_INTERNAL_API_KEY")
    if not _configured_secret(settings.mcp_call_signing_key, minimum_length=32):
        missing.append("MCP_CALL_SIGNING_KEY")
    return not missing, missing


async def _probe_mcp_readiness() -> tuple[bool, str | None]:
    url = f"{settings.mcp_base_url.rstrip('/')}/health"
    timeout_seconds = max(0.1, min(2.0, settings.mcp_timeout_millis / 1000))
    try:
        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            response = await client.get(url)
        if response.status_code != 200:
            try:
                payload = response.json()
            except ValueError:
                payload = {}
            reason = payload.get("reason") if isinstance(payload, dict) else None
            return False, str(reason or f"HTTP {response.status_code}")
        payload = response.json()
        if str(payload.get("status") or "").upper() != "UP":
            return False, str(payload.get("reason") or "MCP readiness is degraded")
        return True, None
    except (httpx.HTTPError, ValueError) as exc:
        return False, str(exc)


async def _worker_readiness() -> tuple[int, dict[str, Any]]:
    global _READINESS_CACHE

    now = time.monotonic()
    if _READINESS_CACHE is not None and _READINESS_CACHE[0] > now:
        return _READINESS_CACHE[1], _READINESS_CACHE[2]

    async with _READINESS_LOCK:
        now = time.monotonic()
        if _READINESS_CACHE is not None and _READINESS_CACHE[0] > now:
            return _READINESS_CACHE[1], _READINESS_CACHE[2]

        config_ready, missing = _configuration_readiness()
        checks: dict[str, Any] = {
            "configuration": {
                "status": "UP" if config_ready else "DOWN",
                "missing": missing,
            }
        }
        if config_ready:
            mcp_ready, reason = await _probe_mcp_readiness()
            checks["mcp"] = {
                "status": "UP" if mcp_ready else "DOWN",
                **({"reason": reason} if reason else {}),
            }
        else:
            mcp_ready = False
            checks["mcp"] = {"status": "SKIPPED", "reason": "configuration is incomplete"}

        ready = config_ready and mcp_ready
        status_code = 200 if ready else 503
        payload = {
            "code": status_code,
            "message": "success" if ready else "degraded",
            "data": {"status": "UP" if ready else "DEGRADED", "checks": checks},
        }
        _READINESS_CACHE = (time.monotonic() + _READINESS_TTL_SECONDS, status_code, payload)
        return status_code, payload


@app.get("/health")
async def health():
    status_code, payload = await _worker_readiness()
    return JSONResponse(status_code=status_code, content=payload)
