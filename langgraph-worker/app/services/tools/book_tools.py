"""
Book Tools - Domain tools for book research packs and chapter materials
"""
from __future__ import annotations

from typing import Any

from app.services.knowledge_client import KnowledgeBackendClient


def create_book_tools(client: KnowledgeBackendClient) -> dict[str, dict[str, Any]]:
    """Create and return book toolset handlers"""

    async def book_research_pack(payload: dict[str, Any]) -> dict[str, Any]:
        """Get book research pack: profile + chapters + analysis"""
        platform = payload.get("platform", "fanqie")
        book_id = payload.get("book_id")
        book_name = payload.get("book_name")
        chapter_limit = payload.get("chapter_limit", 3)
        analysis_limit = payload.get("analysis_limit", 3)

        pack_fn = getattr(client, "get_book_research_pack", None)
        if not callable(pack_fn):
            return {"error": "get_book_research_pack not available"}

        pack = await pack_fn(
            platform=platform,
            book_id=book_id,
            book_name=book_name,
            chapter_limit=chapter_limit,
            analysis_limit=analysis_limit,
        )
        if pack is None:
            return {"pack": None}

        return {
            "pack": pack.model_dump(mode="json"),
            "book": pack.book.model_dump(mode="json") if pack.book else None,
            "chapter_count": len(pack.chapters),
            "analysis_count": len(pack.analyses),
        }

    return {
        "book.research_pack": {
            "handler": book_research_pack,
            "schema": {
                "type": "object",
                "properties": {
                    "platform": {"type": "string"},
                    "book_id": {"type": "integer"},
                    "book_name": {"type": "string"},
                    "chapter_limit": {"type": "integer"},
                    "analysis_limit": {"type": "integer"},
                },
            },
            "check_fn": lambda: callable(getattr(client, "get_book_research_pack", None)),
        },
    }
