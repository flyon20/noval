from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from redis import asyncio as redis_async

from app.backend_client import BackendClient
from app.config import settings
from app.fastmcp_server import FastMcpRuntime, MCP_PATH
from app.registry import ToolRegistry
from app.security import security_configuration_ready
from app.tools import (
    register_book_tools,
    register_knowledge_tools,
    register_memory_tools,
    register_rank_tools,
    register_review_tools,
    register_skill_tools,
)
def build_registry() -> ToolRegistry:
    idempotency_redis = None
    if settings.redis_host:
        idempotency_redis = redis_async.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            db=settings.redis_db,
            socket_connect_timeout=1,
            socket_timeout=1,
            decode_responses=True,
        )
    registry = ToolRegistry(idempotency_redis=idempotency_redis)
    register_rank_tools(registry)
    register_book_tools(registry)
    register_knowledge_tools(registry)
    register_skill_tools(registry)
    register_memory_tools(registry)
    register_review_tools(registry)
    return registry


tool_registry = build_registry()
backend_client = BackendClient()
mcp_runtime = FastMcpRuntime(tool_registry, lambda: backend_client)


@asynccontextmanager
async def app_lifespan(_app: FastAPI):
    async with mcp_runtime.lifespan():
        yield


app = FastAPI(title=settings.app_name, lifespan=app_lifespan)
app.mount(MCP_PATH, mcp_runtime)


@app.get("/health", response_model=None)
async def health() -> Any:
    if not security_configuration_ready():
        return JSONResponse(
            status_code=503,
            content={"status": "DEGRADED", "reason": "MCP security or Redis idempotency is not configured"},
        )
    if not await tool_registry.idempotency_store_ready():
        return JSONResponse(
            status_code=503,
            content={"status": "DEGRADED", "reason": "Redis is unavailable"},
        )
    try:
        backend_ready = await backend_client.health()
    except Exception:
        backend_ready = False
    if not backend_ready:
        return JSONResponse(
            status_code=503,
            content={"status": "DEGRADED", "reason": "backend unavailable"},
        )
    return {"status": "UP"}
