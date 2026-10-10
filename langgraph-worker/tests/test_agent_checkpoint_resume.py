from __future__ import annotations

import unittest

from app.models.knowledge import BookCandidate, KnowledgeChatRequest, KnowledgeChatResponse
from app.services.harness.capability_compiler import CapabilityCompiler
from app.services.harness.contracts import DomainStatus, IntentEnvelope
from app.services.novel_research_agent import NovelResearchAgent


class SnapshotGraph:
    def __init__(self, values: dict, *, pending: bool = False) -> None:
        self.values = values
        self.pending = pending

    async def aget_state(self, _config):
        return type(
            "CheckpointSnapshot",
            (),
            {
                "values": self.values,
                "next": ("compose_answer",) if self.pending else (),
            },
        )()


class StaticLoader:
    def __init__(self, value) -> None:
        self.value = value

    async def assemble_async(self, _request):
        return self.value

    async def load(self, _request):
        return self.value


class RecordingCheckpointer:
    def __init__(self) -> None:
        self.deleted_threads: list[str] = []

    def delete_thread(self, thread_id: str) -> None:
        self.deleted_threads.append(thread_id)


class SemanticCheckpointClient:
    def __init__(self, events: list[dict] | None = None) -> None:
        self.events = list(events or [])
        self.appends: list[dict] = []

    async def append_semantic_checkpoint(self, **kwargs):
        self.appends.append(dict(kwargs))
        return kwargs

    async def list_semantic_checkpoints(self, **_kwargs):
        return list(self.events)


def model_prepared_event(
    *,
    event_id: int | str = 101,
    sequence: int | bool = 7,
    run_id: str = "checkpoint-trace",
    outer_event_type: str = "MODEL_PREPARED",
    envelope_event_type: str = "MODEL_PREPARED",
    event_key: str = "harness:model_prepared:model-semantic-1",
) -> dict:
    return {
        "eventId": event_id,
        "runId": run_id,
        "sequenceNo": sequence,
        "eventType": outer_event_type,
        "eventIdempotencyKey": event_key,
        "payload": {
            "semanticKey": "model-semantic-1",
            "requestFingerprint": "f" * 64,
            "transport": "invoke",
            "turn": 1,
            "model": "unit-model",
            "requestSummary": {
                "messageCount": 2,
                "roleCounts": {"system": 1, "user": 1},
                "messageChars": 26,
                "toolSchemaCount": 1,
                "cachePrefixFingerprint": "a" * 64,
                "bodyRedacted": True,
                "contextCompaction": {
                    "status": "compacted",
                    "beforeSurfaceFingerprint": "sha256:" + "b" * 64,
                    "afterSurfaceFingerprint": "sha256:" + "c" * 64,
                    "bodyRedacted": True,
                    "sourceEvent": {"forged": "must be replaced"},
                },
            },
            "bodyRedacted": True,
            "privateCallbackBody": "must not enter sourceEvent",
            "_event": {
                "schemaVersion": 1,
                "eventId": event_id,
                "runId": run_id,
                "sequence": sequence,
                "eventType": envelope_event_type,
                "visibility": "internal",
                "eventIdempotencyKey": event_key,
                "privateEnvelopeBody": "must not enter sourceEvent",
            },
        },
    }


class SemanticLedgerProbe:
    def __init__(self, identity) -> None:
        self.identity = identity
        self.merged: list[dict] = []

    def merge_semantic_events(self, events):
        self.merged = list(events)
        return [{"semanticKey": "tool-semantic-1", "runId": self.identity.runId}]


class ResumeGuardProbeAgent(NovelResearchAgent):
    def __init__(self, values: dict) -> None:
        self._graph = SnapshotGraph(values)
        self.budget_requested = False
        self.ledger_requested = False

    def _graph_config(self, _request: KnowledgeChatRequest) -> dict:
        return {"configurable": {"thread_id": "probe-thread"}}

    def _budget_for_checkpoint(self, request, checkpoint):
        self.budget_requested = True
        return super()._budget_for_checkpoint(request, checkpoint)

    def _run_tool_ledger(self, request, checkpoint):
        self.ledger_requested = True
        return super()._run_tool_ledger(request, checkpoint)

    async def run(self, request: KnowledgeChatRequest):
        """Exercise resume guards without full WebnovelHarness construction."""
        config = self._graph_config(request)
        checkpoint = await self._prepare_checkpoint(request, config)
        # If prepare accepted the checkpoint, budget/ledger would be requested next.
        _ = self._budget_for_checkpoint(request, checkpoint)
        _ = self._run_tool_ledger(request, checkpoint)
        return checkpoint


