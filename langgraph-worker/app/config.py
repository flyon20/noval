import json
import os

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


MIN_FASTMCP_INTERNAL_API_KEY_LENGTH = 32
MIN_MCP_SIGNING_KEY_LENGTH = 32


def _fastmcp_internal_api_key() -> str:
    return os.getenv(
        "FASTMCP_INTERNAL_API_KEY",
        os.getenv("MCP_INTERNAL_API_KEY", ""),
    )


class Settings(BaseModel):
    model_config = ConfigDict(validate_default=True)

    app_name: str = "novel-langgraph-worker"
    host: str = "0.0.0.0"
    port: int = int(os.getenv("LANGGRAPH_WORKER_PORT", "8001"))
    internal_api_key: str = os.getenv("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY", "")
    provider_type: str = os.getenv("AI_PROVIDER_TYPE", "openai-compatible")
    dify_base_url: str = os.getenv("AI_DIFY_BASE_URL", "")
    dify_api_key: str = os.getenv("DIFY_API_KEY", os.getenv("AI_DIFY_API_KEY", ""))
    fallback_model: str = os.getenv("AI_FALLBACK_MODEL", "local-fallback")
    openai_base_url: str = os.getenv("AI_OPENAI_COMPATIBLE_BASE_URL", "https://api.deepseek.com/v1")
    openai_wire_api: str = os.getenv("AI_OPENAI_COMPATIBLE_WIRE_API", "responses")
    openai_provider_profile: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_PROVIDER_PROFILE",
        "default",
    )
    openai_provider_profile_version: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_PROVIDER_PROFILE_VERSION",
        "1",
    )
    openai_responses_base_url: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_RESPONSES_BASE_URL",
        "https://api.deepseek.com",
    )
    openai_responses_models: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_RESPONSES_MODELS",
        "deepseek-v4-flash,deepseek-v4-pro",
    )
    # 缓存策略按本次实际 dispatch 的模型名匹配（支持末尾 * 通配），不按
    # providerType 或全局默认模型推断。GPT 模型默认启用 prompt_cache_key；
    # 其它模型必须显式加入该列表。
    openai_prompt_cache_key_models: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_PROMPT_CACHE_KEY_MODELS",
        "gpt-*",
    )
    # DeepSeek 的 Responses user / Chat user_id 是隔离标识，不是 cache key。
    # 同样只对当前实际模型名匹配，避免把该字段发送给其它兼容网关。
    openai_provider_user_models: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_PROVIDER_USER_MODELS",
        "deepseek-*",
    )
    context_capabilities_json: str = os.getenv(
        "AI_MODEL_CONTEXT_CAPABILITIES_JSON",
        "",
    )
    context_compaction_enabled: bool = os.getenv(
        "AI_CONTEXT_COMPACTION_ENABLED",
        "true",
    ).lower() in {"1", "true", "yes", "on"}
    openai_responses_chat_fallback_enabled: bool = os.getenv(
        "AI_OPENAI_COMPATIBLE_RESPONSES_CHAT_FALLBACK_ENABLED",
        "true",
    ).lower() in {"1", "true", "yes", "on"}
    openai_api_key: str = os.getenv("DEEPSEEK_API_KEY", os.getenv("AI_OPENAI_COMPATIBLE_API_KEY", ""))
    default_model: str = os.getenv("AI_OPENAI_COMPATIBLE_DEFAULT_MODEL", "deepseek-v4-flash")
    intent_model: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_INTENT_MODEL",
        os.getenv("AI_OPENAI_COMPATIBLE_DEFAULT_MODEL", "deepseek-v4-flash"),
    )
    review_model: str = os.getenv(
        "AI_OPENAI_COMPATIBLE_REVIEW_MODEL",
        os.getenv("AI_OPENAI_COMPATIBLE_DEFAULT_MODEL", "deepseek-v4-flash"),
    )
    provider_fallback_model: str = os.getenv("PROVIDER_FALLBACK_MODEL", os.getenv("AI_PROVIDER_FALLBACK_MODEL", ""))
    deep_model: str = os.getenv("AI_OPENAI_COMPATIBLE_DEEP_MODEL", "deepseek-v4-pro")
    timeout_millis: int = int(os.getenv("AI_LANGGRAPH_WORKER_TIMEOUT_MILLIS", "30000"))
    max_active_deep_runs: int = Field(default=int(os.getenv("NOVAL_RESOURCE_MAX_ACTIVE_DEEP_RUNS", "1")), gt=0)
    max_active_fast_runs: int = Field(default=int(os.getenv("NOVAL_RESOURCE_MAX_ACTIVE_FAST_RUNS", "2")), gt=0)
    max_active_llm_calls: int = Field(
        default=int(os.getenv("NOVAL_RESOURCE_MAX_ACTIVE_LLM_CALLS", os.getenv("AI_LANGGRAPH_MAX_ACTIVE_LLM_CALLS", "2"))),
        gt=0,
    )
    max_delegated_agent_concurrency: int = Field(
        default=int(os.getenv("NOVAL_RESOURCE_MAX_DELEGATED_AGENT_CONCURRENCY", "1")), gt=0
    )
    max_index_concurrency: int = Field(default=int(os.getenv("NOVAL_RESOURCE_MAX_INDEX_CONCURRENCY", "1")), gt=0)
    max_crawler_concurrency: int = Field(default=int(os.getenv("NOVAL_RESOURCE_MAX_CRAWLER_CONCURRENCY", "1")), gt=0)
    memory_pause_percent: int = int(os.getenv("NOVAL_RESOURCE_MEMORY_PAUSE_PERCENT", "85"))
    memory_reject_deep_percent: int = int(os.getenv("NOVAL_RESOURCE_MEMORY_REJECT_DEEP_PERCENT", "92"))
    disk_warn_percent: int = int(os.getenv("NOVAL_RESOURCE_DISK_WARN_PERCENT", "80"))
    disk_stop_import_percent: int = int(os.getenv("NOVAL_RESOURCE_DISK_STOP_IMPORT_PERCENT", "90"))
    queue_backlog_warn_count: int = Field(default=int(os.getenv("NOVAL_RESOURCE_QUEUE_BACKLOG_WARN_COUNT", "20")), gt=0)
    queue_oldest_warn_minutes: int = Field(default=int(os.getenv("NOVAL_RESOURCE_QUEUE_OLDEST_WARN_MINUTES", "5")), gt=0)
    backend_base_url: str = os.getenv("AI_BACKEND_BASE_URL", "http://backend:8080")
    backend_internal_api_key: str = os.getenv(
        "AI_BACKEND_INTERNAL_API_KEY",
        os.getenv("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY", ""),
    )
    backend_tool_timeout_millis: int = int(os.getenv("AI_BACKEND_TOOL_TIMEOUT_MILLIS", "90000"))
    mcp_base_url: str = os.getenv("AI_MCP_BASE_URL", "http://fastmcp-tools:7001")
    mcp_internal_api_key: str = _fastmcp_internal_api_key()
    mcp_call_signing_key: str = os.getenv("MCP_CALL_SIGNING_KEY", "")
    mcp_timeout_millis: int = int(os.getenv("AI_MCP_TIMEOUT_MILLIS", "30000"))
    agent_max_parallel_tool_calls: int = max(1, int(os.getenv("AI_AGENT_MAX_PARALLEL_TOOL_CALLS", "3")))
    agent_max_skill_chars: int = max(0, int(os.getenv("AI_AGENT_MAX_SKILL_CHARS", "0")))
    agent_max_material_chars: int = max(1000, int(os.getenv("AI_AGENT_MAX_MATERIAL_CHARS", "12000")))
    agent_market_topn_default: int = max(1, int(os.getenv("AI_AGENT_MARKET_TOPN_DEFAULT", "30")))
    agent_latest_rank_max_age_days: int = max(1, int(os.getenv("AI_AGENT_LATEST_RANK_MAX_AGE_DAYS", "3")))
    agent_chapters_per_rank_book: int = max(1, int(os.getenv("AI_AGENT_CHAPTERS_PER_RANK_BOOK", "1")))
    agent_intent_llm_fallback_enabled: bool = os.getenv("AGENT_INTENT_LLM_FALLBACK_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
    agent_intent_llm_min_confidence: float = float(os.getenv("AGENT_INTENT_LLM_MIN_CONFIDENCE", "0.82"))
    agent_model_first_intent_enabled: bool = os.getenv("AGENT_MODEL_FIRST_INTENT_ENABLED", "false").lower() in {"1", "true", "yes", "on"}
    agent_domain_model_specialists_enabled: bool = os.getenv("AGENT_DOMAIN_MODEL_SPECIALISTS_ENABLED", "false").lower() in {"1", "true", "yes", "on"}
    agent_answer_review_enabled: bool = os.getenv("AGENT_ANSWER_REVIEW_ENABLED", "false").lower() in {"1", "true", "yes", "on"}
    agent_answer_revision_enabled: bool = os.getenv("AGENT_ANSWER_REVISION_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
    mysql_host: str = os.getenv("MYSQL_HOST", "mysql")
    mysql_port: int = int(os.getenv("MYSQL_PORT", "3306"))
    mysql_database: str = os.getenv("MYSQL_DATABASE", "novel_analyzer")
    mysql_user: str = os.getenv("MYSQL_USER", "novel")
    mysql_password: str = os.getenv("MYSQL_PASSWORD", "")
    langgraph_checkpoint_backend: str = os.getenv("AI_LANGGRAPH_CHECKPOINT_BACKEND", "mysql")

    @field_validator("openai_wire_api")
    @classmethod
    def validate_openai_wire_api(cls, value: str) -> str:
        normalized = str(value or "").strip().lower().replace("-", "_")
        if normalized in {"chat", "chat_completion", "chat_completions"}:
            return "chat_completions"
        if normalized == "responses":
            return normalized
        raise ValueError("AI_OPENAI_COMPATIBLE_WIRE_API must be responses or chat_completions")

    @field_validator("context_capabilities_json")
    @classmethod
    def validate_context_capabilities_json(cls, value: str) -> str:
        normalized = str(value or "").strip()
        if not normalized:
            return ""
        try:
            payload = json.loads(normalized)
        except json.JSONDecodeError as exc:
            raise ValueError("AI_MODEL_CONTEXT_CAPABILITIES_JSON must be valid JSON") from exc
        if not isinstance(payload, dict):
            raise ValueError("AI_MODEL_CONTEXT_CAPABILITIES_JSON must be a JSON object")
        return normalized

    @field_validator("mcp_internal_api_key")
    @classmethod
    def validate_fastmcp_internal_api_key(cls, value: str) -> str:
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

    @field_validator("mcp_call_signing_key")
    @classmethod
    def validate_mcp_call_signing_key(cls, value: str) -> str:
        normalized = str(value or "").strip()
        if (
            len(normalized) < MIN_MCP_SIGNING_KEY_LENGTH
            or normalized.upper().startswith("CHANGE_ME")
        ):
            raise ValueError(
                "MCP_CALL_SIGNING_KEY is required, must not be a placeholder, "
                f"and must be at least {MIN_MCP_SIGNING_KEY_LENGTH} characters"
            )
        return normalized

    @model_validator(mode="after")
    def validate_resource_thresholds(self) -> "Settings":
        if not 0 < self.memory_pause_percent < self.memory_reject_deep_percent <= 100:
            raise ValueError("memory thresholds must satisfy 0 < pause < reject <= 100")
        if not 0 < self.disk_warn_percent < self.disk_stop_import_percent <= 100:
            raise ValueError("disk thresholds must satisfy 0 < warn < stop <= 100")
        return self


settings = Settings()
