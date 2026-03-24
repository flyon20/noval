from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

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


if __name__ == "__main__":
    unittest.main()
