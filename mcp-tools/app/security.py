from __future__ import annotations

import hashlib
import hmac
import json
import re
import threading
import time
from typing import Any

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
from redis import Redis
from redis.exceptions import RedisError

from app.config import MIN_FASTMCP_INTERNAL_API_KEY_LENGTH, settings


RISKY_KEYS = {
    "cmd",
    "command",
    "file",
    "filepath",
    "filename",
    "href",
    "path",
    "script",
    "shell",
    "sql",
    "uri",
    "url",
}
URL_PATTERN = re.compile(r"(?i)\b(?:https?|ftp)://")
PATH_PATTERN = re.compile(
    r"(?i)(?:\b[a-z]:[\\/]|\\\\[^\\/\s]+[\\/][^\\/\s]+|"
    r"(?:^|[\s\"'])\.{1,2}[\\/]|"
    r"(?:^|[\s\"'])/(?:etc|home|users|tmp|var|opt|root|windows|program files)(?:[\\/]|$))"
)
SQL_SELECT_LIST_PATTERN = re.compile(
    r"(?is)\bselect\s+(?:distinct\s+)?(?:\*|"
    r"[a-z_][\w$]*(?:\s*,\s*[a-z_][\w$]*)+|"
    r"(?:[a-z_][\w$]*\.)+[a-z_*][\w$]*|"
    r"[a-z_][\w$]*\s*\([^\r\n;]*\))\s+from\s+[a-z_][\w$]*"
)
SQL_BARE_SELECT_PATTERN = re.compile(
    r"(?is)^\s*select\s+[a-z_][\w$]*\s+from\s+[a-z_][\w$]*\s*;?\s*$"
)
SQL_SELECT_CLAUSE_PATTERN = re.compile(
    r"(?is)\bselect\b.{0,512}\bfrom\b.{0,512}"
    r"\b(?:where|join|group\s+by|order\s+by|having|limit|offset|union)\b"
)
SQL_MUTATION_PATTERN = re.compile(
    r"(?is)\b(?:insert\s+into|update\s+[a-z_][\w$]*\s+set|"
    r"delete\s+from|merge\s+into)\b"
)
SQL_DDL_PATTERN = re.compile(
    r"(?is)\b(?:drop|alter|truncate|create)\s+"
    r"(?:table|database|schema|index|view|user)\b"
)
SQL_PERMISSION_PATTERN = re.compile(
    r"(?is)\b(?:grant|revoke)\b.{0,512}\b(?:on|to|from)\b"
)
SQL_COMMENT_PATTERN = re.compile(r"(?s)(?:--[^\r\n]*|/\*.*?\*/)")
SQL_SELECT_FROM_PATTERN = re.compile(r"(?is)\bselect\b.{0,512}\bfrom\b")
SQL_STATEMENT_SEPARATOR_PATTERN = re.compile(
    r"(?is);\s*(?:select|insert|update|delete|drop|alter|truncate|create|grant|revoke|merge)\b"
)
COMMAND_PATTERN = re.compile(
    r"(?i)(?:^|(?:&&|\|\||[;|])\s*)(?:sudo\s+)?"
    r"(?:bash|sh|zsh|cmd(?:\.exe)?|powershell|pwsh|curl|wget|invoke-webrequest|"
    r"rm|del|erase|copy|move|chmod|chown|nc|netcat|python(?:3)?|node|npm|npx|pip(?:3)?|git)"
    r"\b(?:\s|$)"
)
COMMAND_SUBSTITUTION_PATTERN = re.compile(r"(?:`[^`\r\n]+`|\$\([^\)\r\n]+\))")


class SignedCallEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    arguments: dict[str, Any]
    route: str = Field(min_length=1)
    userId: str = Field(min_length=1)
    projectId: str = Field(default="", max_length=128)
    supervisorPermissions: list[str] = Field(default_factory=list)
    timestamp: int
    nonce: str = Field(min_length=16, max_length=128)
    signature: str = Field(min_length=64, max_length=64, pattern=r"^[0-9a-f]+$")

    @field_validator("name", "route", "userId", "nonce", mode="before")
    @classmethod
    def normalize_required_text(cls, value: Any) -> str:
        return str(value or "").strip()

    @field_validator("projectId", mode="before")
    @classmethod
    def normalize_optional_project_id(cls, value: Any) -> str:
        return str(value or "").strip()

    @field_validator("supervisorPermissions", mode="before")
    @classmethod
    def normalize_permissions(cls, value: Any) -> list[str]:
        if not isinstance(value, (list, tuple, set, frozenset)):
            return []
        return sorted({str(item).strip() for item in value if str(item).strip()})


