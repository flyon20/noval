from __future__ import annotations

import asyncio
import contextvars
import hashlib
import hmac
import json
from contextlib import asynccontextmanager
from datetime import timedelta
from types import SimpleNamespace
import unittest

import httpx

from app.services.mcp.client import ENVELOPE_HEADER, McpClient, _McpRequestAuth


class FakeOfficialSession:
    def __init__(self, pages: dict[str | None, SimpleNamespace]) -> None:
        self.pages = pages
        self.calls: list[dict] = []
        self.initialized = False

    async def initialize(self) -> None:
        self.initialized = True

    async def list_tools(self, cursor: str | None = None):
        return self.pages[cursor]

    async def call_tool(self, name: str, arguments: dict, *, read_timeout_seconds: timedelta):
        self.calls.append({"name": name, "arguments": arguments, "timeout": read_timeout_seconds})
        return SimpleNamespace(isError=False, structuredContent={"items": []}, content=[])


class OfficialMcpClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_client_session_lists_paginated_manifests_and_signs_hidden_identity(self) -> None:
        session = FakeOfficialSession(
            {
                None: SimpleNamespace(
                    tools=[
                        SimpleNamespace(
                            name="rank.lookup",
                            description="rank",
                            inputSchema={"type": "object", "properties": {"platform": {"type": "string"}}},
                            meta={
                                "noval.ai/tool-manifest": {
                                    "routes": ["market_scan"],
                                    "side_effect_type": "read",
                                    "scope_requirement": "user",
                                    "timeout_ms": 30000,
                                    "identity_keys": ["userId"],
                                    "secret_input_keys": [],
                                    "secret_output_keys": [],
                                    "requires_supervisor_permission": False,
                                }
                            },
                        )
                    ],
                    nextCursor="page-2",
                ),
                "page-2": SimpleNamespace(tools=[], nextCursor=None),
            }
        )
        captured: list[dict] = []

        @asynccontextmanager
        async def session_factory(**kwargs):
            captured.append(kwargs)
            yield session

        client = McpClient(
            base_url="http://mcp.test",
            internal_api_key="test-fastmcp-internal-key-1234567890",
            call_signing_key="test-mcp-call-signing-key-1234567890",
            session_factory=session_factory,
            clock=lambda: 1_700_000_000,
            nonce_factory=lambda: "0123456789abcdef0123456789abcdef",
        )

        listed = await client.list_tools()
        self.assertEqual("market_scan", listed["tools"][0]["routes"][0])
        self.assertTrue(session.initialized)
        self.assertEqual(1, len(captured))

        response = await client.call_tool(
            "rank.lookup",
            {"platform": "fanqie", "userId": "7", "projectId": "91"},
            timeout=12.5,
            route="market_scan",
            user_id="7",
            project_id="91",
        )

        self.assertEqual({"name": "rank.lookup", "result": {"items": []}}, response)
        self.assertEqual({"platform": "fanqie"}, session.calls[0]["arguments"])
        self.assertEqual(timedelta(seconds=12.5), session.calls[0]["timeout"])
        envelope = json.loads(captured[-1]["envelope"])
        self.assertEqual("7", envelope["userId"])
        self.assertEqual("91", envelope["projectId"])
        self.assertEqual("rank.lookup", envelope["name"])
        signature = envelope.pop("signature")
        canonical = json.dumps(envelope, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
        self.assertEqual(
            hmac.new(b"test-mcp-call-signing-key-1234567890", canonical.encode(), hashlib.sha256).hexdigest(),
            signature,
        )

    async def test_request_auth_carries_internal_token_and_isolates_concurrent_envelopes(self) -> None:
        envelope_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("test_envelope", default=None)
        auth = _McpRequestAuth("test-fastmcp-internal-key-1234567890", envelope_var)

        async def authorized_headers(envelope: str) -> httpx.Headers:
            token = envelope_var.set(envelope)
            try:
                await asyncio.sleep(0)
                request = httpx.Request("POST", "http://mcp.test/mcp/v1/")
                next(auth.auth_flow(request))
                return request.headers
            finally:
                envelope_var.reset(token)

        first, second = await asyncio.gather(authorized_headers("first"), authorized_headers("second"))
        self.assertEqual("first", first[ENVELOPE_HEADER])
        self.assertEqual("second", second[ENVELOPE_HEADER])
        self.assertEqual("test-fastmcp-internal-key-1234567890", first["X-Internal-Service-Token"])

    async def test_client_fails_closed_without_required_keys_or_identity(self) -> None:
        @asynccontextmanager
        async def unused_factory(**_kwargs):
            raise AssertionError("session must not open")
            yield

        missing_signing = McpClient(
            internal_api_key="test-fastmcp-internal-key-1234567890",
            call_signing_key="",
            session_factory=unused_factory,
        )
        with self.assertRaisesRegex(RuntimeError, "MCP_CALL_SIGNING_KEY"):
            await missing_signing.call_tool("rank.lookup", {}, route="market_scan", user_id="7")

        missing_internal = McpClient(
            internal_api_key="",
            call_signing_key="test-mcp-call-signing-key-1234567890",
            session_factory=unused_factory,
        )
        with self.assertRaisesRegex(RuntimeError, "MCP_INTERNAL_API_KEY"):
            await missing_internal.list_tools()

        configured = McpClient(
            internal_api_key="test-fastmcp-internal-key-1234567890",
            call_signing_key="test-mcp-call-signing-key-1234567890",
            session_factory=unused_factory,
        )
        with self.assertRaisesRegex(RuntimeError, "route and userId"):
            await configured.call_tool("rank.lookup", {}, route="", user_id="7")

    async def test_client_surfaces_mcp_tool_errors_and_missing_structured_content(self) -> None:
        class ResultSession(FakeOfficialSession):
            def __init__(self, result) -> None:
                super().__init__({})
                self.result = result

            async def call_tool(self, *_args, **_kwargs):
                return self.result

        results = [
            SimpleNamespace(isError=True, structuredContent=None, content=[SimpleNamespace(text="admin tool denied")]),
            SimpleNamespace(isError=False, structuredContent=None, content=[]),
        ]

        for result, expected in zip(results, ("admin tool denied", "no structured content"), strict=True):
            session = ResultSession(result)

            @asynccontextmanager
            async def session_factory(**_kwargs):
                yield session

            client = McpClient(
                internal_api_key="test-fastmcp-internal-key-1234567890",
                call_signing_key="test-mcp-call-signing-key-1234567890",
                session_factory=session_factory,
            )
            with self.assertRaisesRegex(RuntimeError, expected):
                await client.call_tool("rank.lookup", {"userId": "7"}, route="market_scan", user_id="7")

    async def test_aclose_closes_shared_http_client(self) -> None:
        class FakeHttpClient:
            def __init__(self, **kwargs) -> None:
                self.kwargs = kwargs
                self.closed = False

            async def aclose(self) -> None:
                self.closed = True

        http_client = FakeHttpClient()

        @asynccontextmanager
        async def session_factory(**_kwargs):
            yield FakeOfficialSession({None: SimpleNamespace(tools=[], nextCursor=None)})

        client = McpClient(
            internal_api_key="test-fastmcp-internal-key-1234567890",
            call_signing_key="test-mcp-call-signing-key-1234567890",
            async_client_factory=lambda **_kwargs: http_client,
            session_factory=session_factory,
        )
        await client.list_tools()
        await client.aclose()
        self.assertTrue(http_client.closed)


if __name__ == "__main__":
    unittest.main()
