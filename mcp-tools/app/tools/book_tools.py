from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class BookSearchArgs(BaseModel):
    platform: str = Field(min_length=1)
    keyword: str = Field(min_length=1)
    candidateType: Literal["novel", "audio", "video"] = "novel"
    limit: int = Field(default=5, ge=1, le=20)


async def book_search(args: BookSearchArgs, client: BackendClient) -> Any:
    payload = args.model_dump(exclude_none=True)
    payload.pop("candidateType", None)
    result = await client.post("/internal/knowledge/books/search", payload)
    return {"candidateType": args.candidateType, "items": result}


def register_book_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="book.search",
        description="Search local and crawler book candidates.",
        args_model=BookSearchArgs,
        handler=book_search,
    ))
