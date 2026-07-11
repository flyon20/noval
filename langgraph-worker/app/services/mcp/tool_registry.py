from __future__ import annotations

import re
from typing import Any


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
RISKY_ARGUMENT_KEYS = {"url", "uri", "href", "path", "file", "filePath", "filename", "sql", "query"}
URL_PATTERN = re.compile(r"(?i)\bhttps?://")
PATH_PATTERN = re.compile(r"(?i)(?:[a-z]:[\\/]|(?:^|[\\/])\.\.(?:[\\/]|$)|/etc/|/home/|/users/)")
SQL_PATTERN = re.compile(r"(?i)\b(select|insert|update|delete|drop|alter|truncate)\b.+\b(from|into|table|where|set)\b")


class McpToolRegistry:
    def __init__(self, tools: list[dict[str, Any]] | None = None) -> None:
        self._tools = {str(tool.get("name")): dict(tool) for tool in tools or [] if tool.get("name")}

    @classmethod
    async def load(cls, client: Any) -> "McpToolRegistry":
        payload = await client.list_tools()
        tools = payload.get("tools") if isinstance(payload, dict) else []
        return cls([tool for tool in tools if isinstance(tool, dict)])

    def openai_tools(self, *, route: str, supervisor_permissions: set[str] | None = None) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for name in sorted(self._tools):
            if not self.is_allowed(name, route=route, supervisor_permissions=supervisor_permissions):
                continue
            tool = self._tools[name]
            result.append({
                "type": "function",
                "function": {
                    "name": name,
                    "description": str(tool.get("description") or ""),
                    "parameters": tool.get("inputSchema") or {"type": "object"},
                },
            })
        return result

    def is_allowed(
        self,
        name: str,
        *,
        route: str,
        supervisor_permissions: set[str] | None = None,
    ) -> bool:
        if name not in self._tools:
            return False
        if name in (supervisor_permissions or set()):
            return True
        allowed = ROUTE_TOOL_ALLOWLIST.get(route, set())
        return name in allowed and not bool(self._tools[name].get("admin"))

    def validate_arguments(self, name: str, arguments: dict[str, Any]) -> str | None:
        tool = self._tools.get(name)
        if tool is None:
            return "tool not found"
        schema = tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {}
        required = schema.get("required") if isinstance(schema.get("required"), list) else []
        for key in required:
            if key not in arguments or arguments.get(key) is None:
                return f"missing required argument: {key}"
        risky = self._find_risky_argument(arguments)
        if risky:
            return f"unsafe tool argument: {risky}"
        return None

    def _find_risky_argument(self, value: Any, *, key: str = "") -> str | None:
        if isinstance(value, dict):
            for child_key, child_value in value.items():
                child_key_text = str(child_key)
                if child_key_text in RISKY_ARGUMENT_KEYS:
                    return child_key_text
                found = self._find_risky_argument(child_value, key=child_key_text)
                if found:
                    return found
        elif isinstance(value, list):
            for item in value:
                found = self._find_risky_argument(item, key=key)
                if found:
                    return found
        elif isinstance(value, str):
            if URL_PATTERN.search(value) or PATH_PATTERN.search(value) or SQL_PATTERN.search(value):
                return key or "value"
        return None
