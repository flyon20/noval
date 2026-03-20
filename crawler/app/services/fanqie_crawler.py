from __future__ import annotations

from typing import List

from app.models.book import BookDetail
from app.models.chapter import ChapterItem
from app.models.rank import RankItem
from app.services.base_crawler import BaseCrawler


class FanqieCrawler(BaseCrawler):
    """Phase 3 baseline crawler.

    这里先提供稳定可联调的样例数据。
    后续可在不改接口契约的前提下替换为真实爬取逻辑。
    """

    def fetch_rank(self, category: str) -> List[RankItem]:
        return [
            RankItem(
                rankNo=1,
                bookName="番茄样例小说A",
                author="作者A",
                intro=f"{category} 榜单样例数据A",
                bookUrl="https://fanqienovel.com/page/demo-a",
                platformBookId=f"fanqie-{category}-1",
            ),
            RankItem(
                rankNo=2,
                bookName="番茄样例小说B",
                author="作者B",
                intro=f"{category} 榜单样例数据B",
                bookUrl="https://fanqienovel.com/page/demo-b",
                platformBookId=f"fanqie-{category}-2",
            ),
        ]

    def fetch_book(self, book_url: str) -> BookDetail:
        return BookDetail(
            bookName="番茄样例书籍详情",
            author="样例作者",
            intro="这是番茄书籍详情样例，后续替换为真实抓取。",
            bookUrl=book_url,
            platformBookId="fanqie-book-sample",
        )

    def fetch_chapters(self, book_url: str, chapter_count: int) -> List[ChapterItem]:
        chapters: List[ChapterItem] = []
        for i in range(1, chapter_count + 1):
            chapters.append(
                ChapterItem(
                    chapterNo=i,
                    chapterTitle=f"第{i}章 样例标题",
                    content=f"来自 {book_url} 的第{i}章样例内容。",
                )
            )
        return chapters

