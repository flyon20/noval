from __future__ import annotations

import httpx

from app.config import settings


class HttpClient:
    def __init__(self) -> None:
        self._headers = {
            "User-Agent": settings.user_agent,
            "Referer": settings.fanqie_base_url,
        }

    def get_text(self, url: str) -> str:
        with httpx.Client(headers=self._headers, follow_redirects=True, timeout=settings.timeout_seconds) as client:
            response = client.get(url)
            response.raise_for_status()
            return response.text
