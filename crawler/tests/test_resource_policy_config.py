import os
import subprocess
import sys

from app.config import settings


def test_j3160_crawler_concurrency_default() -> None:
    assert settings.chapter_fetch_workers == 2


def test_crawler_rejects_non_positive_environment_limit_on_startup() -> None:
    environment = os.environ.copy()
    environment["NOVAL_RESOURCE_MAX_CRAWLER_CONCURRENCY"] = "0"

    result = subprocess.run(
        [sys.executable, "-c", "from app.config import settings"],
        cwd=os.getcwd(),
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode != 0
    assert "chapter_fetch_workers" in result.stderr
