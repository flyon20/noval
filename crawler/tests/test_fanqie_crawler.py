from __future__ import annotations

import unittest
from unittest.mock import patch

from app.api import rank as rank_api
from app.models.rank import RankRequest
from app.services.fanqie_crawler import FanqieCrawler
from app.utils.parsers import extract_initial_state, html_to_text


class StubHttpClient:
    def __init__(self, responses: dict[str, str]) -> None:
        self._responses = responses

    def get_text(self, url: str) -> str:
        if url not in self._responses:
            raise AssertionError(f"unexpected url: {url}")
        return self._responses[url]


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

    def test_fetch_board_catalog_returns_channels_and_boards(self) -> None:
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    "https://fanqienovel.com/rank?enter_from=menu": (
                        '<script>(function(){window.__INITIAL_STATE__={"rank":{"channelList":['
                        '{"channelName":"Male","channelCode":"2","boardList":['
                        '{"boardName":"Hot","boardCode":"1141"},'
                        '{"boardName":"New","boardCode":"1140"}'
                        "]},"
                        '{"channelName":"Female","channelCode":"1","boardList":['
                        '{"boardName":"Rising","boardCode":"2141"}'
                        "]}"
                        ']}};})()</script>'
                    )
                }
            )
        )

        result = crawler.fetch_board_catalog()

        self.assertEqual(2, len(result))
        self.assertEqual("2", result[0].channelCode)
        self.assertEqual("Male", result[0].channelName)
        self.assertEqual(2, len(result[0].boards))
        self.assertEqual("1141", result[0].boards[0].boardCode)
        self.assertEqual("Hot", result[0].boards[0].boardName)

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
                    "https://fanqienovel.com/rank/1_2_1141": (
                        '<script>(function(){window.__INITIAL_STATE__={"common":{"css":"@font-face{font-family:test;}"},"rank":{"bookList":['
                        '{"currentPos":1,"bookId":"101","bookName":"Book A","author":"Author A","abstract":"Intro A"},'
                        '{"currentPos":2,"bookId":"102","bookName":"Book B","author":"Author B","abstract":"Intro B"}'
                        ']}};})()</script>'
                    )
                }
            )
        )

        result = crawler.fetch_rank("2", "1141")

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


if __name__ == "__main__":
    unittest.main()
