from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class VectorSearchArgs(BaseModel):
    query: str = Field(min_length=1)
    bookId: int | None = Field(default=None, ge=1)
    platform: str | None = None
    sourceType: str | None = None
    analysisType: str | None = None
    limit: int = Field(default=5, ge=1, le=20)


async def vector_search(args: VectorSearchArgs, client: BackendClient) -> Any:
    return await client.post("/internal/knowledge/search", args.model_dump(exclude_none=True))


def register_knowledge_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="knowledge.vector_search",
        description="Search indexed knowledge evidence.",
        args_model=VectorSearchArgs,
        handler=vector_search,
    ))
