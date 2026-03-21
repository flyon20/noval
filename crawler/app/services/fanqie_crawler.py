from __future__ import annotations

from typing import Any, List
from urllib.parse import urljoin

from app.config import settings
from app.models.book import BookDetail
from app.models.chapter import ChapterItem
from app.models.rank import RankItem
from app.services.base_crawler import BaseCrawler
from app.utils.confuse_font_decoder import ConfuseFontDecoder
from app.utils.http_client import HttpClient
from app.utils.parsers import extract_initial_state, html_to_text


class FanqieCrawler(BaseCrawler):
    CATEGORY_ALIAS = {
        "male-hot-a": "1_2_1141",
        "male-hot-b": "1_2_1140",
        "male-new-a": "1_1_1141",
    }

    def __init__(self,
                 http_client: HttpClient | None = None,
                 decoder: ConfuseFontDecoder | None = None) -> None:
        self._http_client = http_client or HttpClient()
        self._decoder = decoder or ConfuseFontDecoder()

    def fetch_rank(self, category: str) -> List[RankItem]:
        rank_url = self._resolve_rank_url(category)
        state = self._fetch_state(rank_url)
        rank_state = state.get("rank", {})
        book_list = rank_state.get("book_list") or rank_state.get("readRankList") or rank_state.get("newRankList") or []
        if not book_list:
            raise ValueError(f"rank list is empty for category: {category}")

        items: List[RankItem] = []
        for index, item in enumerate(book_list, start=1):
            book_id = str(item.get("bookId") or "")
            if not book_id:
                continue
            items.append(
                RankItem(
                    rankNo=int(item.get("currentPos") or item.get("rankPos") or index),
                    bookName=str(item.get("bookName") or ""),
                    author=str(item.get("author") or ""),
                    intro=str(item.get("abstract") or item.get("description") or ""),
                    bookUrl=f"{settings.fanqie_base_url}/page/{book_id}",
                    platformBookId=book_id,
                )
            )
        if not items:
            raise ValueError(f"rank list parse failed for category: {category}")
        return items

    def fetch_book(self, book_url: str) -> BookDetail:
        normalized_url = self._normalize_book_url(book_url)
        state = self._fetch_state(normalized_url)
        page_state = state.get("page", {})
        book_id = str(page_state.get("bookId") or self._extract_path_id(normalized_url))
        book_name = str(page_state.get("bookName") or "")
        if not book_name:
            raise ValueError("book detail parse failed")
        return BookDetail(
            bookName=book_name,
            author=str(page_state.get("author") or ""),
            intro=str(page_state.get("abstract") or page_state.get("description") or ""),
            bookUrl=normalized_url,
            platformBookId=book_id,
        )

    def fetch_chapters(self, book_url: str, chapter_count: int) -> List[ChapterItem]:
        normalized_url = self._normalize_book_url(book_url)
        state = self._fetch_state(normalized_url)
        page_state = state.get("page", {})
        chapter_ids = self._extract_chapter_ids(page_state)
        if not chapter_ids:
            raise ValueError("chapter directory parse failed")

        chapters: List[ChapterItem] = []
        for chapter_no, item_id in enumerate(chapter_ids[:chapter_count], start=1):
            reader_url = f"{settings.fanqie_base_url}/reader/{item_id}"
            reader_state = self._fetch_state(reader_url)
            chapter_data = reader_state.get("reader", {}).get("chapterData", {})
            title = str(chapter_data.get("title") or f"Chapter {chapter_no}")
            content = html_to_text(str(chapter_data.get("content") or ""))
            if any(0xE000 <= ord(ch) <= 0xF8FF for ch in content):
                css = str(reader_state.get("common", {}).get("css") or "")
                content = self._decoder.decode(content, css)
            if not content:
                raise ValueError(f"chapter content parse failed for itemId: {item_id}")
            chapters.append(
                ChapterItem(
                    chapterNo=chapter_no,
                    chapterTitle=title,
                    content=content,
                )
            )
        return chapters

    def _resolve_rank_url(self, category: str) -> str:
        normalized = (category or "").strip()
        if not normalized:
            return settings.fanqie_rank_url
        mapped = self.CATEGORY_ALIAS.get(normalized, normalized.lstrip("/"))
        if mapped.startswith("http://") or mapped.startswith("https://"):
            return mapped
        if mapped.startswith("rank/"):
            return f"{settings.fanqie_base_url}/{mapped}"
        if mapped.count("_") == 2:
            return f"{settings.fanqie_base_url}/rank/{mapped}"
        return settings.fanqie_rank_url

    def _normalize_book_url(self, book_url: str) -> str:
        normalized = (book_url or "").strip()
        if normalized.startswith("http://") or normalized.startswith("https://"):
            return normalized
        if normalized.startswith("/page/"):
            return urljoin(settings.fanqie_base_url, normalized)
        if normalized.isdigit():
            return f"{settings.fanqie_base_url}/page/{normalized}"
        raise ValueError(f"unsupported book url: {book_url}")

    def _extract_chapter_ids(self, page_state: dict[str, Any]) -> List[str]:
        item_ids = page_state.get("itemIds") or []
        if item_ids:
            return [str(item_id) for item_id in item_ids]

        chapter_ids: List[str] = []
        for volume in page_state.get("chapterListWithVolume") or []:
            for chapter in volume.get("chapterList") or []:
                item_id = chapter.get("itemId")
                if item_id:
                    chapter_ids.append(str(item_id))
        return chapter_ids

    def _fetch_state(self, url: str) -> dict[str, Any]:
        html = self._http_client.get_text(url)
        return extract_initial_state(html)

    def _extract_path_id(self, url: str) -> str:
        return url.rstrip("/").rsplit("/", maxsplit=1)[-1]
