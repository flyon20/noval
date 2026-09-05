from __future__ import annotations

from typing import Any, Literal, TypedDict

from pydantic import BaseModel, Field, field_validator

from app.models.agent_task import EvidencePack, TaskGraph, ToolPlan, ToolRun
from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.intents.domain_intents import IntentDecision


class SourcePolicy(BaseModel):
    freshness: Literal["latest", "time_window", "any"] = "latest"
    allowHistorical: bool = False
    timeWindowDays: int | None = None
    requireSnapshotTime: bool = True
    sourcePriority: list[str] = Field(default_factory=list)

    @field_validator("timeWindowDays")
    @classmethod
    def normalize_time_window(cls, value: int | None) -> int | None:
        if value is None:
            return None
        return max(1, min(int(value), 365))


class MemoryPolicy(BaseModel):
    useUserProfile: bool = False
    useProjectProfile: bool = True
    useThreadSummary: bool = True
    writeCandidates: bool = True


class ContextLayer(BaseModel):
    scope: Literal["system", "user", "project", "thread", "turn"]
    content: dict[str, Any] = Field(default_factory=dict)
    sourceIds: list[str] = Field(default_factory=list)


class ContextBundle(BaseModel):
    systemBaseline: ContextLayer | None = None
    userProfile: ContextLayer | None = None
    projectProfile: ContextLayer | None = None
    threadSummary: ContextLayer | None = None
    currentTurn: ContextLayer


class SupervisorDecision(BaseModel):
    status: Literal[
        "answerable",
        "needs_more_data",
        "needs_fresh_rank",
        "needs_book_selection",
        "needs_clarification",
        "out_of_scope",
    ]
    freshnessSatisfied: bool = True
    evidenceEnough: bool = True
    missingSlots: list[str] = Field(default_factory=list)
    requiredActions: list[str] = Field(default_factory=list)
    reason: str | None = None
    nextRoute: str | None = None


class MemoryCandidate(BaseModel):
    scope: Literal["project", "user", "thread", "discard"]
    type: Literal["fact", "preference", "constraint", "risk", "decision", "revision"]
    content: str
    confidence: float = 0.0
    factKey: str | None = None
    sourceTraceId: str | None = None
    reason: str | None = None

    @field_validator("confidence")
    @classmethod
    def clamp_confidence(cls, value: float) -> float:
        return max(0.0, min(1.0, float(value)))


class AgentRuntimeState(TypedDict, total=False):
    request: KnowledgeChatRequest
    context: ContextBundle
    intent: IntentDecision
    route: str
    sourcePolicy: SourcePolicy
    memoryPolicy: MemoryPolicy
    taskGraph: TaskGraph
    toolPlan: list[ToolPlan]
    toolRuns: list[ToolRun]
    evidence: EvidencePack
    supervisor: SupervisorDecision
    answerDraft: str
    memoryCandidates: list[MemoryCandidate]
    response: KnowledgeChatResponse
    trace: dict[str, Any]
