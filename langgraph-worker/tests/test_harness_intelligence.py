from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

import pytest
from pydantic import ValidationError

from app.services.harness import contracts
from app.services.harness.tool_ledger import RunToolLedger
from app.models.agent_task import TaskGraph, TaskNode, TaskType, Perspective, ToolPlan, RetrievalPlan
from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse, KnowledgeSource


def test_intelligence_policy_defaults_and_strict_values() -> None:
    policy = contracts.HarnessIntelligencePolicy.from_runtime_config({})
    assert not any(policy.model_dump().values())
    enabled = contracts.HarnessIntelligencePolicy.from_runtime_config({
        "harnessEvidenceRepairEnabled": True,
        "unrelatedSetting": "preserved by runtime config",
    })
    assert enabled.harnessEvidenceRepairEnabled is True
    for invalid in ("false", "true", 1, None, [], {}):
        with pytest.raises(ValidationError):
            contracts.HarnessIntelligencePolicy.from_runtime_config({
                "harnessEvidenceRepairEnabled": invalid,
            })


def test_validation_is_bound_to_answer_and_evidence() -> None:
    result = contracts.AnswerValidationResult(
        status="passed", checkedAnswerHash="sha256:answer", checkedEvidenceCommitId="commit-1",
    )
    assert result.matches("sha256:answer", "commit-1")
    assert not result.matches("sha256:other", "commit-1")
    assert not result.matches("sha256:answer", "commit-2")
    with pytest.raises(ValidationError):
        contracts.AnswerValidationResult(status="revised")
    with pytest.raises(ValidationError):
        contracts.ToolObservationSummary(toolCallId="call-1", status="succeeded", evidenceRefs=["x" * 257])


def test_deterministic_validation_rejects_forged_citations_and_preserves_unknown() -> None:
    from app.services.harness.intelligence import validate_answer

    response = KnowledgeChatResponse(status="answered", answer="Result [2]", sources=[KnowledgeSource(chunkId=1)])
    commit = contracts.EvidenceCommit(commitId="commit", canCommit=True, decisions=[
        contracts.EvidenceDecision(evidenceId="1", citationRef="1", decision="ACCEPTED"),
    ])
    result = validate_answer(response, commit=commit)
    assert result.status == "failed"
    assert "citation_unresolved" in [issue.issueId for issue in result.issues]
    response.answer = "Result [1]"
    checked = validate_answer(response, commit=commit)
    assert checked.status == "passed"
    assert checked.semanticStatus == "unknown"
    assert not result.matches(checked.checkedAnswerHash, commit.commitId)
    assert validate_answer(response, commit=None).status == "unknown"


def test_task_checkpoint_requires_current_identity_and_accepted_task_evidence() -> None:
    from app.services.harness.intelligence import build_task_checkpoint, current_task_checkpoint

    request = KnowledgeChatRequest(question="Current goal", userId=1, projectId=7, traceId="run-1")
    envelope = contracts.IntentEnvelope(domainStatus="IN_SCOPE", goal="current", constraints=["current constraint"])
    commit = contracts.EvidenceCommit(commitId="commit", canCommit=True, decisions=[
        contracts.EvidenceDecision(evidenceId="1", decision="ACCEPTED"),
        contracts.EvidenceDecision(evidenceId="2", decision="REJECTED"),
    ])
    state = {
        "request": request, "intent_envelope": envelope.model_dump(), "evidence_commit": commit.model_dump(),
        "task_graph": {"tasks": [{"id": "read-1"}, {"id": "read-2"}]},
        "tool_runs": [{"name": "project.retrieve", "status": "succeeded", "input": {"taskId": "read-1"}, "resultCount": 1}],
    }
    checkpoint = build_task_checkpoint(state)
    assert checkpoint.pendingTaskIds == ("read-1", "read-2")
    assert checkpoint.acceptedEvidenceRefs == ("1",)
    assert checkpoint.rejectedEvidenceRefs == ("2",)
    assert current_task_checkpoint(checkpoint.model_dump(), state) == checkpoint
    assert current_task_checkpoint({**checkpoint.model_dump(), "schemaVersion": "future"}, state) is None
    assert current_task_checkpoint(checkpoint.model_dump(), {**state, "request": request.model_copy(update={"projectId": 8})}) is None
    assert current_task_checkpoint(checkpoint.model_dump(), {**state, "intent_envelope": envelope.model_copy(update={"goal": "new"}).model_dump()}) is None
    assert current_task_checkpoint(checkpoint.model_dump(), {**state, "evidence_commit": {**commit.model_dump(), "commitId": "new"}}) is None


