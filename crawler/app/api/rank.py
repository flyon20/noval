from fastapi import APIRouter, Depends, HTTPException

from app.models.common import ApiResult
from app.models.rank import RankRequest
from app.security import require_internal_service_token
from app.services.crawler_factory import build_crawler

router = APIRouter(
    prefix="/internal",
    tags=["rank"],
    dependencies=[Depends(require_internal_service_token)],
)


@router.get("/board-catalog", response_model=ApiResult)
def board_catalog(platform: str):
    try:
        crawler = build_crawler(platform)
        data = crawler.fetch_board_catalog()
        return ApiResult(code=200, message="success", data=data)
    except ValueError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex


@router.post("/rank", response_model=ApiResult)
def rank(req: RankRequest):
    try:
        crawler = build_crawler(req.platform)
        data = crawler.fetch_rank(req.channelCode, req.boardCode)
        return ApiResult(code=200, message="success", data=data)
    except ValueError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex
