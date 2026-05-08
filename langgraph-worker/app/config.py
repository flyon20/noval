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
    timeout_millis: int = int(os.getenv("AI_LANGGRAPH_WORKER_TIMEOUT_MILLIS", "30000"))
    max_active_llm_calls: int = max(1, int(os.getenv("AI_LANGGRAPH_MAX_ACTIVE_LLM_CALLS", "4")))
    backend_base_url: str = os.getenv("AI_BACKEND_BASE_URL", "http://backend:8080")
    backend_internal_api_key: str = os.getenv(
        "AI_BACKEND_INTERNAL_API_KEY",
        os.getenv("AI_LANGGRAPH_WORKER_INTERNAL_API_KEY", ""),
    )
    backend_tool_timeout_millis: int = int(os.getenv("AI_BACKEND_TOOL_TIMEOUT_MILLIS", "90000"))


settings = Settings()
