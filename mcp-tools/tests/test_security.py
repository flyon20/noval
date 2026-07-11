from __future__ import annotations

import unittest

from fastapi.testclient import TestClient

from app import main
from app.config import settings


class McpSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_key = settings.mcp_internal_api_key
        settings.mcp_internal_api_key = "mcp-test-key"

    def tearDown(self) -> None:
        settings.mcp_internal_api_key = self.original_key

    def test_missing_internal_token_rejects_tool_listing(self) -> None:
        with TestClient(main.app) as client:
            response = client.get("/mcp/tools")

        self.assertEqual(401, response.status_code)

    def test_invalid_tool_name_rejected(self) -> None:
        with TestClient(main.app) as client:
            response = client.post(
                "/mcp/call",
                headers={"X-Internal-Service-Token": "mcp-test-key"},
                json={"name": "shell.exec", "arguments": {}},
            )

        self.assertEqual(404, response.status_code)

    def test_admin_tool_requested_by_normal_route_is_rejected(self) -> None:
        with TestClient(main.app) as client:
            response = client.post(
                "/mcp/call",
                headers={"X-Internal-Service-Token": "mcp-test-key"},
                json={"name": "memory.admin.list", "route": "mixed_creation_research", "arguments": {}},
            )

        self.assertEqual(403, response.status_code)

    def test_tool_args_reject_arbitrary_url_path_or_sql(self) -> None:
        risky_arguments = [
            {"url": "https://evil.example/steal"},
            {"filePath": "C:/Users/test/.env"},
            {"query": "select * from users"},
        ]
        with TestClient(main.app) as client:
            for arguments in risky_arguments:
                response = client.post(
                    "/mcp/call",
                    headers={"X-Internal-Service-Token": "mcp-test-key"},
                    json={"name": "rank.lookup", "arguments": {"platform": "fanqie", **arguments}},
                )
                self.assertEqual(400, response.status_code)


if __name__ == "__main__":
    unittest.main()
