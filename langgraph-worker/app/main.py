from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.analysis import router as analysis_router
from app.api.knowledge import router as knowledge_router
from app.config import settings
from app.security import validate_internal_api_key_config


@asynccontextmanager
async def lifespan(_: FastAPI):
    validate_internal_api_key_config()
    yield


app = FastAPI(title=settings.app_name, lifespan=lifespan)
app.include_router(analysis_router)
app.include_router(knowledge_router)


@app.get("/health")
def health():
    return {"code": 200, "message": "success", "data": {"status": "UP"}}
