from __future__ import annotations

import asyncio
import json
import unittest
from unittest.mock import patch

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource, RankLookupResult
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.harness.cancellation import CancellationToken, RunCancelledError, cancellation_scope
from app.services.novel_research_agent import NovelResearchAgent
from app.services.skills.runtime_skill import RuntimeSkill


def reply(content: str = "", *, name: str | None = None, arguments: dict | None = None) -> dict:
    calls = [] if name is None else [{"id": "call-" + name, "type": "function", "function": {
        "name": name, "arguments": json.dumps(arguments or {}, ensure_ascii=False),
    }}]
    return {"content": content, "tool_calls": calls, "model_name": "test-model", "token_used": 12}


class ScriptedProvider:
    def __init__(self, replies: list[dict]) -> None:
        self.replies = list(replies)
        self.calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.calls.append(kwargs)
        if not self.replies:
            raise AssertionError("unexpected model call")
        return self.replies.pop(0)


class ResearchBackend:
    def __init__(self) -> None:
        self.searches: list[dict] = []
        self.project_calls: list[dict] = []
        self.foreign_output = False
        self.ambiguous = False
        self.fail_first_search = False

    async def get_agent_runtime_config(self):
        return {}

    async def search_evidence(self, **kwargs):
        self.searches.append(kwargs)
        if self.fail_first_search and len(self.searches) == 1:
            raise RuntimeError("synthetic upstream failure")
        return [KnowledgeSource(chunkId=51, title="钟楼约定", preview="主角答应在钟楼交还怀表。", sourceType="chapter")]

    async def resolve_project_work(self, **kwargs):
        self.project_calls.append(kwargs)
        if self.ambiguous:
            return {"status": "ambiguous", "candidates": [{"userId": 7, "projectId": 91, "workId": 911, "title": "初稿"},
                                                           {"userId": 7, "projectId": 91, "workId": 912, "title": "修订稿"}]}
        return {"status": "resolved", "userId": kwargs["user_id"], "projectId": 91, "workId": 911}

    async def retrieve_project_knowledge(self, **kwargs):
        self.project_calls.append(kwargs)
        return {"evidence": [{"userId": 8 if self.foreign_output else 7, "projectId": 91, "workId": 911,
                              "documentId": 71, "chapterId": 3, "chapterNo": 3, "generationId": 1,
                              "chapterVersion": 1, "contentHash": "synthetic", "sourceType": "CHAPTER",
                              "title": "钟楼约定", "preview": "FOREIGN_SECRET" if self.foreign_output else "第三章尚未兑现交还怀表的约定。"}]}


class RankBackend(ResearchBackend):
    def __init__(self):
        super().__init__()
        self.rank_calls: list[dict] = []

    async def lookup_rank(self, **kwargs):
        self.rank_calls.append(kwargs)
        snapshots = [(20, "2026-10-08T08:00:00"), (10, "2026-10-01T08:00:00")]
        if not kwargs.get("allow_historical"):
            snapshots = snapshots[:1]
        return [RankLookupResult(
            rankId=snapshot_id * 100 + rank, snapshotId=snapshot_id, snapshotTime=timestamp,
            platform="fanqie", channelCode="male-new", boardCode="262", category="都市脑洞",
            rankNo=rank, bookId=1000 + rank, bookName=f"作品{rank}", intro="测试榜单资料。",
            historicalReference=bool(kwargs.get("allow_historical")),
        ) for snapshot_id, timestamp in snapshots for rank in range(1, 31)][:kwargs["limit"]]