def test_task_checkpoint_requires_structured_multi_chapter_coverage_and_fresh_material() -> None:
    from app.services.harness.intelligence import build_task_checkpoint, current_task_checkpoint

    request = KnowledgeChatRequest(question="Review chapters", userId=1, projectId=7, workId=9, traceId="run-2")
    envelope = contracts.IntentEnvelope(domainStatus="IN_SCOPE", goal="review", constraints=[])
    sources = [
        KnowledgeSource(projectId=7, workId=9, documentId=11, chapterNo=1, contentHash="v1", material="one"),
        KnowledgeSource(projectId=7, workId=9, documentId=12, chapterNo=2, contentHash="v1", material="two"),
        KnowledgeSource(projectId=7, workId=9, documentId=13, chapterNo=3, contentHash="v1", material="three"),
    ]
    commit = contracts.EvidenceCommit(commitId="commit", canCommit=True, decisions=[
        contracts.EvidenceDecision(evidenceId="source:1", citationRef="source:1", decision="ACCEPTED"),
        contracts.EvidenceDecision(evidenceId="source:2", citationRef="source:2", decision="ACCEPTED"),
        contracts.EvidenceDecision(evidenceId="source:3", citationRef="source:3", decision="ACCEPTED"),
    ])
    state = {
        "request": request,
        "intent_envelope": envelope.model_dump(),
        "evidence_commit": commit.model_dump(),
        "task_graph": {"tasks": [{"id": "chapters"}, {"id": "single"}]},
        "task_tool_plan": [{"taskId": "chapters", "retrievalPlan": {"chapterFrom": 1, "chapterTo": 2}},
                            {"taskId": "single"}],
        "sources": sources,
        "tool_runs": [
            {"name": "project.retrieve", "status": "succeeded", "input": {"taskId": "chapters"},
             "output": {"evidence": [{"documentId": 11, "chapterNo": 1}, {"documentId": 12, "chapterNo": 2}]}},
            {"name": "project.retrieve", "status": "succeeded", "input": {"taskId": "single"},
             "output": {"evidence": [{"documentId": 13, "chapterNo": 3}]}},
        ],
    }
    checkpoint = build_task_checkpoint(state)
    assert checkpoint.completedTaskIds == ("chapters", "single")
    changed = {**state, "sources": [sources[0].model_copy(update={"material": "changed"}), *sources[1:]]}
    assert current_task_checkpoint(checkpoint.model_dump(), changed) is None
    assert current_task_checkpoint(checkpoint.model_dump(), {**state, "request": request.model_copy(update={"workId": 10})}) is None


def test_read_repair_only_refines_original_authorized_plan() -> None:
    from app.services.task_graph.planner import DomainToolPlanner

    envelope = contracts.IntentEnvelope(domainStatus="IN_SCOPE", goal="recall")
    capability = contracts.CapabilityPlan(intentEnvelopeHash=envelope.fingerprint)
    plan = ToolPlan(taskId="read", taskType=TaskType.project_knowledge_qa, required=True,
                    tools=["project.retrieve"], retrievalPlan=RetrievalPlan(query="plot", entities=["hero"]))
    graph = TaskGraph(userGoal="recall", tasks=[TaskNode(id="read", type=plan.taskType, goal="recall", perspective=Perspective.book)])
    arguments = dict(graph=graph, plans=[plan], envelope=envelope, capability_plan=capability,
                     scope=contracts.CapabilityScope(userId=1, projectId=7), allowed_tools={"project.retrieve"},
                     runs=[{"name": "project.retrieve", "status": "succeeded", "resultCount": 0, "input": {"taskId": "read"}}])
    result = DomainToolPlanner().propose_read_repair(**arguments)
    assert result is not None
    proposal, plans = result
    assert proposal.missingRequirementIds == ("read",)
    assert plans[0].retrievalPlan.query != plan.retrievalPlan.query
    assert plans[0].retrievalPlan.filters == plan.retrievalPlan.filters
    assert set(plans[0].retrievalPlan.channels) <= set(plan.retrievalPlan.channels)
    assert DomainToolPlanner().propose_read_repair(**{**arguments, "allowed_tools": set()}) is None
    assert DomainToolPlanner().propose_read_repair(**{**arguments, "runs": [{"name": "project.retrieve", "status": "denied", "input": {"taskId": "read"}}]}) is None


