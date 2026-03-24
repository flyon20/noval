from __future__ import annotations

from secrets import compare_digest

from fastapi import Header, HTTPException

from app.config import settings

INTERNAL_API_KEY_HEADER = "X-Internal-Service-Token"
MIN_INTERNAL_API_KEY_LENGTH = 32


def validate_internal_api_key_config() -> None:
    internal_api_key = settings.internal_api_key.strip()
    if not internal_api_key:
        raise RuntimeError("crawler internal API key must be configured")
    if len(internal_api_key) < MIN_INTERNAL_API_KEY_LENGTH:
        raise RuntimeError("crawler internal API key must be at least 32 characters")


def require_internal_service_token(
    x_internal_service_token: str | None = Header(default=None),
) -> None:
    token = x_internal_service_token or ""
    expected = settings.internal_api_key
    if not token or not compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="unauthorized internal caller")
