from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

from app.models.knowledge import KnowledgeChatRequest
from app.services.evaluation.dsh_session import DSH_VERSION, DshSessionEvaluationKernel
from app.services.harness.autonomous_context import AutonomousConversationContext
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.novel_research_agent import NovelResearchAgent
from app.services.skills.runtime_skill import RuntimeSkill
from test_autonomous_research import RankBackend, ResearchBackend, ScriptedProvider, reply


NODE = os.getenv("NOVAL_DSH_NODE") or os.getenv("NOVAL_PI_NODE")
PACKAGE = Path(__file__).resolve().parents[1] / "scripts/dsh-eval/node_modules/@deepseek-ai/dsh-agent-loop/package.json"


@unittest.skipUnless(NODE and PACKAGE.is_file(), "Requires the pinned DSH packages and NOVAL_DSH_NODE or NOVAL_PI_NODE")
class DshSessionTest(unittest.IsolatedAsyncioTestCase):
    def setup_agent(self, directory, provider, backend=None):
        agent = NovelResearchAgent(knowledge_client=backend or ResearchBackend(), provider_client=provider,
                                   runtime_mode="autonomous", autonomous_context=AutonomousConversationContext())
        kernel = DshSessionEvaluationKernel(agent.agent_kernel, node=NODE, session_dir=Path(directory))
        agent.agent_kernel = kernel
        agent.harness.agent_kernel = kernel
        self.addAsyncCleanup(kernel.aclose)
        self.addAsyncCleanup(agent.aclose)
        return agent, kernel

    @staticmethod
    def request(question="比较同一榜单两期的排名变化", **kwargs):
        return KnowledgeChatRequest(question=question, userId=7, conversationId="dsh-comparison",
                                    limits={"modelName": "test-model"}, **kwargs)

    async def test_native_dsh_runs_historical_tool_and_persists_its_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = RankBackend()
            provider = ScriptedProvider([
                reply(name="rank_lookup", arguments={"snapshotStartDate": "2026-10-01", "snapshotEndDate": "2026-10-08", "limit": 60}),
                reply("两期榜首都是作品1[1][31]。"),
            ])
            agent, kernel = self.setup_agent(directory, provider, backend)
            with run_budget_scope(RunBudget(mode="fast", max_total_tokens=80_000, max_tool_calls=2, max_delegations=0)):
                response = await agent.run(self.request())
            self.assertEqual("answered", response.status, kernel.runs)
            self.assertEqual(1, len(backend.rank_calls))
            self.assertTrue(backend.rank_calls[0]["allow_historical"])
            self.assertEqual(60, len(response.sources))
            run = kernel.runs[-1]
            self.assertEqual(DSH_VERSION, run["version"])
            self.assertEqual("@deepseek-ai/dsh-agent-loop", run["sdk"])
            transcript = Path(run["sessionFile"]).read_text(encoding="utf-8")
            self.assertIn("2026-10-01T08:00:00", transcript)
            self.assertIn("rank_lookup", transcript)
            self.assertIn("tool/result", transcript)
            await kernel.aclose()

    async def test_native_skill_loading_and_authorized_history_reuse(self):
        skill = RuntimeSkill(skillId="test-ranking-method", version="1", intents=(), triggers=("榜单",),
                             description="Compare ranking snapshots", instructions="UNIQUE_DSH_METHOD: compare the same board at both dates.")

        class SkillProvider(ScriptedProvider):
            async def invoke(self, **kwargs):
                self.calls.append(kwargs)
                if len(self.calls) == 1:
                    prompt = "\n".join(message.get("content", "") for message in kwargs["messages"])
                    match = re.search(r'"path":"([^"\n]+SKILL\.md)"', prompt)
                    if match is None:
                        raise AssertionError("Native DSH skill catalog was not supplied")
                    return reply(name="read", arguments={"path": json.loads('"' + match.group(1) + '"')})
                return reply("会按同一榜单、同一排名范围比较日期。")

        with tempfile.TemporaryDirectory() as directory:
            provider = SkillProvider([])
            agent, kernel = self.setup_agent(directory, provider)
            first = self.request("记住本次按同一榜单、同一范围比较的方法")
            with patch.object(agent.skill_registry, "load_all", return_value=(skill,)):
                response = await agent.run(first)
                second = self.request("继续按这个方法", history=[{"role": "user", "content": first.question},
                                      {"role": "assistant", "content": response.answer}])
                try:
                    await agent.run(second)
                except RuntimeError:
                    diagnostic = Path(directory) / "bridge-failure.json"
                    detail = json.loads(diagnostic.read_text(encoding="utf-8")) if diagnostic.is_file() else {}
                    self.fail(str({"runs": kernel.runs, "bridgeFailure": {key: detail.get(key) for key in ("stage", "errorType", "message")}}))
                third = self.request("新的独立问题", history=[])
                await agent.run(third)
            self.assertEqual([False, True, False], [run["sessionReused"] for run in kernel.runs])
            self.assertEqual(1, kernel.runs[0]["nativeSkillCount"])
            self.assertEqual([skill.skillId], kernel.runs[0]["loadedSkills"])
            self.assertIn("UNIQUE_DSH_METHOD", json.dumps(provider.calls[1]["messages"]))
            self.assertNotEqual(kernel.runs[0]["sessionFile"], kernel.runs[2]["sessionFile"])
            await kernel.aclose()

    async def test_native_dsh_cannot_execute_an_ungranted_tool(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = RankBackend()
            agent, kernel = self.setup_agent(directory, ScriptedProvider([
                reply(name="rank_refresh", arguments={"force": True}),
            ]), backend)
            response = await agent.run(self.request())
            self.assertEqual("insufficient_evidence", response.status)
            self.assertEqual("unauthorized_tool", response.resultJson["kernelStopReason"])
            self.assertEqual([], backend.rank_calls)
            await kernel.aclose()

    async def test_cancellation_closes_the_native_dsh_process(self):
        started = asyncio.Event()

        class WaitingProvider(ScriptedProvider):
            async def invoke(self, **kwargs):
                self.calls.append(kwargs)
                started.set()
                await asyncio.Event().wait()

        with tempfile.TemporaryDirectory() as directory:
            provider = WaitingProvider([])
            agent, kernel = self.setup_agent(directory, provider)
            task = asyncio.create_task(agent.run(self.request()))
            try:
                await asyncio.wait_for(started.wait(), timeout=20)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                self.assertEqual(1, len(provider.calls))
                self.assertIsNotNone(kernel.process.returncode)
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                await kernel.aclose()