class ResumeBoundaryProbeAgent(ResumeGuardProbeAgent):
    def __init__(self, values: dict, current_boundary: dict) -> None:
        super().__init__(values)
        self.current_boundary = current_boundary

    async def _authorization_boundary_for_resume(self, _request, _values):
        return self.current_boundary


class AgentCheckpointResumeTest(unittest.IsolatedAsyncioTestCase):
    def _request(self, **updates) -> KnowledgeChatRequest:
        values = {
            "question": "compare the selected novel with recent rankings",
            "traceId": "checkpoint-trace",
            "conversationId": "checkpoint-conversation",
            "reasoningMode": "deep",
            "mode": "research",
            "userId": 7,
            "projectId": 11,
            "bookName": "The First Book",
            "bookId": 13,
            "selectedCandidate": BookCandidate(
                bookId=13,
                platform="test",
                platformBookId="book-13",
                bookName="The First Book",
                author="Author One",
            ),
            "contextSummary": "Earlier research summary",
            "contextBundle": {"rank": {"board": "new", "position": 2}},
            "history": [{"role": "user", "content": "Earlier question"}],
            "limits": {"evidenceLimit": 8, "timeoutMillis": 12_000},
        }
        values.update(updates)
        return KnowledgeChatRequest(**values)

    def test_request_fingerprint_covers_all_answer_affecting_request_fields(self) -> None:
        agent = object.__new__(NovelResearchAgent)
        base = self._request()
        base_fingerprint = agent._request_fingerprint(base)
        variants = {
            "question": {"question": "a different question"},
            "reasoningMode": {"reasoningMode": "fast"},
            "mode": {"mode": "creative"},
            "userId": {"userId": 8},
            "projectId": {"projectId": 12},
            "bookName": {"bookName": "Another Book"},
            "bookId": {"bookId": 14},
            "selectedCandidate": {
                "selectedCandidate": BookCandidate(bookId=14, bookName="Another Book")
            },
            "contextSummary": {"contextSummary": "Different summary"},
            "contextBundle": {"contextBundle": {"rank": {"board": "hot"}}},
            "history": {"history": [{"role": "assistant", "content": "Earlier answer"}]},
            "limits": {"limits": {"evidenceLimit": 2}},
        }

        for field, updates in variants.items():
            with self.subTest(field=field):
                self.assertNotEqual(base_fingerprint, agent._request_fingerprint(self._request(**updates)))

    def test_request_fingerprint_is_stable_for_equivalent_mapping_order(self) -> None:
        agent = object.__new__(NovelResearchAgent)
        first = self._request(
            contextBundle={"alpha": 1, "nested": {"left": 2, "right": 3}},
            limits={"evidenceLimit": 8, "timeoutMillis": 12_000},
        )
        second = self._request(
            contextBundle={"nested": {"right": 3, "left": 2}, "alpha": 1},
            limits={"timeoutMillis": 12_000, "evidenceLimit": 8},
        )

        self.assertEqual(agent._request_fingerprint(first), agent._request_fingerprint(second))
        self.assertEqual(
            agent._request_fingerprint(self._request()),
            agent._request_fingerprint(self._request(resumeFromCheckpoint=True)),
        )

    async def test_initial_checkpoint_state_contains_request_fingerprint(self) -> None:
        agent = object.__new__(NovelResearchAgent)
        agent.context_assembler = StaticLoader({"assembled": True})
        agent.memory_agent = StaticLoader({"memory": True})
        request = self._request()

        state = await agent._initial_state(request)

        self.assertEqual(agent._request_fingerprint(request), state["request_fingerprint"])

    async def test_run_tool_ledger_binds_semantic_writer_to_stable_run_identity(self) -> None:
        client = SemanticCheckpointClient()
        agent = object.__new__(NovelResearchAgent)
        agent.knowledge_client = client
        request = self._request()
        ledger = agent._run_tool_ledger(request, None)

        run = await ledger.execute("rank.lookup", {}, lambda: {"items": []})

        self.assertEqual("succeeded", run.status)
        self.assertEqual(["TOOL_PREPARED", "TOOL_COMMITTED"], [item["event_type"] for item in client.appends])
        self.assertTrue(all(item["run_id"] == "checkpoint-trace" for item in client.appends))
        self.assertTrue(all(item["user_id"] == 7 for item in client.appends))

    async def test_resume_hydration_persists_tool_and_model_unknown_events(self) -> None:
        client = SemanticCheckpointClient([{
            "sequenceNo": 1,
            "eventType": "MODEL_PREPARED",
            "payload": {"semanticKey": "model-semantic-1", "bodyRedacted": True},
        }])
        agent = object.__new__(NovelResearchAgent)
        agent.knowledge_client = client
        request = self._request(resumeFromCheckpoint=True)
        ledger = SemanticLedgerProbe(agent._run_tool_identity(request))

        await agent._hydrate_semantic_checkpoints(request, ledger)

        self.assertEqual(client.events, ledger.merged)
        self.assertEqual(["TOOL_UNKNOWN", "MODEL_UNKNOWN"], [item["event_type"] for item in client.appends])
        unknown_payload = client.appends[1]["payload"]
        self.assertNotIn("_event", unknown_payload)
        self.assertNotIn("sourceEvent", unknown_payload)

    async def test_resume_hydration_links_model_unknown_to_valid_prepared_event(self) -> None:
        client = SemanticCheckpointClient([model_prepared_event()])
        agent = object.__new__(NovelResearchAgent)
        agent.knowledge_client = client
        request = self._request(resumeFromCheckpoint=True)
        ledger = SemanticLedgerProbe(agent._run_tool_identity(request))

        await agent._hydrate_semantic_checkpoints(request, ledger)

        unknown_payload = client.appends[1]["payload"]
        source_event = unknown_payload["requestSummary"]["contextCompaction"]["sourceEvent"]
        prepared_payload = client.events[0]["payload"]
        self.assertEqual(prepared_payload["semanticKey"], unknown_payload["semanticKey"])
        self.assertEqual(
            prepared_payload["requestFingerprint"],
            unknown_payload["requestFingerprint"],
        )
        self.assertEqual(prepared_payload["transport"], unknown_payload["transport"])
        self.assertEqual(prepared_payload["turn"], unknown_payload["turn"])
        self.assertEqual(prepared_payload["model"], unknown_payload["model"])
        prepared_summary = dict(prepared_payload["requestSummary"])
        prepared_compaction = dict(prepared_summary["contextCompaction"])
        prepared_compaction.pop("sourceEvent", None)
        prepared_summary["contextCompaction"] = prepared_compaction
        unknown_summary = dict(unknown_payload["requestSummary"])
        unknown_compaction = dict(unknown_summary["contextCompaction"])
        unknown_compaction.pop("sourceEvent", None)
        unknown_summary["contextCompaction"] = unknown_compaction
        self.assertEqual(prepared_summary, unknown_summary)
        self.assertNotIn("_event", unknown_payload)
        self.assertEqual("unknown_after_interrupted_provider_call", unknown_payload["outcome"])
        self.assertEqual(
            {
                "schemaVersion": 1,
                "eventId": 101,
                "sequence": 7,
                "eventType": "MODEL_PREPARED",
                "bodyRedacted": True,
            },
            source_event,
        )
        self.assertNotIn("runId", source_event)
        self.assertNotIn("eventIdempotencyKey", source_event)
        self.assertNotIn("visibility", source_event)
        self.assertNotIn("privateCallbackBody", str(source_event))
        self.assertNotIn("privateEnvelopeBody", str(source_event))

    async def test_resume_hydration_ignores_invalid_prepared_source_identity(self) -> None:
        cases = {
            "string_event_id": model_prepared_event(event_id="101"),
            "boolean_sequence": model_prepared_event(sequence=True),
            "wrong_run": model_prepared_event(run_id="different-run"),
            "wrong_event_type": model_prepared_event(envelope_event_type="MODEL_COMMITTED"),
            "wrong_event_key": model_prepared_event(event_key="wrong-key"),
        }

        for name, event in cases.items():
            with self.subTest(name=name):
                client = SemanticCheckpointClient([event])
                agent = object.__new__(NovelResearchAgent)
                agent.knowledge_client = client
                request = self._request(resumeFromCheckpoint=True)
                ledger = SemanticLedgerProbe(agent._run_tool_identity(request))

                await agent._hydrate_semantic_checkpoints(request, ledger)

                unknown_payload = client.appends[1]["payload"]
                compaction = unknown_payload["requestSummary"]["contextCompaction"]
                self.assertNotIn("_event", unknown_payload)
                self.assertNotIn("sourceEvent", compaction)
                self.assertEqual(
                    "unknown_after_interrupted_provider_call",
                    unknown_payload["outcome"],
                )

    async def test_resume_rejects_legacy_checkpoint_before_budget_or_ledger_reuse(self) -> None:
        response = KnowledgeChatResponse(status="answered", answer="stale answer")
        agent = ResumeGuardProbeAgent({"response": response})

        with self.assertRaisesRegex(RuntimeError, "fingerprint.*missing.*refusing"):
            await agent.run(self._request(resumeFromCheckpoint=True))

        self.assertFalse(agent.budget_requested)
        self.assertFalse(agent.ledger_requested)

    async def test_resume_rejects_mismatched_checkpoint_before_budget_or_ledger_reuse(self) -> None:
        response = KnowledgeChatResponse(status="answered", answer="stale answer")
        agent = ResumeGuardProbeAgent({
            "request_fingerprint": "sha256:not-the-current-request",
            "response": response,
        })

        with self.assertRaisesRegex(RuntimeError, "fingerprint mismatch.*refusing"):
            await agent.run(self._request(resumeFromCheckpoint=True))

        self.assertFalse(agent.budget_requested)
        self.assertFalse(agent.ledger_requested)

    async def test_resume_rejects_changed_authorization_boundary(self) -> None:
        request = self._request(resumeFromCheckpoint=True)
        response = KnowledgeChatResponse(status="answered", answer="stale answer")
        fingerprint_probe = object.__new__(NovelResearchAgent)
        agent = ResumeBoundaryProbeAgent(
            {
                "request_fingerprint": NovelResearchAgent._request_fingerprint(fingerprint_probe, request),
                "authorization_decision": {"decisionId": "auth-old", "grants": []},
                "authorization_boundary": {"fingerprint": "sha256:old"},
                "response": response,
            },
            {"fingerprint": "sha256:new"},
        )

        with self.assertRaisesRegex(RuntimeError, "authorization boundary mismatch.*refusing"):
            await agent.run(request)

        self.assertFalse(agent.budget_requested)
        self.assertFalse(agent.ledger_requested)

    async def test_resume_rejects_checkpoint_authorization_without_boundary(self) -> None:
        request = self._request(resumeFromCheckpoint=True)
        response = KnowledgeChatResponse(status="answered", answer="stale answer")
        fingerprint_probe = object.__new__(NovelResearchAgent)
        agent = ResumeGuardProbeAgent({
            "request_fingerprint": NovelResearchAgent._request_fingerprint(fingerprint_probe, request),
            "authorization_decision": {"decisionId": "auth-old", "grants": []},
            "response": response,
        })

        with self.assertRaisesRegex(RuntimeError, "authorization boundary is missing.*refusing"):
            await agent.run(request)

        self.assertFalse(agent.budget_requested)
        self.assertFalse(agent.ledger_requested)

    async def test_resume_boundary_recompute_detects_local_manifest_change(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=object(),
            provider_client=object(),
        )
        request = self._request(userId=7, projectId=91)
        plan = CapabilityCompiler().compile(IntentEnvelope(
            domainStatus=DomainStatus.IN_SCOPE,
            goal="market_scan",
            operations=("market_scan",),
            confidence=0.9,
            classificationSource="rules",
        ))
        authorization = agent.capability_authorizer.authorize(plan).model_dump(mode="json")
        boundary = agent._authorization_boundary_summary(
            request=request,
            authorization_decision=authorization,
            runtime_config={"specialistMcpEnabled": False},
            phase="planned",
            specialist_mcp_denied_reason="delegation_not_evaluated",
        )
        values = {
            "capability_plan": plan.model_dump(mode="json"),
            "authorization_decision": authorization,
            "authorization_boundary": boundary,
        }

        current = await agent._authorization_boundary_for_resume(request, values)
        self.assertEqual(boundary["fingerprint"], current["fingerprint"])

        agent._tool_registry.register(
            "rank.lookup",
            "rank",
            {"type": "object", "required": ["changed"]},
            lambda _payload: {},
        )
        changed = await agent._authorization_boundary_for_resume(request, values)

        self.assertNotEqual(boundary["fingerprint"], changed["fingerprint"])

    async def test_fresh_requests_clear_stale_checkpoint_for_shared_conversation(self) -> None:
        agent = object.__new__(NovelResearchAgent)
        agent._checkpointer = RecordingCheckpointer()
        request = self._request(traceId=None, conversationId="shared-conversation")
        config = agent._graph_config(request)

        first = await agent._prepare_checkpoint(request, config)
        second = await agent._prepare_checkpoint(request, config)

        self.assertIsNone(first)
        self.assertIsNone(second)
        self.assertEqual(
            ["shared-conversation", "shared-conversation"],
            agent._checkpointer.deleted_threads,
        )

    def test_resume_requires_stable_thread_identity(self) -> None:
        agent = object.__new__(NovelResearchAgent)

        with self.assertRaisesRegex(RuntimeError, "requires traceId or conversationId"):
            agent._graph_config(self._request(
                traceId=None,
                conversationId=None,
                resumeFromCheckpoint=True,
            ))


if __name__ == "__main__":
    unittest.main()
