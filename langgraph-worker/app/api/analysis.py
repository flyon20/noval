from __future__ import annotations

import json
from collections.abc import AsyncGenerator

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.models.analysis import RunRequest
from app.security import verify_internal_api_key
from app.services.analysis_service import LangGraphAnalysisService

router = APIRouter(prefix="/internal/analysis", tags=["analysis"], dependencies=[Depends(verify_internal_api_key)])
analysis_service = LangGraphAnalysisService()


@router.post("/run")
async def run_analysis(request: RunRequest):
    response = await analysis_service.run(request)
    return response.model_dump()


@router.post("/run/stream")
async def run_analysis_stream(request: RunRequest):
    async def event_stream() -> AsyncGenerator[str, None]:
        async for event in analysis_service.stream(request):
            yield f"event: {event['event']}\n"
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")
