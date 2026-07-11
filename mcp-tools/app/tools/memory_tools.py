from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class ProjectContextArgs(BaseModel):
    userId: int = Field(ge=1)
    projectId: int = Field(ge=1)


class MemoryAdminListArgs(BaseModel):
    userId: int | None = Field(default=None, ge=1)
    projectId: int | None = Field(default=None, ge=1)
    status: str | None = None
    limit: int = Field(default=50, ge=1, le=100)


async def project_context(args: ProjectContextArgs, client: BackendClient) -> Any:
    return await client.post(f"/internal/knowledge/projects/{args.projectId}/memory", {"userId": args.userId})


async def memory_admin_list(args: MemoryAdminListArgs, _client: BackendClient) -> Any:
    return {"filters": args.model_dump(exclude_none=True), "items": []}


def register_memory_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="memory.project_context",
        description="Read project-scoped memory for the current user.",
        args_model=ProjectContextArgs,
        handler=project_context,
    ))
    registry.register(ToolDefinition(
        name="memory.admin.list",
        description="Admin-only memory listing.",
        args_model=MemoryAdminListArgs,
        handler=memory_admin_list,
        admin=True,
    ))
