from __future__ import annotations

from app.services.fanqie_crawler import FanqieCrawler


def build_crawler(platform: str,
                  timeout_seconds: int | None = None,
                  chapter_fetch_workers: int | None = None):
    if platform.lower() == "fanqie":
        return FanqieCrawler(
            timeout_seconds=timeout_seconds,
            chapter_fetch_workers=chapter_fetch_workers,
        )
    raise ValueError(f"unsupported platform: {platform}")
