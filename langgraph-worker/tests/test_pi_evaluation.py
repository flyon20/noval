from __future__ import annotations

import asyncio
from copy import deepcopy
import json
import os
from pathlib import Path
import unittest

from scripts.agent_comparison import MeasuredProvider, compare_agents
from app.services.evaluation.pi_kernel import BRIDGE, PiEvaluationKernel
from app.services.harness.agent_kernel import (
    AgentKernel, KernelMessage, KernelStopReason, KernelToolObservation, KernelTurnRequest,
)


NODE = os.getenv("NOVAL_PI_NODE")
INSTALLED = (BRIDGE.parent / "node_modules/@earendil-works/pi-agent-core/package.json").exists()
SCHEMA = {"type": "function", "function": {"name": "lookup", "description": "Read fixture",
          "parameters": {"type": "object", "properties": {"query": {"type": "string"}},
                         "required": ["query"], "additionalProperties": False}}}
GRANTS = {"grants": [{"toolName": "lookup"}]}


def call(identifier="one", name="lookup", **args):
    return {"id": identifier, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}


class ScriptedProvider:
    def __init__(self, replies):
        self.replies = list(replies)
        self.requests = []

    def resolve_provider_profile(self, *args, **kwargs):
        return None

    async def invoke(self, **kwargs):
        self.requests.append(deepcopy(kwargs))
        reply = self.replies.pop(0)
        return {"model_name": "test-model", "content": "", "token_used": 10,
                "usage": {"inputTokens": 7, "outputTokens": 3, "totalTokens": 10}, **reply}


