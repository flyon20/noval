from pydantic import BaseModel


class RankRequest(BaseModel):
    platform: str
    channelCode: str
    boardCode: str


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
