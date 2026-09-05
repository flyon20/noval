from __future__ import annotations

import re
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


_DATA_ACCESS_SQL_PATTERN = re.compile(
    r"(?:\bselect\b[\s\S]{0,160}\bfrom\b|"
    r"\binsert\b[\s\S]{0,80}\binto\b|"
    r"\bupdate\b[\s\S]{0,80}\bset\b|"
    r"\bdelete\b[\s\S]{0,80}\bfrom\b|"
    r"\b(?:drop|alter|create|truncate|grant|revoke)\b[\s\S]{0,80}\b(?:table|database|user|role)\b)",
    re.IGNORECASE,
)
_DATA_ACCESS_PATH_PATTERN = re.compile(
    r"(?:\.\.[/\\]|[A-Za-z]:[/\\]|(?:^|\s)/(?:etc|var|home|root|proc|sys|dev)(?:[/\\]|$))",
    re.IGNORECASE,
)
_DATA_ACCESS_REASON_CODE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")


def _safe_data_access_text(value: Any, *, field_name: str, max_length: int) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError(f"{field_name} must not be empty")
    if len(text) > max_length:
        raise ValueError(f"{field_name} must not exceed {max_length} characters")
    if "://" in text or _DATA_ACCESS_PATH_PATTERN.search(text):
        raise ValueError(f"{field_name} must not contain URLs or filesystem paths")
    if "--" in text or "/*" in text or "*/" in text or _DATA_ACCESS_SQL_PATTERN.search(text):
        raise ValueError(f"{field_name} must not contain executable SQL")
    return text


class Intent(str, Enum):
    market_scan = "market_scan"
    opening_strategy = "opening_strategy"
    book_breakdown = "book_breakdown"
    outline_building = "outline_building"
    chapter_outline = "chapter_outline"
    inspiration_expand = "inspiration_expand"
    character_design = "character_design"
    worldbuilding = "worldbuilding"
    revision_advice = "revision_advice"
    followup_context = "followup_context"
    mixed_creation_research = "mixed_creation_research"
    out_of_scope = "out_of_scope"


class AnswerBoundary(str, Enum):
    market_evidence = "market_evidence"
    market_evidence_plus_author_inference = "market_evidence_plus_author_inference"
    book_evidence_plus_craft_extraction = "book_evidence_plus_craft_extraction"
    creative_inference = "creative_inference"
    outline_generation = "outline_generation"
    needs_more_data = "needs_more_data"
    out_of_scope = "out_of_scope"


class MarketRequestLevel(str, Enum):
    LIST = "LIST"
    ANALYSIS = "ANALYSIS"
    FULL_BOARD = "FULL_BOARD"
    MIXED_CREATION = "MIXED_CREATION"


class MarketQuestionType(str, Enum):
    TAXONOMY_ABSENCE = "taxonomy_absence"
    TAXONOMY_CLASSIFICATION = "taxonomy_classification"
    DERIVATIVE_GENRE = "derivative_genre"


class IntentDataAccessTemporalScope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mode: Literal["CURRENT", "AS_OF", "RANGE", "LATEST_N_SNAPSHOTS"] = "CURRENT"
    asOfDate: str | None = Field(default=None, max_length=32)
    startDate: str | None = Field(default=None, max_length=32)
    endDate: str | None = Field(default=None, max_length=32)
    latestNSnapshots: int | None = Field(default=None, ge=1, le=12)


