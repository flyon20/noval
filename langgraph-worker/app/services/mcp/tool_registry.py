from __future__ import annotations

import copy
import hashlib
import json
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
        "project.retrieve",
        "reader.simulate_feedback",
        "editor.risk_check",
    },
    "book_breakdown": {"book.search", "book.research_pack", "knowledge.vector_search"},
    "project_creation": {"skill.lookup", "memory.project_context", "project.retrieve"},
    "admin": {"memory.admin.list", "skill.admin.list"},
}
MANIFEST_KEYS = {
    "routes",
    "sideEffectType",
    "scopeRequirement",
    "timeoutMs",
    "identityKeys",
    "secretInputKeys",
    "secretOutputKeys",
}
RISKY_ARGUMENT_KEYS = {
    "cmd",
    "command",
    "file",
    "filepath",
    "filename",
    "href",
    "path",
    "script",
    "shell",
    "sql",
    "uri",
    "url",
}
URL_PATTERN = re.compile(r"(?i)\b(?:https?|ftp)://")
PATH_PATTERN = re.compile(
    r"(?i)(?:\b[a-z]:[\\/]|\\\\[^\\/\s]+[\\/][^\\/\s]+|"
    r"(?:^|[\s\"'])\.{1,2}[\\/]|"
    r"(?:^|[\s\"'])/(?:etc|home|users|tmp|var|opt|root|windows|program files)(?:[\\/]|$))"
)
SQL_SELECT_LIST_PATTERN = re.compile(
    r"(?is)\bselect\s+(?:distinct\s+)?(?:\*|"
    r"[a-z_][\w$]*(?:\s*,\s*[a-z_][\w$]*)+|"
    r"(?:[a-z_][\w$]*\.)+[a-z_*][\w$]*|"
    r"[a-z_][\w$]*\s*\([^\r\n;]*\))\s+from\s+[a-z_][\w$]*"
)
SQL_BARE_SELECT_PATTERN = re.compile(
    r"(?is)^\s*select\s+[a-z_][\w$]*\s+from\s+[a-z_][\w$]*\s*;?\s*$"
)
SQL_SELECT_CLAUSE_PATTERN = re.compile(
    r"(?is)\bselect\b.{0,512}\bfrom\b.{0,512}"
    r"\b(?:where|join|group\s+by|order\s+by|having|limit|offset|union)\b"
)
SQL_MUTATION_PATTERN = re.compile(
    r"(?is)\b(?:insert\s+into|update\s+[a-z_][\w$]*\s+set|"
    r"delete\s+from|merge\s+into)\b"
)
SQL_DDL_PATTERN = re.compile(
    r"(?is)\b(?:drop|alter|truncate|create)\s+"
    r"(?:table|database|schema|index|view|user)\b"
)
SQL_PERMISSION_PATTERN = re.compile(
    r"(?is)\b(?:grant|revoke)\b.{0,512}\b(?:on|to|from)\b"
)
SQL_COMMENT_PATTERN = re.compile(r"(?s)(?:--[^\r\n]*|/\*.*?\*/)")
SQL_SELECT_FROM_PATTERN = re.compile(r"(?is)\bselect\b.{0,512}\bfrom\b")
SQL_STATEMENT_SEPARATOR_PATTERN = re.compile(
    r"(?is);\s*(?:select|insert|update|delete|drop|alter|truncate|create|grant|revoke|merge)\b"
)
COMMAND_PATTERN = re.compile(
    r"(?i)(?:^|(?:&&|\|\||[;|])\s*)(?:sudo\s+)?"
    r"(?:bash|sh|zsh|cmd(?:\.exe)?|powershell|pwsh|curl|wget|invoke-webrequest|"
    r"rm|del|erase|copy|move|chmod|chown|nc|netcat|python(?:3)?|node|npm|npx|pip(?:3)?|git)"
    r"\b(?:\s|$)"
)
COMMAND_SUBSTITUTION_PATTERN = re.compile(r"(?:`[^`\r\n]+`|\$\([^\)\r\n]+\))")
_UNSPECIFIED_PROJECT_SCOPE = object()


