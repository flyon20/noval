from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field, field_validator, model_validator


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
    project_knowledge_qa = "project_knowledge_qa"
    foreshadowing_audit = "foreshadowing_audit"
    continuity_check = "continuity_check"


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


class RetrievalPlan(BaseModel):
    query: str
    intent: str = "project_knowledge_qa"
    entities: list[str] = Field(default_factory=list)
    chapterFrom: int | None = Field(default=None, ge=1)
    chapterTo: int | None = Field(default=None, ge=1)
    channels: list[str] = Field(default_factory=lambda: ["structured", "fulltext", "vector", "graph"])
    filters: dict[str, Any] = Field(default_factory=dict)
    weights: dict[str, float] = Field(default_factory=dict)
    limit: int = Field(default=10, ge=1, le=20)
    deep: bool = False
    graphBudgetMillis: int = Field(default=300, ge=1, le=300)
    timeoutMillis: int | None = Field(default=None, ge=1)
    rerankPolicy: str = "intent_aware"

    @field_validator("query", "intent", "rerankPolicy", mode="before")
    @classmethod
    def normalize_required_text(cls, value: Any) -> str:
        normalized = str(value).strip() if value is not None else ""
        if not normalized:
            raise ValueError("retrieval plan text fields must be non-empty")
        return normalized

    @field_validator("entities", mode="before")
    @classmethod
    def normalize_entities(cls, value: Any) -> list[str]:
        values = value if isinstance(value, list) else []
        normalized: list[str] = []
        seen: set[str] = set()
        for item in values:
            text = str(item).strip()
            key = text.casefold()
            if text and key not in seen:
                seen.add(key)
                normalized.append(text[:120])
            if len(normalized) >= 8:
                break
        return normalized

    @model_validator(mode="after")
    def validate_chapter_range(self) -> "RetrievalPlan":
        if self.chapterFrom is not None and self.chapterTo is not None and self.chapterFrom > self.chapterTo:
            raise ValueError("chapter range is invalid")
        return self


class ToolPlan(BaseModel):
    taskId: str
    taskType: TaskType
    tools: list[str] = Field(default_factory=list)
    required: bool = False
    reason: str | None = None
    retrievalPlan: RetrievalPlan | None = None


class RunToolIdentity(BaseModel):
    runId: str
    userId: str
    projectId: str | None = None
    route: str

    @field_validator("runId", "userId", mode="before")
    @classmethod
    def normalize_required_id(cls, value: Any) -> str:
        normalized = str(value).strip() if value is not None else ""
        if not normalized:
            raise ValueError("run and user ids must be non-empty")
        return normalized

    @field_validator("projectId", mode="before")
    @classmethod
    def normalize_optional_id(cls, value: Any) -> str | None:
        normalized = str(value).strip() if value is not None else ""
        return normalized or None

    @field_validator("route", mode="before")
    @classmethod
    def normalize_route(cls, value: Any) -> str:
        normalized = str(value).strip().strip("/\\").casefold() if value is not None else ""
        if not normalized:
            raise ValueError("route must be non-empty")
        return normalized

    @property
    def scope_key(self) -> tuple[str, str, str, str]:
        return self.runId, self.userId, self.projectId or "", self.route

    @property
    def dedupe_scope_key(self) -> tuple[str, str, str]:
        return self.runId, self.userId, self.projectId or ""


class ToolRun(BaseModel):
    name: str
    status: str
    toolset: str | None = None
    input: dict[str, Any] = Field(default_factory=dict)
    output: dict[str, Any] = Field(default_factory=dict)
    resultCount: int = 0
    errorType: str | None = None
    runId: str | None = None
    userId: str | None = None
    projectId: str | None = None
    route: str | None = None
    callId: str | None = None
    idempotencyId: str | None = None
    access: str | None = None
    executed: bool = True
    reused: bool = False
    joined: bool = False


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
