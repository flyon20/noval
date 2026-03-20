from pydantic import BaseModel, Field


class ChapterRequest(BaseModel):
    platform: str
    bookUrl: str
    chapterCount: int = Field(ge=1, le=10)


class ChapterItem(BaseModel):
    chapterNo: int
    chapterTitle: str
    content: str

