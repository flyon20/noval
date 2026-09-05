from __future__ import annotations

import asyncio
import unittest

from app.config import settings
from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.evaluation.faithfulness import RuleBasedFaithfulnessEvaluator
from app.services.evaluation.golden import GoldenEvalCase
from app.services.evaluation.runner import GoldenEvalRunner
from app.services.harness.admission import get_run_semaphore, run_slot
from app.services.harness.budget import RunBudget
from app.services.harness.context_compaction import ContextCompactor
from app.services.harness.tool_ledger import RunToolLedger
from app.services.harness.webnovel_harness import WebnovelHarness
from app.models.agent_task import RunToolIdentity
from app.services.novel_research_agent import NovelResearchAgent, ResearchState


class AdmissionProbeAgent(NovelResearchAgent):
    def __init__(self) -> None:
        self.run_entries = 0
        self.stream_entries = 0
        self.run_entered = asyncio.Event()
        self.release = asyncio.Event()
        self.knowledge_client = object()
        # Harness owns run/stream admission; probes only override runtime hooks.
        self.harness = WebnovelHarness(self)
        self.harness.context_compactor = ContextCompactor(enabled=False)

    def _graph_config(self, request: KnowledgeChatRequest) -> dict:
        return {"configurable": {"thread_id": request.traceId or "admission-probe"}}

    def _reasoning_mode(self, request: KnowledgeChatRequest) -> str:
        return str(request.reasoningMode or "fast").strip().lower() or "fast"

    def _run_admission_identity(self, request: KnowledgeChatRequest) -> str:
        return str(request.traceId or request.conversationId or "admission-probe")

    def _budget_for_checkpoint(self, request, checkpoint):
        return RunBudget.for_mode(self._reasoning_mode(request))

    def _run_tool_ledger(self, request, checkpoint):
        return RunToolLedger(
            RunToolIdentity(
                runId=self._run_admission_identity(request),
                userId=str(request.userId if request.userId is not None else "0"),
                projectId=str(request.projectId) if request.projectId is not None else None,
                route="agent_run",
            )
        )

    async def _prepare_checkpoint(self, request: KnowledgeChatRequest, config: dict):
        return await self._resume_checkpoint(request, config)

    async def _resume_checkpoint(self, request: KnowledgeChatRequest, config: dict):
        return None

    async def _run_scoped(
        self,
        request: KnowledgeChatRequest,
        *,
        config: dict,
        checkpoint: tuple[bool, ResearchState] | None,
        governance=None,
    ) -> KnowledgeChatResponse:
        self.run_entries += 1
        self.run_entered.set()
        await self.release.wait()
        return KnowledgeChatResponse(status="answered", answer="done")

    async def _stream_from_compiled_graph(
        self,
        request: KnowledgeChatRequest,
        *,
        config: dict,
        checkpoint: tuple[bool, ResearchState] | None,
        governance=None,
    ):
        self.stream_entries += 1
        yield {"event": "start"}
        await self.release.wait()
        yield {"event": "done"}


class CheckpointAdmissionProbeAgent(AdmissionProbeAgent):
    def __init__(self) -> None:
        super().__init__()
        self.checkpoint_entries = 0
        self.checkpoint_entered = asyncio.Event()
        self.checkpoint_release = asyncio.Event()

    async def _resume_checkpoint(self, request: KnowledgeChatRequest, config: dict):
        self.checkpoint_entries += 1
        self.checkpoint_entered.set()
        await self.checkpoint_release.wait()
        return None


class CleaningStreamAdmissionProbeAgent(AdmissionProbeAgent):
    def __init__(self) -> None:
        super().__init__()
        self.background_started = asyncio.Event()
        self.cleanup_started = asyncio.Event()
        self.cleanup_release = asyncio.Event()
        self.cleanup_finished = asyncio.Event()

    async def _background_step(self) -> str:
        self.background_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            self.cleanup_started.set()
            await self.cleanup_release.wait()
            self.cleanup_finished.set()

    async def _stream_from_compiled_graph(
        self,
        request: KnowledgeChatRequest,
        *,
        config: dict,
        checkpoint: tuple[bool, ResearchState] | None,
        governance=None,
    ):
        yield self._progress_event("generate", "working")
        await self._background_step()


class FailingStreamAdmissionProbeAgent(AdmissionProbeAgent):
    async def _stream_from_compiled_graph(
        self,
        request: KnowledgeChatRequest,
        *,
        config: dict,
        checkpoint: tuple[bool, ResearchState] | None,
        governance=None,
    ):
        yield self._progress_event("generate", "working")
        await asyncio.sleep(0)
        raise RuntimeError("background failed")


class AgentRunAdmissionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.original_fast_runs = settings.max_active_fast_runs
        settings.max_active_fast_runs = 1
        get_run_semaphore("fast")

    async def asyncTearDown(self) -> None:
        settings.max_active_fast_runs = self.original_fast_runs
        get_run_semaphore("fast")

    async def test_blocking_runs_hold_admission_for_the_full_run(self) -> None:
        agent = AdmissionProbeAgent()
        first = asyncio.create_task(agent.run(self._request("run-1")))
        await asyncio.wait_for(agent.run_entered.wait(), timeout=0.5)

        second = asyncio.create_task(agent.run(self._request("run-2")))
        await asyncio.sleep(0.05)
        self.assertEqual(1, agent.run_entries)

        agent.release.set()
        await asyncio.gather(first, second)
        self.assertEqual(2, agent.run_entries)

    async def test_checkpoint_read_is_covered_by_run_admission(self) -> None:
        agent = CheckpointAdmissionProbeAgent()
        first = asyncio.create_task(agent.run(self._request("checkpoint-1")))
        await asyncio.wait_for(agent.checkpoint_entered.wait(), timeout=0.5)

        second = asyncio.create_task(agent.run(self._request("checkpoint-2")))
        try:
            await asyncio.sleep(0.05)
            self.assertEqual(1, agent.checkpoint_entries)
        finally:
            agent.checkpoint_release.set()
            agent.release.set()
            await asyncio.gather(first, second)
        self.assertEqual(2, agent.checkpoint_entries)

    async def test_stream_checkpoint_read_is_covered_by_run_admission(self) -> None:
        agent = CheckpointAdmissionProbeAgent()

        async def consume_stream() -> None:
            async for _event in agent.stream(self._request("checkpoint-stream")):
                pass

        first = asyncio.create_task(consume_stream())
        await asyncio.wait_for(agent.checkpoint_entered.wait(), timeout=0.5)
        second = asyncio.create_task(agent.run(self._request("checkpoint-run")))
        try:
            await asyncio.sleep(0.05)
            self.assertEqual(1, agent.checkpoint_entries)
        finally:
            agent.checkpoint_release.set()
            agent.release.set()
            await asyncio.gather(first, second)
        self.assertEqual(2, agent.checkpoint_entries)

    async def test_stream_and_blocking_run_share_the_same_admission_pool(self) -> None:
        agent = AdmissionProbeAgent()
        stream = agent.stream(self._request("stream-1"))

        self.assertEqual({"event": "start"}, await anext(stream))
        self.assertEqual(1, agent.stream_entries)

        blocking = asyncio.create_task(agent.run(self._request("run-1")))
        await asyncio.sleep(0.05)
        self.assertEqual(0, agent.run_entries)

        await stream.aclose()
        await asyncio.wait_for(agent.run_entered.wait(), timeout=0.5)
        agent.release.set()
        await blocking

    async def test_stream_cancellation_holds_slot_until_background_cleanup_finishes(self) -> None:
        agent = CleaningStreamAdmissionProbeAgent()
        first_event = asyncio.Event()

        async def consume_stream() -> None:
            stream = agent.stream(self._request("stream-cleanup"))
            self.assertEqual("progress", (await anext(stream))["event"])
            first_event.set()
            await anext(stream)

        consumer = asyncio.create_task(consume_stream())
        await asyncio.wait_for(first_event.wait(), timeout=0.5)
        await asyncio.wait_for(agent.background_started.wait(), timeout=0.5)

        consumer.cancel()
        await asyncio.wait_for(agent.cleanup_started.wait(), timeout=0.5)

        blocking = asyncio.create_task(agent.run(self._request("run-after-cancel")))
        try:
            await asyncio.sleep(0.05)
            self.assertFalse(agent.cleanup_finished.is_set())
            self.assertEqual(0, agent.run_entries)
        finally:
            agent.cleanup_release.set()
            with self.assertRaises(asyncio.CancelledError):
                await consumer
        await asyncio.wait_for(agent.run_entered.wait(), timeout=0.5)
        self.assertTrue(agent.cleanup_finished.is_set())
        agent.release.set()
        await blocking

    async def test_stream_background_error_is_not_swallowed(self) -> None:
        agent = FailingStreamAdmissionProbeAgent()
        events = agent.stream(self._request("stream-error"))
        self.assertEqual("progress", (await anext(events))["event"])
        with self.assertRaisesRegex(RuntimeError, "background failed"):
            await anext(events)

    async def test_eval_runner_uses_agent_run_admission(self) -> None:
        agent = AdmissionProbeAgent()
        runner = GoldenEvalRunner(
            agent=agent,
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        )

        async with run_slot("fast", run_id="capacity-holder"):
            evaluation = asyncio.create_task(runner.run_case(
                GoldenEvalCase(case_id="admission-eval", question="test admission")
            ))
            await asyncio.sleep(0.05)
            self.assertEqual(0, agent.run_entries)

        await asyncio.wait_for(agent.run_entered.wait(), timeout=0.5)
        agent.release.set()
        await evaluation

    def _request(self, trace_id: str) -> KnowledgeChatRequest:
        return KnowledgeChatRequest(
            question="test admission",
            traceId=trace_id,
            reasoningMode="fast",
        )


if __name__ == "__main__":
    unittest.main()
