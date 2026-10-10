from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field

from app.models.knowledge import KnowledgeSource


class EvidenceStatus(str, Enum):
    verified_latest = "verified_latest"
    degraded_directional = "degraded_directional"
    stale = "stale"
    missing = "missing"
    conflict = "conflict"


class EvidenceWarning(BaseModel):
    code: str
    message: str
    severity: str = "warning"


class EvidenceBoundary(BaseModel):
    factual: str = "selected_sources_only"
    inference: str = "creative_or_directional_only"


class SnapshotGroup(BaseModel):
    groupId: str
    platform: str | None = None
    channelCode: str | None = None
    boardCode: str | None = None
    category: str | None = None
    snapshotId: int | None = None
    snapshotTime: str | None = None
    sourceTool: str | None = None
    sourceCount: int = 0
    topRankCoverage: int = 0
    ranks: list[int] = Field(default_factory=list)
    bookIds: list[int] = Field(default_factory=list)
    snapshotComplete: bool = False
    categoryMatch: bool = True
    channelMatch: bool = True
    boardMatch: bool = True
    snapshotAgeDays: float | None = None
    freshness: str | None = None
    historicalReference: bool | None = None
    score: float = 0.0


class EvidenceContract(BaseModel):
    status: EvidenceStatus
    selectedSources: list[KnowledgeSource] = Field(default_factory=list)
    referenceSignals: list[dict[str, Any]] = Field(default_factory=list)
    warnings: list[EvidenceWarning] = Field(default_factory=list)
    selectedSnapshotGroup: SnapshotGroup | None = None
    rejectedGroups: list[SnapshotGroup] = Field(default_factory=list)
    requiredActions: list[str] = Field(default_factory=list)
    factualBoundary: str = "selected rank snapshot group only"
    inferenceBoundary: str = "non-selected rank groups are reference signals, not latest facts"
    boundary: EvidenceBoundary = Field(default_factory=EvidenceBoundary)
