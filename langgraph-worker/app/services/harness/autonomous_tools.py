from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ToolArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class SkillArguments(ToolArguments):
    skillId: str = Field(min_length=1, max_length=120)


class RankArguments(ToolArguments):
    platform: str = Field(default="fanqie", min_length=1, max_length=40)
    channelCode: str | None = Field(default=None, max_length=80)
    boardCode: str | None = Field(default=None, max_length=80)
    category: str | None = Field(default=None, max_length=100)
    rankNo: int | None = Field(default=None, ge=1, le=100)
    limit: int = Field(default=10, ge=1, le=60, description="Maximum total rows across all returned snapshots, not rows per snapshot.")
    snapshotStartDate: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$", description="Inclusive first calendar date; provide snapshotEndDate too.")
    snapshotEndDate: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$", description="Inclusive last calendar date. Omit dates and timeWindowDays for the latest stored snapshot.")
    timeWindowDays: int | None = Field(default=None, ge=1, le=365, description="Rolling window relative to the latest stored snapshot; use paired dates for a calendar period.")

    @model_validator(mode="after")
    def validate_period(self) -> "RankArguments":
        if (self.snapshotStartDate is None) != (self.snapshotEndDate is None):
            raise ValueError("snapshotStartDate and snapshotEndDate must be supplied together")
        if self.snapshotStartDate is not None and self.snapshotEndDate is not None:
            start, end = date.fromisoformat(self.snapshotStartDate), date.fromisoformat(self.snapshotEndDate)
            if not 0 <= (end - start).days < 365:
                raise ValueError("snapshot date range must be ordered and at most 365 days")
            if self.timeWindowDays is not None:
                raise ValueError("use either paired dates or timeWindowDays")
        return self


class RankResearchArguments(RankArguments):
    limit: int = Field(default=10, ge=1, le=30)
    chapterLimitPerBook: int = Field(default=1, ge=1, le=3)


class BookArguments(ToolArguments):
    platform: str = Field(default="fanqie", min_length=1, max_length=40)
    bookId: int | None = Field(default=None, gt=0)
    bookName: str | None = Field(default=None, min_length=1, max_length=200)
    chapterLimit: int = Field(default=3, ge=1, le=10)
    analysisLimit: int = Field(default=3, ge=1, le=10)

    @model_validator(mode="after")
    def require_book(self) -> "BookArguments":
        if self.bookId is None and not self.bookName:
            raise ValueError("bookId or bookName is required")
        return self


class SearchArguments(ToolArguments):
    query: str = Field(min_length=1, max_length=2000)
    bookId: int | None = Field(default=None, gt=0)
    platform: str | None = Field(default=None, max_length=40)
    analysisType: str | None = Field(default=None, max_length=80)
    sourceType: str | None = Field(default=None, max_length=80)
    limit: int = Field(default=5, ge=1, le=20)


class ResolveArguments(ToolArguments):
    query: str = Field(default="", max_length=500)


class ProjectQueryArguments(ToolArguments):
    query: str = Field(default="", max_length=2000)
    limit: int = Field(default=10, ge=1, le=20)


class ProjectRetrievalArguments(ProjectQueryArguments):
    query: str = Field(min_length=1, max_length=2000)
    chapterFrom: int | None = Field(default=None, ge=1)
    chapterTo: int | None = Field(default=None, ge=1)
    entities: list[str] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def validate_range(self) -> "ProjectRetrievalArguments":
        if self.chapterFrom is not None and self.chapterTo is not None and self.chapterFrom > self.chapterTo:
            raise ValueError("chapterFrom must not exceed chapterTo")
        if any(not item.strip() or len(item) > 120 for item in self.entities):
            raise ValueError("entities must contain short non-empty names")
        return self


class ForeshadowingArguments(ToolArguments):
    status: str | None = Field(default=None, max_length=40)
    limit: int = Field(default=10, ge=1, le=20)


@dataclass(frozen=True)
class AutonomousToolSpec:
    name: str
    description: str
    arguments: type[ToolArguments]

    @property
    def wire_name(self) -> str:
        return self.name.replace(".", "_")

    def schema(self) -> dict[str, Any]:
        return {"type": "function", "function": {
            "name": self.wire_name,
            "description": self.description,
            "parameters": self.arguments.model_json_schema(),
        }}


# These are adapters for existing read APIs, not model-defined capabilities.
AUTONOMOUS_TOOLS = (
    AutonomousToolSpec("skill.lookup", "Load the full instructions of one skill from the available catalog by exact skillId.", SkillArguments),
    AutonomousToolSpec("rank.lookup", "Read latest or historical ranking entries. Paired calendar dates select stored snapshots within that period; a range can return its earliest and latest days. Compare the same board and requested Top N at each requested time; inspect dates and coverage, and query narrower periods if necessary. An absent row is only absent from the returned sample.", RankArguments),
    AutonomousToolSpec("rank.research_pack", "Read a small ranking sample with chapter/analysis material for comparison.", RankResearchArguments),
    AutonomousToolSpec("book.research_pack", "Read an identified book's profile, available chapters and analyses; report missing material honestly.", BookArguments),
    AutonomousToolSpec("knowledge.vector_search", "Search accessible reference material. Change the query if results are insufficient; use project_retrieve for the user's selected manuscript.", SearchArguments),
    AutonomousToolSpec("project.resolve", "Resolve the selected project/work, or a unique owned work matching query. An ambiguous result requires user clarification; never guess an ID.", ResolveArguments),
    AutonomousToolSpec("project.retrieve", "Search the resolved work's manuscript, structured facts and story graph. Call project_resolve first. Optional chapter range narrows evidence.", ProjectRetrievalArguments),
    AutonomousToolSpec("project.foreshadowing.list", "List recognized foreshadowing records in the resolved work. Requires project_resolve; a bounded list is not a complete audit.", ForeshadowingArguments),
    AutonomousToolSpec("project.foreshadowing.aggregate", "Count recognized foreshadowing records in the resolved work. Requires project_resolve; counts do not prove all literary hints were recognized.", ToolArguments),
    AutonomousToolSpec("project.timeline_lookup", "Look up timeline records in the resolved work. Requires project_resolve.", ProjectQueryArguments),
    AutonomousToolSpec("project.character_state_lookup", "Look up character states in the resolved work. Requires project_resolve.", ProjectQueryArguments),
    AutonomousToolSpec("project.world_rule_lookup", "Look up world rules in the resolved work. Requires project_resolve.", ProjectQueryArguments),
)
