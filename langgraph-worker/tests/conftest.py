import os


os.environ["FASTMCP_INTERNAL_API_KEY"] = "fastmcp-worker-test-internal-key-1234567890"
os.environ["MCP_CALL_SIGNING_KEY"] = "worker-test-mcp-call-signing-key-1234567890"

from app.config import settings


def pytest_configure() -> None:
    settings.langgraph_checkpoint_backend = "memory"