class ToolProgressTests(unittest.IsolatedAsyncioTestCase):
    def ledger(self, **kwargs: object) -> RunToolLedger:
        return RunToolLedger(run_id="progress-run", user_id=1, project_id=7, route="book_breakdown", **kwargs)

    async def test_single_repair_slot_is_durable_and_shared_across_routes(self) -> None:
        events = []

        async def write(event_type: str, _key: str, payload: dict) -> None:
            events.append({"eventType": event_type, "payload": payload})

        ledger = self.ledger(checkpoint_writer=write)
        self.assertTrue(await ledger.claim_evidence_repair())
        self.assertFalse(await ledger.for_route("market_scan").claim_evidence_repair())
        recovered = self.ledger()
        recovered.merge_semantic_events(events)
        recovered.merge_semantic_events(events)
        self.assertFalse(await recovered.claim_evidence_repair())
        recovered.merge_checkpoint(ledger.checkpoint_snapshot())
        self.assertTrue(recovered.evidence_repair_used)

    async def test_different_requests_are_not_a_tool_name_loop(self) -> None:
        ledger = self.ledger()
        for book_id in (1, 2, 3):
            result = await ledger.execute(
                "book.read", {"bookId": book_id}, lambda: {"items": [1]},
                call_id=f"call-{book_id}", track_progress=True,
            )
            self.assertEqual("succeeded", result.status)
            self.assertTrue(result.executed)

    async def test_reuse_then_no_progress_survives_checkpoint_and_event_replay(self) -> None:
        events: list[dict] = []
        executions: list[int] = []

        async def write(event_type: str, event_key: str, payload: dict) -> None:
            events.append({"eventType": event_type, "eventKey": event_key, "payload": payload})

        def operation() -> dict:
            executions.append(1)
            return {"items": []}

        ledger = self.ledger(checkpoint_writer=write)
        for call_id in ("first", "second"):
            result = await ledger.execute("book.read", {"bookId": 1}, operation, call_id=call_id, track_progress=True)
            self.assertEqual("succeeded", result.status)
        self.assertTrue(result.reused)
        restored = self.ledger(checkpoint_writer=write)
        restored.merge_checkpoint(ledger.checkpoint_snapshot())
        restored.merge_semantic_events(events)
        restored.merge_semantic_events(events)
        replay = await restored.execute("book.read", {"bookId": 1}, operation, call_id="second", track_progress=True)
        self.assertTrue(replay.reused)
        denied = await restored.execute("book.read", {"bookId": 1}, operation, call_id="third", track_progress=True)
        self.assertEqual("ToolNoProgress", denied.errorType)
        self.assertFalse(denied.executed)
        recovered = self.ledger()
        recovered.merge_semantic_events(events)
        denied_again = await recovered.execute("book.read", {"bookId": 1}, operation, call_id="fourth", track_progress=True)
        self.assertEqual("ToolNoProgress", denied_again.errorType)
        self.assertEqual([1], executions)
        self.assertEqual(3, sum(event["eventType"] == "TOOL_PROGRESS" for event in events))

    async def test_progress_is_persisted_before_execution_and_failure_is_closed(self) -> None:
        executions: list[int] = []

        async def write(event_type: str, _event_key: str, _payload: dict) -> None:
            if event_type == "TOOL_PROGRESS":
                raise RuntimeError("checkpoint unavailable")

        with self.assertRaisesRegex(RuntimeError, "checkpoint unavailable"):
            await self.ledger(checkpoint_writer=write).execute(
                "book.read", {}, lambda: executions.append(1), call_id="first", track_progress=True,
            )
        self.assertEqual([], executions)

    async def test_progress_does_not_bypass_identity_conflicts(self) -> None:
        ledger = self.ledger()
        await ledger.execute("book.read", {"bookId": 1}, lambda: {}, call_id="same", track_progress=True)
        conflict = await ledger.execute("book.read", {"bookId": 2}, lambda: {}, call_id="same", track_progress=True)
        self.assertEqual("CallIdentityConflict", conflict.errorType)


def test_stage_skill_projection_is_bounded_and_pinned() -> None:
    from app.services.skills.mediation import SkillMediator

    catalog = [
        {"skillId": "research", "intents": ["market_scan"], "prompt": "RESEARCH", "pin": {"version": "1"}},
        {"skillId": "create", "intents": ["chapter_outline"], "prompt": "CREATE", "pin": {"version": "2"}},
    ]
    research = SkillMediator.project_stage(catalog, stage="research", loaded_ids=[], reload_count=0, initialized=False)
    assert research["activatedIds"] == ["research"]
    assert research["prompt"] == "RESEARCH"
    compose = SkillMediator.project_stage(catalog, stage="compose", loaded_ids=research["loadedIds"], reload_count=0, initialized=True)
    assert compose["reloadCount"] == 1
    assert compose["pins"] == [{"version": "1"}, {"version": "2"}]
    blocked = SkillMediator.project_stage(catalog, stage="review", loaded_ids=["research"], reload_count=1, initialized=True)
    assert blocked["activatedIds"] == []