class McpToolRegistry:
    MANIFEST_VERSION = "mcp-tool-manifest-v1"

    def __init__(self, tools: list[dict[str, Any]] | None = None) -> None:
        self._tools = {
            str(tool.get("name")): dict(tool)
            for tool in tools or []
            if tool.get("name") and self._has_valid_manifest(tool)
        }

    @classmethod
    async def load(cls, client: Any) -> "McpToolRegistry":
        payload = await client.list_tools()
        tools = payload.get("tools") if isinstance(payload, dict) else []
        return cls([tool for tool in tools if isinstance(tool, dict)])

    def openai_tools(
        self,
        *,
        route: str,
        supervisor_permissions: set[str] | None = None,
        project_id: Any = None,
    ) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for name in sorted(self._tools):
            if not self.is_allowed(name, route=route, supervisor_permissions=supervisor_permissions):
                continue
            tool = self._tools[name]
            if (
                project_id is not _UNSPECIFIED_PROJECT_SCOPE
                and tool.get("scopeRequirement") == "project"
                and not str(project_id or "").strip()
            ):
                continue
            result.append({
                "type": "function",
                "function": {
                    "name": name,
                    "description": str(tool.get("description") or ""),
                    "parameters": self._provider_input_schema(tool),
                },
            })
        return result

    def _provider_input_schema(self, tool: dict[str, Any]) -> dict[str, Any]:
        schema = copy.deepcopy(tool.get("inputSchema") or {"type": "object"})
        hidden_keys = {
            str(key)
            for key in [
                *(tool.get("identityKeys") or []),
                *(tool.get("secretInputKeys") or []),
            ]
            if str(key).strip()
        }
        properties = schema.get("properties")
        if isinstance(properties, dict):
            for key in hidden_keys:
                properties.pop(key, None)
        required = schema.get("required")
        if isinstance(required, list):
            schema["required"] = [key for key in required if str(key) not in hidden_keys]
        return schema

    def manifest_summary(
        self,
        *,
        route: str,
        allowed_tools: set[str] | None = None,
        supervisor_permissions: set[str] | None = None,
        project_id: Any = _UNSPECIFIED_PROJECT_SCOPE,
    ) -> dict[str, Any]:
        allowed = None if allowed_tools is None else set(allowed_tools)
        entries: list[dict[str, Any]] = []
        for provider_tool in self.openai_tools(
            route=route,
            supervisor_permissions=supervisor_permissions,
            project_id=project_id,
        ):
            function = provider_tool.get("function") if isinstance(provider_tool.get("function"), dict) else {}
            name = str(function.get("name") or "").strip()
            if not name or (allowed is not None and name not in allowed):
                continue
            manifest = self._tools[name]
            manifest_identity = {
                key: manifest.get(key)
                for key in sorted({
                    *MANIFEST_KEYS,
                    "admin",
                    "requiresSupervisorPermission",
                })
                if key in manifest
            }
            entries.append({
                "name": name,
                "route": route,
                "scope": str(manifest.get("scopeRequirement") or ""),
                "sideEffectType": str(manifest.get("sideEffectType") or ""),
                "providerDefinitionFingerprint": self._fingerprint(provider_tool),
                "manifestFingerprint": self._fingerprint(manifest_identity),
            })
        entries.sort(key=lambda entry: entry["name"])
        return {
            "version": self.MANIFEST_VERSION,
            "route": route,
            "fingerprint": self._fingerprint({
                "version": self.MANIFEST_VERSION,
                "route": route,
                "entries": entries,
            }),
            "toolNames": [entry["name"] for entry in entries],
            "entries": entries,
        }

    def is_allowed(
        self,
        name: str,
        *,
        route: str,
        supervisor_permissions: set[str] | None = None,
    ) -> bool:
        if name not in self._tools:
            return False
        tool = self._tools[name]
        manifest_routes = tool.get("routes") if isinstance(tool.get("routes"), list) else []
        if route not in manifest_routes:
            return False
        if bool(tool.get("admin")):
            return route == "admin"
        permissions = supervisor_permissions or set()
        if bool(tool.get("requiresSupervisorPermission")) and (
            name not in permissions
            and "tools:write" not in permissions
            and "admin:*" not in permissions
        ):
            return False
        allowed = ROUTE_TOOL_ALLOWLIST.get(route, set())
        return name in allowed or name in permissions

    def validate_arguments(self, name: str, arguments: dict[str, Any]) -> str | None:
        tool = self._tools.get(name)
        if tool is None:
            return "tool not found"
        schema = tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {}
        required = schema.get("required") if isinstance(schema.get("required"), list) else []
        for key in required:
            if key not in arguments or arguments.get(key) is None:
                return f"missing required argument: {key}"
        identity_keys = tool.get("identityKeys") if isinstance(tool.get("identityKeys"), list) else []
        for key in identity_keys:
            if arguments.get(str(key)) is None:
                label = "scope" if str(key) == "projectId" else "identity"
                return f"missing required {label} argument: {key}"
        if tool.get("scopeRequirement") == "project" and arguments.get("projectId") is None:
            return "missing required scope argument: projectId"
        if tool.get("sideEffectType") == "write" and not arguments.get("idempotencyKey"):
            return "missing required argument: idempotencyKey"
        risky = self._find_risky_argument(arguments)
        if risky:
            return f"unsafe tool argument: {risky}"
        return None

    def timeout_seconds(self, name: str) -> float | None:
        tool = self._tools.get(name)
        timeout_ms = tool.get("timeoutMs") if tool is not None else None
        return float(timeout_ms) / 1000 if isinstance(timeout_ms, int) and timeout_ms > 0 else None

    def side_effect_type(self, name: str) -> str:
        tool = self._tools.get(name)
        value = tool.get("sideEffectType") if tool is not None else None
        return str(value) if value in {"none", "read", "write"} else "write"

    def identity_keys(self, name: str) -> tuple[str, ...]:
        tool = self._tools.get(name) or {}
        keys = tool.get("identityKeys") if isinstance(tool.get("identityKeys"), list) else []
        return tuple(str(key) for key in keys)

    def scope_requirement(self, name: str) -> str | None:
        tool = self._tools.get(name) or {}
        value = tool.get("scopeRequirement")
        return str(value) if value in {"user", "project"} else None

    def secret_keys(self, name: str) -> tuple[set[str], set[str]]:
        tool = self._tools.get(name) or {}
        inputs = tool.get("secretInputKeys") if isinstance(tool.get("secretInputKeys"), list) else []
        outputs = tool.get("secretOutputKeys") if isinstance(tool.get("secretOutputKeys"), list) else []
        return {str(key) for key in inputs}, {str(key) for key in outputs}

    def _has_valid_manifest(self, tool: dict[str, Any]) -> bool:
        if not MANIFEST_KEYS.issubset(tool):
            return False
        routes = tool.get("routes")
        identity_keys = tool.get("identityKeys")
        scope_requirement = tool.get("scopeRequirement")
        input_schema = tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {}
        schema_properties = input_schema.get("properties") if isinstance(input_schema.get("properties"), dict) else {}
        schema_required = input_schema.get("required") if isinstance(input_schema.get("required"), list) else []
        project_id_exposed = "projectId" in schema_properties or "projectId" in schema_required
        timeout_ms = tool.get("timeoutMs")
        key_lists = (identity_keys, tool.get("secretInputKeys"), tool.get("secretOutputKeys"))
        return (
            isinstance(routes, list)
            and bool(routes)
            and all(isinstance(route, str) and route for route in routes)
            and tool.get("sideEffectType") in {"none", "read", "write"}
            and scope_requirement in {"user", "project"}
            and isinstance(timeout_ms, int)
            and timeout_ms > 0
            and (
                tool.get("requiresSupervisorPermission") is None
                or isinstance(tool.get("requiresSupervisorPermission"), bool)
            )
            and (
                tool.get("sideEffectType") != "write"
                or tool.get("requiresSupervisorPermission") is True
            )
            and isinstance(identity_keys, list)
            and "userId" in identity_keys
            and (
                (scope_requirement == "user" and "projectId" not in identity_keys)
                or (scope_requirement == "project" and "projectId" in identity_keys)
            )
            and (scope_requirement != "user" or not project_id_exposed)
            and all(isinstance(keys, list) for keys in key_lists)
            and all(isinstance(key, str) and key for keys in key_lists for key in keys)
        )

    @staticmethod
    def _fingerprint(value: Any) -> str:
        canonical = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        return f"sha256:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"

    def _find_risky_argument(self, value: Any, *, key: str = "") -> str | None:
        if isinstance(value, dict):
            for child_key, child_value in value.items():
                child_key_text = str(child_key)
                if child_key_text.casefold() in RISKY_ARGUMENT_KEYS:
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
            if (
                URL_PATTERN.search(value)
                or PATH_PATTERN.search(value)
                or _contains_sql(value)
                or COMMAND_PATTERN.search(value)
                or COMMAND_SUBSTITUTION_PATTERN.search(value)
            ):
                return key or "value"
        return None


def _contains_sql(value: str) -> bool:
    if any(pattern.search(value) for pattern in (
        SQL_SELECT_LIST_PATTERN,
        SQL_BARE_SELECT_PATTERN,
        SQL_SELECT_CLAUSE_PATTERN,
        SQL_MUTATION_PATTERN,
        SQL_DDL_PATTERN,
        SQL_PERMISSION_PATTERN,
        SQL_STATEMENT_SEPARATOR_PATTERN,
    )):
        return True
    return bool(SQL_COMMENT_PATTERN.search(value) and SQL_SELECT_FROM_PATTERN.search(value))
