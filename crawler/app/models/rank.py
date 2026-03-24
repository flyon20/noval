from pydantic import BaseModel, Field, model_validator


class RankRequest(BaseModel):
    platform: str
    category: str | None = None
    channelCode: str | None = None
    boardCode: str | None = None
    rankFetchCount: int | None = Field(default=None, ge=10, le=100)
    timeoutSeconds: int | None = None

    @model_validator(mode="after")
    def validate_rank_locator(self) -> "RankRequest":
        has_category = bool((self.category or "").strip())
        has_channel_board = bool((self.channelCode or "").strip()) and bool((self.boardCode or "").strip())
        if not has_category and not has_channel_board:
            raise ValueError("either category or channelCode + boardCode is required")
        if self.rankFetchCount is not None and self.rankFetchCount % 10 != 0:
            raise ValueError("rankFetchCount must be between 10 and 100, in steps of 10")
        return self


class BoardCatalogBoard(BaseModel):
    boardName: str
    boardCode: str


class BoardCatalogChannel(BaseModel):
    channelName: str
    channelCode: str
    boards: list[BoardCatalogBoard]


class RankItem(BaseModel):
    rankNo: int
    bookName: str
    author: str
    intro: str
    bookUrl: str
    platformBookId: str
