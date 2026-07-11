from __future__ import annotations

import unittest

from fastapi.testclient import TestClient

from app import main
from app.backend_client import BackendClient
from app.config import settings


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self):
        return self._payload


class FakeAsyncClient:
    calls: list[dict] = []

    def __init__(self, **kwargs) -> None:
        self.kwargs = kwargs

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def post(self, path: str, *, json: dict, headers: dict):
        self.__class__.calls.append({"path": path, "json": json, "headers": headers})
        return FakeResponse([{"rankNo": 1, "bookName": "Rank One"}])


class McpToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_mcp_key = settings.mcp_internal_api_key
        self.original_backend_key = settings.backend_internal_api_key
        settings.mcp_internal_api_key = "mcp-test-key"
        settings.backend_internal_api_key = "backend-test-key"
        FakeAsyncClient.calls = []
        self.original_backend_client = main.backend_client
        main.backend_client = BackendClient(async_client_factory=lambda **kwargs: FakeAsyncClient(**kwargs))

    def tearDown(self) -> None:
        settings.mcp_internal_api_key = self.original_mcp_key
        settings.backend_internal_api_key = self.original_backend_key
        main.backend_client = self.original_backend_client

    def test_should_reject_mcp_call_without_internal_token(self) -> None:
        with TestClient(main.app) as client:
            response = client.post("/mcp/call", json={"name": "rank.lookup", "arguments": {"platform": "fanqie"}})

        self.assertEqual(401, response.status_code)

    def test_rank_lookup_validates_input_schema(self) -> None:
        with TestClient(main.app) as client:
            response = client.post(
                "/mcp/call",
                headers={"X-Internal-Service-Token": "mcp-test-key"},
                json={"name": "rank.lookup", "arguments": {"platform": "", "limit": 0}},
            )

        self.assertEqual(400, response.status_code)

    def test_rank_lookup_accepts_route_and_forwards_internal_token_to_backend(self) -> None:
        with TestClient(main.app) as client:
            response = client.post(
                "/mcp/call",
                headers={"X-Internal-Service-Token": "mcp-test-key"},
                json={
                    "name": "rank.lookup",
                    "route": "mixed_creation_research",
                    "arguments": {"platform": "fanqie", "limit": 3},
                },
            )

        self.assertEqual(200, response.status_code)
        self.assertEqual("/internal/knowledge/rank/lookup", FakeAsyncClient.calls[0]["path"])
        self.assertEqual("backend-test-key", FakeAsyncClient.calls[0]["headers"]["X-Internal-Service-Token"])
        self.assertEqual({"platform": "fanqie", "limit": 3}, FakeAsyncClient.calls[0]["json"])

    def test_project_creation_route_cannot_call_rank_lookup_server_side(self) -> None:
        with TestClient(main.app) as client:
            response = client.post(
                "/mcp/call",
                headers={"X-Internal-Service-Token": "mcp-test-key"},
                json={
                    "name": "rank.lookup",
                    "route": "project_creation",
                    "arguments": {"platform": "fanqie", "limit": 3},
                },
            )

        self.assertEqual(403, response.status_code)
        self.assertEqual([], FakeAsyncClient.calls)

    def test_memory_project_context_requires_user_and_project(self) -> None:
        with TestClient(main.app) as client:
            response = client.post(
                "/mcp/call",
                headers={"X-Internal-Service-Token": "mcp-test-key"},
                json={"name": "memory.project_context", "arguments": {"userId": 7}},
            )

        self.assertEqual(400, response.status_code)

    def test_admin_tools_are_hidden_from_normal_toolset(self) -> None:
        with TestClient(main.app) as client:
            response = client.get("/mcp/tools", headers={"X-Internal-Service-Token": "mcp-test-key"})

        self.assertEqual(200, response.status_code)
        names = {tool["name"] for tool in response.json()["tools"]}
        self.assertIn("rank.lookup", names)
        self.assertNotIn("memory.admin.list", names)


if __name__ == "__main__":
    unittest.main()
