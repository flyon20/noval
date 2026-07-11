from __future__ import annotations

from typing import Any

from fastapi import Depends, FastAPI
from pydantic import BaseModel, Field

from app.backend_client import BackendClient
from app.config import settings
from app.registry import ToolRegistry
from app.security import verify_internal_token
from app.tools import (
    register_book_tools,
    register_knowledge_tools,
    register_memory_tools,
    register_rank_tools,
    register_review_tools,
    register_skill_tools,
)


class ToolCallRequest(BaseModel):
    name: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)
    route: str | None = None


def build_registry() -> ToolRegistry:
    registry = ToolRegistry()
    register_rank_tools(registry)
    register_book_tools(registry)
    register_knowledge_tools(registry)
    register_skill_tools(registry)
    register_memory_tools(registry)
    register_review_tools(registry)
    return registry


app = FastAPI(title=settings.app_name)
tool_registry = build_registry()
backend_client = BackendClient()


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "UP"}


@app.get("/mcp/tools", dependencies=[Depends(verify_internal_token)])
async def list_tools(toolset: str = "normal") -> dict[str, Any]:
    return {"tools": tool_registry.list_tools(toolset=toolset)}


@app.post("/mcp/call", dependencies=[Depends(verify_internal_token)])
async def call_tool(request: ToolCallRequest) -> dict[str, Any]:
    result = await tool_registry.call(
        name=request.name,
        arguments=request.arguments,
        backend_client=backend_client,
        route=request.route,
    )
    return {"name": request.name, "result": result}