def test_specialist_evidence_covers_books_beyond_first_six_without_renumbering() -> None:
    from app.services.agents.base import AgentRunContext, BaseSpecialistAgent
    from app.services.intents import IntentDecision, Intent

    sources = [KnowledgeSource(bookId=1, bookName="Book A", chunkId=index, preview="preview") for index in range(6)]
    sources += [KnowledgeSource(bookId=2, bookName="Book B", chunkId=7, material="chapter excerpt"),
                KnowledgeSource(bookId=3, bookName="Book C", chunkId=8, preview="preview")]
    context = AgentRunContext(request=KnowledgeChatRequest(question="Compare Book A, Book B and Book C"),
                              intent_decision=IntentDecision(primaryIntent=Intent.book_breakdown, confidence=1),
                              sources=sources, targeted_evidence_enabled=True)
    agent = BaseSpecialistAgent()
    selected = agent._targeted_sources(context)
    assert {source.bookId for _index, source in selected} == {1, 2, 3}
    assert {7, 8} <= {index for index, _source in selected}
    content = agent._llm_messages(context)[1]["content"]
    assert '"evidenceRef":"source:8"' in content
    assert '"contentKind":"preview"' in content
    assert '"semanticStatus":"unknown"' in content


class HarnessIntelligenceIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_empty_project_retrieval_runs_one_bounded_repair_and_revalidates(self) -> None:
        from app.services.harness.budget import RunBudget, run_budget_scope
        from app.services.harness.tool_ledger import run_tool_ledger_scope
        from app.services.novel_research_agent import NovelResearchAgent
        from tests.test_novel_research_agent import ProjectRagKnowledgeClient, FakeAnswerProvider

        class EmptyThenProjectClient(ProjectRagKnowledgeClient):
            async def get_agent_runtime_config(self) -> dict:
                return {"harnessEvidenceRepairEnabled": True}

            async def retrieve_project_knowledge(self, **kwargs) -> dict:
                if len([name for name, _payload in self.project_calls if name == "retrieve"]) == 0:
                    self.project_calls.append(("retrieve", dict(kwargs)))
                    return {"evidence": []}
                return await super().retrieve_project_knowledge(**kwargs)

        client = EmptyThenProjectClient()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=FakeAnswerProvider())
        request = KnowledgeChatRequest(
            question="回忆月背信号的线索",
            traceId="project-repair-integration",
            projectId=91,
            workId=911,
            userId=7,
        )
        envelope = contracts.IntentEnvelope(domainStatus="IN_SCOPE", goal="recall", entities={"currentTopic": "月背信号"})
        capability_plan = contracts.CapabilityPlan(
            intentEnvelopeHash=envelope.fingerprint,
            requestedToolCapabilities=("project.retrieve",),
        )
        graph = TaskGraph(
            userGoal="recall",
            tasks=[TaskNode(
                id="project-recall",
                type=TaskType.project_knowledge_qa,
                goal="recall the signal",
                perspective=Perspective.editor,
                tools=["project.retrieve"],
            )],
        )
        original_plan = ToolPlan(
            taskId="project-recall",
            taskType=TaskType.project_knowledge_qa,
            tools=["project.retrieve"],
            required=True,
            retrievalPlan=RetrievalPlan(
                query="回忆月背信号的线索",
                entities=["月背信号"],
                channels=["structured", "fulltext"],
                filters={"chapterFrom": 1, "chapterTo": 20},
                chapterFrom=1,
                chapterTo=20,
            ),
        )
        state = {
            "request": request,
            "intent_envelope": envelope.model_dump(mode="json"),
            "capability_plan": capability_plan.model_dump(mode="json"),
            "task_graph": graph.model_dump(mode="json"),
            "task_tool_plan": [original_plan.model_dump(mode="json")],
            "authorization_decision": {"grants": [{"toolName": "project.retrieve"}]},
            "tool_runs": [],
            "source_policy": {},
            "intent": "followup_context",
            "domain_intent": "followup_context",
        }
        with run_budget_scope(RunBudget.fast()), run_tool_ledger_scope({
            "runId": "project-repair-integration",
            "userId": "7",
            "projectId": "91",
            "route": "project_knowledge",
        }):
            initial_runs = await agent._task_tool_executor.execute(
                graph,
                [original_plan],
                context=agent._task_tool_context(request, state),
                allowed_tools=agent._allowed_tools_for_state(state, registry=agent._tool_registry),
                max_tool_calls=agent._max_tool_calls_for_state(state, plans=[original_plan]),
            )
            agent._merge_task_tool_runs(state, initial_runs)
            state["evidence_commit"] = agent._evidence_commit_for_state(state, sources=[])
            proposal = await agent._propose_read_repair(state)
            assert proposal["repair_pending"] is True
            assert proposal["repair_tool_plan"][0]["retrievalPlan"]["query"] == "月背信号"
            executed = await agent._execute_read_repair({**state, **proposal})
            supervised = await agent._supervise_evidence_node({**state, **proposal, **executed})

        retrieval_calls = [payload for name, payload in client.project_calls if name == "retrieve"]
        assert len(retrieval_calls) == 2, [
            {key: run.get(key) for key in ("name", "status", "errorType", "executed", "reused", "output")}
            for run in executed.get("tool_runs", [])
        ]
        assert retrieval_calls[0]["query"] == "回忆月背信号的线索"
        assert retrieval_calls[1]["query"] == "月背信号"
        assert executed["sources"]
        assert all(source.projectId == 91 and source.workId == 911 for source in executed["sources"])
        assert supervised["supervisor"]["status"] == "answerable"
        assert supervised["evidence_commit"]["canCommit"] is True
        assert supervised["repair_pending"] is False

    async def test_rules_run_without_enabling_model_review_or_revision(self) -> None:
        from app.config import settings
        from app.services.novel_research_agent import NovelResearchAgent
        from tests.test_novel_research_agent import FakeKnowledgeClient, FakeAnswerProvider

        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        response = KnowledgeChatResponse(status="answered", answer="short draft")
        state = {"request": KnowledgeChatRequest(question="outline", traceId="validation-run"),
                 "response": response, "runtime_config": {"harnessAnswerValidationEnabled": True}}
        state["context_bundle"] = agent.context_assembler.assemble(state["request"])
        state["memory_context"] = agent.memory_agent.empty_context()
        issue = {"code": "chapter_outline_incomplete", "instruction": "Expand required chapters."}
        with patch.object(settings, "agent_answer_review_enabled", False), patch.object(settings, "agent_answer_revision_enabled", True), \
                patch.object(agent, "_deterministic_answer_review_issues", return_value=[issue]), \
                patch.object(agent, "_provider_invoke", new_callable=AsyncMock) as provider:
            result = await agent._review_answer_node(state)
            self.assertEqual("failed", result["answer_review"]["validation"]["status"])
            self.assertEqual("extract_memory_candidates", agent._route_after_answer_review({**state, **result}))
            provider.assert_not_called()
        review = {"status": "revision_required", "revisionRequired": True, "revisionCount": 0,
                  "issues": [issue["code"]], "deterministicIssues": [issue["code"]]}
        with patch.object(agent, "_run_answer_model", new_callable=AsyncMock, return_value={"content": "still short"}), \
                patch.object(agent, "_deterministic_answer_review_issues", return_value=[issue]):
            result = await agent._revise_answer_node({**state, "answer_review": review})
        self.assertEqual("revised", result["answer_review"]["status"], result.get("provider_calls"))
        self.assertEqual("failed", result["answer_review"]["validation"]["status"])
        self.assertEqual(1, result["answer_review"]["revisionCount"])

    async def test_all_flags_share_blocking_stream_and_final_hash_semantics(self) -> None:
        from app.config import settings
        from app.services.harness.intelligence import content_hash
        from app.services.novel_research_agent import NovelResearchAgent
        from tests.test_novel_research_agent import FakeKnowledgeClient, BoundedHarnessProvider

        class EnabledClient(FakeKnowledgeClient):
            async def get_agent_runtime_config(self) -> dict:
                return {key: True for key in contracts.HarnessIntelligencePolicy.model_fields}

        agent = NovelResearchAgent(knowledge_client=EnabledClient(), provider_client=BoundedHarnessProvider())
        request = KnowledgeChatRequest(question="帮我构思一个男频都市脑洞三卷大纲", traceId="intelligence-blocking", userId=7)
        with patch.object(settings, "agent_answer_review_enabled", False), patch.object(settings, "agent_model_first_intent_enabled", True):
            response = await agent.run(request)
            events = [event async for event in agent.stream(request.model_copy(update={"traceId": "intelligence-stream"}))]
        self.assertEqual("answered", response.status)
        done = [event["data"] for event in events if event.get("event") == "done"]
        self.assertEqual(1, len(done))
        self.assertEqual(response.answer, done[0]["answer"])
        for payload in (response.model_dump(mode="json"), done[0]):
            diagnostics = payload["resultJson"]["harnessIntelligence"]
            validation = diagnostics["validation"]
            if validation["status"] != "not_run":
                self.assertEqual(content_hash(payload["answer"]), validation["checkedAnswerHash"])
                self.assertEqual(payload["resultJson"]["evidenceCommit"]["commitId"], validation["checkedEvidenceCommitId"])
            self.assertLessEqual(diagnostics["skillReloadCount"], 1)
            self.assertNotIn("goal", diagnostics["taskProgress"])
            self.assertFalse(any(call.get("node") in {"review_answer", "revise_answer"}
                                 for call in payload["resultJson"].get("providerCalls", [])))

    async def test_enabled_tool_loop_allows_three_inputs_and_pairs_no_progress(self) -> None:
        from app.services.harness.agent_kernel import AgentKernel
        from app.services.runtime.tool_call_loop import ToolCallLoop
        from tests.test_tool_call_loop import ToolCallLoopTest, FakeProvider, FakeMcpClient

        for same_input in (False, True):
            provider = FakeProvider([
                {"tool_calls": [{"id": f"call-{index}", "name": "rank.lookup", "arguments": {
                    "platform": "fanqie", "limit": 1 if same_input else index,
                }} for index in (1, 2, 3)]},
                {"content": "done"},
            ])
            ledger = ToolCallLoopTest()._ledger()
            ledger.progress_control_enabled = True
            client = FakeMcpClient()
            loop = ToolCallLoop(agent_kernel=AgentKernel(provider), mcp_client=client,
                                registry=ToolCallLoopTest()._registry(), tool_ledger=ledger)
            result = await loop.run(messages=[{"role": "user", "content": "compare"}], route="mixed_creation_research")
            self.assertEqual(1 if same_input else 3, len(client.calls))
            tool_messages = [message for message in provider.calls[-1]["messages"] if message.get("role") == "tool"]
            self.assertEqual(3, len(tool_messages))
            self.assertEqual({"call-1", "call-2", "call-3"}, {message["tool_call_id"] for message in tool_messages})
            if same_input:
                self.assertEqual("ToolNoProgress", result["toolRuns"][-1]["errorType"])


