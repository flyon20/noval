from __future__ import annotations

import re
from typing import Any

from fastapi import Header, HTTPException

from app.config import settings


RISKY_KEYS = {
    "url",
    "uri",
    "href",
    "path",
    "file",
    "filePath",
    "filename",
    "sql",
    "query",
}
URL_PATTERN = re.compile(r"(?i)\bhttps?://")
PATH_PATTERN = re.compile(r"(?i)(?:[a-z]:[\\/]|(?:^|[\\/])\.\.(?:[\\/]|$)|/etc/|/home/|/users/)")
SQL_PATTERN = re.compile(r"(?i)\b(select|insert|update|delete|drop|alter|truncate)\b.+\b(from|into|table|where|set)\b")


async def verify_internal_token(x_internal_service_token: str | None = Header(default=None)) -> None:
    expected = settings.mcp_internal_api_key
    if not expected or x_internal_service_token != expected:
        raise HTTPException(status_code=401, detail="invalid internal service token")


def validate_safe_arguments(arguments: dict[str, Any]) -> None:
    risky = _find_risky_argument(arguments)
    if risky:
        raise HTTPException(status_code=400, detail=f"unsafe tool argument: {risky}")


def _find_risky_argument(value: Any, *, key: str = "") -> str | None:
    if isinstance(value, dict):
        for child_key, child_value in value.items():
            child_key_text = str(child_key)
            if child_key_text in RISKY_KEYS:
                return child_key_text
            found = _find_risky_argument(child_value, key=child_key_text)
            if found:
                return found
    elif isinstance(value, list):
        for item in value:
            found = _find_risky_argument(item, key=key)
            if found:
                return found
    elif isinstance(value, str):
        if URL_PATTERN.search(value) or PATH_PATTERN.search(value) or SQL_PATTERN.search(value):
            return key or "value"
    return None
