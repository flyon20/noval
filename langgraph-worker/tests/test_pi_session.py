from __future__ import annotations

from copy import deepcopy
import asyncio
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

from scripts.agent_comparison import MeasuredProvider, compare_agents
from app.services.evaluation.pi_kernel import BRIDGE, PiEvaluationKernel
from app.services.evaluation.pi_session import PiSessionEvaluationKernel
from app.services.harness.autonomous_context import AutonomousConversationContext
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.novel_research_agent import NovelResearchAgent
from app.services.skills.runtime_skill import RuntimeSkill
from test_pi_evaluation import ScriptedProvider
from test_autonomous_research import ResearchBackend
from test_autonomous_context import request, followup


NODE = os.getenv("NOVAL_PI_NODE")
INSTALLED = (BRIDGE.parent / "node_modules/@earendil-works/pi-coding-agent/package.json").exists()


class SessionProvider:
    def __init__(self, *, review=False, invalid_read=False):
        self.requests = []
        self.review = review
        self.invalid_read = invalid_read

    def resolve_provider_profile(self, *args, **kwargs):
        return None

    async def invoke(self, **kwargs):
        self.requests.append(deepcopy(kwargs))
        messages = kwargs["messages"]
        first = len(self.requests) == 1
        if "independent Chinese fiction" in messages[0]["content"]:
            assert not kwargs.get("tools")
            dossier = json.loads(messages[-1]["content"].split("\n", 1)[1])
            findings = []
            if "看守看见" in dossier["draft"]:
                entry = next(item for item in dossier["entries"] if "失明的钟楼看守" in item["text"])
                findings.append({"check": "world_rules", "kind": "conflict", "draftQuote": "看守看见",
                    "references": [{"entryId": entry["id"], "quote": "失明的钟楼看守"}],
                    "explanation": "失明者不能以视力观察动作。", "correction": "让看守通过声音察觉停顿。"})
            return self.reply(self.report(findings))
        if first:
            path = re.search(r"<location>([^<]*webnovel-opening-hook[^<]*)</location>", messages[0]["content"]).group(1)
            return self.tool("read", {"path": "C:/unavailable.txt" if self.invalid_read else path})
        if self.review and not any(m.get("name") == "review_draft" for m in messages):
            return self.tool("review_draft", {"draft": "林澈举起怀表，看守看见他忘了记忆。"})
        return self.reply("林澈摸着怀表，想不起旧日记忆；看守循声问他为何停住。")

    @staticmethod
    def report(findings=()):
        checks = {name: "ok" for name in ("requirements", "continuity", "world_rules", "causality", "evidence")}
        for finding in findings:
            checks[finding["check"]] = "issue"
        return json.dumps({"checks": checks, "findings": list(findings)}, ensure_ascii=False)

    @staticmethod
    def reply(content):
        return {"model_name": "test-model", "content": content, "token_used": 10,
                "usage": {"inputTokens": 7, "outputTokens": 3, "totalTokens": 10}}

    @classmethod
    def tool(cls, name, args):
        return {**cls.reply(""), "tool_calls": [{"id": name + "-1", "type": "function",
                    "function": {"name": name, "arguments": json.dumps(args)}}]}


