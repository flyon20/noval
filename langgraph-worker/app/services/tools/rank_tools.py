"""
Rank Tools - Domain tools for rank data lookup and research packs
"""
from __future__ import annotations

from typing import Any

from app.services.knowledge_client import KnowledgeBackendClient


def create_rank_tools(client: KnowledgeBackendClient) -> dict[str, dict[str, Any]]:
    """Create and return rank toolset handlers"""

    async def rank_lookup(payload: dict[str, Any]) -> dict[str, Any]:
        """Lookup current rank TopN or specific rank position"""
        platform = payload.get("platform", "fanqie")
        channel_code = payload.get("channel_code")
        board_code = payload.get("board_code")
        category = payload.get("category")
        rank_no = payload.get("rank_no")
        limit = payload.get("limit", 10)
        source_policy = payload.get("sourcePolicy") if isinstance(payload.get("sourcePolicy"), dict) else {}

        lookup_fn = getattr(client, "lookup_rank", None)
        if not callable(lookup_fn):
            return {"error": "lookup_rank not available"}

        results = await lookup_fn(
            platform=platform,
            channel_code=channel_code,
            board_code=board_code,
            category=category,
            rank_no=rank_no,
            limit=limit,
            freshness=payload.get("freshness") or source_policy.get("freshness"),
            allow_historical=_optional_bool(payload, source_policy, "allowHistorical"),
            time_window_days=payload.get("timeWindowDays") or source_policy.get("timeWindowDays"),
            require_snapshot_time=_optional_bool(payload, source_policy, "requireSnapshotTime"),
        )
        return {"results": results, "count": len(results)}

    async def rank_research_pack(payload: dict[str, Any]) -> dict[str, Any]:
        """Get rank research pack: ranks + representative books + chapters"""
        platform = payload.get("platform", "fanqie")
        channel_code = payload.get("channel_code")
        board_code = payload.get("board_code")
        category = payload.get("category")
        rank_no = payload.get("rank_no")
        limit = payload.get("limit", 10)
        chapter_limit_per_book = payload.get("chapter_limit_per_book", 3)
        source_policy = payload.get("sourcePolicy") if isinstance(payload.get("sourcePolicy"), dict) else {}

        pack_fn = getattr(client, "get_rank_research_pack", None)
        if not callable(pack_fn):
            return {"error": "get_rank_research_pack not available"}

        pack = await pack_fn(
            platform=platform,
            channel_code=channel_code,
            board_code=board_code,
            category=category,
            rank_no=rank_no,
            limit=limit,
            chapter_limit_per_book=chapter_limit_per_book,
            freshness=payload.get("freshness") or source_policy.get("freshness"),
            allow_historical=_optional_bool(payload, source_policy, "allowHistorical"),
            time_window_days=payload.get("timeWindowDays") or source_policy.get("timeWindowDays"),
            require_snapshot_time=_optional_bool(payload, source_policy, "requireSnapshotTime"),
        )
        if pack is None:
            return {"pack": None}

        return {
            "pack": pack.model_dump(mode="json"),
            "rank_count": len(pack.ranks),
            "book_count": len(pack.books),
            "chapter_count": len(pack.chapters),
        }

    return {
        "rank.lookup": {
            "handler": rank_lookup,
            "schema": {
                "type": "object",
                "properties": {
                    "platform": {"type": "string"},
                    "channel_code": {"type": "string"},
                    "board_code": {"type": "string"},
                    "category": {"type": "string"},
                    "rank_no": {"type": "integer"},
                    "limit": {"type": "integer"},
                    "freshness": {"type": "string"},
                    "allowHistorical": {"type": "boolean"},
                    "timeWindowDays": {"type": "integer"},
                    "requireSnapshotTime": {"type": "boolean"},
                    "sourcePolicy": {"type": "object"},
                },
            },
            "check_fn": lambda: callable(getattr(client, "lookup_rank", None)),
        },
        "rank.research_pack": {
            "handler": rank_research_pack,
            "schema": {
                "type": "object",
                "properties": {
                    "platform": {"type": "string"},
                    "channel_code": {"type": "string"},
                    "limit": {"type": "integer"},
                    "chapter_limit_per_book": {"type": "integer"},
                    "freshness": {"type": "string"},
                    "allowHistorical": {"type": "boolean"},
                    "timeWindowDays": {"type": "integer"},
                    "requireSnapshotTime": {"type": "boolean"},
                    "sourcePolicy": {"type": "object"},
                },
            },
            "check_fn": lambda: callable(getattr(client, "get_rank_research_pack", None)),
        },
    }


def _optional_bool(payload: dict[str, Any], source_policy: dict[str, Any], key: str) -> bool | None:
    if key in payload:
        value = payload.get(key)
    elif key in source_policy:
        value = source_policy.get(key)
    else:
        return None
    if isinstance(value, bool):
        return value
    return None
