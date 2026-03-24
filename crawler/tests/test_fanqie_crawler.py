from __future__ import annotations

import threading
import time
import unittest
from unittest.mock import patch

from app.api import rank as rank_api
from app.models.rank import RankRequest
from app.services.fanqie_crawler import FanqieCrawler
from app.utils.parsers import extract_initial_state, html_to_text


class StubHttpClient:
    def __init__(self, responses: dict[str, str]) -> None:
        self._responses = responses
        self.calls: list[str] = []
        self.timeout_seconds = 20

    def get_text(self, url: str) -> str:
        self.calls.append(url)
        if url not in self._responses:
            raise AssertionError(f"unexpected url: {url}")
        return self._responses[url]


class SlowTrackingHttpClient(StubHttpClient):
    def __init__(self, responses: dict[str, str], delay_seconds: float = 0.15) -> None:
        super().__init__(responses)
        self.delay_seconds = delay_seconds
        self.max_inflight = 0
        self._inflight = 0
        self._lock = threading.Lock()

    def get_text(self, url: str) -> str:
        self.calls.append(url)
        if url not in self._responses:
            raise AssertionError(f"unexpected url: {url}")
        with self._lock:
            self._inflight += 1
            self.max_inflight = max(self.max_inflight, self._inflight)
        try:
            time.sleep(self.delay_seconds)
            return self._responses[url]
        finally:
            with self._lock:
                self._inflight -= 1


class StubDecoder:
    def __init__(self, decoded_text: str | None = None, mapping: dict[str, str] | None = None) -> None:
        self.decoded_text = decoded_text
        self.mapping = mapping or {}
        self.called = False

    def decode(self, raw_text: str, css: str) -> str:
        self.called = True
        if self.decoded_text is not None:
            return self.decoded_text
        return self.mapping.get(raw_text, raw_text)


class StubRankCrawler:
    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    def fetch_rank(self, *args: str):
        self.calls.append(args)
        return [{"ok": True}]