@unittest.skipUnless(NODE and INSTALLED, "Install full Pi SDK and set NOVAL_PI_NODE")
class PiSessionTest(unittest.IsolatedAsyncioTestCase):
    async def evaluate(self, provider, mode="pi_full", *, tools=8):
        fixture = json.loads((Path(__file__).parent / "fixtures/agent_comparison_v1.json").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(prefix="pi-session-test-") as directory:
            measured = MeasuredProvider(provider, model="test-model", output_tokens=512, max_calls=12)
            report = await compare_agents(fixture, measured, case_limit=1, runtimes=(mode,), pi_node=NODE,
                                          pi_session_dir=Path(directory), tools_per_turn=tools)
            for row in report["rows"]:
                self.assertEqual("answered", row["status"], row)
                self.assertTrue(Path(row["piRuns"][0]["sessionFile"]).is_file())
            return report

    async def test_native_skill_and_tool_history_survive_next_user_turn(self):
        provider = SessionProvider()
        report = await self.evaluate(provider)
        self.assertEqual(3, len(provider.requests))
        first, second = report["rows"]
        self.assertGreater(first["piRuns"][0]["nativeSkillCount"], 0)
        self.assertIn("webnovel-opening-hook", first["selectedSkills"])
        self.assertTrue(second["piRuns"][0]["sessionReused"])
        self.assertEqual(1, second["piRuns"][0]["retainedToolResults"])
        self.assertTrue(any(m.get("name") == "read" for m in provider.requests[-1]["messages"]))
        self.assertIn("Historical evidence authorized", str(provider.requests[-1]["messages"]))
        self.assertEqual(1, sum("我要写都市奇幻" in m.get("content", "") for m in provider.requests[-1]["messages"]))
        self.assertFalse(any(t["function"]["name"] in {"bash", "write", "edit", "skill_lookup"} for t in provider.requests[0]["tools"]))

    async def test_independent_reviewer_gets_original_task_and_no_tools(self):
        provider = SessionProvider(review=True)
        report = await self.evaluate(provider, "pi_full_review")
        reviews = [r for r in provider.requests if "independent Chinese fiction" in r["messages"][0]["content"]]
        self.assertEqual(2, len(reviews))
        self.assertIn("不要查榜单", reviews[0]["messages"][-1]["content"])
        self.assertFalse(reviews[0].get("tools"))
        self.assertEqual(1, report["rows"][0]["piRuns"][0]["reviewInvocations"])
        self.assertEqual(4, report["rows"][0]["providerCallCount"])
        self.assertEqual("succeeded", next(r for r in report["rows"][0]["toolRuns"] if r["name"] == "review_draft")["status"])
        self.assertIn("review", report["rows"][0]["piRuns"][0]["providerPurposes"])
        self.assertEqual("grounded_fiction_v1", report["rows"][0]["piRuns"][0]["reviewContract"])
        self.assertEqual(1, report["rows"][0]["piRuns"][0]["reviewResults"][0]["findingCount"])
        dossier = json.loads(reviews[1]["messages"][-1]["content"].split("\n", 1)[1])
        self.assertIn("prior_proposal", [item["kind"] for item in dossier["entries"]])

    async def test_read_rejects_paths_outside_authorized_skill_catalog(self):
        provider = SessionProvider(invalid_read=True)
        await self.evaluate(provider)
        content = provider.requests[1]["messages"][-1]["content"]
        self.assertIn("Only exact authorized skill paths", content)

    def make_agent(self, replies, *, reviewer=False, memory=None):
        directory = tempfile.TemporaryDirectory(prefix="pi-session-boundary-")
        self.addCleanup(directory.cleanup)
        provider = ScriptedProvider(replies)
        backend = ResearchBackend()
        agent = NovelResearchAgent(knowledge_client=backend, provider_client=provider,
            runtime_mode="autonomous", autonomous_context=memory or AutonomousConversationContext())
        kernel = PiSessionEvaluationKernel(agent.agent_kernel, node=NODE, session_dir=Path(directory.name), reviewer=reviewer)
        agent.agent_kernel = agent.harness.agent_kernel = kernel
        self.addAsyncCleanup(agent.aclose)
        self.addAsyncCleanup(kernel.aclose)
        return agent, kernel, provider, backend

    async def test_scope_and_history_changes_drop_native_tool_messages(self):
        for change in ({"userId": 8}, {"projectId": 92}, {"workId": 912},
                       {"conversationId": "new"}, {"history": []}, {"contextSummary": "changed"}):
            with self.subTest(change=change):
                agent, kernel, provider, _ = self.make_agent([
                    SessionProvider.tool("project_resolve", {}),
                    SessionProvider.tool("project_retrieve", {"query": "怀表"}),
                    SessionProvider.reply("已核对[1]"), SessionProvider.reply("新范围需要重新核对。"),
                ])
                first = request()
                answer = await agent.run(first)
                result = await agent.run(followup(first, answer, **change))
                self.assertEqual("answered", result.status)
                self.assertFalse(kernel.runs[-1]["sessionReused"])
                self.assertFalse(any(m["role"] == "tool" for m in provider.requests[-1]["messages"]))
                self.assertNotIn(answer.sources[0].preview, str(provider.requests[-1]["messages"]))
                await kernel.aclose()

    async def test_expiry_or_reauthorization_failure_drops_native_sources(self):
        for failure in ("expiry", "authorization"):
            with self.subTest(failure=failure):
                now = [0.0]
                memory = AutonomousConversationContext(ttl_seconds=10, clock=lambda: now[0])
                agent, kernel, provider, backend = self.make_agent([
                    SessionProvider.tool("project_resolve", {}), SessionProvider.tool("project_retrieve", {"query": "怀表"}),
                    SessionProvider.reply("已核对[1]"), SessionProvider.reply("无法恢复历史资料。"),
                ], memory=memory)
                first = request()
                answer = await agent.run(first)
                if failure == "expiry":
                    now[0] = 10
                else:
                    backend.ambiguous = True
                result = await agent.run(followup(first, answer))
                self.assertFalse(result.sources)
                self.assertFalse(kernel.runs[-1]["sessionReused"])
                self.assertNotIn(answer.sources[0].preview, str(provider.requests[-1]["messages"]))
                await kernel.aclose()

    async def test_changed_skill_pin_drops_previous_native_method(self):
        agent, kernel, provider, _ = self.make_agent([])
        old = RuntimeSkill(skillId="test-method", version="1", intents=(), triggers=(), instructions="OLD_UNIQUE_METHOD")
        new = RuntimeSkill(skillId="test-method", version="2", intents=(), triggers=(), instructions="NEW_UNIQUE_METHOD")
        first = request(projectId=None, workId=None)
        with patch.object(agent.skill_registry, "load_all", return_value=(old,)):
            # Resolve the generated catalog path inside the deterministic Provider.
            async def invoke(**kwargs):
                provider.requests.append(deepcopy(kwargs))
                if len(provider.requests) == 1:
                    path = re.search(r"<location>([^<]+)</location>", kwargs["messages"][0]["content"]).group(1)
                    return SessionProvider.tool("read", {"path": path})
                return SessionProvider.reply("方案完成。")
            provider.invoke = invoke
            answer = await agent.run(first)
        with patch.object(agent.skill_registry, "load_all", return_value=(new,)):
            await agent.run(followup(first, answer))
        self.assertFalse(kernel.runs[-1]["sessionReused"])
        self.assertNotIn("OLD_UNIQUE_METHOD", str(provider.requests[-1]["messages"]))

    async def test_reviewer_budget_and_duplicate_call_are_enforced(self):
        for delegations in (0, 1):
            with self.subTest(delegations=delegations):
                replies = [SessionProvider.tool("review_draft", {"draft": "草稿"})]
                if delegations:
                    replies += [SessionProvider.reply(SessionProvider.report()), SessionProvider.tool("review_draft", {"draft": "第二稿"})]
                replies.append(SessionProvider.reply("完成。"))
                agent, kernel, provider, _ = self.make_agent(replies, reviewer=True)
                budget = RunBudget(mode="fast", max_total_tokens=10000, max_tool_calls=5, max_delegations=delegations)
                with run_budget_scope(budget):
                    result = await agent.run(request(projectId=None, workId=None))
                self.assertEqual("answered" if delegations else "insufficient_evidence", result.status)
                self.assertEqual(delegations, kernel.runs[-1]["reviewInvocations"])
                self.assertIn("review already used" if delegations else "Review budget exhausted",
                              provider.requests[-1]["messages"][-1]["content"])
                await kernel.aclose()

    async def test_required_review_runs_when_model_omits_tool(self):
        agent, kernel, provider, _ = self.make_agent([
            SessionProvider.reply("未经审稿的草稿。"), SessionProvider.reply(SessionProvider.report()),
            SessionProvider.reply("修订后的完整回答。"),
        ], reviewer=True)
        result = await agent.run(request(projectId=None, workId=None))
        self.assertEqual("answered", result.status)
        self.assertEqual("修订后的完整回答。", result.answer)
        self.assertIn("independent Chinese fiction", provider.requests[1]["messages"][0]["content"])
        self.assertFalse(provider.requests[1].get("tools"))
        self.assertIn("已核对引文出处", str(provider.requests[2]["messages"]))
        self.assertEqual(["answer", "review", "answer"], kernel.runs[-1]["providerPurposes"])
        self.assertFalse(kernel.runs[-1]["reviewRequiredButMissing"])

    async def test_citation_repair_does_not_delegate_a_second_review(self):
        agent, kernel, provider, _ = self.make_agent([
            SessionProvider.reply("错误引用[99]"), SessionProvider.reply(SessionProvider.report()),
            SessionProvider.reply("仍带有引用[99]"), SessionProvider.reply("已修正，不作查证声明。"),
        ], reviewer=True)
        result = await agent.run(request(projectId=None, workId=None, contextSummary="上轮草稿"))
        self.assertEqual("answered", result.status)
        self.assertEqual(4, len(provider.requests))
        self.assertEqual(1, sum(run["reviewInvocations"] for run in kernel.runs))
        self.assertFalse(kernel.runs[-1]["reviewRequiredButMissing"])

    async def test_reviewer_plain_reassurance_does_not_count_as_completed(self):
        agent, kernel, provider, _ = self.make_agent([
            SessionProvider.reply("未验证的草稿。"), SessionProvider.reply("没问题，直接交付。"),
        ], reviewer=True)
        result = await agent.run(request(projectId=None, workId=None))
        self.assertEqual("insufficient_evidence", result.status)
        self.assertEqual(2, len(provider.requests))
        self.assertTrue(kernel.runs[-1]["reviewRequiredButMissing"])
        outcome = kernel.runs[-1]["reviewResults"][0]
        self.assertEqual("invalid", outcome["status"])
        self.assertEqual("report_json", outcome["validationError"])
        self.assertEqual("invalid", next(run for run in result.resultJson["toolRuns"] if run["name"] == "review_draft")["status"])

    async def test_reviewer_summary_is_a_lead_and_cannot_be_quoted_as_canon(self):
        finding = {"check": "world_rules", "kind": "conflict", "draftQuote": "第二次回溯",
            "references": [{"entryId": "summary", "quote": "每天只能一次"}],
            "explanation": "超过摘要记录的次数。", "correction": "删除第二次行动。"}
        agent, kernel, provider, _ = self.make_agent([
            SessionProvider.reply("林澈第二次回溯。"), SessionProvider.reply(SessionProvider.report([finding])),
        ], reviewer=True)
        result = await agent.run(request(projectId=None, workId=None, contextSummary="每天只能一次"))
        dossier = json.loads(provider.requests[1]["messages"][-1]["content"].split("\n", 1)[1])
        self.assertEqual("summary", next(entry for entry in dossier["entries"] if entry["id"] == "summary")["kind"])
        self.assertEqual("insufficient_evidence", result.status)
        self.assertEqual("summary_as_evidence", kernel.runs[-1]["reviewResults"][0]["validationError"])

    async def test_comparison_cancellation_closes_idle_full_session(self):
        provider = MeasuredProvider(SessionProvider(), model="test-model", output_tokens=512, max_calls=8)
        fixture = json.loads((Path(__file__).parent / "fixtures/agent_comparison_v1.json").read_text(encoding="utf-8"))
        processes = []
        close = PiSessionEvaluationKernel.aclose

        async def tracked_close(kernel):
            if kernel.process is not None:
                processes.append(kernel.process)
            await close(kernel)

        def cancel_after_answer(row):
            raise asyncio.CancelledError()

        with tempfile.TemporaryDirectory(prefix="pi-session-cancel-") as directory:
            with patch.object(PiSessionEvaluationKernel, "aclose", tracked_close):
                with self.assertRaises(asyncio.CancelledError):
                    await compare_agents(fixture, provider, case_limit=1, runtimes=("pi_full",), pi_node=NODE,
                        pi_session_dir=Path(directory), on_result=cancel_after_answer)
        self.assertTrue(processes)
        self.assertTrue(all(process.returncode is not None for process in processes))

    async def test_native_compaction_uses_host_provider_and_records_usage(self):
        agent, kernel, provider, _ = self.make_agent([
            SessionProvider.tool("project_resolve", {}), SessionProvider.tool("project_retrieve", {"query": "怀表"}),
            SessionProvider.reply("已核对[1]"),
            SessionProvider.reply("历史摘要：怀表尚未归还；[1] 是历史资料。"),
            SessionProvider.reply("根据历史资料[1]继续设计。"),
        ])
        # Exceed Pi's 12k-token retention window so there is old history to compact.
        first = request(history=[message for index in range(4) for message in (
            {"role": "user", "content": str(index) + "早期情节。" * 5000},
            {"role": "assistant", "content": f"早期回应 {index}。"})])
        answer = await agent.run(first)
        original = PiEvaluationKernel.run

        async def force_compaction(instance, *args, **kwargs):
            instance.run_options["compactBeforePrompt"] = True
            return await original(instance, *args, **kwargs)

        with patch.object(PiEvaluationKernel, "run", force_compaction):
            try:
                result = await agent.run(followup(first, answer))
            except RuntimeError:
                self.fail(f"Compaction failed: {kernel.runs[-1]}, provider calls={len(provider.requests)}")
        self.assertEqual("answered", result.status)
        self.assertTrue(kernel.runs[-1]["sessionReused"])
        self.assertEqual(["compaction", "answer"], kernel.runs[-1]["providerPurposes"])
        self.assertEqual(1, kernel.runs[-1]["compactions"])
        self.assertFalse(provider.requests[-2].get("tools"))
        self.assertTrue(all(source.historicalReference for source in result.sources))
        entries = [json.loads(line) for line in Path(kernel.runs[-1]["sessionFile"]).read_text(encoding="utf-8").splitlines()]
        self.assertTrue(any(entry["type"] == "compaction" for entry in entries))

    async def test_cancellation_during_reviewer_stops_process(self):
        agent, kernel, provider, _ = self.make_agent([], reviewer=True)
        reviewing = asyncio.Event()
        purposes = []

        async def invoke(**kwargs):
            purposes.append("review" if "independent Chinese fiction" in kwargs["messages"][0]["content"] else "answer")
            if "independent Chinese fiction" in kwargs["messages"][0]["content"]:
                reviewing.set()
                await asyncio.Event().wait()
            return SessionProvider.tool("review_draft", {"draft": "检查此草稿"})

        provider.invoke = invoke
        task = asyncio.create_task(agent.run(request(projectId=None, workId=None)))
        try:
            try:
                await asyncio.wait_for(reviewing.wait(), timeout=30)
            except TimeoutError:
                self.fail(f"Reviewer did not start; purposes={purposes}, runs={kernel.runs}, taskDone={task.done()}")
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertIsNotNone(kernel.process.returncode)
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
