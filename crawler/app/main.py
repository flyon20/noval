from fastapi import FastAPI

from app.api.book import router as book_router
from app.api.chapter import router as chapter_router
from app.api.rank import router as rank_router
from app.config import settings
from app.models.common import ApiResult

app = FastAPI(title=settings.app_name)
app.include_router(rank_router)
app.include_router(book_router)
app.include_router(chapter_router)


@app.get("/health", response_model=ApiResult)
def health():
    return ApiResult(code=200, message="success", data={"status": "UP", "service": settings.app_name})

