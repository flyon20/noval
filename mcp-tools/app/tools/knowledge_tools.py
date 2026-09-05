from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class VectorSearchArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    userId: int = Field(ge=1)
    query: str = Field(min_length=1)
    bookId: int | None = Field(default=None, ge=1)
    platform: str | None = None
    sourceType: str | None = None
    analysisType: str | None = None
    limit: int = Field(default=5, ge=1, le=20)


class ProjectRetrievalArgs(BaseModel):
    userId: int = Field(ge=1)
    projectId: int = Field(ge=1)
    workId: int = Field(ge=1)
    query: str = Field(min_length=1, max_length=500)
    intent: str = Field(default="project_knowledge_qa", min_length=1, max_length=80)
    entities: list[str] = Field(default_factory=list, max_length=8)
    chapterFrom: int | None = Field(default=None, ge=1)
    chapterTo: int | None = Field(default=None, ge=1)
    channels: list[Literal["structured", "fulltext", "vector", "graph"]] = Field(
        default_factory=lambda: ["structured", "fulltext", "vector", "graph"],
        min_length=1,
        max_length=4,
    )
    filters: dict[str, Any] = Field(default_factory=dict)
    weights: dict[str, float] = Field(default_factory=dict)
    limit: int = Field(default=10, ge=1, le=20)
    deep: bool = False
    graphBudgetMillis: int = Field(default=300, ge=1, le=300)
    timeoutMillis: int | None = Field(default=None, ge=1, le=60000)
    rerankPolicy: Literal["intent_aware", "raw_score", "none"] = "intent_aware"

    @model_validator(mode="after")
    def validate_plan(self) -> "ProjectRetrievalArgs":
        if self.chapterFrom is not None and self.chapterTo is not None and self.chapterFrom > self.chapterTo:
            raise ValueError("chapter range is invalid")
        if any(key not in {"chapterFrom", "chapterTo"} for key in self.filters):
            raise ValueError("unsupported project retrieval filter")
        if any(key not in {"structured", "fulltext", "vector", "graph"} for key in self.weights):
            raise ValueError("unsupported project retrieval weight")
        if any(weight < 0.0 or weight > 1.0 for weight in self.weights.values()):
            raise ValueError("project retrieval weight must be between 0 and 1")
        return self


async def vector_search(args: VectorSearchArgs, client: BackendClient) -> Any:
    return await client.post("/internal/knowledge/search", args.model_dump(exclude_none=True))


async def retrieve_project(args: ProjectRetrievalArgs, client: BackendClient) -> Any:
    return await client.post(
        "/internal/knowledge/projects/retrieval",
        args.model_dump(exclude_none=True),
    )


def register_knowledge_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="knowledge.vector_search",
        description="Search indexed knowledge evidence.",
        args_model=VectorSearchArgs,
        handler=vector_search,
    ))
    registry.register(ToolDefinition(
        name="project.retrieve",
        description="Retrieve generation-scoped project evidence with a typed hybrid retrieval plan.",
        args_model=ProjectRetrievalArgs,
        handler=retrieve_project,
    ))
