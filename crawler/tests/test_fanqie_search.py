from __future__ import annotations

import unittest
import warnings
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.models.search import BookSearchRequest
from app.services.fanqie_crawler import FanqieCrawler


warnings.simplefilter("ignore", ResourceWarning)

VALID_INTERNAL_KEY = "crawler-internal-test-key-1234567890"


class StubHttpClient:
    def __init__(self, responses: dict[str, str]) -> None:
        self._responses = responses
        self._json_responses: dict[str, object] = {}
        self._anti_bot_urls: set[str] = set()
        self.calls: list[str] = []
        self.json_calls: list[tuple[str, dict[str, str] | None]] = []
        self.timeout_seconds = 20

    def get_text(self, url: str) -> str:
        self.calls.append(url)
        if url not in self._responses:
            raise AssertionError(f"unexpected url: {url}")
        return self._responses[url]

    def get_json(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> object:
        self.json_calls.append((url, params))
        if url in self._anti_bot_urls:
            raise ValueError("fanqie search blocked by anti-bot verification")
        if url not in self._json_responses:
            raise AssertionError(f"unexpected json url: {url}")
        return self._json_responses[url]


class StubSearchCrawler:
    def __init__(self) -> None:
        self.calls: list[tuple[str, int]] = []

    def search_books(self, keyword: str, limit: int = 10):
        self.calls.append((keyword, limit))
        return [
            {
                "bookName": "Book A",
                "author": "Author A",
                "intro": "Intro A",
                "bookUrl": "https://fanqienovel.com/page/101",
                "platformBookId": "101",
            }
        ]


class StubBrowserSearchClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, int, int]] = []

    def search(self, keyword: str, limit: int, timeout_seconds: int) -> tuple[list[dict[str, object]], str]:
        self.calls.append((keyword, limit, timeout_seconds))
        return (
            [
                {
                    "book_id": "301",
                    "book_name": "Book From Browser",
                    "author_name": "Author Browser",
                    "book_abstract": "Intro Browser",
                }
            ],
            '@font-face { font-family: TestFont; src: url("https://example.test/font.woff2") format("woff2"); }',
        )


