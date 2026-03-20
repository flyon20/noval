from pydantic import BaseModel


class BookRequest(BaseModel):
    platform: str
    bookUrl: str


class BookDetail(BaseModel):
    bookName: str
    author: str
    intro: str
    bookUrl: str
    platformBookId: str

