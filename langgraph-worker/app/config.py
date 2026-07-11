import os

from pydantic import BaseModel


class Settings(BaseModel):
    app_name: str = "novel-langgraph-worker"
    host: str = "0.0.0.0"
    port: int = int(os.getenv("LANGGRAPH_WORKER_PORT", "8001"))
    internal_api_key: str = os.getenv("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY", "")
    provider_type: str = os.getenv("AI_PROVIDER_TYPE", "openai-compatible")
    dify_base_url: str = os.getenv("AI_DIFY_BASE_URL", "")
    dify_api_key: str = os.getenv("DIFY_API_KEY", os.getenv("AI_DIFY_API_KEY", ""))
    fallback_model: str = os.getenv("AI_FALLBACK_MODEL", "local-fallback")
    openai_base_url: str = os.getenv("AI_OPENAI_COMPATIBLE_BASE_URL", "https://api.deepseek.com/v1")
    openai_api_key: str = os.getenv("DEEPSEEK_API_KEY", os.getenv("AI_OPENAI_COMPATIBLE_API_KEY", ""))
    default_model: str = os.getenv("AI_OPENAI_COMPATIBLE_DEFAULT_MODEL", "deepseek-chat")
    deep_model: str = os.getenv("AI_OPENAI_COMPATIBLE_DEEP_MODEL", "deepseek-v4-pro")
    timeout_millis: int = int(os.getenv("AI_LANGGRAPH_WORKER_TIMEOUT_MILLIS", "30000"))
    max_active_llm_calls: int = max(1, int(os.getenv("AI_LANGGRAPH_MAX_ACTIVE_LLM_CALLS", "4")))
    backend_base_url: str = os.getenv("AI_BACKEND_BASE_URL", "http://backend:8080")
    backend_internal_api_key: str = os.getenv(
        "AI_BACKEND_INTERNAL_API_KEY",
        os.getenv("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY", ""),
    )
    backend_tool_timeout_millis: int = int(os.getenv("AI_BACKEND_TOOL_TIMEOUT_MILLIS", "90000"))
    mcp_base_url: str = os.getenv("AI_MCP_BASE_URL", "http://fastmcp-tools:7001")
    mcp_internal_api_key: str = os.getenv("MCP_INTERNAL_API_KEY", "")
    mcp_timeout_millis: int = int(os.getenv("AI_MCP_TIMEOUT_MILLIS", "30000"))
    agent_max_parallel_tool_calls: int = max(1, int(os.getenv("AI_AGENT_MAX_PARALLEL_TOOL_CALLS", "3")))
    agent_max_skill_chars: int = max(500, int(os.getenv("AI_AGENT_MAX_SKILL_CHARS", "3000")))
    agent_max_material_chars: int = max(1000, int(os.getenv("AI_AGENT_MAX_MATERIAL_CHARS", "12000")))
    agent_market_topn_default: int = max(1, int(os.getenv("AI_AGENT_MARKET_TOPN_DEFAULT", "10")))
    agent_latest_rank_max_age_days: int = max(1, int(os.getenv("AI_AGENT_LATEST_RANK_MAX_AGE_DAYS", "2")))
    agent_chapters_per_rank_book: int = max(1, int(os.getenv("AI_AGENT_CHAPTERS_PER_RANK_BOOK", "1")))
    agent_intent_llm_fallback_enabled: bool = os.getenv("AGENT_INTENT_LLM_FALLBACK_ENABLED", "true").lower() in {"1", "true", "yes", "on"}
    agent_intent_llm_min_confidence: float = float(os.getenv("AGENT_INTENT_LLM_MIN_CONFIDENCE", "0.82"))
    mysql_host: str = os.getenv("MYSQL_HOST", "mysql")
    mysql_port: int = int(os.getenv("MYSQL_PORT", "3306"))
    mysql_database: str = os.getenv("MYSQL_DATABASE", "novel_analyzer")
    mysql_user: str = os.getenv("MYSQL_USER", "novel")
    mysql_password: str = os.getenv("MYSQL_PASSWORD", "")
    langgraph_checkpoint_backend: str = os.getenv("AI_LANGGRAPH_CHECKPOINT_BACKEND", "mysql")


settings = Settings()
