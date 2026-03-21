from __future__ import annotations

import unittest
import warnings
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


warnings.simplefilter("ignore", ResourceWarning)


VALID_INTERNAL_KEY = "crawler-internal-test-key-1234567890"


class StubChapterCrawler:
    def fetch_chapters(self, book_url: str, chapter_count: int):
        return [
            {
                "chapterNo": 1,
                "chapterTitle": "Chapter 1",
                "content": "content",
            }
        ]


class InternalApiSecurityTest(unittest.TestCase):

    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = VALID_INTERNAL_KEY

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def test_should_reject_internal_rank_request_without_token(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/internal/rank",
                json={"platform": "fanqie", "category": "male-hot-a"},
            )

        self.assertEqual(401, response.status_code)

    def test_should_reject_internal_book_request_with_invalid_token(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/internal/book",
                headers={"X-Internal-Service-Token": "wrong-token"},
                json={"platform": "fanqie", "bookUrl": "https://fanqienovel.com/page/101"},
            )

        self.assertEqual(401, response.status_code)

    def test_should_allow_internal_chapter_request_with_valid_token(self) -> None:
        with patch("app.api.chapter.build_crawler", return_value=StubChapterCrawler()):
            with TestClient(app) as client:
                response = client.post(
                    "/internal/chapters",
                    headers={"X-Internal-Service-Token": VALID_INTERNAL_KEY},
                    json={
                        "platform": "fanqie",
                        "bookUrl": "https://fanqienovel.com/page/101",
                        "chapterCount": 1,
                    },
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual(200, response.json()["code"])

    def test_should_keep_health_endpoint_open(self) -> None:
        with TestClient(app) as client:
            response = client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual("UP", response.json()["data"]["status"])

    def test_should_fail_startup_when_internal_key_missing(self) -> None:
        settings.internal_api_key = ""
        client = TestClient(app)

        try:
            with self.assertRaises(RuntimeError):
                client.__enter__()
        finally:
            client.close()


if __name__ == "__main__":
    unittest.main()
