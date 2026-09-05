from __future__ import annotations

import unittest

from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.harness.cancellation import CancellationToken, cancellation_scope
from app.services.harness.agent_kernel import AgentKernel
from app.services.harness.tool_ledger import RunToolLedger
from app.models.agent_task import RunToolIdentity
from app.services.mcp.tool_registry import McpToolRegistry
from app.services.runtime.tool_call_loop import ToolCallLoop


class FakeMcpClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        timeout: float | None = None,
        route: str | None = None,
        user_id: str | None = None,
        project_id: str | None = None,
        supervisor_permissions: set[str] | None = None,
    ) -> dict:
        self.calls.append({
            "name": name,
            "arguments": arguments,
            "timeout": timeout,
            "route": route,
            "userId": user_id,
            "projectId": project_id,
            "supervisorPermissions": sorted(supervisor_permissions or set()),
        })
        return {"ok": True, "token": "SECRET_TOKEN", "items": [{"rankNo": 1}]}


class FakeProvider:
    def __init__(self, responses: list[dict]) -> None:
        self.responses = list(responses)
        self.calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class ToolCallLoopTest(unittest.IsolatedAsyncioTestCase):
    def _ledger(self) -> RunToolLedger:
        return RunToolLedger(RunToolIdentity(
            runId="run-tool-loop",
            userId=7,
            projectId=91,
            route="mixed_creation_research",
        ))

    def _registry(self) -> McpToolRegistry:
        manifest = {
            "routes": ["mixed_creation_research"],
            "sideEffectType": "read",
            "scopeRequirement": "project",
            "timeoutMs": 30000,
            "identityKeys": ["userId", "projectId"],
            "secretInputKeys": [],
            "secretOutputKeys": ["token"],
        }
        return McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "required": ["platform"]},
                **manifest,
            },
            {
                "name": "rank.refresh",
                "description": "rank refresh",
                "inputSchema": {"type": "object", "required": ["platform"]},
                **{
                    **manifest,
                    "sideEffectType": "write",
                    "requiresSupervisorPermission": True,
                },
            },
        ])

    def _user_registry(self) -> McpToolRegistry:
        return McpToolRegistry([
            {
                "name": "rank.lookup",
                "description": "rank lookup",
                "inputSchema": {"type": "object", "required": ["platform", "userId"]},
                "routes": ["mixed_creation_research"],
                "sideEffectType": "read",
                "scopeRequirement": "user",
                "timeoutMs": 30000,
                "identityKeys": ["userId"],
                "secretInputKeys": [],
                "secretOutputKeys": ["token"],
                "requiresSupervisorPermission": False,
            },
            {
                "name": "project.retrieve",
                "description": "project retrieval",
                "inputSchema": {
                    "type": "object",
                    "required": ["userId", "projectId", "workId", "query"],
                },
                "routes": ["mixed_creation_research"],
                "sideEffectType": "read",
                "scopeRequirement": "project",
                "timeoutMs": 30000,
                "identityKeys": ["userId", "projectId"],
                "secretInputKeys": [],
                "secretOutputKeys": [],
                "requiresSupervisorPermission": False,
            },
        ])

    async def test_projectless_user_tool_strips_model_supplied_project_scope(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [{
                    "id": "call-user-scope",
                    "name": "rank.lookup",
                    "arguments": {
                        "platform": "fanqie",
                        "userId": "999",
                        "projectId": "888",
                    },
                }],
            },
            {"content": "projectless answer"},
        ])
        mcp_client = FakeMcpClient()
        ledger = RunToolLedger(RunToolIdentity(
            runId="run-projectless",
            userId=7,
            projectId=None,
            route="mixed_creation_research",
        ))

        result = await ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._user_registry(),
            tool_ledger=ledger,
        ).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        self.assertEqual(["rank.lookup"], [tool["function"]["name"] for tool in provider.calls[0]["tools"]])
        self.assertEqual({"platform": "fanqie", "userId": "7"}, mcp_client.calls[0]["arguments"])
        self.assertIsNone(mcp_client.calls[0]["projectId"])
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])

    async def test_model_tool_call_executes_through_mcp_client(self) -> None:
        provider = FakeProvider([
            {
                "raw_tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "rank.lookup", "arguments": '{"platform":"fanqie"}'},
                }],
                "tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}],
            },
            {"content": "final answer"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=self._ledger(),
        ).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        self.assertEqual([
            {
                "name": "rank.lookup",
                "arguments": {"platform": "fanqie", "userId": "7", "projectId": "91"},
                "timeout": 30.0,
                "route": "mixed_creation_research",
                "userId": "7",
                "projectId": "91",
                "supervisorPermissions": [],
            }
        ], mcp_client.calls)
        self.assertEqual("final answer", result["content"])
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])
        self.assertTrue(result["kernelUsed"])
        self.assertEqual("completed", result["kernelStopReason"])
        self.assertNotIn("SECRET_TOKEN", str(provider.calls[1]["messages"]))
        self.assertIn("[redacted]", str(provider.calls[1]["messages"]))
        self.assertIn("UNTRUSTED_DATA", str(provider.calls[1]["messages"]))
        assistant = next(
            message
            for message in provider.calls[1]["messages"]
            if message.get("role") == "assistant"
        )
        tool_call = assistant["tool_calls"][0]
        self.assertEqual("function", tool_call["type"])
        self.assertEqual("rank.lookup", tool_call["function"]["name"])
        self.assertIsInstance(tool_call["function"]["arguments"], str)

    async def test_disallowed_tool_is_rejected_without_mcp_call(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [{
                    "id": "call-1",
                    "name": "rank.refresh",
                    "arguments": {"platform": "fanqie"},
                }]
            },
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=mcp_client, registry=self._registry(), tool_ledger=self._ledger()).run(
            messages=[{"role": "user", "content": "refresh?"}],
            route="mixed_creation_research",
        )

        self.assertEqual([], mcp_client.calls)
        self.assertEqual("denied", result["toolRuns"][0]["status"])

    async def test_invalid_arguments_return_tool_error_result(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"apiKey": "SECRET_INPUT"}}]},
            {"content": "final"},
        ])

        result = await ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=FakeMcpClient(), registry=self._registry(), tool_ledger=self._ledger()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        self.assertEqual("failed", result["toolRuns"][0]["status"])
        self.assertIn("missing required argument", result["toolRuns"][0]["error"])
        self.assertNotIn("SECRET_INPUT", str(result))
        self.assertNotIn("SECRET_INPUT", str(provider.calls[-1]["messages"]))

    async def test_rank_refresh_allowed_with_supervisor_permission(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [{
                    "id": "call-1",
                    "name": "rank.refresh",
                    "arguments": {"platform": "fanqie"},
                }]
            },
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=mcp_client, registry=self._registry(), tool_ledger=self._ledger()).run(
            messages=[{"role": "user", "content": "refresh?"}],
            route="mixed_creation_research",
            supervisor_permissions={"rank.refresh"},
        )

        self.assertEqual("rank.refresh", mcp_client.calls[0]["name"])
        self.assertEqual(["rank.refresh"], mcp_client.calls[0]["supervisorPermissions"])
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])

    async def test_model_identity_and_permissions_are_replaced_by_trusted_inputs(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [{
                    "id": "call-1",
                    "name": "rank.lookup",
                    "arguments": {
                        "platform": "fanqie",
                        "userId": "999",
                        "projectId": "888",
                        "supervisorPermissions": ["admin:*"],
                        "permissions": ["admin:*"],
                    },
                }]
            },
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=self._ledger(),
        ).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
            supervisor_permissions={"rank.lookup"},
        )

        call = mcp_client.calls[0]
        self.assertEqual("7", call["arguments"]["userId"])
        self.assertEqual("91", call["arguments"]["projectId"])
        self.assertNotIn("permissions", call["arguments"])
        self.assertNotIn("supervisorPermissions", call["arguments"])
        self.assertEqual(["rank.lookup"], call["supervisorPermissions"])
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])

    async def test_risky_tool_arguments_are_rejected_without_mcp_call(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie", "url": "https://evil.example"}}]},
            {"content": "final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=mcp_client, registry=self._registry(), tool_ledger=self._ledger()).run(
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

        result = await ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=mcp_client, registry=self._registry(), tool_ledger=self._ledger()).run(
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
        self.assertEqual("rank.lookup", assistant_messages[-1]["tool_calls"][0]["function"]["name"])

    async def test_reasoning_effort_is_forwarded_to_model_turns(self) -> None:
        provider = FakeProvider([
            {"content": "final answer"},
        ])

        await ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=FakeMcpClient(), registry=self._registry(), tool_ledger=self._ledger()).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
            reasoning_mode="deep",
            reasoning_effort="high",
        )

        self.assertEqual("high", provider.calls[0]["reasoning_effort"])

    async def test_tool_budget_exhaustion_stops_loop_after_one_tool_free_final_turn(self) -> None:
        repeated_tool_call = {
            "tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}]
        }
        provider = FakeProvider([repeated_tool_call, {"content": "budget-aware final"}])
        mcp_client = FakeMcpClient()
        budget = RunBudget(
            mode="fast",
            max_total_tokens=128_000,
            max_tool_calls=0,
            max_delegations=1,
        )

        with run_budget_scope(budget):
            result = await ToolCallLoop(
                agent_kernel=AgentKernel(provider),
                mcp_client=mcp_client,
                registry=self._registry(),
                tool_ledger=self._ledger(),
            ).run(
                messages=[{"role": "user", "content": "rank?"}],
                route="mixed_creation_research",
            )

        self.assertEqual(2, len(provider.calls))
        self.assertEqual([], mcp_client.calls)
        self.assertEqual("budget-aware final", result["content"])
        self.assertEqual("tool_budget_exceeded", result["finishReason"])
        self.assertEqual("failed", result["toolRuns"][0]["status"])
        self.assertNotIn("tools", provider.calls[-1])

    async def test_tool_budget_exhaustion_closes_all_parallel_tool_call_messages(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [
                    {"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}},
                    {"id": "call-2", "name": "rank.lookup", "arguments": {"platform": "qidian"}},
                ]
            },
            {"content": "closed message sequence"},
        ])
        budget = RunBudget(
            mode="fast",
            max_total_tokens=128_000,
            max_tool_calls=0,
            max_delegations=1,
        )

        with run_budget_scope(budget):
            result = await ToolCallLoop(
                agent_kernel=AgentKernel(provider),
                mcp_client=FakeMcpClient(),
                registry=self._registry(),
                tool_ledger=self._ledger(),
            ).run(
                messages=[{"role": "user", "content": "compare ranks"}],
                route="mixed_creation_research",
            )

        final_messages = provider.calls[-1]["messages"]
        tool_messages = [message for message in final_messages if message.get("role") == "tool"]
        self.assertEqual(["call-1", "call-2"], [message["tool_call_id"] for message in tool_messages])
        self.assertEqual(2, len(result["toolRuns"]))
        self.assertTrue(all(run["status"] == "failed" for run in result["toolRuns"]))

    async def test_explicit_tool_limit_caps_parallel_calls_in_one_model_turn(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [
                    {"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}},
                    {"id": "call-2", "name": "rank.lookup", "arguments": {"platform": "qidian"}},
                ]
            },
            {"content": "one-tool final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=self._ledger(),
        ).run(
            messages=[{"role": "user", "content": "compare"}],
            route="mixed_creation_research",
            max_tool_calls=1,
        )

        self.assertEqual(1, len(mcp_client.calls))
        self.assertEqual(2, len(result["toolRuns"]))
        self.assertEqual("succeeded", result["toolRuns"][0]["status"])
        self.assertEqual("ToolBudgetExceeded", result["toolRuns"][1]["errorType"])

    async def test_default_fast_limit_caps_parallel_calls_in_one_model_turn(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [
                    {
                        "id": f"call-{index}",
                        "name": "rank.lookup",
                        "arguments": {"platform": f"platform-{index}"},
                    }
                    for index in range(1, 9)
                ]
            },
            {"content": "bounded final"},
        ])
        mcp_client = FakeMcpClient()
        loop = ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=self._ledger(),
        )
        loop.max_same_tool_calls = 99

        result = await loop.run(
            messages=[{"role": "user", "content": "compare all"}],
            route="mixed_creation_research",
        )

        self.assertEqual(6, len(mcp_client.calls))
        self.assertEqual(8, len(result["toolRuns"]))
        self.assertTrue(all(run["status"] == "succeeded" for run in result["toolRuns"][:6]))
        self.assertTrue(all(run["errorType"] == "ToolBudgetExceeded" for run in result["toolRuns"][6:]))
        self.assertEqual("tool_budget_exceeded", result["finishReason"])

    async def test_default_deep_limit_caps_parallel_calls_in_one_model_turn(self) -> None:
        provider = FakeProvider([
            {
                "tool_calls": [
                    {
                        "id": f"call-{index}",
                        "name": "rank.lookup",
                        "arguments": {"platform": f"platform-{index}"},
                    }
                    for index in range(1, 15)
                ]
            },
            {"content": "bounded deep final"},
        ])
        mcp_client = FakeMcpClient()
        loop = ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=self._ledger(),
        )
        loop.max_same_tool_calls = 99

        result = await loop.run(
            messages=[{"role": "user", "content": "compare deeply"}],
            route="mixed_creation_research",
            reasoning_mode="deep",
        )

        self.assertEqual(12, len(mcp_client.calls))
        self.assertEqual(14, len(result["toolRuns"]))
        self.assertTrue(all(run["errorType"] == "ToolBudgetExceeded" for run in result["toolRuns"][12:]))
        self.assertEqual("tool_budget_exceeded", result["finishReason"])

    async def test_reuses_committed_read_without_second_mcp_call_or_budget_charge(self) -> None:
        tool_call = {
            "tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}]
        }
        provider = FakeProvider([tool_call, {"content": "first"}, tool_call, {"content": "second"}])
        mcp_client = FakeMcpClient()
        ledger = self._ledger()
        budget = RunBudget.fast()
        loop = ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=ledger,
        )

        with run_budget_scope(budget):
            first = await loop.run(messages=[{"role": "user", "content": "rank?"}], route="mixed_creation_research")
            second = await loop.run(messages=[{"role": "user", "content": "rank again?"}], route="mixed_creation_research")

        self.assertEqual(1, len(mcp_client.calls))
        self.assertEqual(1, budget.used_tool_calls)
        self.assertFalse(first["toolRuns"][0]["reused"])
        self.assertTrue(second["toolRuns"][0]["reused"])

    async def test_checkpoint_resume_reuses_committed_read_without_second_mcp_call(self) -> None:
        tool_call = {
            "tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}]
        }
        provider = FakeProvider([tool_call, {"content": "first"}, tool_call, {"content": "resumed"}])
        mcp_client = FakeMcpClient()
        first_ledger = self._ledger()

        first = await ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=first_ledger,
        ).run(
            messages=[{"role": "user", "content": "rank?"}],
            route="mixed_creation_research",
        )

        resumed_ledger = self._ledger()
        resumed_ledger.merge_checkpoint(first_ledger.checkpoint_snapshot())
        resumed_budget = RunBudget.fast()
        with run_budget_scope(resumed_budget):
            resumed = await ToolCallLoop(
                agent_kernel=AgentKernel(provider),
                mcp_client=mcp_client,
                registry=self._registry(),
                tool_ledger=resumed_ledger,
            ).run(
                messages=[{"role": "user", "content": "rank after resume?"}],
                route="mixed_creation_research",
            )

        self.assertEqual(1, len(mcp_client.calls))
        self.assertFalse(first["toolRuns"][0]["reused"])
        self.assertTrue(resumed["toolRuns"][0]["reused"])
        self.assertEqual(0, resumed_budget.used_tool_calls)

    async def test_rejects_provider_call_id_reuse_with_different_arguments(self) -> None:
        provider = FakeProvider([
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}]},
            {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "qidian"}}]},
            {"content": "conflict-aware final"},
        ])
        mcp_client = FakeMcpClient()

        result = await ToolCallLoop(
            agent_kernel=AgentKernel(provider),
            mcp_client=mcp_client,
            registry=self._registry(),
            tool_ledger=self._ledger(),
        ).run(
            messages=[{"role": "user", "content": "compare"}],
            route="mixed_creation_research",
        )

        self.assertEqual(1, len(mcp_client.calls))
        self.assertEqual("CallIdentityConflict", result["toolRuns"][1]["errorType"])

    async def test_records_exception_timeout_and_cancellation_terminal_states(self) -> None:
        class TerminalMcpClient(FakeMcpClient):
            def __init__(self, mode: str) -> None:
                super().__init__()
                self.mode = mode

            async def call_tool(self, name: str, arguments: dict, **_kwargs) -> dict:
                if self.mode == "failed":
                    raise ValueError("bad tool")
                await __import__("asyncio").Event().wait()
                return {}

        tool_call = {"tool_calls": [{"id": "call-1", "name": "rank.lookup", "arguments": {"platform": "fanqie"}}]}
        failed = await ToolCallLoop(
            agent_kernel=AgentKernel(FakeProvider([tool_call, {"content": "done"}])),
            mcp_client=TerminalMcpClient("failed"),
            registry=self._registry(),
            tool_ledger=self._ledger(),
        ).run(messages=[{"role": "user", "content": "rank?"}], route="mixed_creation_research")
        self.assertEqual("failed", failed["toolRuns"][0]["status"])
        self.assertEqual("ValueError", failed["toolRuns"][0]["errorType"])

        timeout_registry = self._registry()
        timeout_registry._tools["rank.lookup"]["timeoutMs"] = 10
        timed_out = await ToolCallLoop(
            agent_kernel=AgentKernel(FakeProvider([tool_call, {"content": "done"}])),
            mcp_client=TerminalMcpClient("timeout"),
            registry=timeout_registry,
            tool_ledger=self._ledger(),
        ).run(messages=[{"role": "user", "content": "rank?"}], route="mixed_creation_research")
        self.assertEqual("timed_out", timed_out["toolRuns"][0]["status"])
        self.assertEqual("ToolTimeout", timed_out["toolRuns"][0]["errorType"])

        token = CancellationToken()
        provider = FakeProvider([tool_call, {"content": "unused"}])
        with cancellation_scope(token):
            task = __import__("asyncio").create_task(ToolCallLoop(
                agent_kernel=AgentKernel(provider),
                mcp_client=TerminalMcpClient("cancelled"),
                registry=self._registry(),
                tool_ledger=self._ledger(),
            ).run(messages=[{"role": "user", "content": "rank?"}], route="mixed_creation_research"))
            await __import__("asyncio").sleep(0.01)
            token.cancel("user_requested")
            cancelled = await task
        self.assertEqual("cancelled", cancelled["toolRuns"][0]["status"])
        self.assertEqual("RunCancelledError", cancelled["toolRuns"][0]["errorType"])


if __name__ == "__main__":
    unittest.main()
