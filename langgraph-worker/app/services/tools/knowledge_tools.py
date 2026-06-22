"""
Knowledge Tools - Domain tools for vector search and evidence retrieval
"""
from __future__ import annotations

from typing import Any

from app.services.knowledge_client import KnowledgeBackendClient


def create_knowledge_tools(client: KnowledgeBackendClient) -> dict[str, dict[str, Any]]:
    """Create and return knowledge toolset handlers"""

    async def vector_search(payload: dict[str, Any]) -> dict[str, Any]:
        """Search knowledge base with vector retrieval"""
        query = payload.get("query", "")
        book_id = payload.get("book_id")
        platform = payload.get("platform")
        analysis_type = payload.get("analysis_type")
        source_type = payload.get("source_type")
        limit = payload.get("limit", 5)

        search_fn = getattr(client, "search_evidence", None)
        if not callable(search_fn):
            return {"error": "search_evidence not available"}

        sources = await search_fn(
            query=query,
            book_id=book_id,
            platform=platform,
            analysis_type=analysis_type,
            source_type=source_type,
            limit=limit,
        )
        return {
            "sources": [source.model_dump(mode="json") for source in sources],
            "count": len(sources),
        }

    return {
        "knowledge.vector_search": {
            "handler": vector_search,
            "schema": {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "book_id": {"type": "integer"},
                    "platform": {"type": "string"},
                    "analysis_type": {"type": "string"},
                    "source_type": {"type": "string"},
                    "limit": {"type": "integer"},
                },
                "required": ["query"],
            },
            "check_fn": lambda: callable(getattr(client, "search_evidence", None)),
        },
    }
