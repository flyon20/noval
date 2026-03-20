from pydantic import BaseModel


class RankRequest(BaseModel):
    platform: str
    category: str


class RankItem(BaseModel):
    rankNo: int
    bookName: str
    author: str
    intro: str
    bookUrl: str
    platformBookId: str

