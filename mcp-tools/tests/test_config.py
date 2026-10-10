from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

import pytest

from app.config import Settings, validate_fastmcp_internal_api_key, validate_mcp_signing_key


@pytest.mark.parametrize(
    "value",
    ["", "short-fastmcp-key", "CHANGE_ME_WITH_A_RANDOM_FASTMCP_INTERNAL_KEY"],
)
def test_fastmcp_internal_api_key_rejects_missing_weak_or_placeholder_values(value: str) -> None:
    with pytest.raises(ValueError, match="FASTMCP_INTERNAL_API_KEY"):
        validate_fastmcp_internal_api_key(value)


def test_fastmcp_internal_api_key_accepts_32_character_secret() -> None:
    value = "f" * 32

    assert validate_fastmcp_internal_api_key(value) == value


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("MCP_CALL_SIGNING_KEY", "short-signing-key"),
        ("MCP_BACKEND_ATTESTATION_KEY", ""),
        ("MCP_CALL_SIGNING_KEY", "CHANGE_ME_WITH_A_RANDOM_MCP_CALL_SIGNING_KEY"),
    ],
)
def test_mcp_signing_keys_reject_missing_weak_or_placeholder_values(name: str, value: str) -> None:
    with pytest.raises(ValueError, match=name):
        validate_mcp_signing_key(name, value)


def test_mcp_signing_keys_accept_32_character_secrets() -> None:
    value = "s" * 32

    assert validate_mcp_signing_key("MCP_CALL_SIGNING_KEY", value) == value


def test_settings_prefers_canonical_fastmcp_internal_api_key() -> None:
    with patch.dict(
        os.environ,
        {
            "FASTMCP_INTERNAL_API_KEY": "canonical-fastmcp-internal-key-1234567890",
            "MCP_INTERNAL_API_KEY": "legacy-fastmcp-internal-key-1234567890123",
        },
    ):
        configured = Settings()

    assert configured.mcp_internal_api_key == "canonical-fastmcp-internal-key-1234567890"


def test_settings_accepts_legacy_name_during_migration() -> None:
    environment = os.environ.copy()
    environment.pop("FASTMCP_INTERNAL_API_KEY", None)
    environment["MCP_INTERNAL_API_KEY"] = "legacy-fastmcp-internal-key-1234567890123"
    with patch.dict(os.environ, environment, clear=True):
        configured = Settings()

    assert configured.mcp_internal_api_key == "legacy-fastmcp-internal-key-1234567890123"


def test_fastmcp_internal_api_key_is_required_on_process_startup() -> None:
    environment = os.environ.copy()
    environment.pop("FASTMCP_INTERNAL_API_KEY", None)
    environment.pop("MCP_INTERNAL_API_KEY", None)

    result = subprocess.run(
        [sys.executable, "-c", "from app.config import settings"],
        cwd=Path(__file__).resolve().parents[1],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode != 0
    assert "FASTMCP_INTERNAL_API_KEY" in result.stderr


@pytest.mark.parametrize("missing_name", ["MCP_CALL_SIGNING_KEY", "MCP_BACKEND_ATTESTATION_KEY"])
def test_mcp_signing_keys_are_required_on_process_startup(missing_name: str) -> None:
    environment = os.environ.copy()
    environment.pop(missing_name, None)

    result = subprocess.run(
        [sys.executable, "-c", "from app.config import settings"],
        cwd=Path(__file__).resolve().parents[1],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode != 0
    assert missing_name in result.stderr
