from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from typing import Any, List
from urllib.parse import quote, urljoin, urlparse

from app.config import settings
from app.models.book import BookDetail
from app.models.chapter import ChapterItem
from app.models.rank import BoardCatalogBoard, BoardCatalogChannel, RankItem
from app.models.search import BookSearchItem
from app.services.base_crawler import BaseCrawler
from app.utils.confuse_font_decoder import ConfuseFontDecoder
from app.utils.http_client import HttpClient
from app.utils.parsers import extract_initial_state, html_to_text


class FanqieCrawler(BaseCrawler):
    RANK_API_URL = f"{settings.fanqie_base_url}/api/rank/category/list"
    SEARCH_API_URL = f"{settings.fanqie_base_url}/api/author/search/search_book/v1"
    MOBILE_SEARCH_API_URL = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/"
    MOBILE_SEARCH_MAX_ATTEMPTS = 2
    RANK_API_PAGE_SIZE = 10
    RANK_API_BASE_PARAMS = {
        "app_id": "2503",
        "rank_list_type": "3",
        "rank_version": "",
    }
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
                 chapter_fetch_workers: int | None = None,
                 browser_search_client: Any | None = None) -> None:
        self._http_client = http_client or HttpClient(timeout_seconds=timeout_seconds)
        self._decoder = decoder or ConfuseFontDecoder()
        self._chapter_fetch_workers = max(1, chapter_fetch_workers or settings.chapter_fetch_workers)
        self._browser_search_client = browser_search_client

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

    def fetch_rank(
        self,
        category_or_channel_code: str,
        board_code: str | None = None,
        rank_fetch_count: int | None = None,
    ) -> List[RankItem]:
        rank_url = self._resolve_rank_url(category_or_channel_code, board_code)
        state = self._fetch_state(rank_url)
        css = str(state.get("common", {}).get("css") or "")
        rank_state = state.get("rank", {})
        book_list = self._fetch_rank_books_via_api(rank_url, rank_fetch_count) or self._extract_rank_books(rank_state)
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
        if rank_fetch_count is not None and rank_fetch_count > 0:
            return items[:rank_fetch_count]
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
        candidate_refs = [
            (chapter_no, item_id, title)
            for chapter_no, (item_id, title) in enumerate(chapter_refs[start_index:], start=safe_start_chapter_no)
        ]
        if not candidate_refs:
            raise ValueError("chapter directory parse failed")
        if len(candidate_refs) == 1 or self._chapter_fetch_workers == 1:
            return self._fetch_available_chapters(candidate_refs, chapter_count)

        with ThreadPoolExecutor(max_workers=min(self._chapter_fetch_workers, len(candidate_refs))) as executor:
            chapters: List[ChapterItem] = []
            cursor = 0
            while cursor < len(candidate_refs) and len(chapters) < chapter_count:
                remaining = chapter_count - len(chapters)
                batch_refs = candidate_refs[cursor:cursor + remaining]
                futures = [
                    executor.submit(self._fetch_single_chapter, chapter_no, item_id, title)
                    for chapter_no, item_id, title in batch_refs
                ]
                chapters.extend(self._collect_available_chapters(futures, remaining, raise_if_empty=False))
                cursor += len(batch_refs)
            if not chapters:
                raise ValueError("chapter content parse failed")
            return chapters

    def search_books(self, keyword: str, limit: int = 10) -> List[BookSearchItem]:
        normalized_keyword = (keyword or "").strip()
        if not normalized_keyword:
            raise ValueError("keyword is required")
        safe_limit = max(1, min(limit or 10, 20))
        search_url = f"{settings.fanqie_base_url}/search/{quote(normalized_keyword)}"
        state = self._fetch_state(search_url)
        css = str(state.get("common", {}).get("css") or "")
        raw_books = self._extract_search_books(state)
        web_api_blocked = False
        if not raw_books:
            try:
                raw_books = self._fetch_search_books_via_api(normalized_keyword, safe_limit)
            except ValueError as ex:
                if "anti-bot verification" not in str(ex):
                    raise
                web_api_blocked = True
        if not raw_books:
            raw_books = self._fetch_search_books_via_mobile_api(normalized_keyword, safe_limit)
        if not raw_books:
            raw_books, browser_css = self._fetch_search_books_via_browser(normalized_keyword, safe_limit)
            css = browser_css or css
        if not raw_books:
            if web_api_blocked:
                raise ValueError("fanqie search blocked by anti-bot verification and fallback returned empty")
            raise ValueError("book search result is empty")

        return self._normalize_search_items(raw_books, css, safe_limit)

    def _normalize_search_items(
        self,
        raw_books: list[dict[str, Any]],
        css: str,
        safe_limit: int,
    ) -> List[BookSearchItem]:
        items: List[BookSearchItem] = []
        seen_book_ids: set[str] = set()
        for item in raw_books:
            if not isinstance(item, dict):
                continue
            book_id = str(item.get("bookId") or item.get("book_id") or item.get("id") or "").strip()
            if not book_id or book_id in seen_book_ids:
                continue
            book_name = self._decode_text_if_needed(
                str(item.get("bookName") or item.get("book_name") or item.get("title") or ""), css
            )
            if not book_name:
                continue
            seen_book_ids.add(book_id)
            author = self._decode_text_if_needed(
                str(item.get("author") or item.get("authorName") or item.get("author_name") or ""), css
            )
            intro = self._decode_text_if_needed(
                str(
                    item.get("abstract")
                    or item.get("book_abstract")
                    or item.get("description")
                    or item.get("intro")
                    or ""
                ),
                css,
            )
            items.append(
                BookSearchItem(
                    bookName=book_name,
                    author=author,
                    intro=intro,
                    bookUrl=f"{settings.fanqie_base_url}/page/{book_id}",
                    platformBookId=book_id,
                )
            )
            if len(items) >= safe_limit:
                break
        if not items:
            raise ValueError("book search result parse failed")
        return items

    def _fetch_search_books_via_api(self, keyword: str, limit: int) -> list[dict[str, Any]]:
        try:
            payload = self._http_client.get_json(
                self.SEARCH_API_URL,
                params={
                    "filter": "127,127,127,127",
                    "page_count": str(limit),
                    "page_index": "0",
                    "query_type": "0",
                    "query_word": keyword,
                },
            )
        except ValueError as ex:
            if "anti-bot verification" in str(ex):
                raise
            return []
        except Exception:
            return []

        if not isinstance(payload, dict):
            return []
        response_code = payload.get("code")
        if response_code not in (None, 0, "0"):
            return []
        data = payload.get("data") or {}
        if not isinstance(data, dict):
            return []
        books = data.get("search_book_data_list") or data.get("searchBookDataList") or []
        return books if isinstance(books, list) else []

    def _fetch_search_books_via_mobile_api(self, keyword: str, limit: int) -> list[dict[str, Any]]:
        for _ in range(self.MOBILE_SEARCH_MAX_ATTEMPTS):
            try:
                payload = self._http_client.get_json(
                    self.MOBILE_SEARCH_API_URL,
                    params={
                        "device_platform": "android",
                        "parent_enterfrom": "novel_channel_search.tab.",
                        "offset": "0",
                        "aid": "1967",
                        "q": keyword,
                    },
                    headers={
                        "User-Agent": (
                            "Mozilla/5.0 (Linux; Android 10; TAS-AN00 Build/HUAWEITAS-AN00; wv) "
                            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
                            "Chrome/114.0.5735.61 Mobile Safari/537.36 Super 4.6.5"
                        ),
                        "Referer": "https://fanqienovel.com/",
                    },
                )
            except Exception:
                continue

            if not isinstance(payload, dict):
                continue
            response_code = payload.get("code")
            if response_code not in (None, 0, "0"):
                continue
            data = payload.get("data") or {}
            if not isinstance(data, dict):
                continue
            books = data.get("ret_data") or data.get("search_book_data_list") or data.get("searchBookDataList") or []
            if isinstance(books, list) and books:
                return books[:limit]
        return []

    def _fetch_search_books_via_browser(self, keyword: str, limit: int) -> tuple[list[dict[str, Any]], str]:
        if self._browser_search_client is None:
            return [], ""
        browser_search_client = self._browser_search_client
        try:
            return browser_search_client.search(keyword, limit, self._http_client.timeout_seconds)
        except ValueError as ex:
            if "browser search" in str(ex):
                raise ValueError(
                    "fanqie search blocked by anti-bot verification; "
                    f"browser fallback failed: {ex}"
                ) from ex
            raise

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
        source_word_count = self._parse_positive_int(chapter_data.get("chapterWordNumber")) or len(
            content.replace("\r", "").replace("\n", "")
        )
        return ChapterItem(
            chapterNo=chapter_no,
            chapterTitle=title,
            content=content,
            sourceWordCount=source_word_count,
        )

    def _fetch_available_chapters(
        self,
        candidate_refs: List[tuple[int, str, str | None]],
        chapter_count: int,
    ) -> List[ChapterItem]:
        chapters: List[ChapterItem] = []
        for chapter_no, item_id, title in candidate_refs:
            try:
                chapters.append(self._fetch_single_chapter(chapter_no, item_id, title))
            except Exception:
                continue
            if len(chapters) >= chapter_count:
                return chapters
        if not chapters:
            raise ValueError("chapter content parse failed")
        return chapters

    def _collect_available_chapters(
        self,
        futures: List[Any],
        chapter_count: int,
        *,
        raise_if_empty: bool = True,
    ) -> List[ChapterItem]:
        chapters: List[ChapterItem] = []
        for future in futures:
            try:
                chapters.append(future.result())
            except Exception:
                continue
            if len(chapters) >= chapter_count:
                return chapters
        if not chapters and raise_if_empty:
            raise ValueError("chapter content parse failed")
        return chapters

    def _fetch_state(self, url: str) -> dict[str, Any]:
        html = self._http_client.get_text(url)
        return extract_initial_state(html)

    def _extract_search_books(self, state: dict[str, Any]) -> list[dict[str, Any]]:
        for container_key in ("search", "searchResult", "bookSearch", "page"):
            container = state.get(container_key)
            books = self._find_book_list(container)
            if books:
                return books
        return self._find_book_list(state)

    def _find_book_list(self, value: Any) -> list[dict[str, Any]]:
        if isinstance(value, list):
            dict_items = [item for item in value if isinstance(item, dict)]
            if dict_items and any(self._looks_like_book(item) for item in dict_items):
                return dict_items
            for item in dict_items:
                nested = self._find_book_list(item)
                if nested:
                    return nested
            return []
        if not isinstance(value, dict):
            return []
        for key in ("bookList", "book_list", "books", "data", "items", "list", "searchBookList"):
            nested = value.get(key)
            books = self._find_book_list(nested)
            if books:
                return books
        for nested in value.values():
            books = self._find_book_list(nested)
            if books:
                return books
        return []

    def _looks_like_book(self, item: dict[str, Any]) -> bool:
        has_id = any(item.get(key) for key in ("bookId", "book_id", "id"))
        has_name = any(item.get(key) for key in ("bookName", "book_name", "title"))
        return has_id and has_name

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

    def _fetch_rank_books_via_api(self, rank_url: str, rank_fetch_count: int | None = None) -> list[dict[str, Any]]:
        params = self._build_rank_api_params(rank_url)
        if params is None:
            return []

        books: list[dict[str, Any]] = []
        seen_keys: set[str] = set()
        offset = 0
        total_num: int | None = None
        target_count = rank_fetch_count if rank_fetch_count is not None and rank_fetch_count > 0 else None

        try:
            while True:
                payload = self._http_client.get_json(
                    self.RANK_API_URL,
                    params={
                        **params,
                        "offset": str(offset),
                        "limit": str(self.RANK_API_PAGE_SIZE),
                    },
                )
                if not isinstance(payload, dict):
                    return []
                response_code = payload.get("code")
                if response_code not in (None, 0, "0"):
                    return []

                data = payload.get("data") or {}
                if not isinstance(data, dict):
                    return []
                page_books = data.get("book_list") or data.get("bookList") or []
                if not isinstance(page_books, list):
                    return []

                if total_num is None:
                    total_num = self._parse_positive_int(data.get("total_num"))

                appended_count = 0
                for book in page_books:
                    if not isinstance(book, dict):
                        continue
                    book_id = str(book.get("bookId") or "").strip()
                    rank_no = str(book.get("currentPos") or book.get("rankPos") or "").strip()
                    dedupe_key = book_id or rank_no
                    if not dedupe_key or dedupe_key in seen_keys:
                        continue
                    seen_keys.add(dedupe_key)
                    books.append(book)
                    appended_count += 1
                    if target_count is not None and len(books) >= target_count:
                        return books[:target_count]

                if total_num is not None and len(books) >= total_num:
                    if target_count is not None:
                        return books[:min(total_num, target_count)]
                    return books[:total_num]
                if len(page_books) < self.RANK_API_PAGE_SIZE or appended_count == 0:
                    if target_count is not None:
                        return books[:target_count]
                    return books

                offset += self.RANK_API_PAGE_SIZE
        except Exception:
            return []

    def _build_rank_api_params(self, rank_url: str) -> dict[str, str] | None:
        rank_slug = urlparse(rank_url).path.rstrip("/").rsplit("/", maxsplit=1)[-1]
        gender_code, rank_mold, category_id = (rank_slug.split("_") + ["", "", ""])[:3]
        if not gender_code or not rank_mold or not category_id:
            return None
        if not (gender_code.isdigit() and rank_mold.isdigit() and category_id.isdigit()):
            return None
        return {
            **self.RANK_API_BASE_PARAMS,
            "category_id": category_id,
            "gender": gender_code,
            "rankMold": rank_mold,
        }

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

    def _parse_positive_int(self, value: Any) -> int | None:
        if value is None:
            return None
        try:
            parsed = int(str(value).strip())
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None