def test_compaction_preserves_current_task_projection_and_complete_recent_tool_turn() -> None:
    from app.services.runtime.context_assembler import ContextAssembler
    from app.services.harness.context_compaction import ContextCompactor
    from app.services.novel_research_agent import NovelResearchAgent
    from tests.test_context_compaction import capability

    request = KnowledgeChatRequest(question="current task", traceId="compaction-run", userId=1)
    envelope = contracts.IntentEnvelope(domainStatus="IN_SCOPE", goal="current goal", constraints=["current constraint"])
    state = {"request": request, "intent_envelope": envelope.model_dump(),
             "runtime_config": {"harnessTaskCheckpointEnabled": True},
             "task_graph": {"tasks": [{"id": "remaining-task"}]}}
    assembler = ContextAssembler()
    compiled = assembler.compile_prompt_context(bundle=assembler.assemble(request),
                                               intent_plan=NovelResearchAgent._prompt_intent_plan_block(state))
    prefix = compiled["messages"]
    recent = [{"role": "user", "content": request.question},
              {"role": "assistant", "content": "", "tool_calls": [{"id": "recent", "type": "function", "function": {"name": "book.read", "arguments": "{}"}}]},
              {"role": "tool", "tool_call_id": "recent", "content": "evidence"}]
    messages = prefix + [{"role": "user", "content": "old " * 4000}, {"role": "assistant", "content": "old result"}] + recent
    compacted, dropped = ContextCompactor()._compact_provider_turns(
        messages, capability=capability(minimum_recent_turns=1), target_tokens=1000,
        meter=lambda value: sum(len(message["content"]) for message in value),
    )
    assert dropped > 0
    assert compacted[:len(prefix)] == prefix
    assert compacted[-len(recent):] == recent
    assert "remaining-task" in str(compacted)
