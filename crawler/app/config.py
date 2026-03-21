import os

from pydantic import BaseModel


class Settings(BaseModel):
    app_name: str = "novel-crawler-service"
    host: str = "0.0.0.0"
    port: int = 5000
    timeout_seconds: int = 20
    internal_api_key: str = os.getenv("CRAWLER_INTERNAL_API_KEY", "")
    fanqie_base_url: str = "https://fanqienovel.com"
    fanqie_rank_url: str = "https://fanqienovel.com/rank?enter_from=menu"
    user_agent: str = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/134.0.0.0 Safari/537.36"
    )


settings = Settings()
