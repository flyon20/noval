from pydantic import BaseModel


class Settings(BaseModel):
    app_name: str = "novel-crawler-service"
    host: str = "0.0.0.0"
    port: int = 5000
    timeout_seconds: int = 20
    fanqie_rank_url: str = "https://fanqienovel.com/rank?enter_from=menu"


settings = Settings()

