from __future__ import annotations

import ast
import unittest
from pathlib import Path

from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse
from app.services.harness.contracts import EvidenceCommit, EvidenceDecision, EvidenceDecisionState
from app.services.harness.context_compaction import ContextCompactor, ModelContextCapability
from app.services.harness.data_access_planner import DataAccessPlanner
from app.services.harness.webnovel_harness import WebnovelHarness
from app.services.novel_research_agent import NovelResearchAgent
from tests.test_novel_research_agent import (
    CurrentStructuredRankTrendKnowledgeClient,
    FakeAnswerProvider,
    FakeKnowledgeClient,
)


class WebnovelHarnessTests(unittest.IsolatedAsyncioTestCase):
    async def test_harness_run_matches_facade_run(self) -> None:
        client = CurrentStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
        )

        facade = await agent.run(request)
        direct = await agent.harness.run(request)

        self.assertEqual(facade.status, direct.status)
        self.assertEqual(facade.answer, direct.answer)
        self.assertEqual(facade.resultJson.get("intent"), direct.resultJson.get("intent"))
        self.assertIsInstance(agent.harness, WebnovelHarness)

    async def test_harness_is_single_lifecycle_owner_for_simple_run(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        self.assertIs(agent.harness.runtime, agent)
        self.assertIs(agent.harness.graph, agent._graph)
        self.assertIs(agent.harness.agent_kernel, agent.agent_kernel)
        self.assertIs(agent.harness.intent_agent, agent.intent_agent)
        self.assertIs(agent.harness.skill_registry, agent.skill_registry)
        self.assertIs(agent.harness.capability_authorizer, agent.capability_authorizer)
        self.assertIs(agent.harness.data_access_planner, agent.data_access_planner)
        self.assertIsInstance(agent.data_access_planner, DataAccessPlanner)
        response = await agent.harness.run(KnowledgeChatRequest(question="你好"))
        self.assertIn(response.status, {"answered", "out_of_scope", "needs_clarification", "insufficient_evidence"})


    def test_harness_is_only_production_agent_kernel_composition_root(self) -> None:
        services_root = Path(__file__).resolve().parents[1] / "app" / "services"
        composition_root = services_root / "harness" / "webnovel_harness.py"
        offenders: list[str] = []

        for path in services_root.rglob("*.py"):
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            for node in ast.walk(tree):
                if not isinstance(node, ast.Call):
                    continue
                if isinstance(node.func, ast.Name) and node.func.id == "AgentKernel":
                    if path != composition_root:
                        offenders.append(f"{path.relative_to(services_root)}:{node.lineno}")

        self.assertEqual([], offenders)

    def test_harness_owns_knowledge_graph_compilation(self) -> None:
        services_root = Path(__file__).resolve().parents[1] / "app" / "services"
        facade_path = services_root / "novel_research_agent.py"
        harness_path = services_root / "harness" / "webnovel_harness.py"

        def state_graph_calls(path: Path) -> list[int]:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            return [
                node.lineno
                for node in ast.walk(tree)
                if isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "StateGraph"
            ]

        self.assertEqual([], state_graph_calls(facade_path))
        self.assertEqual(1, len(state_graph_calls(harness_path)))

    def test_knowledge_provider_transport_is_owned_by_kernel(self) -> None:
        services_root = Path(__file__).resolve().parents[1] / "app" / "services"
        allowed_transport_files = {
            services_root / "analysis_service.py",
            services_root / "harness" / "agent_kernel.py",
        }
        offenders: list[str] = []

        def dotted_name(node: ast.AST) -> str:
            if isinstance(node, ast.Name):
                return node.id
            if isinstance(node, ast.Attribute):
                prefix = dotted_name(node.value)
                return f"{prefix}.{node.attr}" if prefix else node.attr
            return ""

        for path in services_root.rglob("*.py"):
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            for node in ast.walk(tree):
                if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                    continue
                target = dotted_name(node.func)
                if node.func.attr not in {"invoke", "stream"} or "provider" not in target.lower():
                    continue
                if path not in allowed_transport_files:
                    offenders.append(f"{path.relative_to(services_root)}:{node.lineno}:{target}")

        self.assertEqual([], offenders)

    def test_commit_run_aligns_terminal_and_evidence(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        response = KnowledgeChatResponse(
            status="answered",
            answer="ok [1]",
            sources=[],
            resultJson={"answerStatus": "answered_with_evidence", "trace": {}},
        )
        rejected = EvidenceCommit(
            commitId="evidence:test",
            decisions=(
                EvidenceDecision(
                    evidenceId="citation:99",
                    decision=EvidenceDecisionState.REJECTED,
                    reasonCodes=("forged_citation",),
                ),
            ),
            canCommit=False,
            repairAllowed=False,
            reasonCodes=("forged_citation", "commit_blocked"),
        )
        finalized = agent.harness.commit_run(
            response=response,
            state={"memory_candidates": [{"scope": "project", "content": "note"}]},
            evidence_commit=rejected,
        )
        self.assertEqual("insufficient_evidence", finalized.status)
        self.assertEqual("insufficient_evidence", finalized.resultJson["status"])
        self.assertEqual("needs_data", finalized.resultJson["answerStatus"])
        self.assertEqual("insufficient_evidence", finalized.resultJson["trace"]["terminalStatus"])
        self.assertFalse(finalized.resultJson["evidenceCommit"]["canCommit"])
        self.assertEqual([{"scope": "project", "content": "note"}], finalized.resultJson["memoryCandidates"])

    def test_commit_run_rejects_malformed_evidence_commit_dict(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        response = KnowledgeChatResponse(
            status="answered",
            answer="ok",
            sources=[],
            resultJson={"answerStatus": "answered_with_evidence", "trace": {}},
        )

        finalized = agent.harness.commit_run(
            response=response,
            evidence_commit={"unexpected": "secret-payload"},
        )

        self.assertEqual("insufficient_evidence", finalized.status)
        self.assertEqual("insufficient_evidence", finalized.resultJson["status"])
        self.assertEqual("needs_data", finalized.resultJson["answerStatus"])
        self.assertEqual("insufficient_evidence", finalized.resultJson["trace"]["terminalStatus"])
        self.assertFalse(finalized.resultJson["evidenceCommit"]["canCommit"])
        self.assertEqual(
            ["malformed_evidence_commit"],
            finalized.resultJson["evidenceCommit"]["reasonCodes"],
        )
        self.assertNotIn("unexpected", finalized.resultJson["evidenceCommit"])
        self.assertNotIn("secret-payload", str(finalized.resultJson["trace"]))

    def test_commit_run_rejects_non_mapping_evidence_commit(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        response = KnowledgeChatResponse(
            status="answered",
            answer="ok",
            sources=[],
            resultJson={"answerStatus": "answered_with_evidence", "trace": {}},
        )

        finalized = agent.harness.commit_run(
            response=response,
            evidence_commit="secret-payload",  # type: ignore[arg-type]
        )

        self.assertEqual("insufficient_evidence", finalized.status)
        self.assertEqual(
            ["malformed_evidence_commit"],
            finalized.resultJson["evidenceCommit"]["reasonCodes"],
        )
        self.assertNotIn("secret-payload", str(finalized.resultJson))

    def test_commit_run_preserves_answer_without_evidence_commit(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        response = KnowledgeChatResponse(
            status="answered",
            answer="creative answer",
            sources=[],
            resultJson={"trace": {}},
        )

        finalized = agent.harness.commit_run(response=response, evidence_commit=None)

        self.assertEqual("answered", finalized.status)
        self.assertEqual("answered", finalized.resultJson["status"])
        self.assertEqual("answered_with_evidence", finalized.resultJson["answerStatus"])
        self.assertEqual("answered", finalized.resultJson["trace"]["terminalStatus"])
        self.assertNotIn("evidenceCommit", finalized.resultJson)

    async def test_stream_emits_sanitized_compaction_events_and_terminal_metadata(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=FakeKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        compactor = ContextCompactor(ModelContextCapability(
            context_window_tokens=5_000,
            max_output_tokens=2_000,
            reserved_output_tokens=300,
            safety_margin_tokens=200,
            target_ratio=0.62,
            minimum_recent_turns=2,
            max_summary_tokens=900,
        ))
        agent.context_compactor = compactor
        agent.harness.context_compactor = compactor
        history = [
            {
                "role": "user" if index % 2 == 0 else "assistant",
                "content": f"第 {index + 1} 条可见对话：" + ("设定与结论" * 500),
            }
            for index in range(16)
        ]

        events = [
            event
            async for event in agent.harness.stream(KnowledgeChatRequest(
                question="继续当前任务",
                history=history,
            ))
        ]

        event_names = [str(event.get("event") or "") for event in events]
        self.assertEqual(["context_compacting", "context_compacted"], event_names[:2])
        compacted_event = events[1]
        self.assertGreater(compacted_event["beforeInputTokens"], compacted_event["afterInputTokens"])
        self.assertNotIn("compactedSummary", compacted_event)
        done = next(event for event in events if event.get("event") == "done")
        metadata = done["data"]["resultJson"]["contextCompaction"]
        self.assertEqual("compacted", metadata["status"])
        self.assertIn("compactedSummary", metadata)
        self.assertNotIn("compactedSummary", str(events[:2]))
        result_json = done["data"]["resultJson"]
        self.assertEqual(
            result_json["contextBudget"],
            result_json["trace"]["contextBudget"],
        )


if __name__ == "__main__":
    unittest.main()
