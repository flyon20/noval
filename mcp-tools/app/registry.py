from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from fastapi import HTTPException
from pydantic import BaseModel, ValidationError

from app.backend_client import BackendClient
from app.security import validate_safe_arguments


ToolHandler = Callable[[BaseModel, BackendClient], Awaitable[Any]]


ROUTE_TOOL_ALLOWLIST: dict[str, set[str]] = {
    "market_scan": {"rank.lookup", "rank.research_pack", "knowledge.vector_search"},
    "mixed_creation_research": {
        "rank.lookup",
        "rank.research_pack",
        "knowledge.vector_search",
        "skill.lookup",
        "memory.project_context",
        "reader.simulate_feedback",
        "editor.risk_check",
    },
    "book_breakdown": {"book.search", "book.research_pack", "knowledge.vector_search"},
    "project_creation": {"skill.lookup", "memory.project_context"},
    "admin": {"memory.admin.list", "skill.admin.list"},
}
UNSCOPED_LEGACY_ROUTES = {None, ""}


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    args_model: type[BaseModel]
    handler: ToolHandler
    admin: bool = False

    def schema(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "inputSchema": self.args_model.model_json_schema(),
            "admin": self.admin,
        }


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(self, tool: ToolDefinition) -> None:
        self._tools[tool.name] = tool

    def list_tools(self, *, toolset: str = "normal") -> list[dict[str, Any]]:
        include_admin = toolset == "admin"
        return [
            tool.schema()
            for tool in self._tools.values()
            if include_admin or not tool.admin
        ]

    async def call(
        self,
        *,
        name: str,
        arguments: dict[str, Any],
        backend_client: BackendClient,
        route: str | None = None,
    ) -> Any:
        tool = self._tools.get(name)
        if tool is None:
            raise HTTPException(status_code=404, detail="tool not found")
        if tool.admin and route != "admin":
            raise HTTPException(status_code=403, detail="admin tool denied")
        if not self._is_route_allowed(tool, route):
            raise HTTPException(status_code=403, detail="tool denied for route")
        validate_safe_arguments(arguments or {})
        try:
            args = tool.args_model.model_validate(arguments or {})
        except ValidationError as exc:
            raise HTTPException(status_code=400, detail=exc.errors()) from exc
        validate_safe_arguments(args.model_dump(exclude_none=True))
        return await tool.handler(args, backend_client)

    def _is_route_allowed(self, tool: ToolDefinition, route: str | None) -> bool:
        if route in UNSCOPED_LEGACY_ROUTES:
            return True
        allowed = ROUTE_TOOL_ALLOWLIST.get(str(route))
        if allowed is None:
            return False
        return tool.name in allowed
