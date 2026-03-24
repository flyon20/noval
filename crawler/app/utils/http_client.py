from __future__ import annotations

import httpx

from app.config import settings


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
        response = self._client.get(url)
        response.raise_for_status()
        return response.text

    def close(self) -> None:
        self._client.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass
