from fastapi import APIRouter, Depends, HTTPException

from app.models.chapter import ChapterRequest
from app.models.common import ApiResult
from app.security import require_internal_service_token
from app.services.crawler_factory import build_crawler

router = APIRouter(
    prefix="/internal",
    tags=["chapter"],
    dependencies=[Depends(require_internal_service_token)],
)


@router.post("/chapters", response_model=ApiResult)
def chapters(req: ChapterRequest):
    try:
        crawler = build_crawler(req.platform)
        data = crawler.fetch_chapters(req.bookUrl, req.chapterCount)
        return ApiResult(code=200, message="success", data=data)
    except ValueError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex
