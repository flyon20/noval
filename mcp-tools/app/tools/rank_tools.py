from __future__ import annotations

from datetime import date
from typing import Any

from pydantic import BaseModel, Field, model_validator

from app.backend_client import BackendClient
from app.registry import ToolDefinition, ToolRegistry


class RankLookupArgs(BaseModel):
    platform: str = Field(min_length=1)
    channelCode: str | None = None
    boardCode: str | None = None
    category: str | None = None
    rankNo: int | None = Field(default=None, ge=1)
    limit: int = Field(default=10, ge=1, le=50)
    freshness: str | None = None
    allowHistorical: bool | None = None
    timeWindowDays: int | None = Field(default=None, ge=1, le=365)
    snapshotStartDate: date | None = None
    snapshotEndDate: date | None = None
    requireSnapshotTime: bool | None = None

    @model_validator(mode="after")
    def validate_snapshot_date_range(self) -> RankLookupArgs:
        start = self.snapshotStartDate
        end = self.snapshotEndDate
        if (start is None) != (end is None):
            raise ValueError("snapshotStartDate and snapshotEndDate must be provided together")
        if start is not None and end is not None:
            if start > end:
                raise ValueError("snapshotStartDate must not be after snapshotEndDate")
            if self.allowHistorical is not True:
                raise ValueError("snapshot date range requires allowHistorical=true")
        return self


class RankRefreshArgs(BaseModel):
    userId: int = Field(ge=1)
    platform: str = Field(min_length=1)
    channelCode: str | None = None
    boardCode: str | None = None
    category: str | None = None
    rankFetchCount: int | None = Field(default=None, ge=1, le=100)
    forceReason: str = Field(min_length=8)
    idempotencyKey: str = Field(min_length=1, max_length=200)


async def rank_lookup(args: RankLookupArgs, client: BackendClient) -> Any:
    return await client.post(
        "/internal/knowledge/rank/lookup",
        args.model_dump(mode="json", exclude_none=True),
    )


async def rank_refresh(args: RankRefreshArgs, client: BackendClient) -> Any:
    payload = args.model_dump(exclude_none=True)
    payload["refreshMode"] = "FORCE"
    return await client.post_governed_rank_refresh(payload)


def register_rank_tools(registry: ToolRegistry) -> None:
    registry.register(ToolDefinition(
        name="rank.lookup",
        description="Look up structured web-novel rank evidence.",
        args_model=RankLookupArgs,
        handler=rank_lookup,
    ))
    registry.register(ToolDefinition(
        name="rank.refresh",
        description="Refresh a rank board with supervisor permission.",
        args_model=RankRefreshArgs,
        handler=rank_refresh,
    ))
