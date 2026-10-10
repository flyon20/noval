from __future__ import annotations

import http.client
import json
import socket
import ssl
import time
from typing import Any
from urllib.parse import urljoin, urlparse, urlunparse

import httpx

from app.config import settings


_FANQIE_HOST_IP_FALLBACKS = {
    "fanqienovel.com": (
        "112.90.76.103",
        "163.177.46.102",
        "163.177.46.106",
    ),
    "www.fanqienovel.com": (
        "163.177.118.64",
        "163.177.118.63",
        "163.177.182.64",
        "163.142.155.63",
    ),
}

_IO_TIMEOUT_SECONDS = 5.0
_READ_CHUNK_BYTES = 64 * 1024
_MAX_RESPONSE_BODY_BYTES = 16 * 1024 * 1024


class _ResolvedIpHttpsConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, ip_address: str, timeout: float, context: ssl.SSLContext) -> None:
        super().__init__(host, timeout=timeout, context=context)
        self._ip_address = ip_address

    def connect(self) -> None:
        self.sock = socket.create_connection(
            (self._ip_address, self.port),
            self.timeout,
            self.source_address,
        )
        self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host)


class HttpClient:
    def __init__(self, timeout_seconds: int | None = None) -> None:
        self._headers = {
            "User-Agent": settings.user_agent,
            "Referer": settings.fanqie_base_url,
        }
        self.timeout_seconds = timeout_seconds or settings.timeout_seconds
        self._client = httpx.Client(
            headers=self._headers,
            follow_redirects=True,
            timeout=self.timeout_seconds,
            trust_env=False,
        )

    def get_text(self, url: str) -> str:
        deadline = time.monotonic() + self.timeout_seconds
        try:
            with self._client.stream(
                "GET",
                url,
                timeout=httpx.Timeout(_io_timeout_seconds(deadline, url)),
            ) as response:
                response.raise_for_status()
                encoding = response.encoding or "utf-8"
                body = _read_httpx_response_body(response, deadline, url)
        except httpx.TransportError:
            if not _has_resolved_ip_fallback(url):
                raise
            return _request_text_via_resolved_ip(
                url,
                self._headers,
                self.timeout_seconds,
                deadline=deadline,
            )
        return body.decode(encoding, errors="replace")

    def get_json(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        with self._client.stream(
            "GET",
            url,
            params=params,
            headers=headers,
            timeout=httpx.Timeout(_io_timeout_seconds(deadline, url)),
        ) as response:
            response.raise_for_status()
            if response.headers.get("bdturing-verify"):
                raise ValueError("fanqie search blocked by anti-bot verification")
            body = _read_httpx_response_body(response, deadline, url)
        return json.loads(body)

    def close(self) -> None:
        self._client.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass


def _has_resolved_ip_fallback(url: str) -> bool:
    host = urlparse(url).hostname or ""
    return host in _FANQIE_HOST_IP_FALLBACKS


def _request_text_via_resolved_ip(
    url: str,
    headers: dict[str, str],
    timeout_seconds: int,
    redirect_count: int = 0,
    *,
    deadline: float | None = None,
) -> str:
    if deadline is None:
        deadline = time.monotonic() + timeout_seconds
    parsed = urlparse(url)
    host = parsed.hostname or ""
    fallback_ips = _FANQIE_HOST_IP_FALLBACKS.get(host)
    if parsed.scheme != "https" or not fallback_ips:
        raise ValueError(f"unsupported resolved-ip fallback url: {url}")
    if redirect_count > 3:
        raise ValueError(f"too many redirects for resolved-ip fallback url: {url}")

    last_error: Exception | None = None
    for ip_address in fallback_ips:
        remaining_seconds = deadline - time.monotonic()
        if remaining_seconds <= 0:
            break
        connection: _ResolvedIpHttpsConnection | None = None
        try:
            connection = _ResolvedIpHttpsConnection(
                host,
                ip_address,
                min(_IO_TIMEOUT_SECONDS, remaining_seconds),
                ssl.create_default_context(),
            )
            request_headers = {
                **headers,
                "Host": host,
                "Accept-Encoding": "identity",
                "Connection": "close",
            }
            connection.request("GET", _request_target(parsed), headers=request_headers)
            _set_connection_read_timeout(connection, deadline, url)
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader("Location")
                if not location:
                    raise ValueError(f"redirect without location for resolved-ip fallback url: {url}")
                return _request_text_via_resolved_ip(
                    urljoin(url, location),
                    headers,
                    timeout_seconds,
                    redirect_count + 1,
                    deadline=deadline,
                )

            if response.status >= 400:
                raise ValueError(f"resolved-ip fallback returned status {response.status} for {url}")
            body = _read_resolved_ip_response_body(response, connection, deadline, url)
            return body.decode(_response_encoding(response), errors="replace")
        except Exception as ex:
            last_error = ex
        finally:
            if connection is not None:
                connection.close()

    if last_error is not None:
        raise last_error
    raise TimeoutError(f"request deadline exceeded for {url}")


def _io_timeout_seconds(deadline: float, url: str) -> float:
    remaining_seconds = deadline - time.monotonic()
    if remaining_seconds <= 0:
        raise httpx.ReadTimeout(
            f"request deadline exceeded for {url}",
            request=httpx.Request("GET", url),
        )
    return min(_IO_TIMEOUT_SECONDS, remaining_seconds)


def _ensure_httpx_deadline(deadline: float, url: str, request: httpx.Request) -> None:
    if time.monotonic() >= deadline:
        raise httpx.ReadTimeout(
            f"request deadline exceeded for {url}",
            request=request,
        )


def _read_httpx_response_body(response: httpx.Response, deadline: float, url: str) -> bytes:
    chunks: list[bytes] = []
    total_bytes = 0
    iterator = response.iter_bytes(chunk_size=_READ_CHUNK_BYTES)
    while True:
        _ensure_httpx_deadline(deadline, url, response.request)
        try:
            chunk = next(iterator)
        except StopIteration:
            _ensure_httpx_deadline(deadline, url, response.request)
            break
        _ensure_httpx_deadline(deadline, url, response.request)
        total_bytes += len(chunk)
        if total_bytes > _MAX_RESPONSE_BODY_BYTES:
            raise ValueError(f"response body exceeds {_MAX_RESPONSE_BODY_BYTES} bytes for {url}")
        chunks.append(chunk)
    return b"".join(chunks)


def _set_connection_read_timeout(
    connection: _ResolvedIpHttpsConnection,
    deadline: float,
    url: str,
) -> None:
    remaining_seconds = deadline - time.monotonic()
    if remaining_seconds <= 0:
        raise TimeoutError(f"request deadline exceeded for {url}")
    if connection.sock is not None:
        connection.sock.settimeout(min(_IO_TIMEOUT_SECONDS, remaining_seconds))


def _read_resolved_ip_response_body(
    response: http.client.HTTPResponse,
    connection: _ResolvedIpHttpsConnection,
    deadline: float,
    url: str,
) -> bytes:
    chunks: list[bytes] = []
    total_bytes = 0
    while True:
        _set_connection_read_timeout(connection, deadline, url)
        chunk = response.read(_READ_CHUNK_BYTES)
        if not chunk:
            return b"".join(chunks)
        total_bytes += len(chunk)
        if total_bytes > _MAX_RESPONSE_BODY_BYTES:
            raise ValueError(f"response body exceeds {_MAX_RESPONSE_BODY_BYTES} bytes for {url}")
        chunks.append(chunk)


def _request_target(parsed_url: Any) -> str:
    path = parsed_url.path or "/"
    return urlunparse(("", "", path, parsed_url.params, parsed_url.query, ""))


def _response_encoding(response: http.client.HTTPResponse) -> str:
    content_type = response.getheader("Content-Type") or ""
    for part in content_type.split(";"):
        part = part.strip()
        if part.lower().startswith("charset="):
            return part.split("=", maxsplit=1)[1].strip() or "utf-8"
    return "utf-8"
