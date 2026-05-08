from pydantic import BaseModel, Field, field_validator


class BookSearchRequest(BaseModel):
    platform: str
    keyword: str
    limit: int = Field(default=10, ge=1, le=20)
    timeoutSeconds: int | None = Field(default=None, ge=5, le=120)

    @field_validator("keyword")
    @classmethod
    def validate_keyword(cls, value: str) -> str:
        normalized = (value or "").strip()
        if not normalized:
            raise ValueError("keyword is required")
        return normalized


class BookSearchItem(BaseModel):
    bookName: str
    author: str
    intro: str
    bookUrl: str
    platformBookId: str