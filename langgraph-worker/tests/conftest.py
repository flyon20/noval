from app.config import settings


def pytest_configure() -> None:
    settings.langgraph_checkpoint_backend = "memory"
