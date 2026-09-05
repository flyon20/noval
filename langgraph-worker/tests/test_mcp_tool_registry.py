from __future__ import annotations

import unittest

from app.services.mcp.tool_registry import McpToolRegistry


class McpToolRegistryTest(unittest.TestCase):
    def _manifest(self, **overrides: object) -> dict[str, object]:
        manifest: dict[str, object] = {
            "routes": ["mixed_creation_research"],
            "sideEffectType": "read",
            "scopeRequirement": "project",
            "timeoutMs": 30000,
            "identityKeys": ["userId", "projectId"],
            "secretInputKeys": [],
            "secretOutputKeys": [],
            "requiresSupervisorPermission": False,
        }
        manifest.update(overrides)
        return manifest

    def test_converts_allowed_mcp_tools_to_openai_tool_schemas(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "properties": {"platform": {"type": "string"}}},
                **self._manifest(),
            },
            {
                "name": "memory.admin.list",
                "description": "admin memory",
                "inputSchema": {"type": "object"},
                "admin": True,
                **self._manifest(routes=["admin"]),
            },
        ])

        tools = registry.openai_tools(route="mixed_creation_research", project_id=91)

        self.assertEqual(["rank.lookup"], [tool["function"]["name"] for tool in tools])
        self.assertEqual("function", tools[0]["type"])
        self.assertEqual("rank lookup", tools[0]["function"]["description"])
        self.assertEqual({"type": "object", "properties": {"platform": {"type": "string"}}}, tools[0]["function"]["parameters"])

    def test_projectless_projection_exposes_user_tools_and_hides_project_tools(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "properties": {"platform": {"type": "string"}}},
                **self._manifest(scopeRequirement="user", identityKeys=["userId"]),
            },
            {
                "name": "project.retrieve",
                "description": "project retrieval",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "userId": {"type": "integer"},
                        "projectId": {"type": "integer"},
                        "workId": {"type": "integer"},
                        "query": {"type": "string"},
                    },
                    "required": ["userId", "projectId", "workId", "query"],
                },
                **self._manifest(routes=["mixed_creation_research"]),
            },
        ])

        projectless = registry.openai_tools(
            route="mixed_creation_research",
            project_id=None,
        )
        project_scoped = registry.openai_tools(
            route="mixed_creation_research",
            project_id=91,
        )

        self.assertEqual(["rank.lookup"], [tool["function"]["name"] for tool in projectless])
        self.assertEqual(
            ["project.retrieve", "rank.lookup"],
            [tool["function"]["name"] for tool in project_scoped],
        )
        project_parameters = project_scoped[0]["function"]["parameters"]
        self.assertEqual(
            {"workId": {"type": "integer"}, "query": {"type": "string"}},
            project_parameters["properties"],
        )
        self.assertEqual(["workId", "query"], project_parameters["required"])
        self.assertEqual("user", registry.scope_requirement("rank.lookup"))
        self.assertEqual("project", registry.scope_requirement("project.retrieve"))
        self.assertEqual(
            ["rank.lookup"],
            registry.manifest_summary(
                route="mixed_creation_research",
                project_id=None,
            )["toolNames"],
        )

    def test_rejects_user_scope_manifest_that_exposes_project_id_in_schema(self) -> None:
        registry = McpToolRegistry([{
            "name": "rank.lookup",
            "description": "rank lookup",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "platform": {"type": "string"},
                    "projectId": {"type": "integer"},
                },
            },
            **self._manifest(scopeRequirement="user", identityKeys=["userId"]),
        }])

        self.assertFalse(registry.is_allowed("rank.lookup", route="mixed_creation_research"))
        self.assertEqual([], registry.openai_tools(route="mixed_creation_research", project_id=None))

    def test_provider_schema_hides_trusted_identity_and_secret_inputs(self) -> None:
        input_schema = {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "userId": {"type": "integer"},
                "projectId": {"type": "integer"},
                "accessToken": {"type": "string"},
            },
            "required": ["query", "userId", "projectId", "accessToken"],
        }
        registry = McpToolRegistry([{
            "name": "knowledge.vector_search",
            "description": "knowledge search",
            "inputSchema": input_schema,
            **self._manifest(secretInputKeys=["accessToken"]),
        }])

        parameters = registry.openai_tools(
            route="mixed_creation_research",
            project_id=91,
        )[0]["function"]["parameters"]

        self.assertEqual({"query": {"type": "string"}}, parameters["properties"])
        self.assertEqual(["query"], parameters["required"])
        self.assertIn("userId", input_schema["properties"])
        self.assertIn("accessToken", input_schema["properties"])

    def test_manifest_summary_and_provider_projection_are_stable(self) -> None:
        tools = [
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object"},
                **self._manifest(routes=["market_scan"]),
            },
            {
                "name": "knowledge.vector_search",
                "description": "vector search",
                "inputSchema": {"type": "object"},
                **self._manifest(routes=["market_scan"]),
            },
        ]
        first = McpToolRegistry(tools)
        second = McpToolRegistry(list(reversed(tools)))
        changed = McpToolRegistry([
            {**tools[0], "inputSchema": {"type": "object", "required": ["platform"]}},
            tools[1],
        ])

        first_summary = first.manifest_summary(
            route="market_scan",
            allowed_tools={"rank.lookup", "knowledge.vector_search"},
        )
        second_summary = second.manifest_summary(
            route="market_scan",
            allowed_tools={"knowledge.vector_search", "rank.lookup"},
        )
        changed_summary = changed.manifest_summary(
            route="market_scan",
            allowed_tools={"rank.lookup", "knowledge.vector_search"},
        )

        self.assertEqual(
            ["knowledge.vector_search", "rank.lookup"],
            first_summary["toolNames"],
        )
        self.assertEqual(first_summary["fingerprint"], second_summary["fingerprint"])
        self.assertNotEqual(first_summary["fingerprint"], changed_summary["fingerprint"])
        self.assertNotIn("parameters", first_summary)

    def test_route_mismatch_has_empty_provider_projection(self) -> None:
        registry = McpToolRegistry([{
            "name": "rank.lookup",
            "description": "rank lookup",
            "inputSchema": {"type": "object"},
            **self._manifest(routes=["book_breakdown"]),
        }])

        summary = registry.manifest_summary(
            route="market_scan",
            allowed_tools={"rank.lookup"},
        )

        self.assertEqual([], summary["toolNames"])
        self.assertEqual([], summary["entries"])

    def test_allows_project_retrieve_on_project_and_mixed_routes(self) -> None:
        registry = McpToolRegistry([{
            "name": "project.retrieve",
            "description": "project retrieval",
            "inputSchema": {
                "type": "object",
                "required": ["userId", "projectId", "workId", "query"],
            },
            **self._manifest(routes=["project_creation", "mixed_creation_research"]),
        }])

        self.assertTrue(registry.is_allowed("project.retrieve", route="project_creation"))
        self.assertTrue(registry.is_allowed("project.retrieve", route="mixed_creation_research"))
        self.assertIsNone(registry.validate_arguments(
            "project.retrieve",
            {"userId": 7, "projectId": 9, "workId": 11, "query": "continuity"},
        ))

    def test_denies_tool_not_allowed_for_route(self) -> None:
        registry = McpToolRegistry([
            {"name": "rank.refresh", "description": "refresh", "inputSchema": {"type": "object"}},
        ])

        self.assertFalse(registry.is_allowed("rank.refresh", route="mixed_creation_research"))
        self.assertFalse(registry.is_allowed("rank.refresh", route="mixed_creation_research", supervisor_permissions={"rank.refresh"}))

    def test_requires_complete_governed_manifest(self) -> None:
        registry = McpToolRegistry([
            {"name": "rank.lookup", "description": "rank", "inputSchema": {"type": "object"}},
        ])

        self.assertFalse(registry.is_allowed("rank.lookup", route="mixed_creation_research"))
        self.assertEqual([], registry.openai_tools(route="mixed_creation_research"))

    def test_write_tool_requires_explicit_supervisor_permission(self) -> None:
        registry = McpToolRegistry([{
            "name": "rank.refresh",
            "description": "refresh",
            "inputSchema": {"type": "object"},
            **self._manifest(
                sideEffectType="write",
                routes=["market_scan"],
                requiresSupervisorPermission=True,
            ),
        }])

        self.assertFalse(registry.is_allowed("rank.refresh", route="market_scan"))
        self.assertTrue(registry.is_allowed(
            "rank.refresh",
            route="market_scan",
            supervisor_permissions={"rank.refresh"},
        ))

    def test_rejects_manifest_without_user_identity_scope(self) -> None:
        registry = McpToolRegistry([{
            "name": "rank.lookup",
            "description": "rank",
            "inputSchema": {"type": "object"},
            **self._manifest(identityKeys=["projectId"]),
        }])

        self.assertFalse(registry.is_allowed("rank.lookup", route="mixed_creation_research"))

    def test_rejects_write_manifest_without_supervisor_permission_requirement(self) -> None:
        registry = McpToolRegistry([{
            "name": "rank.refresh",
            "description": "refresh",
            "inputSchema": {"type": "object"},
            **self._manifest(
                sideEffectType="write",
                routes=["market_scan"],
                requiresSupervisorPermission=False,
            ),
        }])

        self.assertFalse(registry.is_allowed(
            "rank.refresh",
            route="market_scan",
            supervisor_permissions={"rank.refresh"},
        ))

    def test_validates_required_arguments_from_schema(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "memory.project_context",
                "description": "memory",
                "inputSchema": {"type": "object", "required": ["userId", "projectId"]},
                **self._manifest(),
            },
        ])

        self.assertEqual("missing required argument: projectId", registry.validate_arguments("memory.project_context", {"userId": 7}))
        self.assertIsNone(registry.validate_arguments("memory.project_context", {"userId": 7, "projectId": 900}))

    def test_validates_manifest_scope_identity_and_idempotency(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "rank.refresh",
                "description": "refresh",
                "inputSchema": {"type": "object"},
                **self._manifest(
                    sideEffectType="write",
                    requiresSupervisorPermission=True,
                ),
            },
        ])

        self.assertEqual("missing required scope argument: projectId", registry.validate_arguments("rank.refresh", {"userId": 7}))
        self.assertEqual(
            "missing required argument: idempotencyKey",
            registry.validate_arguments("rank.refresh", {"userId": 7, "projectId": 9}),
        )
        self.assertIsNone(registry.validate_arguments(
            "rank.refresh",
            {"userId": 7, "projectId": 9, "idempotencyKey": "run-1:rank.refresh"},
        ))

    def test_allows_natural_language_query_and_rejects_unsafe_query_content(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "knowledge.vector_search",
                "description": "knowledge search",
                "inputSchema": {
                    "type": "object",
                    "required": ["query", "userId", "projectId"],
                },
                **self._manifest(),
            },
        ])
        base_arguments = {"userId": 7, "projectId": 9}

        self.assertIsNone(registry.validate_arguments(
            "knowledge.vector_search",
            {**base_arguments, "query": "分析这部小说的叙事节奏与人物成长"},
        ))
        for natural_language_query in (
            "Select books from the latest ranking",
            "Update my understanding of the latest ranking",
            "Delete repetitive wording from this chapter",
            "Create a story from the ranking data",
        ):
            with self.subTest(query=natural_language_query):
                self.assertIsNone(registry.validate_arguments(
                    "knowledge.vector_search",
                    {**base_arguments, "query": natural_language_query},
                ))
        for unsafe_arguments in (
            {**base_arguments, "query": "select * from users where project_id = 9"},
            {**base_arguments, "query": "SELECT title, rank_no FROM rankings WHERE platform = 'fanqie';"},
            {**base_arguments, "query": "select title from rankings -- latest"},
            {**base_arguments, "query": "/* ranked */ SELECT title FROM rankings"},
            {**base_arguments, "query": "1; DROP TABLE books;"},
            {**base_arguments, "query": "DELETE FROM books WHERE id = 1"},
            {**base_arguments, "query": "https://evil.example/steal"},
            {**base_arguments, "query": "C:/Users/test/.env"},
            {**base_arguments, "query": "powershell Get-ChildItem Env:"},
            {**base_arguments, "url": "harmless text", "query": "正常检索"},
            {**base_arguments, "path": "harmless text", "query": "正常检索"},
            {**base_arguments, "sql": "harmless text", "query": "正常检索"},
        ):
            with self.subTest(arguments=unsafe_arguments):
                self.assertIn(
                    "unsafe tool argument",
                    registry.validate_arguments("knowledge.vector_search", unsafe_arguments) or "",
                )

    def test_exposes_governed_manifest_through_public_accessors(self) -> None:
        registry = McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank",
                "inputSchema": {"type": "object"},
                **self._manifest(
                    timeoutMs=12500,
                    identityKeys=["projectId", "userId"],
                    secretInputKeys=["accessToken"],
                    secretOutputKeys=["credential"],
                ),
            },
        ])

        self.assertEqual("read", registry.side_effect_type("rank.lookup"))
        self.assertEqual(12.5, registry.timeout_seconds("rank.lookup"))
        self.assertEqual(("projectId", "userId"), registry.identity_keys("rank.lookup"))
        self.assertEqual(({"accessToken"}, {"credential"}), registry.secret_keys("rank.lookup"))
        self.assertEqual("write", registry.side_effect_type("missing.tool"))
        self.assertEqual((), registry.identity_keys("missing.tool"))


if __name__ == "__main__":
    unittest.main()
