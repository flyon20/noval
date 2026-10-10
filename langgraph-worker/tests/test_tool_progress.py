import asyncio
from contextlib import aclosing
from copy import deepcopy
import unittest
from unittest.mock import AsyncMock

from langgraph.graph import END, StateGraph

from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.harness.cancellation import CancellationToken
from app.services.harness.tool_ledger import RunToolLedger, run_tool_ledger_scope
from app.services.harness.tool_progress import MAX_TOOL_PROGRESS_ITEMS, tool_progress_scope
from app.services.novel_research_agent import NovelResearchAgent
from test_novel_research_agent import FakeKnowledgeClient, FakeAnswerProvider


def ledger(**kwargs):
    return RunToolLedger(run_id="run-progress", user_id=7, project_id=91, route="answer", **kwargs)


class ToolProgressTest(unittest.IsolatedAsyncioTestCase):
    async def test_prepared_start_commit_end_and_join_reuse_share_one_item(self) -> None:
        release, entered = asyncio.Event(), asyncio.Event()
        progress, checkpoints = [], []

        async def writer(event_type, _key, _payload):
            checkpoints.append(event_type)

        def observe(payload):
            self.assertEqual("TOOL_PREPARED" if payload["itemStatus"] == "running" else "TOOL_COMMITTED",
                             checkpoints[-1])
            progress.append(payload)

        async def operation():
            entered.set()
            await release.wait()
            return {"privateOutput": "must stay inside the tool observation"}

        active = ledger(checkpoint_writer=writer)
        with tool_progress_scope(observe):
            first = asyncio.create_task(active.execute("knowledge.search", {"query": "private input"}, operation))
            await asyncio.wait_for(entered.wait(), 1)
            self.assertEqual(["running"], [item["itemStatus"] for item in progress])
            joined = asyncio.create_task(active.execute("knowledge.search", {"query": "private input"}, operation))
            await asyncio.sleep(0)
            release.set()
            first_run, joined_run = await asyncio.gather(first, joined)
            reused = await active.execute("knowledge.search", {"query": "private input"}, operation)
        self.assertTrue(joined_run.joined)
        self.assertTrue(reused.reused)
        self.assertTrue(first_run.executed)
        self.assertEqual(["running", "completed"], [item["itemStatus"] for item in progress])
        self.assertEqual(progress[0]["itemId"], progress[1]["itemId"])
        self.assertNotIn("private", str(progress))
        self.assertEqual({}, active._state.pending_tokens)

    async def test_failed_read_retry_gets_a_new_item_and_interruption_recovers_latest_attempt(self) -> None:
        persisted = {}
        interrupt_commit = False
        progress = []

        async def writer(event_type, key, payload):
            if interrupt_commit and event_type == "TOOL_COMMITTED":
                raise RuntimeError("fixture commit interruption")
            persisted.setdefault(key, {"sequenceNo": len(persisted) + 1,
                                       "eventType": event_type, "payload": deepcopy(payload)})

        async def failed():
            raise ValueError("fixture read failure")

        async def success():
            return {"items": [1]}

        active = ledger(checkpoint_writer=writer)
        with tool_progress_scope(progress.append):
            self.assertEqual("failed", (await active.execute("knowledge.search", {}, failed)).status)
            interrupt_commit = True
            with self.assertRaisesRegex(RuntimeError, "commit interruption"):
                await active.execute("knowledge.search", {}, success)
        self.assertEqual(["running", "failed", "running", "unknown"],
                         [item["itemStatus"] for item in progress])
        self.assertNotEqual(progress[0]["itemId"], progress[2]["itemId"])
        restored = ledger()
        unknowns = restored.merge_semantic_events(list(persisted.values()))
        self.assertEqual(1, len(unknowns))
        self.assertEqual(progress[-1], restored.recovered_progress_events()[-1])
        result = await restored.execute("knowledge.search", {}, failed)
        self.assertEqual("unknown", result.status)
        self.assertFalse(result.executed)

    async def test_cancelling_last_waiter_awaits_cleanup_and_emits_cancelled(self) -> None:
        entered, cleaned = asyncio.Event(), asyncio.Event()
        token = CancellationToken()
        progress = []

        async def operation():
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned.set()

        active = ledger(cancellation_token=token)
        with tool_progress_scope(progress.append):
            task = asyncio.create_task(active.execute("knowledge.search", {}, operation))
            await asyncio.wait_for(entered.wait(), 1)
            token.cancel("fixture cancellation")
            result = await asyncio.wait_for(task, 1)
        self.assertEqual("cancelled", result.status)
        self.assertTrue(cleaned.is_set())
        self.assertEqual("cancelled", progress[-1]["itemStatus"])
        self.assertEqual({}, active._state.pending_tokens)

    async def test_progress_is_bounded_and_observer_failure_cannot_retry_operation(self) -> None:
        progress = []
        active = ledger()
        with tool_progress_scope(progress.append):
            for index in range(MAX_TOOL_PROGRESS_ITEMS + 1):
                await active.execute("knowledge.search", {"index": index}, lambda: {})
        self.assertEqual(MAX_TOOL_PROGRESS_ITEMS * 2, len(progress))
        self.assertEqual(MAX_TOOL_PROGRESS_ITEMS + 1, len(active.runs))

        def broken(_payload):
            raise ValueError("fixture display failure")

        with tool_progress_scope(broken):
            result = await ledger().execute("knowledge.search", {}, lambda: {})
        self.assertEqual("succeeded", result.status)

    async def test_real_graph_custom_progress_precedes_node_completion_and_close_cleans_up(self) -> None:
        entered, cleaned = asyncio.Event(), asyncio.Event()
        request = KnowledgeChatRequest(question="fixture")
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        agent._initial_state = AsyncMock(return_value={"request": request})

        async def operation():
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned.set()

        active = ledger()

        async def node(_state):
            await active.execute("knowledge.search", {}, operation)
            return {"response": KnowledgeChatResponse(answer="verified", resultJson={})}

        graph = StateGraph(dict)
        graph.add_node("execute_tools", agent._budgeted_node(node, "execute_tools"))
        graph.set_entry_point("execute_tools")
        graph.add_edge("execute_tools", END)
        agent._graph = graph.compile()
        with run_tool_ledger_scope(active):
            async with aclosing(agent._stream_from_compiled_graph(
                request, config={}, checkpoint=None,
            )) as stream:
                self.assertEqual("start", (await anext(stream))["event"])
                progress = await asyncio.wait_for(anext(stream), 1)
                self.assertEqual("running", progress["itemStatus"])
                await asyncio.wait_for(entered.wait(), 1)
                self.assertFalse(cleaned.is_set())
        self.assertTrue(cleaned.is_set())
        self.assertEqual({}, active._state.pending_tokens)
