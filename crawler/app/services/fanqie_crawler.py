from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from typing import Any, List
from urllib.parse import urljoin

from app.config import settings
from app.models.book import BookDetail
from app.models.chapter import ChapterItem
from app.models.rank import BoardCatalogBoard, BoardCatalogChannel, RankItem
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
    CHANNEL_ALIAS = {
        "male-new": ("1", "1", "男频新书榜"),
        "male-read": ("1", "2", "男频阅读榜"),
        "female-new": ("0", "1", "女频新书榜"),
        "female-read": ("0", "2", "女频阅读榜"),
        "1": ("1", "1", "男频新书榜"),
        "2": ("1", "2", "男频阅读榜"),
    }
    CHANNEL_GROUPS = [
        ("male", "male-new"),
        ("male", "male-read"),
        ("female", "female-new"),
        ("female", "female-read"),
    ]

    def __init__(self,
                 http_client: HttpClient | None = None,
                 decoder: ConfuseFontDecoder | None = None,
                 timeout_seconds: int | None = None,
                 chapter_fetch_workers: int | None = None) -> None:
        self._http_client = http_client or HttpClient(timeout_seconds=timeout_seconds)
        self._decoder = decoder or ConfuseFontDecoder()
        self._chapter_fetch_workers = max(1, chapter_fetch_workers or settings.chapter_fetch_workers)

    def fetch_board_catalog(self) -> List[BoardCatalogChannel]:
        state = self._fetch_state(settings.fanqie_rank_url)
        rank_state = state.get("rank", {})
        channels: List[BoardCatalogChannel] = []
        rank_category_type_list = rank_state.get("rankCategoryTypeList") or {}
        for group_key, channel_code in self.CHANNEL_GROUPS:
            channel_alias = self.CHANNEL_ALIAS.get(channel_code)
            if channel_alias is None:
                continue
            _, _, channel_name = channel_alias
            boards = self._extract_rank_category_boards(rank_category_type_list.get(group_key))
            if not boards:
                continue
            channels.append(
                BoardCatalogChannel(
                    channelName=channel_name,
                    channelCode=channel_code,
                    boards=boards,
                )
            )
        if not channels:
            raise ValueError("board catalog parse failed")
        return channels

    def fetch_rank(self, category_or_channel_code: str, board_code: str | None = None) -> List[RankItem]:
        rank_url = self._resolve_rank_url(category_or_channel_code, board_code)
        state = self._fetch_state(rank_url)
        css = str(state.get("common", {}).get("css") or "")
        rank_state = state.get("rank", {})
        book_list = self._extract_rank_books(rank_state)
        if not book_list:
            locator = self._format_rank_locator(category_or_channel_code, board_code)
            raise ValueError(f"rank list is empty for {locator}")

        items: List[RankItem] = []
        for index, item in enumerate(book_list, start=1):
            book_id = str(item.get("bookId") or "")
            if not book_id:
                continue
            book_name = self._decode_text_if_needed(str(item.get("bookName") or ""), css)
            author = self._decode_text_if_needed(str(item.get("author") or ""), css)
            intro = self._decode_text_if_needed(str(item.get("abstract") or item.get("description") or ""), css)
            items.append(
                RankItem(
                    rankNo=int(item.get("currentPos") or item.get("rankPos") or index),
                    bookName=book_name,
                    author=author,
                    intro=intro,
                    bookUrl=f"{settings.fanqie_base_url}/page/{book_id}",
                    platformBookId=book_id,
                )
            )
        if not items:
            locator = self._format_rank_locator(category_or_channel_code, board_code)
            raise ValueError(f"rank list parse failed for {locator}")
        return items

    def fetch_book(self, book_url: str) -> BookDetail:
        normalized_url = self._normalize_book_url(book_url)
        state = self._fetch_state(normalized_url)
        css = str(state.get("common", {}).get("css") or "")
        page_state = state.get("page", {})
        book_id = str(page_state.get("bookId") or self._extract_path_id(normalized_url))
        book_name = self._decode_text_if_needed(str(page_state.get("bookName") or ""), css)
        if not book_name:
            raise ValueError("book detail parse failed")
        return BookDetail(
            bookName=book_name,
            author=self._decode_text_if_needed(str(page_state.get("author") or ""), css),
            intro=self._decode_text_if_needed(str(page_state.get("abstract") or page_state.get("description") or ""), css),
            bookUrl=normalized_url,
            platformBookId=book_id,
        )

    def fetch_chapters(self, book_url: str, chapter_count: int, start_chapter_no: int = 1) -> List[ChapterItem]:
        normalized_url = self._normalize_book_url(book_url)
        state = self._fetch_state(normalized_url)
        page_state = state.get("page", {})
        chapter_refs = self._extract_chapter_refs(page_state)
        if not chapter_refs:
            raise ValueError("chapter directory parse failed")

        safe_start_chapter_no = max(1, start_chapter_no)
        start_index = safe_start_chapter_no - 1
        selected_refs = chapter_refs[start_index:start_index + chapter_count]
        if not selected_refs:
            raise ValueError("chapter directory parse failed")
        if len(selected_refs) == 1 or self._chapter_fetch_workers == 1:
            return [
                self._fetch_single_chapter(chapter_no, item_id, title)
                for chapter_no, (item_id, title) in enumerate(selected_refs, start=safe_start_chapter_no)
            ]

        with ThreadPoolExecutor(max_workers=min(self._chapter_fetch_workers, len(selected_refs))) as executor:
            futures = [
                executor.submit(self._fetch_single_chapter, chapter_no, item_id, title)
                for chapter_no, (item_id, title) in enumerate(selected_refs, start=safe_start_chapter_no)
            ]
            return [future.result() for future in futures]

    def _resolve_rank_url(self, category_or_channel_code: str, board_code: str | None = None) -> str:
        normalized_value = (category_or_channel_code or "").strip()
        if board_code is None:
            if not normalized_value:
                return settings.fanqie_rank_url
            mapped = self.CATEGORY_ALIAS.get(normalized_value, normalized_value.lstrip("/"))
            if mapped.startswith("http://") or mapped.startswith("https://"):
                return mapped
            if mapped.startswith("rank/"):
                return f"{settings.fanqie_base_url}/{mapped}"
            if mapped.count("_") == 2:
                return f"{settings.fanqie_base_url}/rank/{mapped}"
            return settings.fanqie_rank_url

        normalized_board = (board_code or "").strip()
        if not normalized_value or not normalized_board:
            raise ValueError("channelCode and boardCode are required")
        gender_code, rank_type_code, _ = self._resolve_channel_alias(normalized_value)
        return f"{settings.fanqie_base_url}/rank/{gender_code}_{rank_type_code}_{normalized_board}"

    def _normalize_book_url(self, book_url: str) -> str:
        normalized = (book_url or "").strip()
        if normalized.startswith("http://") or normalized.startswith("https://"):
            return normalized
        if normalized.startswith("/page/"):
            return urljoin(settings.fanqie_base_url, normalized)
        if normalized.isdigit():
            return f"{settings.fanqie_base_url}/page/{normalized}"
        raise ValueError(f"unsupported book url: {book_url}")

    def _extract_chapter_refs(self, page_state: dict[str, Any]) -> List[tuple[str, str | None]]:
        chapter_refs: List[tuple[str, str | None]] = []
        seen_item_ids: set[str] = set()

        for volume in page_state.get("chapterListWithVolume") or []:
            if isinstance(volume, dict):
                chapter_items = volume.get("chapterList") or []
            elif isinstance(volume, list):
                chapter_items = volume
            else:
                continue
            for chapter in chapter_items:
                if not isinstance(chapter, dict):
                    continue
                item_id = str(chapter.get("itemId") or "").strip()
                if not item_id or item_id in seen_item_ids:
                    continue
                seen_item_ids.add(item_id)
                chapter_refs.append(
                    (
                        item_id,
                        str(
                            chapter.get("title")
                            or chapter.get("chapterTitle")
                            or chapter.get("itemTitle")
                            or ""
                        ).strip() or None,
                    )
                )

        for item_id in page_state.get("itemIds") or []:
            normalized_item_id = str(item_id).strip()
            if not normalized_item_id or normalized_item_id in seen_item_ids:
                continue
            seen_item_ids.add(normalized_item_id)
            chapter_refs.append((normalized_item_id, None))
        return chapter_refs

    def _fetch_single_chapter(self, chapter_no: int, item_id: str, fallback_title: str | None) -> ChapterItem:
        reader_url = f"{settings.fanqie_base_url}/reader/{item_id}"
        reader_state = self._fetch_state(reader_url)
        chapter_data = reader_state.get("reader", {}).get("chapterData", {})
        title = str(chapter_data.get("title") or fallback_title or f"Chapter {chapter_no}")
        content = html_to_text(str(chapter_data.get("content") or ""))
        if any(0xE000 <= ord(ch) <= 0xF8FF for ch in content):
            css = str(reader_state.get("common", {}).get("css") or "")
            content = self._decoder.decode(content, css)
        if not content:
            raise ValueError(f"chapter content parse failed for itemId: {item_id}")
        return ChapterItem(
            chapterNo=chapter_no,
            chapterTitle=title,
            content=content,
        )

    def _fetch_state(self, url: str) -> dict[str, Any]:
        html = self._http_client.get_text(url)
        return extract_initial_state(html)

    def _extract_rank_books(self, rank_state: dict[str, Any]) -> list[dict[str, Any]]:
        for key in ("bookList", "book_list", "readRankList", "newRankList"):
            book_list = rank_state.get(key) or []
            if book_list:
                return book_list

        for value in rank_state.values():
            if not isinstance(value, dict):
                continue
            for key in ("bookList", "book_list", "readRankList", "newRankList"):
                book_list = value.get(key) or []
                if book_list:
                    return book_list
        return []

    def _extract_rank_category_boards(self, board_list: Any) -> list[BoardCatalogBoard]:
        if not isinstance(board_list, list):
            return []

        boards: list[BoardCatalogBoard] = []
        for board in board_list:
            if not isinstance(board, dict):
                continue
            board_code = str(board.get("boardCode") or board.get("board_code") or board.get("id") or "").strip()
            board_name = str(board.get("boardName") or board.get("board_name") or board.get("name") or "").strip()
            if not board_code or not board_name:
                continue
            boards.append(BoardCatalogBoard(boardName=board_name, boardCode=board_code))
        return boards

    def _resolve_channel_alias(self, channel_code: str) -> tuple[str, str, str]:
        channel = self.CHANNEL_ALIAS.get(channel_code)
        if channel is None:
            raise ValueError(f"unsupported channelCode: {channel_code}")
        return channel

    def _format_rank_locator(self, category_or_channel_code: str, board_code: str | None = None) -> str:
        if board_code is None:
            return f"category: {category_or_channel_code}"
        return f"channelCode: {category_or_channel_code}, boardCode: {board_code}"

    def _extract_path_id(self, url: str) -> str:
        return url.rstrip("/").rsplit("/", maxsplit=1)[-1]

    def _decode_text_if_needed(self, raw_text: str, css: str) -> str:
        if not raw_text:
            return raw_text
        if any(0xE000 <= ord(ch) <= 0xF8FF for ch in raw_text):
            return self._decoder.decode(raw_text, css)
        return raw_text
