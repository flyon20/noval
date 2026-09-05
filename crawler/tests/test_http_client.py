from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import httpx

from app.utils.http_client import HttpClient, _request_text_via_resolved_ip


class HttpClientTest(unittest.TestCase):

    @staticmethod
    def _stream_response(mock_client: MagicMock, response: MagicMock) -> None:
        stream_context = MagicMock()
        stream_context.__enter__.return_value = response
        stream_context.__exit__.return_value = False
        mock_client.stream.return_value = stream_context

    def test_should_create_httpx_client_without_inheriting_proxy_env(self) -> None:
        mock_response = MagicMock()
        mock_response.encoding = "utf-8"
        mock_response.request = httpx.Request("GET", "https://fanqienovel.com/page/demo")
        mock_response.iter_bytes.return_value = iter([b"ok"])
        mock_response.raise_for_status.return_value = None

        mock_client = MagicMock()
        self._stream_response(mock_client, mock_response)

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client) as client_cls:
            client = HttpClient()
            body = client.get_text("https://fanqienovel.com/page/demo")

        self.assertEqual("ok", body)
        client_cls.assert_called_once()
        self.assertEqual(False, client_cls.call_args.kwargs.get("trust_env"))
        mock_client.stream.assert_called_once()
        self.assertEqual(
            ("GET", "https://fanqienovel.com/page/demo"),
            mock_client.stream.call_args.args,
        )

    def test_should_fetch_json_payload_with_params(self) -> None:
        mock_response = MagicMock()
        mock_response.headers = {}
        mock_response.request = httpx.Request(
            "GET",
            "https://fanqienovel.com/api/rank/category/list",
        )
        mock_response.iter_bytes.return_value = iter([b'{"code":0,"data":{"book_list":[]}}'])
        mock_response.raise_for_status.return_value = None

        mock_client = MagicMock()
        self._stream_response(mock_client, mock_response)

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            client = HttpClient()
            payload = client.get_json(
                "https://fanqienovel.com/api/rank/category/list",
                params={"offset": "10", "limit": "10"},
        )

        self.assertEqual({"code": 0, "data": {"book_list": []}}, payload)
        mock_client.stream.assert_called_once_with(
            "GET",
            "https://fanqienovel.com/api/rank/category/list",
            params={"offset": "10", "limit": "10"},
            headers=None,
            timeout=mock_client.stream.call_args.kwargs["timeout"],
        )
        mock_response.raise_for_status.assert_called_once_with()
        mock_response.iter_bytes.assert_called_once()

    def test_should_report_fanqie_anti_bot_json_response(self) -> None:
        mock_response = MagicMock()
        mock_response.headers = {"bdturing-verify": "{\"type\":\"verify\"}"}
        mock_response.request = httpx.Request(
            "GET",
            "https://fanqienovel.com/api/author/search/search_book/v1",
        )
        mock_response.raise_for_status.return_value = None

        mock_client = MagicMock()
        self._stream_response(mock_client, mock_response)

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            client = HttpClient()
            with self.assertRaisesRegex(ValueError, "anti-bot verification"):
                client.get_json("https://fanqienovel.com/api/author/search/search_book/v1")

        mock_response.raise_for_status.assert_called_once_with()
        mock_response.iter_bytes.assert_not_called()

    def test_should_fallback_to_fanqie_ip_fetch_when_text_request_cannot_connect(self) -> None:
        mock_client = MagicMock()
        mock_client.stream.side_effect = httpx.ConnectError("dns failed")

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

    def test_should_share_one_timeout_budget_across_resolved_ip_candidates(self) -> None:
        first_connection = MagicMock()
        first_connection.request.side_effect = OSError("first ip timed out")
        second_connection = MagicMock()
        second_connection.request.side_effect = OSError("second ip timed out")

        with patch(
            "app.utils.http_client._FANQIE_HOST_IP_FALLBACKS",
            {"fanqienovel.com": ("192.0.2.1", "192.0.2.2", "192.0.2.3")},
        ):
            with patch(
                "app.utils.http_client._ResolvedIpHttpsConnection",
                side_effect=[first_connection, second_connection],
            ) as connection_cls:
                with patch(
                    "app.utils.http_client.time.monotonic",
                    side_effect=[100.0, 100.0, 106.0, 110.0],
                ):
                    with self.assertRaisesRegex(OSError, "second ip timed out"):
                        _request_text_via_resolved_ip(
                            "https://fanqienovel.com/page/demo",
                            {},
                            10,
                        )

        self.assertEqual(2, connection_cls.call_count)
        self.assertEqual(5.0, connection_cls.call_args_list[0].args[2])
        self.assertEqual(4.0, connection_cls.call_args_list[1].args[2])

    def test_should_enforce_total_deadline_while_response_keeps_streaming(self) -> None:
        url = "https://example.com/slow"
        mock_response = MagicMock()
        mock_response.encoding = "utf-8"
        mock_response.request = httpx.Request("GET", url)
        mock_response.iter_bytes.return_value = iter([b"first", b"second"])
        mock_response.raise_for_status.return_value = None
        mock_client = MagicMock()
        self._stream_response(mock_client, mock_response)

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            with patch(
                "app.utils.http_client.time.monotonic",
                side_effect=[100.0, 100.0, 101.0, 105.0, 111.0],
            ):
                with self.assertRaisesRegex(httpx.ReadTimeout, "request deadline exceeded"):
                    HttpClient(timeout_seconds=10).get_text(url)

    def test_should_reject_response_body_over_limit(self) -> None:
        url = "https://example.com/large"
        mock_response = MagicMock()
        mock_response.encoding = "utf-8"
        mock_response.request = httpx.Request("GET", url)
        mock_response.iter_bytes.return_value = iter([b"ab", b"cd"])
        mock_response.raise_for_status.return_value = None
        mock_client = MagicMock()
        self._stream_response(mock_client, mock_response)

        with patch("app.utils.http_client.httpx.Client", return_value=mock_client):
            with patch("app.utils.http_client._MAX_RESPONSE_BODY_BYTES", 3):
                with self.assertRaisesRegex(ValueError, "response body exceeds 3 bytes"):
                    HttpClient(timeout_seconds=10).get_text(url)

    def test_should_read_resolved_ip_response_in_bounded_chunks(self) -> None:
        connection = MagicMock()
        connection.sock = MagicMock()
        response = MagicMock()
        response.status = 200
        response.read.side_effect = [b"first", b"second", b""]
        response.getheader.return_value = None
        connection.getresponse.return_value = response

        with patch(
            "app.utils.http_client._FANQIE_HOST_IP_FALLBACKS",
            {"fanqienovel.com": ("192.0.2.1",)},
        ):
            with patch(
                "app.utils.http_client._ResolvedIpHttpsConnection",
                return_value=connection,
            ):
                with patch("app.utils.http_client.time.monotonic", return_value=100.0):
                    body = _request_text_via_resolved_ip(
                        "https://fanqienovel.com/page/demo",
                        {},
                        10,
                        deadline=110.0,
                    )

        self.assertEqual("firstsecond", body)
        self.assertEqual(3, response.read.call_count)
        response.read.assert_called_with(64 * 1024)
        connection.sock.settimeout.assert_called()


if __name__ == "__main__":
    unittest.main()
