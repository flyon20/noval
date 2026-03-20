from __future__ import annotations

from app.services.fanqie_crawler import FanqieCrawler


def build_crawler(platform: str):
    if platform.lower() == "fanqie":
        return FanqieCrawler()
    raise ValueError(f"unsupported platform: {platform}")

