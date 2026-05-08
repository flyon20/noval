from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class BookCandidate(BaseModel):
    bookId: int | None = None
    platform: str | None = None
    platformBookId: str | None = None
    bookName: str
    author: str | None = None
    intro: str | None = None
    bookUrl: str | None = None
    local: bool = False


class KnowledgeSource(BaseModel):
    chunkId: int | None = None
    documentId: int | None = None
    score: float | None = None
    bookId: int | None = None
    bookName: str | None = None
    platform: str | None = None
    sourceType: str | None = None
    sourceRefId: int | None = None
    chapterNo: int | None = None
    analysisType: str | None = None
    title: str | None = None
    preview: str | None = None


class KnowledgeChatRequest(BaseModel):
    question: str
    bookName: str | None = None
    bookId: int | None = None
    selectedCandidate: BookCandidate | None = None
    mode: str | None = "research"
    userId: int | None = None
    contextSummary: str | None = None
    history: list[dict[str, str]] = Field(default_factory=list)
    limits: dict[str, Any] = Field(default_factory=dict)


class KnowledgeChatResponse(BaseModel):
    status: str
    answer: str
    candidates: list[BookCandidate] = Field(default_factory=list)
    sources: list[KnowledgeSource] = Field(default_factory=list)
    actions: list[str] = Field(default_factory=list)
    resultJson: dict[str, Any] = Field(default_factory=dict)