class FanqieCrawlerTest(unittest.TestCase):

    def test_should_allow_runtime_overrides_for_timeout_and_workers(self) -> None:
        crawler = FanqieCrawler(
            decoder=StubDecoder(),
            timeout_seconds=45,
            chapter_fetch_workers=4,
        )

        self.assertEqual(4, crawler._chapter_fetch_workers)
        self.assertEqual(45, crawler._http_client.timeout_seconds)

    def test_extract_initial_state(self) -> None:
        html = '<script>(function(){window.__INITIAL_STATE__={"rank":{"book_list":[{"bookId":"1"}]}};})()</script>'
        state = extract_initial_state(html)
        self.assertEqual("1", state["rank"]["book_list"][0]["bookId"])

    def test_extract_initial_state_should_convert_undefined_to_null(self) -> None:
        html = (
            '<script>(function(){window.__INITIAL_STATE__='
            '{"page":{"bookId":"101","bookName":"Book A","description":undefined}};})()</script>'
        )

        state = extract_initial_state(html)

        self.assertEqual("101", state["page"]["bookId"])
        self.assertIsNone(state["page"]["description"])

    def test_html_to_text(self) -> None:
        content = html_to_text("<p>Hello</p><p>World</p>")
        self.assertEqual("Hello\nWorld", content)

    def test_fetch_board_catalog_returns_rank_category_channels_and_boards(self) -> None:
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/rank?enter_from=menu": (
                        '<script>(function(){window.__INITIAL_STATE__={"rank":{"rankCategoryTypeList":{'
                        '"male":[{"id":"262","name":"都市脑洞"},{"id":"1014","name":"都市高武"}],'
                        '"female":[{"id":"267","name":"现言脑洞"}]'
                        '}}};})()</script>'
                    )
                }
            )
        )

        result = crawler.fetch_board_catalog()

        self.assertEqual(4, len(result))
        self.assertEqual("male-new", result[0].channelCode)
        self.assertEqual("男频新书榜", result[0].channelName)
        self.assertEqual(2, len(result[0].boards))
        self.assertEqual("262", result[0].boards[0].boardCode)
        self.assertEqual("都市脑洞", result[0].boards[0].boardName)
        self.assertEqual("male-read", result[1].channelCode)
        self.assertEqual("女频新书榜", result[2].channelName)
        self.assertEqual("267", result[2].boards[0].boardCode)

    def test_rank_request_accepts_legacy_category_payload(self) -> None:
        request = RankRequest.model_validate({"platform": "fanqie", "category": "male-hot-a"})

        self.assertEqual("fanqie", request.platform)
        self.assertEqual("male-hot-a", request.category)

    def test_fetch_rank_supports_legacy_category_alias(self) -> None:
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/rank/1_2_1141": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"rank":{"bookList":['
                        '{"currentPos":1,"bookId":"101","bookName":"Book A","author":"Author A","abstract":"Intro A"}'
                        ']}};})()</script>'
                    )
                }
            )
        )

        result = crawler.fetch_rank("male-hot-a")

        self.assertEqual(1, len(result))
        self.assertEqual("Book A", result[0].bookName)
        self.assertEqual("101", result[0].platformBookId)

    def test_rank_api_accepts_legacy_category_payload(self) -> None:
        crawler = StubRankCrawler()
        request = RankRequest.model_validate({"platform": "fanqie", "category": "male-hot-a"})

        with patch("app.api.rank.build_crawler", return_value=crawler):
            response = rank_api.rank(request)

        self.assertEqual([("male-hot-a",)], crawler.calls)
        self.assertEqual([{"ok": True}], response.data)

    def test_fetch_rank_uses_channel_code_and_board_code(self) -> None:
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/rank/1_1_262": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"rank":{"bookList":['
                        '{"currentPos":1,"bookId":"101","bookName":"Book A","author":"Author A","abstract":"Intro A"},'
                        '{"currentPos":2,"bookId":"102","bookName":"Book B","author":"Author B","abstract":"Intro B"}'
                        ']}};})()</script>'
                    )
                }
            )
        )

        result = crawler.fetch_rank("male-new", "262")

        self.assertEqual(2, len(result))
        self.assertEqual(1, result[0].rankNo)
        self.assertEqual("Book A", result[0].bookName)
        self.assertEqual("Author A", result[0].author)
        self.assertEqual("Intro A", result[0].intro)
        self.assertEqual("https://fanqienovel.com/page/101", result[0].bookUrl)
        self.assertEqual("101", result[0].platformBookId)
        self.assertEqual(2, result[1].rankNo)

    def test_fetch_rank_should_decode_obfuscated_fields(self) -> None:
        raw_name = "\ue4e9\ue3ea\ue4f3\ue4e7"
        raw_author = "\ue478\ue4f3\ue4a2"
        raw_intro = "\ue3e9\ue421\ue4de\ue436"
        decoder = StubDecoder(
            mapping={
                raw_name: "Decoded Book",
                raw_author: "Decoded Author",
                raw_intro: "Decoded Intro",
            }
        )
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/rank/1_2_1141": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"rank":{"bookList":['
                        '{"currentPos":1,"bookId":"101","bookName":"'
                        + raw_name
                        + '","author":"'
                        + raw_author
                        + '","abstract":"'
                        + raw_intro
                        + '"}'
                        ']}};})()</script>'
                    )
                }
            ),
            decoder,
        )

        result = crawler.fetch_rank("2", "1141")

        self.assertTrue(decoder.called)
        self.assertEqual("Decoded Book", result[0].bookName)
        self.assertEqual("Decoded Author", result[0].author)
        self.assertEqual("Decoded Intro", result[0].intro)

    def test_fetch_book_and_chapters(self) -> None:
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/page/101": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"page":{"bookId":"101","bookName":"Book A",'
                        '"author":"Author A","abstract":"Intro A","itemIds":["c1","c2"]}};})()</script>'
                    ),
                    "https://fanqienovel.com/reader/c1": (
                        '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                        '"title":"Chapter 1","content":"<p>Line 1</p><p>Line 2</p>"}}};})()</script>'
                    ),
                    "https://fanqienovel.com/reader/c2": (
                        '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                        '"title":"Chapter 2","content":"<p>Line 3</p>"}}};})()</script>'
                    ),
                }
            )
        )

        book = crawler.fetch_book("https://fanqienovel.com/page/101")
        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 2)

        self.assertEqual("Book A", book.bookName)
        self.assertEqual("Intro A", book.intro)
        self.assertEqual(2, len(chapters))
        self.assertEqual("Chapter 1", chapters[0].chapterTitle)
        self.assertEqual("Line 1\nLine 2", chapters[0].content)

    def test_fetch_book_should_decode_obfuscated_fields(self) -> None:
        raw_name = "\ue4e9\ue3ea\ue4f3\ue4e7"
        raw_author = "\ue478\ue4f3\ue4a2"
        raw_intro = "\ue3e9\ue421\ue4de\ue436"
        decoder = StubDecoder(
            mapping={
                raw_name: "Decoded Book",
                raw_author: "Decoded Author",
                raw_intro: "Decoded Intro",
            }
        )
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/page/101": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"page":{"bookId":"101","bookName":"'
                        + raw_name
                        + '","author":"'
                        + raw_author
                        + '","abstract":"'
                        + raw_intro
                        + '","itemIds":["c1"]}};})()</script>'
                    ),
                }
            ),
            decoder,
        )

        book = crawler.fetch_book("https://fanqienovel.com/page/101")

        self.assertTrue(decoder.called)
        self.assertEqual("Decoded Book", book.bookName)
        self.assertEqual("Decoded Author", book.author)
        self.assertEqual("Decoded Intro", book.intro)

    def test_fetch_chapters_should_decode_obfuscated_text(self) -> None:
        decoder = StubDecoder("Decoded chapter content")
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/page/101": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"page":{"bookId":"101","bookName":"Book A",'
                        '"author":"Author A","abstract":"Intro A","itemIds":["c1"]}};})()</script>'
                    ),
                    "https://fanqienovel.com/reader/c1": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"reader":{"chapterData":{'
                        '"title":"Chapter 1","content":"<p>\ue4e9\ue3ea\ue4f3\ue4e7</p>"}}};})()</script>'
                    ),
                }
            ),
            decoder,
        )

        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 1)

        self.assertTrue(decoder.called)
        self.assertEqual("Decoded chapter content", chapters[0].content)
        self.assertFalse(any(0xE000 <= ord(ch) <= 0xF8FF for ch in chapters[0].content))

    def test_fetch_chapters_should_capture_source_word_count_from_reader_metadata(self) -> None:
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/page/101": (
                        '<script>(function(){window.__INITIAL_STATE__={"page":{"bookId":"101","bookName":"Book A",'
                        '"author":"Author A","abstract":"Intro A","itemIds":["c1"]}};})()</script>'
                    ),
                    "https://fanqienovel.com/reader/c1": (
                        '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                        '"title":"Chapter 1","chapterWordNumber":"2452","content":"<p>Line 1</p><p>Line 2</p>"}}};})()</script>'
                    ),
                }
            )
        )

        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 1)

        self.assertEqual(2452, chapters[0].sourceWordCount)

    def test_fetch_chapters_should_follow_catalog_order_instead_of_unsorted_item_ids(self) -> None:
        http_client = StubHttpClient(
            {
                "https://fanqienovel.com/page/101": (
                    '<script>(function(){window.__INITIAL_STATE__={"page":{"bookId":"101","bookName":"Book A",'
                    '"author":"Author A","abstract":"Intro A","itemIds":["c3","c1","c2","c4"],'
                    '"chapterListWithVolume":[{"chapterList":['
                    '{"itemId":"c1","title":"Chapter 1"},'
                    '{"itemId":"c2","title":"Chapter 2"},'
                    '{"itemId":"c3","title":"Chapter 3"},'
                    '{"itemId":"c4","title":"Chapter 4"}'
                    ']}]}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c1": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 1","content":"<p>Content 1</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c2": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 2","content":"<p>Content 2</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c3": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 3","content":"<p>Content 3</p>"}}};})()</script>'
                ),
            }
        )
        crawler = FanqieCrawler(http_client=http_client)

        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 3)

        self.assertEqual(["Chapter 1", "Chapter 2", "Chapter 3"], [item.chapterTitle for item in chapters])
        self.assertEqual(
            [
                "https://fanqienovel.com/page/101",
                "https://fanqienovel.com/reader/c1",
                "https://fanqienovel.com/reader/c2",
                "https://fanqienovel.com/reader/c3",
            ],
            http_client.calls,
        )

    def test_fetch_chapters_should_support_real_world_nested_volume_structure(self) -> None:
        http_client = StubHttpClient(
            {
                "https://fanqienovel.com/page/101": (
                    '<script>(function(){window.__INITIAL_STATE__={"page":{"bookId":"101","bookName":"Book A",'
                    '"author":"Author A","abstract":"Intro A","itemIds":["c5","c4","c3","c2","c1"],'
                    '"chapterListWithVolume":[['
                    '{"itemId":"c1","title":"Chapter 1","realChapterOrder":"1"},'
                    '{"itemId":"c2","title":"Chapter 2","realChapterOrder":"2"},'
                    '{"itemId":"c3","title":"Chapter 3","realChapterOrder":"3"},'
                    '{"itemId":"c4","title":"Chapter 4","realChapterOrder":"4"},'
                    '{"itemId":"c5","title":"Chapter 5","realChapterOrder":"5"}'
                    ']]}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c1": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 1","content":"<p>Content 1</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c2": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 2","content":"<p>Content 2</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c3": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 3","content":"<p>Content 3</p>"}}};})()</script>'
                ),
            }
        )
        crawler = FanqieCrawler(http_client=http_client)

        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 3)

        self.assertEqual(["Chapter 1", "Chapter 2", "Chapter 3"], [item.chapterTitle for item in chapters])

    def test_fetch_chapters_should_only_fetch_missing_range_when_start_chapter_no_is_provided(self) -> None:
        http_client = StubHttpClient(
            {
                "https://fanqienovel.com/page/101": (
                    '<script>(function(){window.__INITIAL_STATE__={"page":{"bookId":"101","bookName":"Book A",'
                    '"author":"Author A","abstract":"Intro A","chapterListWithVolume":[['
                    '{"itemId":"c1","title":"Chapter 1","realChapterOrder":"1"},'
                    '{"itemId":"c2","title":"Chapter 2","realChapterOrder":"2"},'
                    '{"itemId":"c3","title":"Chapter 3","realChapterOrder":"3"},'
                    '{"itemId":"c4","title":"Chapter 4","realChapterOrder":"4"},'
                    '{"itemId":"c5","title":"Chapter 5","realChapterOrder":"5"},'
                    '{"itemId":"c6","title":"Chapter 6","realChapterOrder":"6"},'
                    '{"itemId":"c7","title":"Chapter 7","realChapterOrder":"7"},'
                    '{"itemId":"c8","title":"Chapter 8","realChapterOrder":"8"},'
                    '{"itemId":"c9","title":"Chapter 9","realChapterOrder":"9"},'
                    '{"itemId":"c10","title":"Chapter 10","realChapterOrder":"10"}'
                    ']]}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c6": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 6","content":"<p>Content 6</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c7": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 7","content":"<p>Content 7</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c8": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 8","content":"<p>Content 8</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c9": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 9","content":"<p>Content 9</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c10": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 10","content":"<p>Content 10</p>"}}};})()</script>'
                ),
            }
        )
        crawler = FanqieCrawler(http_client=http_client)

        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 5, start_chapter_no=6)

        self.assertEqual([6, 7, 8, 9, 10], [item.chapterNo for item in chapters])
        self.assertEqual(["Chapter 6", "Chapter 7", "Chapter 8", "Chapter 9", "Chapter 10"], [item.chapterTitle for item in chapters])
        self.assertEqual(
            [
                "https://fanqienovel.com/page/101",
                "https://fanqienovel.com/reader/c6",
                "https://fanqienovel.com/reader/c7",
                "https://fanqienovel.com/reader/c8",
                "https://fanqienovel.com/reader/c9",
                "https://fanqienovel.com/reader/c10",
            ],
            http_client.calls,
        )

    def test_fetch_chapters_should_fetch_multiple_chapters_with_limited_parallelism(self) -> None:
        http_client = SlowTrackingHttpClient(
            {
                "https://fanqienovel.com/page/101": (
                    '<script>(function(){window.__INITIAL_STATE__={"page":{"bookId":"101","bookName":"Book A",'
                    '"author":"Author A","abstract":"Intro A","chapterListWithVolume":[{"chapterList":['
                    '{"itemId":"c1","title":"Chapter 1"},'
                    '{"itemId":"c2","title":"Chapter 2"},'
                    '{"itemId":"c3","title":"Chapter 3"},'
                    '{"itemId":"c4","title":"Chapter 4"}'
                    ']}]}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c1": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 1","content":"<p>Content 1</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c2": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 2","content":"<p>Content 2</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c3": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 3","content":"<p>Content 3</p>"}}};})()</script>'
                ),
                "https://fanqienovel.com/reader/c4": (
                    '<script>(function(){window.__INITIAL_STATE__={"reader":{"chapterData":{'
                    '"title":"Chapter 4","content":"<p>Content 4</p>"}}};})()</script>'
                ),
            }
        )
        crawler = FanqieCrawler(http_client=http_client)

        chapters = crawler.fetch_chapters("https://fanqienovel.com/page/101", 4)

        self.assertEqual(["Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"], [item.chapterTitle for item in chapters])
        self.assertGreaterEqual(http_client.max_inflight, 2)


if __name__ == "__main__":
    unittest.main()