class AutonomousResearchTest(unittest.IsolatedAsyncioTestCase):
    def agent(self, replies: list[dict], backend: ResearchBackend | None = None):
        provider = ScriptedProvider(replies)
        backend = backend or ResearchBackend()
        agent = NovelResearchAgent(knowledge_client=backend, provider_client=provider, runtime_mode="autonomous")
        return agent, provider, backend

    def request(self, **kwargs):
        return KnowledgeChatRequest(question="检查钟楼伏笔并给出下一章建议", userId=7, limits={"modelName": "test-model"}, **kwargs)

    async def test_model_loads_skill_then_refines_retrieval_before_answering(self):
        skill = RuntimeSkill(skillId="test-revision", version="1", intents=(), triggers=("修订",),
                             instructions="UNIQUE_SKILL_BODY: preserve the original promise.", description="Revise a story")
        agent, provider, backend = self.agent([
            reply(name="skill_lookup", arguments={"skillId": skill.skillId}),
            reply(name="knowledge_vector_search", arguments={"query": "钟楼"}),
            reply(name="knowledge_vector_search", arguments={"query": "怀表约定的兑现"}),
            reply("主角承诺归还怀表[1]。下一章让他为兑现承诺付出代价。"),
        ])
        with patch.object(agent.skill_registry, "load_all", return_value=(skill,)):
            response = await agent.run(self.request())
        self.assertEqual("answered", response.status)
        self.assertEqual(["钟楼", "怀表约定的兑现"], [call["query"] for call in backend.searches])
        self.assertEqual([7, 7], [call["user_id"] for call in backend.searches])
        self.assertNotIn("UNIQUE_SKILL_BODY", json.dumps(provider.calls[0]["messages"]))
        self.assertIn("UNIQUE_SKILL_BODY", json.dumps(provider.calls[1]["messages"]))
        self.assertEqual([skill.skillId], response.resultJson["selectedSkills"])
        self.assertEqual(4, response.resultJson["kernelTurns"])
        self.assertEqual(1, len(response.sources))
        self.assertEqual(["autonomous_research", "autonomous_finalize"], response.resultJson["trace"]["executedRuntimeNodes"])

    async def test_followup_keeps_history_and_user_constraints(self):
        agent, provider, _ = self.agent([reply("保留无系统设定，把代价改为记忆损耗。")])
        request = self.request(history=[{"role": "user", "content": "主角没有系统，能力消耗记忆"},
                                        {"role": "assistant", "content": "提出了三种代价"}], contextSummary="不要重写人物动机")
        request.question = "第二种继续展开，别改我的设定"
        response = await agent.run(request)
        prompt = json.dumps(provider.calls[0]["messages"], ensure_ascii=False)
        for text in ("主角没有系统", "不要重写人物动机", "第二种继续展开"):
            self.assertIn(text, prompt)
        self.assertEqual("answered", response.status)

    async def test_rank_history_parameters_reach_backend_without_latest_override(self):
        for parameters in (
            {"snapshotStartDate": "2026-10-01", "snapshotEndDate": "2026-10-08"},
            {"timeWindowDays": 30},
        ):
            with self.subTest(parameters=parameters):
                backend = RankBackend()
                agent, provider, _ = self.agent([
                    reply(name="rank_lookup", arguments={"category": "都市脑洞", **parameters}),
                    reply("已取得所请求范围的榜单资料[1]。"),
                ], backend)
                response = await agent.run(self.request())
                self.assertEqual("answered", response.status)
                self.assertEqual(1, len(backend.rank_calls))
                call = backend.rank_calls[0]
                self.assertEqual("time_window", call["freshness"])
                self.assertTrue(call["allow_historical"])
                self.assertTrue(call["require_snapshot_time"])
                self.assertEqual(parameters.get("snapshotStartDate"), call["snapshot_start_date"])
                self.assertEqual(parameters.get("snapshotEndDate"), call["snapshot_end_date"])
                self.assertEqual(parameters.get("timeWindowDays"), call["time_window_days"])
                self.assertIn('"currentDate"', provider.calls[0]["messages"][-1]["content"])

    async def test_current_rank_default_keeps_latest_policy(self):
        backend = RankBackend()
        agent, _, _ = self.agent([reply(name="rank_lookup"), reply("本次返回的榜首是作品1[1]。")], backend)
        await agent.run(self.request())
        self.assertEqual(1, len(backend.rank_calls))
        self.assertEqual("latest", backend.rank_calls[0]["freshness"])
        self.assertFalse(backend.rank_calls[0]["allow_historical"])

    async def test_invalid_rank_periods_are_rejected_before_backend_call(self):
        for parameters in (
            {"snapshotStartDate": "2026-10-01"},
            {"snapshotStartDate": "2026-10-08", "snapshotEndDate": "2026-10-01"},
            {"snapshotStartDate": "2026-02-30", "snapshotEndDate": "2026-03-01"},
            {"snapshotStartDate": "2024-01-01", "snapshotEndDate": "2026-10-01"},
            {"snapshotStartDate": "2026-10-01", "snapshotEndDate": "2026-10-08", "timeWindowDays": 30},
            {"timeWindowDays": 0},
            {"limit": 61},
        ):
            with self.subTest(parameters=parameters):
                backend = RankBackend()
                agent, provider, _ = self.agent([
                    reply(name="rank_lookup", arguments=parameters), reply("需要修正查询范围。"),
                ], backend)
                await agent.run(self.request())
                self.assertEqual([], backend.rank_calls)
                self.assertIn("InvalidToolArguments", json.dumps(provider.calls[1]["messages"]))

    async def test_rank_comparison_keeps_both_top30_snapshots_and_identifiable_citations(self):
        backend = RankBackend()
        agent, provider, _ = self.agent([
            reply(name="rank_lookup", arguments={"snapshotStartDate": "2026-10-01", "snapshotEndDate": "2026-10-08", "limit": 60}),
            reply("两期榜首都是作品1[1][31]。"),
        ], backend)
        response = await agent.run(self.request())
        self.assertEqual("answered", response.status)
        self.assertEqual(60, len(response.sources))
        self.assertEqual({10, 20}, {source.snapshotId for source in response.sources})
        tool_message = next(message["content"] for message in provider.calls[1]["messages"] if message["role"] == "tool")
        envelope = json.loads(tool_message.split(": ", 1)[1])
        observation = envelope["content"]["toolObservation"]
        self.assertFalse(envelope["truncated"])
        self.assertFalse(observation["truncated"])
        self.assertEqual(60, len(observation["citations"]))
        earlier = next(row for row in observation["citations"] if row["citation"] == "[31]")
        self.assertEqual(("2026-10-01T08:00:00", 1001, 1),
                         (earlier["snapshotTime"], earlier["bookId"], earlier["rankNo"]))

    async def test_scope_arguments_are_rejected_before_backend_call(self):
        agent, provider, backend = self.agent([
            reply(name="knowledge_vector_search", arguments={"query": "材料", "userId": 99}),
            reply("只能查询当前用户可访问的资料。"),
        ])
        response = await agent.run(self.request())
        self.assertFalse(backend.searches)
        self.assertIn("InvalidToolArguments", json.dumps(provider.calls[1]["messages"]))
        self.assertEqual("failed", response.resultJson["toolRuns"][0]["status"])

    async def test_project_requires_resolution_and_binds_all_scope_fields(self):
        agent, provider, backend = self.agent([
            reply(name="project_retrieve", arguments={"query": "伏笔"}),
            reply(name="project_resolve", arguments={}),
            reply(name="project_retrieve", arguments={"query": "怀表约定"}),
            reply("第三章的怀表约定尚未兑现[1]。"),
        ])
        response = await agent.run(self.request(projectId=91, workId=911))
        self.assertIn("ProjectScopeUnresolved", json.dumps(provider.calls[1]["messages"]))
        self.assertEqual(2, len(backend.project_calls))
        self.assertEqual((7, 91, 911), tuple(backend.project_calls[-1][key] for key in ("user_id", "project_id", "work_id")))
        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(response.sources))

    async def test_foreign_output_never_reaches_model_or_response(self):
        backend = ResearchBackend()
        backend.foreign_output = True
        agent, provider, _ = self.agent([
            reply(name="project_resolve"), reply(name="project_retrieve", arguments={"query": "伏笔"}),
            reply("资料暂不可用，无法确定伏笔状态。"),
        ], backend)
        response = await agent.run(self.request(projectId=91, workId=911))
        self.assertNotIn("FOREIGN_SECRET", json.dumps(provider.calls))
        self.assertNotIn("FOREIGN_SECRET", response.model_dump_json())
        self.assertFalse(response.sources)
        self.assertEqual("failed", response.resultJson["toolRuns"][-1]["status"])

    async def test_ambiguous_project_cannot_be_read(self):
        backend = ResearchBackend()
        backend.ambiguous = True
        agent, _, _ = self.agent([
            reply(name="project_resolve", arguments={"query": "旧稿"}),
            reply(name="project_retrieve", arguments={"query": "伏笔"}),
            reply("你指初稿还是修订稿？"),
        ], backend)
        response = await agent.run(self.request(projectId=91))
        self.assertEqual(1, len(backend.project_calls))
        self.assertIsNone(response.resultJson["resolvedProject"])

    async def test_failure_observation_allows_corrected_search(self):
        backend = ResearchBackend()
        backend.fail_first_search = True
        agent, provider, _ = self.agent([
            reply(name="knowledge_vector_search", arguments={"query": "钟楼"}),
            reply(name="knowledge_vector_search", arguments={"query": "归还怀表的承诺"}),
            reply("查到归还怀表的承诺[1]。"),
        ], backend)
        response = await agent.run(self.request())
        self.assertEqual(2, len(backend.searches))
        self.assertIn("ToolFailed", json.dumps(provider.calls[1]["messages"]))
        self.assertEqual("answered", response.status)

    async def test_budget_exhaustion_removes_tools_before_final_answer(self):
        agent, provider, backend = self.agent([
            reply(name="knowledge_vector_search", arguments={"query": "钟楼"}),
            reply("仅检索到一段材料，无法声称完整检查；承诺尚待兑现[1]。"),
        ])
        budget = RunBudget(mode="fast", max_total_tokens=100_000, max_tool_calls=1, max_delegations=0)
        with run_budget_scope(budget):
            response = await agent.run(self.request())
        self.assertEqual(1, len(backend.searches))
        self.assertFalse(provider.calls[-1].get("tools"))
        self.assertEqual("answered", response.status)
        self.assertEqual(1, budget.used_tool_calls)

    async def test_ignored_stop_budget_is_not_reported_as_success(self):
        agent, _, backend = self.agent([reply("还要继续", name="knowledge_vector_search", arguments={"query": "钟楼"})] * 3)
        with run_budget_scope(RunBudget(mode="fast", max_total_tokens=100_000, max_tool_calls=1, max_delegations=0)):
            response = await agent.run(self.request())
        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("max_turns", response.resultJson["kernelStopReason"])
        self.assertEqual(1, len(backend.searches))

    async def test_unknown_or_write_tool_is_never_executed(self):
        agent, _, backend = self.agent([reply("准备保存", name="project_save", arguments={"content": "稿件"})])
        response = await agent.run(self.request())
        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("unauthorized_tool", response.resultJson["kernelStopReason"])
        self.assertFalse(backend.project_calls)

    async def test_forged_citation_fails_without_rewriting_the_answer(self):
        agent, _, _ = self.agent([reply("第三章已经兑现承诺[99]。")])
        response = await agent.run(self.request())
        self.assertEqual("insufficient_evidence", response.status)
        self.assertFalse(response.resultJson["citationValidation"]["valid"])
        self.assertEqual("第三章已经兑现承诺[99]。", response.answer)

    async def test_followup_revalidates_old_citations_in_the_same_bounded_run(self):
        agent, provider, backend = self.agent([
            reply("怀表还未交还[1]。"),
            reply(name="project_resolve"),
            reply(name="project_retrieve", arguments={"query": "怀表归还"}),
            reply("第三章的约定尚未兑现[1]。第六章可以安排交还。"),
        ])
        response = await agent.run(self.request(projectId=91, workId=911,
            history=[{"role": "assistant", "content": "怀表还未交还[1]。"}]))
        self.assertEqual("answered", response.status)
        self.assertEqual(4, response.resultJson["kernelTurns"])
        self.assertEqual(2, len(backend.project_calls))
        self.assertEqual(1, len(response.sources))
        self.assertIn("引用校验未通过", json.dumps(provider.calls[1]["messages"], ensure_ascii=False))

    async def test_stream_has_live_tool_progress_and_same_terminal_answer(self):
        agent, _, _ = self.agent([reply(name="knowledge_vector_search", arguments={"query": "钟楼"}), reply("怀表约定[1]。")])
        events = [event async for event in agent.stream(self.request())]
        self.assertTrue(any(event.get("event") == "progress" for event in events))
        self.assertEqual("done", events[-1]["event"])
        self.assertEqual("怀表约定[1]。", events[-1]["data"]["answer"])

    async def test_cancellation_stops_before_model_dispatch(self):
        agent, provider, _ = self.agent([reply("should not happen")])
        token = CancellationToken()
        token.cancel()
        with cancellation_scope(token), self.assertRaises((RunCancelledError, asyncio.CancelledError)):
            await agent.run(self.request())
        self.assertFalse(provider.calls)

    async def test_runtime_is_opt_in_and_checkpoint_identity_is_separate(self):
        agent, _, backend = self.agent([reply("ok")])
        control = NovelResearchAgent(knowledge_client=backend, provider_client=ScriptedProvider([]))
        request = self.request(traceId="same-trace")
        self.assertEqual("workflow", control.runtime_mode)
        self.assertIn("classify_intent", control._graph.nodes)
        self.assertNotIn("classify_intent", agent._graph.nodes)
        self.assertNotEqual(control._graph_config(request), agent._graph_config(request))
        request.resumeFromCheckpoint = True
        with self.assertRaisesRegex(ValueError, "does not support checkpoint resume"):
            await agent.run(request)
