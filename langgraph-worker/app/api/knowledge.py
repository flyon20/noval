from __future__ import annotations

import json
from collections.abc import AsyncGenerator

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.models.knowledge import KnowledgeChatRequest
from app.security import verify_internal_api_key
from app.services.novel_research_agent import NovelResearchAgent

router = APIRouter(prefix="/internal/knowledge", tags=["knowledge"], dependencies=[Depends(verify_internal_api_key)])
research_agent = NovelResearchAgent()


@router.post("/chat")
async def run_knowledge_chat(request: KnowledgeChatRequest):
    response = await research_agent.run(request)
    return response.model_dump()


@router.post("/chat/stream")
async def run_knowledge_chat_stream(request: KnowledgeChatRequest):
    async def event_stream() -> AsyncGenerator[str, None]:
        async for event in research_agent.stream(request):
            yield f"event: {event['event']}\n"
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")
