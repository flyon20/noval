from typing import Any

from pydantic import BaseModel


class ApiResult(BaseModel):
    code: int
    message: str
    data: Any

