from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class TaskType(str, Enum):
    market_scan = "market_scan"
    book_breakdown = "book_breakdown"
    topic_strategy = "topic_strategy"
    outline_building = "outline_building"
    chapter_outline = "chapter_outline"
    character_design = "character_design"
    worldbuilding = "worldbuilding"
    revision_advice = "revision_advice"
    reader_risk = "reader_risk"
    editor_risk = "editor_risk"
    skill_governance = "skill_governance"
    followup_context = "followup_context"


class Perspective(str, Enum):
    market = "market"
    book = "book"
    editor = "editor"
    author = "author"
    reader = "reader"
    supervisor = "supervisor"


class TaskNode(BaseModel):
    id: str
    type: TaskType
    goal: str
    perspective: Perspective
    tools: list[str] = Field(default_factory=list)
    dependsOn: list[str] = Field(default_factory=list)
    evidencePolicy: str = "creative_inference"
    freshnessPolicy: dict[str, Any] = Field(default_factory=dict)
    memoryPolicy: dict[str, Any] = Field(default_factory=dict)
    output: dict[str, Any] = Field(default_factory=dict)


class TaskGraph(BaseModel):
    schemaVersion: str = "webnovel-task-graph-v1"
    userGoal: str
    tasks: list[TaskNode] = Field(default_factory=list)
    answerBoundary: str = "creative_inference"
    adminOperationRequested: bool = False
    projectMemoryPolicy: str = "project_scoped"


class ToolPlan(BaseModel):
    taskId: str
    taskType: TaskType
    tools: list[str] = Field(default_factory=list)
    required: bool = False
    reason: str | None = None


class ToolRun(BaseModel):
    name: str
    status: str
    toolset: str | None = None
    input: dict[str, Any] = Field(default_factory=dict)
    output: dict[str, Any] = Field(default_factory=dict)
    resultCount: int = 0
    errorType: str | None = None


class PerspectiveResult(BaseModel):
    taskType: TaskType
    perspective: Perspective
    summary: str
    evidenceRefs: list[str] = Field(default_factory=list)


class EvidencePack(BaseModel):
    facts: list[dict[str, Any]] = Field(default_factory=list)
    examples: list[dict[str, Any]] = Field(default_factory=list)
    signals: list[dict[str, Any]] = Field(default_factory=list)
    inferenceSeeds: list[dict[str, Any]] = Field(default_factory=list)

    def summary(self, *, max_items: int = 3) -> dict[str, Any]:
        return {
            "factCount": len(self.facts),
            "exampleCount": len(self.examples),
            "signalCount": len(self.signals),
            "inferenceSeedCount": len(self.inferenceSeeds),
            "facts": self.facts[:max_items],
            "examples": self.examples[:max_items],
            "signals": self.signals[:max_items],
        }

    def to_perspective_result(
        self,
        *,
        task_type: TaskType,
        perspective: Perspective,
        summary: str,
        evidence_refs: list[str] | None = None,
    ) -> PerspectiveResult:
        return PerspectiveResult(
            taskType=task_type,
            perspective=perspective,
            summary=summary,
            evidenceRefs=list(evidence_refs or []),
        )