class _BoundedMemoryNonceReplayGuard:
    def __init__(self, *, max_entries: int) -> None:
        self._lock = threading.Lock()
        self._expires_at: dict[str, int] = {}
        self._max_entries = max(1, max_entries)

    def consume(self, nonce: str, *, now: int, expires_at: int) -> None:
        with self._lock:
            self._expires_at = {
                key: expiry
                for key, expiry in self._expires_at.items()
                if expiry > now
            }
            if nonce in self._expires_at:
                raise HTTPException(status_code=401, detail="replayed MCP call nonce")
            if len(self._expires_at) >= self._max_entries:
                raise HTTPException(status_code=503, detail="MCP nonce capacity exhausted")
            self._expires_at[nonce] = expires_at


class _RedisNonceReplayGuard:
    _CONSUME_SCRIPT = """
        if redis.call('exists', KEYS[1]) == 1 then
            return 0
        end
        local count = redis.call('incr', KEYS[2])
        if count == 1 then
            redis.call('expire', KEYS[2], ARGV[1])
        end
        if count > tonumber(ARGV[2]) then
            redis.call('decr', KEYS[2])
            return -1
        end
        redis.call('set', KEYS[1], '1', 'EX', ARGV[1])
        return 1
    """

    def __init__(self) -> None:
        self._client: Redis | None = None

    def consume(self, nonce: str, *, now: int, expires_at: int) -> None:
        if not settings.redis_host:
            raise HTTPException(status_code=503, detail="MCP nonce store is not configured")
        ttl_seconds = max(1, expires_at - now)
        nonce_hash = hashlib.sha256(nonce.encode("utf-8")).hexdigest()
        try:
            result = self._redis().eval(
                self._CONSUME_SCRIPT,
                2,
                f"noval:mcp:nonce:{nonce_hash}",
                "noval:mcp:nonce:capacity",
                ttl_seconds,
                settings.mcp_nonce_max_entries,
            )
        except RedisError as exc:
            raise HTTPException(status_code=503, detail="MCP nonce store is unavailable") from exc
        if result == 0:
            raise HTTPException(status_code=401, detail="replayed MCP call nonce")
        if result != 1:
            raise HTTPException(status_code=503, detail="MCP nonce capacity exhausted")

    def _redis(self) -> Redis:
        if self._client is None:
            self._client = Redis(
                host=settings.redis_host,
                port=settings.redis_port,
                password=settings.redis_password or None,
                db=settings.redis_db,
                socket_connect_timeout=1,
                socket_timeout=1,
                decode_responses=True,
            )
        return self._client


_NONCE_REPLAY_GUARD = _RedisNonceReplayGuard()


def internal_service_token_valid(provided: str | None) -> bool:
    expected = settings.mcp_internal_api_key
    return bool(
        provided
        and _configured_fastmcp_internal_api_key(expected)
        and hmac.compare_digest(provided, expected)
    )


