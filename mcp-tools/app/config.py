from __future__ import annotations

import os


MIN_FASTMCP_INTERNAL_API_KEY_LENGTH = 32
MIN_MCP_SIGNING_KEY_LENGTH = 32


def _fastmcp_internal_api_key() -> str:
    return os.getenv(
        "FASTMCP_INTERNAL_API_KEY",
        os.getenv("MCP_INTERNAL_API_KEY", ""),
    )


def validate_fastmcp_internal_api_key(value: str) -> str:
    normalized = str(value or "").strip()
    if (
        len(normalized) < MIN_FASTMCP_INTERNAL_API_KEY_LENGTH
        or normalized.upper().startswith("CHANGE_ME")
    ):
        raise ValueError(
            "FASTMCP_INTERNAL_API_KEY is required, must not be a placeholder, "
            f"and must be at least {MIN_FASTMCP_INTERNAL_API_KEY_LENGTH} characters"
        )
    return normalized


def validate_mcp_signing_key(name: str, value: str) -> str:
    normalized = str(value or "").strip()
    if (
        len(normalized) < MIN_MCP_SIGNING_KEY_LENGTH
        or normalized.upper().startswith("CHANGE_ME")
    ):
        raise ValueError(
            f"{name} is required, must not be a placeholder, "
            f"and must be at least {MIN_MCP_SIGNING_KEY_LENGTH} characters"
        )
    return normalized


class Settings:
    app_name = "noval-mcp-tools"

    def __init__(self) -> None:
        self.backend_base_url = os.getenv("AI_BACKEND_BASE_URL", "http://backend:8080").rstrip("/")
        self.backend_internal_api_key = os.getenv("AI_BACKEND_INTERNAL_API_KEY", "")
        self.mcp_internal_api_key = validate_fastmcp_internal_api_key(_fastmcp_internal_api_key())
        self.mcp_call_signing_key = validate_mcp_signing_key(
            "MCP_CALL_SIGNING_KEY",
            os.getenv("MCP_CALL_SIGNING_KEY", ""),
        )
        self.mcp_backend_attestation_key = validate_mcp_signing_key(
            "MCP_BACKEND_ATTESTATION_KEY",
            os.getenv("MCP_BACKEND_ATTESTATION_KEY", ""),
        )
        self.mcp_backend_attestation_max_age_seconds = max(
            1,
            int(os.getenv("MCP_BACKEND_ATTESTATION_MAX_AGE_SECONDS", "60")),
        )
        self.mcp_call_signature_max_age_seconds = max(
            1,
            int(os.getenv("MCP_CALL_SIGNATURE_MAX_AGE_SECONDS", "60")),
        )
        self.mcp_nonce_max_entries = max(1, int(os.getenv("MCP_NONCE_MAX_ENTRIES", "10000")))
        self.redis_host = os.getenv("REDIS_HOST", "").strip()
        self.redis_port = int(os.getenv("REDIS_PORT", "6379"))
        self.redis_password = os.getenv("REDIS_PASSWORD", "")
        self.redis_db = int(os.getenv("MCP_NONCE_REDIS_DB", "3"))
        self.backend_timeout_millis = int(os.getenv("AI_BACKEND_TOOL_TIMEOUT_MILLIS", "90000"))


settings = Settings()
