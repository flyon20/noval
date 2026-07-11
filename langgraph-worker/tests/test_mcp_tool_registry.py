from __future__ import annotations

import unittest

from app.services.mcp.tool_registry import McpToolRegistry


class McpToolRegistryTest(unittest.TestCase):
    def test_converts_allowed_mcp_tools_to_openai_tool_schemas(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "properties": {"platform": {"type": "string"}}},
            },
            {
                "name": "memory.admin.list",
                "description": "admin memory",
                "inputSchema": {"type": "object"},
                "admin": True,
            },
        ])

        tools = registry.openai_tools(route="mixed_creation_research")

        self.assertEqual(["rank.lookup"], [tool["function"]["name"] for tool in tools])
        self.assertEqual("function", tools[0]["type"])
        self.assertEqual("rank lookup", tools[0]["function"]["description"])
        self.assertEqual({"type": "object", "properties": {"platform": {"type": "string"}}}, tools[0]["function"]["parameters"])

    def test_denies_tool_not_allowed_for_route(self) -> None:
        registry = McpToolRegistry([
            {"name": "rank.refresh", "description": "refresh", "inputSchema": {"type": "object"}},
        ])

        self.assertFalse(registry.is_allowed("rank.refresh", route="mixed_creation_research"))
        self.assertTrue(registry.is_allowed("rank.refresh", route="mixed_creation_research", supervisor_permissions={"rank.refresh"}))

    def test_validates_required_arguments_from_schema(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "memory.project_context",
                "description": "memory",
                "inputSchema": {"type": "object", "required": ["userId", "projectId"]},
            },
        ])

        self.assertEqual("missing required argument: projectId", registry.validate_arguments("memory.project_context", {"userId": 7}))
        self.assertIsNone(registry.validate_arguments("memory.project_context", {"userId": 7, "projectId": 900}))


if __name__ == "__main__":
    unittest.main()