class FanqieSearchTest(unittest.TestCase):

    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = VALID_INTERNAL_KEY

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def test_search_request_requires_non_blank_keyword(self) -> None:
        with self.assertRaises(ValueError):
            BookSearchRequest.model_validate({"platform": "fanqie", "keyword": "   "})

    def test_search_books_extracts_candidates_from_initial_state(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        crawler = FanqieCrawler(
            StubHttpClient(
                {
                    search_url: (
                        '<script>(function(){window.__INITIAL_STATE__={'
                        '"common":{"css":""},'
                        '"search":{"bookList":[{'
                        '"bookId":"101",'
                        '"bookName":"Book A",'
                        '"author":"Author A",'
                        '"abstract":"Intro A"'
                        '},{'
                        '"book_id":"102",'
                        '"book_name":"Book B",'
                        '"author_name":"Author B",'
                        '"description":"Intro B"'
                        '}]}'
                        '};})()</script>'
                    )
                }
            )
        )

        result = crawler.search_books("test-book", limit=1)

        self.assertEqual(1, len(result))
        self.assertEqual("Book A", result[0].bookName)
        self.assertEqual("Author A", result[0].author)
        self.assertEqual("Intro A", result[0].intro)
        self.assertEqual("https://fanqienovel.com/page/101", result[0].bookUrl)
        self.assertEqual("101", result[0].platformBookId)
        self.assertEqual([search_url], crawler._http_client.calls)

    def test_search_books_falls_back_to_search_api_when_initial_state_is_empty(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        api_url = "https://fanqienovel.com/api/author/search/search_book/v1"
        http_client = StubHttpClient(
            {
                search_url: (
                    '<script>(function(){window.__INITIAL_STATE__={'
                    '"common":{"css":""},'
                    '"search":{"searchBookList":null,"total":0}'
                    '};})()</script>'
                )
            }
        )
        http_client._json_responses[api_url] = {
            "code": 0,
            "data": {
                "search_book_data_list": [
                    {
                        "book_id": "201",
                        "book_name": "Book From Api",
                        "author_name": "Author Api",
                        "book_abstract": "Intro Api",
                    }
                ]
            },
        }
        crawler = FanqieCrawler(http_client)

        result = crawler.search_books("test-book", limit=3)

        self.assertEqual(1, len(result))
        self.assertEqual("Book From Api", result[0].bookName)
        self.assertEqual("Author Api", result[0].author)
        self.assertEqual("Intro Api", result[0].intro)
        self.assertEqual("https://fanqienovel.com/page/201", result[0].bookUrl)
        self.assertEqual([search_url], http_client.calls)
        self.assertEqual(
            [
                (
                    api_url,
                    {
                        "filter": "127,127,127,127",
                        "page_count": "3",
                        "page_index": "0",
                        "query_type": "0",
                        "query_word": "test-book",
                    },
                )
            ],
            http_client.json_calls,
        )

    def test_search_books_reports_anti_bot_verification_clearly(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        api_url = "https://fanqienovel.com/api/author/search/search_book/v1"
        http_client = StubHttpClient(
            {
                search_url: (
                    '<script>(function(){window.__INITIAL_STATE__={'
                    '"common":{"css":""},'
                    '"search":{"searchBookList":null,"total":0}'
                    '};})()</script>'
                )
            }
        )
        http_client._anti_bot_urls.add(api_url)
        crawler = FanqieCrawler(http_client)

        with self.assertRaisesRegex(ValueError, "anti-bot verification"):
            crawler.search_books("test-book", limit=3)

    def test_search_books_uses_browser_fallback_when_api_hits_anti_bot(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        api_url = "https://fanqienovel.com/api/author/search/search_book/v1"
        http_client = StubHttpClient(
            {
                search_url: (
                    '<script>(function(){window.__INITIAL_STATE__={'
                    '"common":{"css":""},'
                    '"search":{"searchBookList":null,"total":0}'
                    '};})()</script>'
                )
            }
        )
        http_client._anti_bot_urls.add(api_url)
        browser_search_client = StubBrowserSearchClient()
        crawler = FanqieCrawler(http_client, browser_search_client=browser_search_client)

        result = crawler.search_books("test-book", limit=3)

        self.assertEqual(1, len(result))
        self.assertEqual("Book From Browser", result[0].bookName)
        self.assertEqual("Author Browser", result[0].author)
        self.assertEqual("Intro Browser", result[0].intro)
        self.assertEqual("https://fanqienovel.com/page/301", result[0].bookUrl)
        self.assertEqual([("test-book", 3, 20)], browser_search_client.calls)

    def test_search_books_uses_browser_fallback_when_api_returns_empty(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        api_url = "https://fanqienovel.com/api/author/search/search_book/v1"
        http_client = StubHttpClient(
            {
                search_url: (
                    '<script>(function(){window.__INITIAL_STATE__={'
                    '"common":{"css":""},'
                    '"search":{"searchBookList":null,"total":0}'
                    '};})()</script>'
                )
            }
        )
        http_client._json_responses[api_url] = {"code": 0, "data": {"search_book_data_list": []}}
        browser_search_client = StubBrowserSearchClient()
        crawler = FanqieCrawler(http_client, browser_search_client=browser_search_client)

        result = crawler.search_books("test-book", limit=3)

        self.assertEqual(1, len(result))
        self.assertEqual("Book From Browser", result[0].bookName)
        self.assertEqual([("test-book", 3, 20)], browser_search_client.calls)

    def test_search_books_uses_mobile_api_when_web_api_hits_anti_bot(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        web_api_url = "https://fanqienovel.com/api/author/search/search_book/v1"
        mobile_api_url = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/"
        http_client = StubHttpClient(
            {
                search_url: (
                    '<script>(function(){window.__INITIAL_STATE__={'
                    '"common":{"css":""},'
                    '"search":{"searchBookList":null,"total":0}'
                    '};})()</script>'
                )
            }
        )
        http_client._anti_bot_urls.add(web_api_url)
        http_client._json_responses[mobile_api_url] = {
            "code": 0,
            "data": {
                "ret_data": [
                    {
                        "book_id": "401",
                        "title": "Book From Mobile Api",
                        "author": "Author Mobile",
                        "abstract": "Intro Mobile",
                    }
                ]
            },
        }
        crawler = FanqieCrawler(http_client)

        result = crawler.search_books("test-book", limit=3)

        self.assertEqual(1, len(result))
        self.assertEqual("Book From Mobile Api", result[0].bookName)
        self.assertEqual("Author Mobile", result[0].author)
        self.assertEqual("Intro Mobile", result[0].intro)
        self.assertIn(
            (
                mobile_api_url,
                {
                    "device_platform": "android",
                    "parent_enterfrom": "novel_channel_search.tab.",
                    "offset": "0",
                    "aid": "1967",
                    "q": "test-book",
                },
            ),
            http_client.json_calls,
        )

    def test_search_books_retries_mobile_api_when_first_response_is_empty(self) -> None:
        search_url = "https://fanqienovel.com/search/test-book"
        web_api_url = "https://fanqienovel.com/api/author/search/search_book/v1"
        mobile_api_url = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/"
        http_client = StubHttpClient(
            {
                search_url: (
                    '<script>(function(){window.__INITIAL_STATE__={'
                    '"common":{"css":""},'
                    '"search":{"searchBookList":null,"total":0}'
                    '};})()</script>'
                )
            }
        )
        http_client._anti_bot_urls.add(web_api_url)
        mobile_responses = [
            {"code": 0, "data": {"ret_data": []}},
            {
                "code": 0,
                "data": {
                    "ret_data": [
                        {
                            "book_id": "402",
                            "title": "Book From Mobile Retry",
                            "author": "Author Mobile",
                            "abstract": "Intro Mobile",
                        }
                    ]
                },
            },
        ]

        def get_json(url, params=None, headers=None):
            http_client.json_calls.append((url, params))
            if url in http_client._anti_bot_urls:
                raise ValueError("fanqie search blocked by anti-bot verification")
            if url == mobile_api_url:
                return mobile_responses.pop(0)
            raise AssertionError(f"unexpected json url: {url}")

        http_client.get_json = get_json
        crawler = FanqieCrawler(http_client)

        result = crawler.search_books("test-book", limit=3)

        self.assertEqual(1, len(result))
        self.assertEqual("Book From Mobile Retry", result[0].bookName)
        self.assertEqual(2, sum(1 for url, _ in http_client.json_calls if url == mobile_api_url))

    def test_internal_search_endpoint_requires_token(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/internal/books/search",
                json={"platform": "fanqie", "keyword": "Book A"},
            )

        self.assertEqual(401, response.status_code)

    def test_internal_search_endpoint_returns_candidates(self) -> None:
        crawler = StubSearchCrawler()
        with patch("app.api.search.build_crawler", return_value=crawler):
            with TestClient(app) as client:
                response = client.post(
                    "/internal/books/search",
                    headers={"X-Internal-Service-Token": VALID_INTERNAL_KEY},
                    json={"platform": "fanqie", "keyword": "Book A", "limit": 3},
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual(200, response.json()["code"])
        self.assertEqual("Book A", response.json()["data"][0]["bookName"])
        self.assertEqual([("Book A", 3)], crawler.calls)


if __name__ == "__main__":
    unittest.main()
