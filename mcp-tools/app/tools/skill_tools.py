from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class SkillLookupArgs(BaseModel):
    intent: str = Field(min_length=1)
    maxSkillChars: int = Field(default=1600, ge=200, le=4000)


async def skill_lookup(args: SkillLookupArgs, _client: BackendClient) -> Any:
    return {
        "selectedSkills": [],
        "intent": args.intent,
        "maxSkillChars": args.maxSkillChars,
    }


def register_skill_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="skill.lookup",
        description="Select runtime skill packs for a task intent.",
        args_model=SkillLookupArgs,
        handler=skill_lookup,
    ))
