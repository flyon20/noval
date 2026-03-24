from __future__ import annotations

from typing import List

from app.models.book import BookDetail
from app.models.chapter import ChapterItem
from app.models.rank import RankItem


class BaseCrawler:
    def fetch_rank(self, category: str) -> List[RankItem]:
        raise NotImplementedError

    def fetch_book(self, book_url: str) -> BookDetail:
        raise NotImplementedError

    def fetch_chapters(self, book_url: str, chapter_count: int, start_chapter_no: int = 1) -> List[ChapterItem]:
        raise NotImplementedError
