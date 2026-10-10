from __future__ import annotations

import json
from pathlib import Path
import unittest
from unittest.mock import patch

from app.models.knowledge import KnowledgeChatResponse
from scripts.agent_comparison import MeasuredProvider, compare_agents
from app.services.harness.budget import current_run_budget


class FakeProvider:
    def resolve_provider_profile(self, *args, **kwargs):
        return None

    async def invoke(self, **kwargs):
        return {"content": "林澈保管怀表，需要付出记忆的代价。", "model_name": "test-model", "token_used": 10,
                "usage": {"inputTokens": 7, "outputTokens": 3, "totalTokens": 10}}


class AgentComparisonTest(unittest.IsolatedAsyncioTestCase):
    async def test_same_model_limits_and_separate_actual_histories(self):
        requests = []

        class Agent:
            def __init__(self, *, runtime_mode, provider_client, **kwargs):
                self.mode = runtime_mode
                self.provider = provider_client

            async def run(self, request):
                requests.append((self.mode, request, current_run_budget().limits))
                await self.provider.invoke(model="test-model", max_tokens=9000)
                return KnowledgeChatResponse(status="answered", answer="answer-" + self.mode)

            async def aclose(self):
                pass

        fixture = {"id": "test", "cases": [{"id": "multi", "request": {"userId": 7},
                                             "turns": [{"question": "first"}, {"question": "continue"}]}]}
        provider = MeasuredProvider(FakeProvider(), model="test-model", output_tokens=512, max_calls=8)
        with patch("scripts.agent_comparison.NovelResearchAgent", Agent):
            report = await compare_agents(fixture, provider, case_limit=1, repeats=2, answer_length_hint="Keep the answer brief.")
        self.assertEqual(8, len(report["rows"]))
        self.assertEqual("manual_review_required", report["qualityVerdict"])
        self.assertEqual(["workflow", "autonomous", "workflow", "autonomous", "autonomous", "workflow", "autonomous", "workflow"],
                         [mode for mode, _, _ in requests])
        for mode, request, limits in requests[2:4] + requests[6:8]:
            self.assertEqual("answer-" + mode, request.history[-1]["content"])
            self.assertEqual("test-model", request.limits["intentModelName"])
            self.assertEqual((80_000, 8, 2), limits)
        for _, request, _ in requests[:2] + requests[4:6]:
            self.assertFalse(request.history)
        self.assertTrue(all(request.question.endswith("Keep the answer brief.") for _, request, _ in requests))
        self.assertEqual(8, len({request.traceId for _, request, _ in requests}))

    async def test_failed_attempt_is_not_complete_accounting(self):
        class FailingProvider(FakeProvider):
            async def invoke(self, **kwargs):
                raise ValueError("synthetic failure")

        provider = MeasuredProvider(FailingProvider(), model="test-model", output_tokens=512, max_calls=1)
        with self.assertRaises(ValueError):
            await provider.invoke(model="test-model", messages=[])
        self.assertEqual("failed", provider.calls[0]["status"])
        self.assertNotIn("tokens", provider.calls[0])
        self.assertIn("durationMs", provider.calls[0])
        self.assertIn("requestSha256", provider.calls[0])

    async def test_provider_enforces_model_and_call_budget(self):
        provider = MeasuredProvider(FakeProvider(), model="test-model", output_tokens=512, max_calls=1)
        with self.assertRaisesRegex(ValueError, "same model"):
            await provider.invoke(model="other")
        self.assertFalse(provider.calls)
        await provider.invoke(model="test-model", max_tokens=512)
        with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
            await provider.invoke(model="test-model")

    async def test_fixture_exercises_actual_workflow_and_autonomous_paths(self):
        fixture_path = Path(__file__).parent / "fixtures/agent_comparison_v1.json"
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        fixture["cases"] = [fixture["cases"][1]]
        fixture["cases"][0]["turns"] = fixture["cases"][0]["turns"][:1]
        provider = MeasuredProvider(FakeProvider(), model="test-model", output_tokens=512, max_calls=16)
        report = await compare_agents(fixture, provider, case_limit=1)
        self.assertEqual(2, len(report["rows"]))
        self.assertTrue(all(row["status"] != "error" for row in report["rows"]), report)
