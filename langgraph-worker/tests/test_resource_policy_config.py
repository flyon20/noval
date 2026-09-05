import os
from pathlib import Path
import subprocess
import sys

import pytest
from pydantic import ValidationError

from app.config import Settings, settings


def test_j3160_resource_policy_defaults() -> None:
    assert settings.max_active_deep_runs == 1
    assert settings.max_active_fast_runs == 2
    assert settings.max_active_llm_calls == 2
    assert settings.max_delegated_agent_concurrency == 1
    assert settings.max_index_concurrency == 1
    assert settings.max_crawler_concurrency == 1
    assert settings.memory_pause_percent == 85
    assert settings.memory_reject_deep_percent == 92
    assert settings.disk_warn_percent == 80
    assert settings.disk_stop_import_percent == 90
    assert settings.queue_backlog_warn_count == 20
    assert settings.queue_oldest_warn_minutes == 5
    assert settings.agent_latest_rank_max_age_days == 3
    assert settings.default_model == "deepseek-v4-flash"
    assert settings.intent_model == "deepseek-v4-flash"
    assert settings.review_model == "deepseek-v4-flash"


def test_compose_passes_explicit_deep_model_to_worker() -> None:
    compose = (Path(__file__).resolve().parents[2] / "docker-compose.yml").read_text(encoding="utf-8")

    assert "AI_OPENAI_COMPATIBLE_DEEP_MODEL: ${AI_OPENAI_COMPATIBLE_DEEP_MODEL:-deepseek-v4-pro}" in compose


def test_responses_is_default_worker_wire_with_explicit_compatibility_controls() -> None:
    root = Path(__file__).resolve().parents[2]
    compose = (root / "docker-compose.yml").read_text(encoding="utf-8")
    example = (root / ".env.example").read_text(encoding="utf-8")

    assert Settings().openai_wire_api == "responses"
    assert Settings().openai_responses_models == "deepseek-v4-flash,deepseek-v4-pro"
    assert "AI_OPENAI_COMPATIBLE_WIRE_API: ${AI_OPENAI_COMPATIBLE_WIRE_API:-responses}" in compose
    assert "AI_OPENAI_COMPATIBLE_RESPONSES_BASE_URL: ${AI_OPENAI_COMPATIBLE_RESPONSES_BASE_URL:-https://api.deepseek.com}" in compose
    assert "AI_OPENAI_COMPATIBLE_RESPONSES_MODELS: ${AI_OPENAI_COMPATIBLE_RESPONSES_MODELS:-deepseek-v4-flash,deepseek-v4-pro}" in compose
    assert "AI_OPENAI_COMPATIBLE_RESPONSES_CHAT_FALLBACK_ENABLED: ${AI_OPENAI_COMPATIBLE_RESPONSES_CHAT_FALLBACK_ENABLED:-true}" in compose
    assert "AI_OPENAI_COMPATIBLE_DEFAULT_MODEL: ${AI_OPENAI_COMPATIBLE_DEFAULT_MODEL:-deepseek-v4-flash}" in compose
    assert "AI_OPENAI_COMPATIBLE_INTENT_MODEL: ${AI_OPENAI_COMPATIBLE_INTENT_MODEL:-deepseek-v4-flash}" in compose
    assert "AI_OPENAI_COMPATIBLE_REVIEW_MODEL: ${AI_OPENAI_COMPATIBLE_REVIEW_MODEL:-deepseek-v4-flash}" in compose
    assert "AI_OPENAI_COMPATIBLE_WIRE_API=responses" in example
    assert "AI_OPENAI_COMPATIBLE_RESPONSES_BASE_URL=https://api.deepseek.com" in example
    assert "AI_OPENAI_COMPATIBLE_RESPONSES_MODELS=deepseek-v4-flash,deepseek-v4-pro" in example
    assert "AI_OPENAI_COMPATIBLE_DEFAULT_MODEL=deepseek-v4-flash" in example


def test_provider_wire_rejects_unknown_protocol() -> None:
    with pytest.raises(ValidationError, match="WIRE_API"):
        Settings(openai_wire_api="unknown")


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("max_active_deep_runs", 0),
        ("max_active_fast_runs", 0),
        ("max_active_llm_calls", 0),
        ("max_delegated_agent_concurrency", 0),
        ("max_index_concurrency", 0),
        ("max_crawler_concurrency", 0),
        ("queue_backlog_warn_count", 0),
        ("queue_oldest_warn_minutes", 0),
    ],
)
def test_resource_policy_rejects_non_positive_limits(field: str, value: int) -> None:
    with pytest.raises(ValidationError):
        Settings(**{field: value})


def test_resource_policy_rejects_invalid_memory_threshold_order() -> None:
    with pytest.raises(ValidationError, match="memory thresholds"):
        Settings(memory_pause_percent=92, memory_reject_deep_percent=92)


def test_resource_policy_rejects_invalid_disk_threshold_order() -> None:
    with pytest.raises(ValidationError, match="disk thresholds"):
        Settings(disk_warn_percent=91, disk_stop_import_percent=90)


def test_resource_policy_rejects_invalid_environment_default_on_startup() -> None:
    environment = os.environ.copy()
    environment["NOVAL_RESOURCE_MAX_ACTIVE_LLM_CALLS"] = "0"

    result = subprocess.run(
        [sys.executable, "-c", "from app.config import settings"],
        cwd=os.getcwd(),
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode != 0
    assert "max_active_llm_calls" in result.stderr


@pytest.mark.parametrize(
    "value",
    ["", "short-fastmcp-key", "CHANGE_ME_WITH_A_RANDOM_FASTMCP_INTERNAL_KEY"],
)
def test_fastmcp_internal_api_key_rejects_missing_weak_or_placeholder_values(value: str) -> None:
    with pytest.raises(ValidationError, match="FASTMCP_INTERNAL_API_KEY"):
        Settings(mcp_internal_api_key=value)


def test_fastmcp_internal_api_key_accepts_32_character_secret() -> None:
    value = "f" * 32

    configured = Settings(mcp_internal_api_key=value)

    assert configured.mcp_internal_api_key == value


@pytest.mark.parametrize(
    "value",
    ["", "short-signing-key", "CHANGE_ME_WITH_A_RANDOM_MCP_CALL_SIGNING_KEY"],
)
def test_mcp_call_signing_key_rejects_missing_weak_or_placeholder_values(value: str) -> None:
    with pytest.raises(ValidationError, match="MCP_CALL_SIGNING_KEY"):
        Settings(mcp_call_signing_key=value)


def test_mcp_call_signing_key_accepts_32_character_secret() -> None:
    value = "s" * 32

    configured = Settings(mcp_call_signing_key=value)

    assert configured.mcp_call_signing_key == value


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


def test_mcp_call_signing_key_is_required_on_process_startup() -> None:
    environment = os.environ.copy()
    environment.pop("MCP_CALL_SIGNING_KEY", None)

    result = subprocess.run(
        [sys.executable, "-c", "from app.config import settings"],
        cwd=Path(__file__).resolve().parents[1],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode != 0
    assert "MCP_CALL_SIGNING_KEY" in result.stderr
