from fastapi import APIRouter, Depends, HTTPException

from app.models.common import ApiResult
from app.models.search import BookSearchRequest
from app.security import require_internal_service_token
from app.services.crawler_factory import build_crawler

router = APIRouter(
    prefix="/internal",
    tags=["search"],
    dependencies=[Depends(require_internal_service_token)],
)


@router.post("/books/search", response_model=ApiResult)
def search_books(req: BookSearchRequest):
    try:
        crawler = build_crawler(req.platform, timeout_seconds=req.timeoutSeconds)
        data = crawler.search_books(req.keyword, req.limit)
        return ApiResult(code=200, message="success", data=data)
    except ValueError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex