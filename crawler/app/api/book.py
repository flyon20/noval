from fastapi import APIRouter, HTTPException

from app.models.book import BookRequest
from app.models.common import ApiResult
from app.services.crawler_factory import build_crawler

router = APIRouter(prefix="/internal", tags=["book"])


@router.post("/book", response_model=ApiResult)
def book(req: BookRequest):
    try:
        crawler = build_crawler(req.platform)
        data = crawler.fetch_book(req.bookUrl)
        return ApiResult(code=200, message="success", data=data)
    except ValueError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex

