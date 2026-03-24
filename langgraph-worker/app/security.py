from fastapi import Header, HTTPException

from app.config import settings


def validate_internal_api_key_config() -> None:
    if not settings.internal_api_key or len(settings.internal_api_key.strip()) < 8:
        raise RuntimeError("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY is required and must be at least 8 characters")


def verify_internal_api_key(x_internal_service_token: str | None = Header(default=None)) -> None:
    if x_internal_service_token != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="invalid internal service token")
