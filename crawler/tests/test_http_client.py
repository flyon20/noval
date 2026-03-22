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


if __name__ == "__main__":
    unittest.main()
