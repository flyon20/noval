from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


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


class IntentEntities(BaseModel):
    platform: str | None = None
    channel: str | None = None
    boardCode: str | None = None
    category: str | None = None
    bookName: str | None = None
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
                for key, value in self.entities.model_dump(exclude_none=True).items()
                if value not in (None, "", [])
            }
        else:
            self.entities = {key: value for key, value in self.entities.items() if value not in (None, "", [])}
        self.confidence = max(0.0, min(1.0, float(self.confidence)))


INTENT_VALUES = {intent.value for intent in Intent}
ANSWER_BOUNDARY_VALUES = {boundary.value for boundary in AnswerBoundary}
