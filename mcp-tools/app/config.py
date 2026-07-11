from __future__ import annotations

import os


class Settings:
    app_name = "noval-mcp-tools"

    def __init__(self) -> None:
        self.backend_base_url = os.getenv("AI_BACKEND_BASE_URL", "http://backend:8080").rstrip("/")
        self.backend_internal_api_key = os.getenv("AI_BACKEND_INTERNAL_API_KEY", "")
        self.mcp_internal_api_key = os.getenv("MCP_INTERNAL_API_KEY", "")
        self.backend_timeout_millis = int(os.getenv("AI_BACKEND_TOOL_TIMEOUT_MILLIS", "90000"))


settings = Settings()
