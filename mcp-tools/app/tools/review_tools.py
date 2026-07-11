from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class ReviewArgs(BaseModel):
    question: str = Field(min_length=1)
    draft: str | None = None


async def reader_feedback(args: ReviewArgs, _client: BackendClient) -> Any:
    return {
        "perspective": "reader",
        "signals": ["opening hook clarity", "early goal pressure"],
        "questionPreview": args.question[:160],
    }


async def editor_risk(args: ReviewArgs, _client: BackendClient) -> Any:
    return {
        "perspective": "editor",
        "risks": ["unsupported factual claim", "unclear genre promise"],
        "questionPreview": args.question[:160],
    }


def register_review_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="reader.simulate_feedback",
        description="Simulate reader-facing risk signals.",
        args_model=ReviewArgs,
        handler=reader_feedback,
    ))
    registry.register(ToolDefinition(
        name="editor.risk_check",
        description="Check draft for editor-facing risks.",
        args_model=ReviewArgs,
        handler=editor_risk,
    ))
