from __future__ import annotations

import hashlib
import hmac
import json
import time
import unittest
import uuid

import httpx
from fastapi import FastAPI
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from app import main, security
from app.config import settings
from app.fastmcp_server import ENVELOPE_HEADER, FastMcpRuntime, MCP_PATH


INTERNAL_KEY = "fastmcp-cutover-test-internal-key-1234567890"
SIGNING_KEY = "fastmcp-cutover-signing-key-1234567890"
MANIFEST_META_KEY = "noval.ai/tool-manifest"


def signed_envelope(*, name: str, route: str, arguments: dict, permissions: set[str] | None = None) -> dict:
    claims = {
        "name": name,
        "arguments": arguments,
        "route": route,
        "userId": str(arguments.get("userId") or ""),
        "projectId": str(arguments.get("projectId") or ""),
        "supervisorPermissions": sorted(permissions or set()),
        "timestamp": int(time.time()),
        "nonce": uuid.uuid4().hex,
    }
    canonical = json.dumps(claims, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    claims["signature"] = hmac.new(SIGNING_KEY.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return claims


class FakeBackendClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def post(self, path: str, payload: dict) -> dict:
        self.calls.append({"path": path, "payload": payload})
        return {"items": [{"rankNo": 1, "bookName": "历史榜单样本"}]}


class FastMcpCutoverTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.original_internal_key = settings.mcp_internal_api_key
        self.original_signing_key = settings.mcp_call_signing_key
        self.original_nonce_guard = security._NONCE_REPLAY_GUARD
        self.original_backend_client = main.backend_client
        settings.mcp_internal_api_key = INTERNAL_KEY
        settings.mcp_call_signing_key = SIGNING_KEY
        security._NONCE_REPLAY_GUARD = security._BoundedMemoryNonceReplayGuard(max_entries=1000)
        self.backend_client = FakeBackendClient()
        main.backend_client = self.backend_client
        self.runtime = FastMcpRuntime(main.tool_registry, lambda: main.backend_client)
        self.app = FastAPI()
        self.app.mount(MCP_PATH, self.runtime)

    def tearDown(self) -> None:
        settings.mcp_internal_api_key = self.original_internal_key
        settings.mcp_call_signing_key = self.original_signing_key
        security._NONCE_REPLAY_GUARD = self.original_nonce_guard
        main.backend_client = self.original_backend_client

    async def test_standard_endpoint_is_only_mcp_http_surface(self) -> None:
        transport = httpx.ASGITransport(app=self.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            self.assertEqual(404, (await client.get("/mcp/tools")).status_code)
            self.assertEqual(404, (await client.post("/mcp/call", json={})).status_code)
            response = await client.post(
                f"{MCP_PATH}/",
                headers={"Accept": "application/json, text/event-stream", "Content-Type": "application/json"},
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2025-06-18",
                        "capabilities": {},
                        "clientInfo": {"name": "cutover-test", "version": "1"},
                    },
                },
            )
        self.assertEqual(401, response.status_code)

    async def test_list_exposes_manifest_metadata_and_historical_rank_lookup(self) -> None:
        async with self.runtime.lifespan():
            transport = httpx.ASGITransport(app=self.app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://testserver",
                headers={"X-Internal-Service-Token": INTERNAL_KEY},
            ) as http_client:
                async with streamable_http_client(
                    f"http://testserver{MCP_PATH}/", http_client=http_client, terminate_on_close=False
                ) as (read_stream, write_stream, _get_session_id):
                    async with ClientSession(read_stream, write_stream) as session:
                        await session.initialize()
                        listed = await session.list_tools()
                        tools = {tool.name: tool for tool in listed.tools}
                        self.assertIn("rank.lookup", tools)
                        self.assertIn("memory.admin.list", tools)
                        self.assertNotIn("userId", tools["rank.lookup"].inputSchema["properties"])
                        manifest = (tools["rank.lookup"].meta or {}).get(MANIFEST_META_KEY)
                        self.assertEqual(["market_scan", "mixed_creation_research"], manifest["routes"])
                        self.assertEqual("read", manifest["side_effect_type"])

                        arguments = {
                            "platform": "fanqie",
                            "allowHistorical": True,
                            "snapshotStartDate": "2026-08-01",
                            "snapshotEndDate": "2026-08-07",
                            "userId": "7",
                        }
                        http_client.headers[ENVELOPE_HEADER] = json.dumps(
                            signed_envelope(name="rank.lookup", route="market_scan", arguments=arguments),
                            separators=(",", ":"),
                        )
                        result = await session.call_tool(
                            "rank.lookup",
                            {
                                "platform": "fanqie",
                                "allowHistorical": True,
                                "snapshotStartDate": "2026-08-01",
                                "snapshotEndDate": "2026-08-07",
                            },
                        )

        self.assertFalse(result.isError)
        self.assertEqual({"items": [{"rankNo": 1, "bookName": "历史榜单样本"}]}, result.structuredContent)
        self.assertEqual(
            {
                "path": "/internal/knowledge/rank/lookup",
                "payload": {
                    "platform": "fanqie",
                    "limit": 10,
                    "allowHistorical": True,
                    "snapshotStartDate": "2026-08-01",
                    "snapshotEndDate": "2026-08-07",
                },
            },
            self.backend_client.calls[0],
        )

    async def test_admin_tool_requires_admin_route_and_permission(self) -> None:
        async with self.runtime.lifespan():
            transport = httpx.ASGITransport(app=self.app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://testserver",
                headers={"X-Internal-Service-Token": INTERNAL_KEY},
            ) as http_client:
                async with streamable_http_client(
                    f"http://testserver{MCP_PATH}/", http_client=http_client, terminate_on_close=False
                ) as (read_stream, write_stream, _get_session_id):
                    async with ClientSession(read_stream, write_stream) as session:
                        await session.initialize()
                        denied_args = {"userId": "7", "projectId": "91", "limit": 2}
                        http_client.headers[ENVELOPE_HEADER] = json.dumps(
                            signed_envelope(name="memory.admin.list", route="market_scan", arguments=denied_args),
                            separators=(",", ":"),
                        )
                        denied = await session.call_tool("memory.admin.list", {"limit": 2})
                        self.assertTrue(denied.isError)

                        allowed_args = {"userId": "7", "projectId": "91", "limit": 2}
                        http_client.headers[ENVELOPE_HEADER] = json.dumps(
                            signed_envelope(
                                name="memory.admin.list",
                                route="admin",
                                arguments=allowed_args,
                                permissions={"admin:*"},
                            ),
                            separators=(",", ":"),
                        )
                        allowed = await session.call_tool("memory.admin.list", {"limit": 2})

        self.assertFalse(allowed.isError)
        self.assertEqual({"filters": {"userId": 7, "projectId": 91, "limit": 2}, "items": []}, allowed.structuredContent)


if __name__ == "__main__":
    unittest.main()
