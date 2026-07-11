from __future__ import annotations

import unittest

from app.services.mcp.tool_registry import McpToolRegistry
from app.services.runtime.tool_call_loop import ToolCallLoop


class FakeMcpClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def call_tool(self, name: str, arguments: dict, timeout: float | None = None, route: str | None = None) -> dict:
        self.calls.append({"name": name, "arguments": arguments, "timeout": timeout, "route": route})
        return {"ok": True, "token": "SECRET_TOKEN", "items": [{"rankNo": 1}]}


class FakeProvider:
    def __init__(self, responses: list[dict]) -> None:
        self.responses = list(responses)
        self.calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class ToolCallLoopTest(unittest.IsolatedAsyncioTestCase):
    def _registry(self) -> McpToolRegistry:
        return McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "required": ["platform"]},
            },
            {
                "name": "rank.refresh",
                "description": "rank refresh",
                "inputSchema": {"type": "object", "required": ["platform"]},
            },
        ])

    async def test_model_tool_call_executes_through_mcp_client(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}]},
            {"content": "final answer"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(provider_client=provider, mcp_client=mcp_client, registry=self._registry()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        self.assertEqual([
            {
                "name": "rank.lookup",
                "arguments": {"platform": "fanqie"},
                "timeout": None,
                "route": "mixed_creation_research",
            }
        ], mcp_client.calls)
        self.assertEqual("final answer", result["content"])
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])
        self.assertNotIn("SECRET_TOKEN", str(provider.calls[1]["messages"]))
        self.assertIn("[redacted]", str(provider.calls[1]["messages"]))

    async def test_disallowed_tool_is_rejected_without_mcp_call(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.refresh", "arguments": {"platform": "fanqie"}}]},
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(provider_client=provider, mcp_client=mcp_client, registry=self._registry()).run(
            messages=[{"role": "user", "content": "refresh?"}],
            route="mixed_creation_research",
        )

        self.assertEqual([], mcp_client.calls)
        self.assertEqual("denied", result["toolRuns"][0]["status"])

    async def test_invalid_arguments_return_tool_error_result(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {}}]},
            {"content": "final"},
        ])

        result = await ToolCallLoop(provider_client=provider, mcp_client=FakeMcpClient(), registry=self._registry()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        self.assertEqual("failed", result["toolRuns"][0]["status"])
        self.assertIn("missing required argument", result["toolRuns"][0]["error"])

    async def test_rank_refresh_allowed_with_supervisor_permission(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.refresh", "arguments": {"platform": "fanqie"}}]},
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(provider_client=provider, mcp_client=mcp_client, registry=self._registry()).run(
            messages=[{"role": "user", "content": "refresh?"}],
            route="mixed_creation_research",
            supervisor_permissions={"rank.refresh"},
        )

        self.assertEqual("rank.refresh", mcp_client.calls[0]["name"])
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])

    async def test_risky_tool_arguments_are_rejected_without_mcp_call(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie", "url": "https://evil.example"}}]},
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(provider_client=provider, mcp_client=mcp_client, registry=self._registry()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        self.assertEqual([], mcp_client.calls)
        self.assertEqual("failed", result["toolRuns"][0]["status"])
        self.assertIn("unsafe tool argument", result["toolRuns"][0]["error"])

    async def test_thinking_mode_tool_call_preserves_reasoning_content_for_next_model_turn(self) -> None:
        provider = FakeProvider([
            {
                "content": "",
                "reasoning_content": "Need rank data before answering.",
                "tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}],
            },
            {"content": "final answer"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(provider_client=provider, mcp_client=mcp_client, registry=self._registry()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
            reasoning_mode="deep",
        )

        self.assertEqual("final answer", result["content"])
        assistant_messages = [
            message for message in provider.calls[1]["messages"]
            if message.get("role") == "assistant"
        ]
        self.assertEqual("Need rank data before answering.", assistant_messages[-1]["reasoning_content"])
        self.assertEqual("rank.lookup", assistant_messages[-1]["tool_calls"][0]["name"])

    async def test_reasoning_effort_is_forwarded_to_model_turns(self) -> None:
        provider = FakeProvider([
            {"content": "final answer"},
        ])

        await ToolCallLoop(provider_client=provider, mcp_client=FakeMcpClient(), registry=self._registry()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
            reasoning_mode="deep",
            reasoning_effort="high",
        )

        self.assertEqual("high", provider.calls[0]["reasoning_effort"])


if __name__ == "__main__":
    unittest.main()
