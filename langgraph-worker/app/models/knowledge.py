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
    snapshotId: int | None = None
    snapshotTime: str | None = None
    channelCode: str | None = None
    boardCode: str | None = None
    channelName: str | None = None
    boardName: str | None = None
    chapterNo: int | None = None
    analysisType: str | None = None
    rankNo: int | None = None
    author: str | None = None
    category: str | None = None
    title: str | None = None
    preview: str | None = None
    material: str | None = Field(default=None, exclude=True)


class RankLookupResult(BaseModel):
    rankId: int | None = None
    snapshotId: int | None = None
    snapshotTime: str | None = None
    platform: str | None = None
    channelCode: str | None = None
    boardCode: str | None = None
    channelName: str | None = None
    boardName: str | None = None
    category: str | None = None
    rankNo: int | None = None
    bookId: int | None = None
    bookName: str | None = None
    author: str | None = None
    intro: str | None = None
    sourceLabel: str | None = None


class BookProfile(BaseModel):
    bookId: int | None = None
    platform: str | None = None
    platformBookId: str | None = None
    bookName: str | None = None
    author: str | None = None
    intro: str | None = None
    category: str | None = None
    bookUrl: str | None = None
    latestRankNo: int | None = None
    latestRankLabel: str | None = None


class ChapterMaterial(BaseModel):
    chapterId: int | None = None
    sourceRefId: int | None = None
    bookId: int | None = None
    bookName: str | None = None
    platform: str | None = None
    chapterNo: int | None = None
    title: str | None = None
    content: str | None = None
    preview: str | None = None


class AnalysisMaterial(BaseModel):
    analysisId: int | None = None
    sourceRefId: int | None = None
    bookId: int | None = None
    bookName: str | None = None
    platform: str | None = None
    analysisType: str | None = None
    title: str | None = None
    content: str | None = None
    summary: str | None = None
    preview: str | None = None


class BookResearchPack(BaseModel):
    book: BookProfile | None = None
    ranks: list[RankLookupResult] = Field(default_factory=list)
    chapters: list[ChapterMaterial] = Field(default_factory=list)
    analyses: list[AnalysisMaterial] = Field(default_factory=list)


class RankResearchPack(BaseModel):
    ranks: list[RankLookupResult] = Field(default_factory=list)
    books: list[BookProfile] = Field(default_factory=list)
    chapters: list[ChapterMaterial] = Field(default_factory=list)
    analyses: list[AnalysisMaterial] = Field(default_factory=list)


class KnowledgeChatRequest(BaseModel):
    question: str
    traceId: str | None = None
    conversationId: str | None = None
    projectId: int | None = None
    bookName: str | None = None
    bookId: int | None = None
    selectedCandidate: BookCandidate | None = None
    mode: str | None = "research"
    userId: int | None = None
    contextSummary: str | None = None
    history: list[dict[str, str]] = Field(default_factory=list)
    contextBundle: dict[str, Any] | None = None
    limits: dict[str, Any] = Field(default_factory=dict)


class KnowledgeChatResponse(BaseModel):
    status: str
    answer: str
    candidates: list[BookCandidate] = Field(default_factory=list)
    sources: list[KnowledgeSource] = Field(default_factory=list)
    actions: list[str] = Field(default_factory=list)
    resultJson: dict[str, Any] = Field(default_factory=dict)