def verify_signed_call(
    *,
    name: str,
    arguments: dict[str, Any],
    route: str | None,
    envelope: SignedCallEnvelope | None,
) -> tuple[set[str], dict[str, Any]]:
    signing_key = settings.mcp_call_signing_key
    if not _configured_secret(signing_key):
        raise HTTPException(status_code=503, detail="MCP call signing is not configured")
    if envelope is None:
        raise HTTPException(status_code=401, detail="missing MCP call signature envelope")
    normalized_route = str(route or "").strip()
    if (
        envelope.name != name
        or envelope.route != normalized_route
        or not hmac.compare_digest(
            _canonical_json(envelope.arguments).encode("utf-8"),
            _canonical_json(arguments).encode("utf-8"),
        )
    ):
        raise HTTPException(status_code=403, detail="MCP call body does not match signed envelope")
    if (
        str(arguments.get("userId") or "").strip() != envelope.userId
        or str(arguments.get("projectId") or "").strip() != envelope.projectId
    ):
        raise HTTPException(status_code=403, detail="MCP call identity does not match signed envelope")

    now = int(time.time())
    max_age = settings.mcp_call_signature_max_age_seconds
    if abs(now - envelope.timestamp) > max_age:
        raise HTTPException(status_code=401, detail="expired MCP call signature")

    claims = envelope.model_dump(exclude={"signature"})
    expected = hmac.new(
        signing_key.encode("utf-8"),
        _canonical_json(claims).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(expected, envelope.signature):
        raise HTTPException(status_code=401, detail="invalid MCP call signature")

    _NONCE_REPLAY_GUARD.consume(
        envelope.nonce,
        now=now,
        expires_at=max(now, envelope.timestamp) + max_age,
    )
    return set(envelope.supervisorPermissions), dict(envelope.arguments)


def verify_standard_signed_call(
    *,
    name: str,
    public_arguments: dict[str, Any],
    envelope: SignedCallEnvelope | None,
    hidden_keys: set[str],
) -> tuple[set[str], dict[str, Any], str]:
    if envelope is None:
        raise HTTPException(status_code=401, detail="missing MCP call signature envelope")
    if any(key in public_arguments for key in hidden_keys):
        raise HTTPException(status_code=403, detail="trusted MCP arguments must not be supplied by the client")
    signed_arguments = dict(envelope.arguments)
    signed_public_arguments = {
        key: value
        for key, value in signed_arguments.items()
        if key not in hidden_keys
    }
    if not hmac.compare_digest(
        _canonical_json(signed_public_arguments).encode("utf-8"),
        _canonical_json(public_arguments).encode("utf-8"),
    ):
        raise HTTPException(status_code=403, detail="MCP call body does not match signed public arguments")
    permissions, verified_arguments = verify_signed_call(
        name=name,
        arguments=signed_arguments,
        route=envelope.route,
        envelope=envelope,
    )
    return permissions, verified_arguments, envelope.route


def _configured_secret(value: str | None) -> bool:
    normalized = str(value or "").strip()
    return len(normalized) >= 32 and not normalized.upper().startswith("CHANGE_ME")


def _configured_fastmcp_internal_api_key(value: str | None) -> bool:
    normalized = str(value or "").strip()
    return (
        len(normalized) >= MIN_FASTMCP_INTERNAL_API_KEY_LENGTH
        and not normalized.upper().startswith("CHANGE_ME")
    )


def security_configuration_ready() -> bool:
    return (
        _configured_fastmcp_internal_api_key(settings.mcp_internal_api_key)
        and _configured_secret(settings.mcp_call_signing_key)
        and _configured_secret(settings.mcp_backend_attestation_key)
        and bool(settings.redis_host)
    )


def _canonical_json(value: Any) -> str:
    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="MCP call contains non-canonical JSON") from exc


def validate_safe_arguments(arguments: dict[str, Any]) -> None:
    risky = _find_risky_argument(arguments)
    if risky:
        raise HTTPException(status_code=400, detail=f"unsafe tool argument: {risky}")


def _find_risky_argument(value: Any, *, key: str = "") -> str | None:
    if isinstance(value, dict):
        for child_key, child_value in value.items():
            child_key_text = str(child_key)
            if child_key_text.casefold() in RISKY_KEYS:
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
        if (
            URL_PATTERN.search(value)
            or PATH_PATTERN.search(value)
            or _contains_sql(value)
            or COMMAND_PATTERN.search(value)
            or COMMAND_SUBSTITUTION_PATTERN.search(value)
        ):
            return key or "value"
    return None


def _contains_sql(value: str) -> bool:
    if any(pattern.search(value) for pattern in (
        SQL_SELECT_LIST_PATTERN,
        SQL_BARE_SELECT_PATTERN,
        SQL_SELECT_CLAUSE_PATTERN,
        SQL_MUTATION_PATTERN,
        SQL_DDL_PATTERN,
        SQL_PERMISSION_PATTERN,
        SQL_STATEMENT_SEPARATOR_PATTERN,
    )):
        return True
    return bool(SQL_COMMENT_PATTERN.search(value) and SQL_SELECT_FROM_PATTERN.search(value))