@unittest.skipUnless(NODE and INSTALLED, "Install scripts/pi-eval and set NOVAL_PI_NODE to Node >=22.19")
class PiEvaluationTest(unittest.IsolatedAsyncioTestCase):
    def make_kernel(self, replies):
        provider = ScriptedProvider(replies)
        measured = MeasuredProvider(provider, model="test-model", output_tokens=512, max_calls=12)
        return PiEvaluationKernel(AgentKernel(measured), node=NODE), provider

    def request(self, **kwargs):
        return KernelTurnRequest(messages=[KernelMessage("system", "instruction one"),
                                           KernelMessage("system", "skill catalog"), KernelMessage("user", "read fixture")],
                                 model="test-model", tool_schemas=[SCHEMA], **kwargs)

    async def test_real_pi_loop_preserves_provider_envelope_and_tool_observations(self):
        kernel, provider = self.make_kernel([
            {"tool_calls": [call(query="chapter")], "reasoning_content": "internal continuation"},
            {"content": "Answer [1]"},
        ])
        executed = []

        async def execute(tool):
            executed.append(tool.arguments)
            return KernelToolObservation(tool.id, tool.name, "succeeded", "evidence [1]")

        request = self.request(max_tool_calls=2)
        result = await kernel.run(request, authorization=GRANTS, tool_executor=execute)
        self.assertEqual(KernelStopReason.COMPLETED, result.stop_reason)
        self.assertEqual("Answer [1]", result.content)
        self.assertEqual(20, result.token_used)
        self.assertEqual([{"query": "chapter"}], executed)
        self.assertEqual([message.to_openai() for message in request.messages], provider.requests[0]["messages"])
        self.assertEqual([SCHEMA], provider.requests[0]["tools"])
        self.assertEqual("evidence [1]", provider.requests[1]["messages"][-1]["content"])
        self.assertEqual("internal continuation", provider.requests[1]["messages"][-2]["reasoning_content"])
        self.assertEqual("tool", result.messages[-1].role)
        self.assertIsNotNone(kernel.process.returncode)
        self.assertEqual("0.85.0", kernel.runs[0]["version"])

    async def test_tool_budget_prevents_extra_execution_then_allows_final_answer(self):
        kernel, provider = self.make_kernel([
            {"tool_calls": [call("one", query="a"), call("two", query="b")]}, {"content": "finished"},
        ])
        executed = []

        async def execute(tool):
            executed.append(tool.id)
            return KernelToolObservation(tool.id, tool.name, "succeeded", "result")

        result = await kernel.run(self.request(max_tool_calls=1), authorization=GRANTS, tool_executor=execute)
        self.assertEqual(["one"], executed)
        self.assertNotIn("tools", provider.requests[1])
        self.assertEqual(KernelStopReason.COMPLETED, result.stop_reason)
        self.assertIn("budget exhausted", provider.requests[1]["messages"][-1]["content"])

    async def test_unauthorized_tool_stops_before_execution(self):
        kernel, provider = self.make_kernel([{"tool_calls": [call(name="shell", command="ignored")]}])

        async def execute(tool):
            self.fail("Unauthorized tool reached executor")

        result = await kernel.run(self.request(), authorization=GRANTS, tool_executor=execute)
        self.assertEqual(KernelStopReason.UNAUTHORIZED_TOOL, result.stop_reason)
        self.assertEqual(1, len(provider.requests))

    async def test_turn_budget_stops_real_pi_loop(self):
        kernel, provider = self.make_kernel([{"tool_calls": [call(query="a")]}])

        async def execute(tool):
            return KernelToolObservation(tool.id, tool.name, "succeeded", "result")

        result = await kernel.run(self.request(max_turns=1), authorization=GRANTS, tool_executor=execute)
        self.assertEqual(KernelStopReason.MAX_TURNS, result.stop_reason)
        self.assertEqual(1, len(provider.requests))

    async def test_invalid_tool_arguments_are_not_dispatched(self):
        kernel, provider = self.make_kernel([{"tool_calls": [call(unexpected="wrong")]}, {"content": "corrected"}])

        async def execute(tool):
            self.fail("Invalid arguments reached executor")

        result = await kernel.run(self.request(), authorization=GRANTS, tool_executor=execute)
        self.assertEqual(KernelStopReason.COMPLETED, result.stop_reason)
        self.assertEqual("tool", provider.requests[1]["messages"][-1]["role"])

    async def test_cancellation_terminates_child_and_records_failed_attempt(self):
        started = asyncio.Event()

        class WaitingProvider:
            def resolve_provider_profile(self, *args, **kwargs):
                return None

            async def invoke(self, **kwargs):
                started.set()
                await asyncio.Event().wait()

        measured = MeasuredProvider(WaitingProvider(), model="test-model", output_tokens=512, max_calls=1)
        kernel = PiEvaluationKernel(AgentKernel(measured), node=NODE)
        task = asyncio.create_task(kernel.run(self.request()))
        try:
            await asyncio.wait_for(started.wait(), timeout=25)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
        self.assertIsNotNone(kernel.process.returncode)
        self.assertEqual("failed", measured.calls[0]["status"])

    async def test_reused_process_has_fresh_context_and_keeps_correction_transcript(self):
        kernel, provider = self.make_kernel([
            {"tool_calls": [call(query="one")]}, {"content": "first answer"},
            {"content": "corrected answer"}, {"content": "independent answer"},
        ])
        kernel.keep_alive = True

        async def execute(tool):
            return KernelToolObservation(tool.id, tool.name, "succeeded", "evidence [1]")

        try:
            first = await kernel.run(self.request(), authorization=GRANTS, tool_executor=execute)
            pid = kernel.process.pid
            correction = self.request()
            correction.messages = [*first.messages, KernelMessage("assistant", first.content), KernelMessage("user", "correct it")]
            second = await kernel.run(correction, authorization=GRANTS, tool_executor=execute)
            self.assertEqual(pid, kernel.process.pid)
            self.assertEqual("corrected answer", second.content)
            self.assertEqual("evidence [1]", provider.requests[2]["messages"][-3]["content"])
            await kernel.run(self.request())
            self.assertEqual([message.to_openai() for message in self.request().messages], provider.requests[-1]["messages"])
            self.assertEqual([False, True, True], [run["processReused"] for run in kernel.runs])
        finally:
            await kernel.aclose()
        self.assertIsNotNone(kernel.process.returncode)

    async def test_actual_harness_pi_arm_uses_same_golden_checks(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/agent_comparison_v1.json").read_text(encoding="utf-8"))
        fixture["cases"] = [fixture["cases"][0]]
        fixture["cases"][0]["turns"] = fixture["cases"][0]["turns"][:1]
        provider = MeasuredProvider(ScriptedProvider([{"content": "林澈保管怀表，付出记忆的代价。"}]),
                                    model="test-model", output_tokens=512, max_calls=4)
        report = await compare_agents(fixture, provider, runtimes=("pi",), pi_node=NODE)
        row = report["rows"][0]
        self.assertEqual("answered", row["status"], row)
        self.assertEqual("passed", row["checks"]["status"], row)
        self.assertTrue(row["usageComplete"])
        self.assertEqual(1, len(row["piRuns"]))

    async def test_pi_context_arm_continues_with_one_model_call_and_scoped_snapshot(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/agent_comparison_v1.json").read_text(encoding="utf-8"))
        fixture["cases"] = [fixture["cases"][1]]
        provider = MeasuredProvider(ScriptedProvider([
            {"tool_calls": [call(name="project_resolve")]},
            {"tool_calls": [call(name="project_retrieve", query="怀表")]},
            {"content": "怀表尚未归还[1]。"},
            {"content": "依据上轮资料[1]，提出归还怀表并保留记忆代价的方案。"},
        ]), model="test-model", output_tokens=512, max_calls=6)
        report = await compare_agents(fixture, provider, runtimes=("pi_context",), pi_node=NODE)
        self.assertEqual(2, len(report["rows"]))
        follow = report["rows"][1]
        self.assertEqual("answered", follow["status"], follow)
        self.assertEqual(1, follow["providerCallCount"])
        self.assertEqual(["project.resolve"], follow["backendCalls"])
        self.assertEqual(3, follow["continuationContext"]["historicalSources"])
        self.assertTrue(all(item["historicalReference"] for item in follow["sources"]))
