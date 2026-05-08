from __future__ import annotations

import http.client
import socket
import ssl
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


class _ResolvedIpHttpsConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, ip_address: str, timeout: int, context: ssl.SSLContext) -> None:
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
        try:
            response = self._client.get(url)
        except httpx.TransportError:
            if not _has_resolved_ip_fallback(url):
                raise
            return _request_text_via_resolved_ip(url, self._headers, self.timeout_seconds)
        response.raise_for_status()
        return response.text

    def get_json(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        response = self._client.get(url, params=params, headers=headers)
        response.raise_for_status()
        if response.headers.get("bdturing-verify"):
            raise ValueError("fanqie search blocked by anti-bot verification")
        return response.json()

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
) -> str:
    parsed = urlparse(url)
    host = parsed.hostname or ""
    fallback_ips = _FANQIE_HOST_IP_FALLBACKS.get(host)
    if parsed.scheme != "https" or not fallback_ips:
        raise ValueError(f"unsupported resolved-ip fallback url: {url}")
    if redirect_count > 3:
        raise ValueError(f"too many redirects for resolved-ip fallback url: {url}")

    last_error: Exception | None = None
    for ip_address in fallback_ips:
        connection: _ResolvedIpHttpsConnection | None = None
        try:
            connection = _ResolvedIpHttpsConnection(
                host,
                ip_address,
                timeout_seconds,
                ssl.create_default_context(),
            )
            request_headers = {
                **headers,
                "Host": host,
                "Accept-Encoding": "identity",
                "Connection": "close",
            }
            connection.request("GET", _request_target(parsed), headers=request_headers)
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader("Location")
                response.read()
                if not location:
                    raise ValueError(f"redirect without location for resolved-ip fallback url: {url}")
                return _request_text_via_resolved_ip(
                    urljoin(url, location),
                    headers,
                    timeout_seconds,
                    redirect_count + 1,
                )

            body = response.read()
            if response.status >= 400:
                raise ValueError(f"resolved-ip fallback returned status {response.status} for {url}")
            return body.decode(_response_encoding(response), errors="replace")
        except Exception as ex:
            last_error = ex
        finally:
            if connection is not None:
                connection.close()

    if last_error is not None:
        raise last_error
    raise ValueError(f"resolved-ip fallback has no candidate ip for {url}")


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
