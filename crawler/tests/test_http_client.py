from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import httpx

from app.utils.http_client import HttpClient


class HttpClientTest(unittest.TestCase):

    def test_should_create_httpx_client_without_inheriting_proxy_env(self) -> None:
        mock_response = MagicMock()
        mock_response.text = "ok"
        mock_response.raise_for_status.return_value = None

        mock_client = MagicMock()
        mock_client.get.return_value = mock_response

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client) as client_cls:
            client = HttpClient()
            body = client.get_text("https://fanqienovel.com/page/demo")

        self.assertEqual("ok", body)
        client_cls.assert_called_once()
        self.assertEqual(False, client_cls.call_args.kwargs.get("trust_env"))
        mock_client.get.assert_called_once_with("https://fanqienovel.com/page/demo")

    def test_should_fetch_json_payload_with_params(self) -> None:
        mock_response = MagicMock()
        mock_response.headers = {}
        mock_response.raise_for_status.return_value = None
        mock_response.json.return_value = {"code": 0, "data": {"book_list": []}}

        mock_client = MagicMock()
        mock_client.get.return_value = mock_response

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            client = HttpClient()
            payload = client.get_json(
                "https://fanqienovel.com/api/rank/category/list",
                params={"offset": "10", "limit": "10"},
            )

        self.assertEqual({"code": 0, "data": {"book_list": []}}, payload)
        mock_client.get.assert_called_once_with(
            "https://fanqienovel.com/api/rank/category/list",
            params={"offset": "10", "limit": "10"},
            headers=None,
        )
        mock_response.raise_for_status.assert_called_once_with()
        mock_response.json.assert_called_once_with()

    def test_should_report_fanqie_anti_bot_json_response(self) -> None:
        mock_response = MagicMock()
        mock_response.headers = {"bdturing-verify": "{\"type\":\"verify\"}"}
        mock_response.raise_for_status.return_value = None

        mock_client = MagicMock()
        mock_client.get.return_value = mock_response

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            client = HttpClient()
            with self.assertRaisesRegex(ValueError, "anti-bot verification"):
                client.get_json("https://fanqienovel.com/api/author/search/search_book/v1")

        mock_response.raise_for_status.assert_called_once_with()
        mock_response.json.assert_not_called()

    def test_should_fallback_to_fanqie_ip_fetch_when_text_request_cannot_connect(self) -> None:
        mock_client = MagicMock()
        mock_client.get.side_effect = httpx.ConnectError("dns failed")

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            with patch(
                "app.utils.http_client._request_text_via_resolved_ip",
                return_value="<html>book detail</html>",
                create=True,
            ) as fallback_request:
                client = HttpClient()
                body = client.get_text("https://fanqienovel.com/page/demo")

        self.assertEqual("<html>book detail</html>", body)
        fallback_request.assert_called_once()


if __name__ == "__main__":
    unittest.main()
