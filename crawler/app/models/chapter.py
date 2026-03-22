from pydantic import BaseModel, Field


class ChapterRequest(BaseModel):
    platform: str
    bookUrl: str
    chapterCount: int = Field(ge=1, le=10)
    startChapterNo: int = Field(default=1, ge=1, le=10)
    timeoutSeconds: int | None = Field(default=None, ge=5, le=120)
    chapterFetchWorkers: int | None = Field(default=None, ge=1, le=8)


class ChapterItem(BaseModel):
    chapterNo: int
    chapterTitle: str
    content: str
