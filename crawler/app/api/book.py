from fastapi import APIRouter, Depends, HTTPException

from app.models.book import BookRequest
from app.models.common import ApiResult
from app.security import require_internal_service_token
from app.services.crawler_factory import build_crawler

router = APIRouter(
    prefix="/internal",
    tags=["book"],
    dependencies=[Depends(require_internal_service_token)],
)


@router.post("/book", response_model=ApiResult)
def book(req: BookRequest):
    try:
        crawler = build_crawler(req.platform)
        data = crawler.fetch_book(req.bookUrl)
        return ApiResult(code=200, message="success", data=data)
    except ValueError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex
