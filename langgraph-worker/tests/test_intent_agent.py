from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.services.intents import AnswerBoundary, Intent, IntentDecision, ToolNeeds
from app.services.runtime.intent_agent import IntentAgent


class IntentAgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_scan_opening_and_outline_routes_to_mixed_creation_research(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(
            question="帮我扫榜男频都市脑洞，再给开文建议和三卷大纲",
            mode="research",
        ))

        self.assertEqual(Intent.mixed_creation_research, decision.primaryIntent)
        self.assertIn(Intent.market_scan, decision.subIntents)
        self.assertIn(Intent.opening_strategy, decision.subIntents)
        self.assertIn(Intent.outline_building, decision.subIntents)
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertEqual(AnswerBoundary.market_evidence_plus_author_inference, decision.answerBoundary)

    async def test_book_followup_chapter_request_needs_book_or_chapter_evidence(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(
            question="这本书前三章怎么写才更抓人？",
            mode="research",
            contextSummary="上文正在讨论一本番茄都市脑洞作品。",
        ))

        self.assertIn(decision.primaryIntent, {Intent.chapter_outline, Intent.book_breakdown})
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration or decision.toolNeeds.needsBookResearch)

    async def test_project_premise_followup_uses_project_context(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(
            question="项目里的三端一体设定继续扩展一下",
            mode="research",
            projectId=12,
            contextSummary="项目设定：主角金手指是三端一体。",
        ))

        self.assertEqual(Intent.followup_context, decision.primaryIntent)
        self.assertTrue(decision.memoryPolicy.get("useProjectProfile"))
        self.assertTrue(decision.memoryPolicy.get("useThreadSummary"))

    async def test_plain_chitchat_is_out_of_scope(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(question="今天上海天气怎么样？", mode="research"))

        self.assertEqual(Intent.out_of_scope, decision.primaryIntent)
        self.assertEqual(AnswerBoundary.out_of_scope, decision.answerBoundary)

    async def test_recent_market_question_requires_latest_rank_policy(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(question="最近男频都市脑洞榜单趋势如何？", mode="research"))

        self.assertEqual("latest", decision.sourcePolicy.get("freshness"))
        self.assertFalse(decision.sourcePolicy.get("allowHistorical"))
        self.assertTrue(decision.sourcePolicy.get("requireSnapshotTime"))

    async def test_low_confidence_route_can_call_injected_llm_fallback(self) -> None:
        calls: list[str] = []

        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            calls.append(_request.question)
            return IntentDecision(
                primaryIntent=Intent.market_scan,
                subIntents=[Intent.opening_strategy],
                confidence=0.91,
                toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True),
                answerBoundary=AnswerBoundary.market_evidence_plus_author_inference,
                sourcePolicy={"freshness": "latest", "requireSnapshotTime": True},
                routingNotes=["llm:test"],
            )

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            llm_min_confidence=0.82,
        )

        decision = await agent.decide(KnowledgeChatRequest(
            question="which recent urban web novel topics should I write next",
            mode="research",
        ))

        self.assertEqual(["which recent urban web novel topics should I write next"], calls)
        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertIn("llm:test", decision.routingNotes)

    async def test_high_confidence_route_skips_llm_fallback(self) -> None:
        calls: list[str] = []

        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            calls.append(_request.question)
            return IntentDecision(primaryIntent=Intent.out_of_scope)

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            llm_min_confidence=0.82,
        )

        decision = await agent.decide(KnowledgeChatRequest(
            question="帮我看番茄男频都市脑洞新书榜Top10，榜一有什么趋势？",
            mode="research",
        ))

        self.assertEqual([], calls)
        self.assertEqual(Intent.market_scan, decision.primaryIntent)


if __name__ == "__main__":
    unittest.main()