class IntentDataAccessFilter(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: Literal[
        "platform",
        "board",
        "category",
        "work_title",
        "author",
        "chapter_from",
        "chapter_to",
        "evidence_status",
    ]
    value: str | int | float | bool

    @field_validator("value", mode="before")
    @classmethod
    def validate_value(cls, value: Any) -> Any:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value
        return _safe_data_access_text(value, field_name="dataAccess filter value", max_length=256)


class IntentDataAccessProposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    datasetCapability: Literal[
        "market.rank",
        "market.history",
        "book.source",
        "project.knowledge",
        "project.continuity",
        "conversation.thread",
    ]
    purpose: Literal[
        "market_current_state",
        "market_taxonomy",
        "market_history",
        "creative_calibration",
        "book_analysis",
        "project_recall",
        "project_continuity",
        "followup_context",
    ]
    temporalScope: IntentDataAccessTemporalScope = Field(default_factory=IntentDataAccessTemporalScope)
    retrievalChannels: list[Literal["structured", "fulltext", "vector", "graph"]] = Field(
        default_factory=list,
        max_length=4,
    )
    evidenceTypes: list[Literal[
        "current_rank",
        "historical_snapshot",
        "book_source",
        "project_chapter",
        "project_structured_fact",
        "thread_context",
    ]] = Field(default_factory=list, max_length=8)
    filters: list[IntentDataAccessFilter] = Field(default_factory=list, max_length=12)
    limit: int = Field(default=20, ge=1, le=100)
    required: bool = True
    reasonCodes: list[str] = Field(default_factory=list, max_length=20)

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def validate_reason_codes(cls, value: Any) -> list[str]:
        if value is None:
            return []
        values = [value] if isinstance(value, str) else list(value)
        normalized: list[str] = []
        for item in values:
            code = str(item or "").strip()
            if not _DATA_ACCESS_REASON_CODE_PATTERN.fullmatch(code):
                raise ValueError("dataAccess reason codes must be bounded identifiers")
            if code not in normalized:
                normalized.append(code)
        return normalized


class IntentEntities(BaseModel):
    platform: str | None = None
    channel: str | None = None
    boardCode: str | None = None
    category: str | None = None
    bookName: str | None = None
    bookSearchQuery: str | None = Field(default=None, max_length=80)
    bookId: str | None = None
    author: str | None = None
    chapterScope: str | None = None
    targetAudience: str | None = None
    targetLength: str | None = None
    stylePreference: str | None = None
    constraints: list[str] = Field(default_factory=list)
    currentTopic: str | None = None
    currentPremise: str | None = None
    outlineStage: str | None = None
    marketRequestLevel: MarketRequestLevel | None = None
    marketQuestionType: MarketQuestionType | None = None
    rankLimit: int | None = None
    timeWindowDays: int | None = Field(default=None, ge=1, le=365)
    startDate: str | None = Field(default=None, max_length=32)
    endDate: str | None = Field(default=None, max_length=32)
    dataAccess: list[IntentDataAccessProposal] = Field(default_factory=list, max_length=12)


class ToolNeeds(BaseModel):
    needsRankData: bool = False
    needsBookResearch: bool = False
    needsVectorEvidence: bool = False
    needsCreativeGeneration: bool = False
    needsOutlineMemory: bool = False
    needsChapterEvidence: bool = False
    needsSkillPack: bool = False
    needsCandidateSelection: bool = False


class IntentDecision(BaseModel):
    schemaVersion: str = "intent-v2"
    primaryIntent: Intent
    subIntents: list[Intent] = Field(default_factory=list)
    confidence: float = 0.0
    entities: IntentEntities | dict[str, Any] = Field(default_factory=IntentEntities)
    toolNeeds: ToolNeeds = Field(default_factory=ToolNeeds)
    answerBoundary: AnswerBoundary = AnswerBoundary.needs_more_data
    routingNotes: list[str] = Field(default_factory=list)
    sourcePolicy: dict[str, Any] = Field(default_factory=dict)
    memoryPolicy: dict[str, Any] = Field(default_factory=dict)
    missingSlots: list[str] = Field(default_factory=list)

    def model_post_init(self, __context: Any) -> None:
        if isinstance(self.entities, IntentEntities):
            self.entities = {
                key: value
                for key, value in self.entities.model_dump(mode="json", exclude_none=True).items()
                if value not in (None, "", [])
            }
        else:
            self.entities = {key: value for key, value in self.entities.items() if value not in (None, "", [])}
        self.confidence = max(0.0, min(1.0, float(self.confidence)))


INTENT_VALUES = {intent.value for intent in Intent}
ANSWER_BOUNDARY_VALUES = {boundary.value for boundary in AnswerBoundary}
MARKET_REQUEST_LEVEL_VALUES = {level.value for level in MarketRequestLevel}
MARKET_QUESTION_TYPE_VALUES = {question_type.value for question_type in MarketQuestionType}
