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

    async def test_ranked_book_imitation_requests_reference_book_evidence(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(
            question="根据当前男频都市脑洞新书榜第一的书，模仿题材并给前三章细纲。",
            mode="research",
        ))

        self.assertEqual(Intent.mixed_creation_research, decision.primaryIntent)
        self.assertIn(Intent.market_scan, decision.subIntents)
        self.assertIn(Intent.book_breakdown, decision.subIntents)
        self.assertIn(Intent.chapter_outline, decision.subIntents)
        self.assertIn(Intent.inspiration_expand, decision.subIntents)
        self.assertTrue(decision.toolNeeds.needsBookResearch)
        self.assertTrue(decision.toolNeeds.needsChapterEvidence)

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

    async def test_model_first_cannot_override_project_foreshadowing_query(self) -> None:
        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            return IntentDecision(primaryIntent=Intent.out_of_scope, confidence=0.99)

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            model_first_enabled=True,
        )

        request = KnowledgeChatRequest(
            question="我前面一共有多少伏笔？",
            projectId=91,
            userId=7,
        )
        decision = await agent.decide(request)
        envelope = agent.to_envelope(decision, request=request)

        self.assertEqual(Intent.followup_context, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsChapterEvidence)
        self.assertIn("supervisor:project-foreshadowing-query-preserved", decision.routingNotes)
        self.assertIn("project_knowledge", envelope.operations)

    async def test_plain_chitchat_is_out_of_scope(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(question="今天上海天气怎么样？", mode="research"))

        self.assertEqual(Intent.out_of_scope, decision.primaryIntent)
        self.assertEqual(AnswerBoundary.out_of_scope, decision.answerBoundary)

    async def test_recent_market_question_requires_latest_rank_policy(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(question="最近男频都市脑洞榜单趋势如何？", mode="research"))

        self.assertEqual("ANALYSIS", decision.entities.get("marketRequestLevel"))
        self.assertEqual("time_window", decision.sourcePolicy.get("freshness"))
        self.assertTrue(decision.sourcePolicy.get("allowHistorical"))
        self.assertTrue(decision.sourcePolicy.get("requireSnapshotTime"))

        envelope = agent.to_envelope(decision)

        self.assertIn("market_research", envelope.operations)

    async def test_market_side_research_inherits_active_outline_goal_without_llm(self) -> None:
        calls: list[str] = []

        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            calls.append(_request.question)
            return IntentDecision(primaryIntent=Intent.out_of_scope)

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            llm_min_confidence=0.82,
        )

        request = KnowledgeChatRequest(
            question="看看最近男频都市脑洞新书榜",
            mode="research",
            conversationId="conversation-outline-1",
            contextSummary=(
                "最近意图：outline_building\n"
                "最近用户目标：继续完善三卷大纲和第一卷主线"
            ),
        )
        decision = await agent.decide(request)

        self.assertEqual([], calls)
        self.assertEqual(Intent.mixed_creation_research, decision.primaryIntent)
        self.assertEqual(
            [Intent.market_scan, Intent.outline_building],
            decision.subIntents,
        )
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertTrue(decision.toolNeeds.needsOutlineMemory)
        self.assertEqual("supporting_research", decision.entities.get("conversationTaskMode"))
        self.assertEqual("outline_building", decision.entities.get("activeGoalIntent"))
        self.assertIn("supervisor:active_goal_inherited", decision.routingNotes)

        envelope = agent.to_envelope(decision, request=request)

        self.assertEqual("context_followup", envelope.conversationMode)
        self.assertEqual("supervised_rules", envelope.classificationSource)
        self.assertIn("market_scan", envelope.operations)
        self.assertIn("outline_building", envelope.operations)

    async def test_explicit_standalone_market_request_does_not_inherit_outline_goal(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(
            question="先别结合大纲，只看最近男频都市脑洞新书榜",
            mode="research",
            conversationId="conversation-outline-2",
            contextSummary=(
                "最近意图：outline_building\n"
                "最近用户目标：继续完善三卷大纲和第一卷主线"
            ),
        ))

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertNotIn(Intent.outline_building, decision.subIntents)
        self.assertFalse(decision.toolNeeds.needsCreativeGeneration)
        self.assertNotIn("supervisor:active_goal_inherited", decision.routingNotes)

    async def test_plain_rank_list_does_not_escalate_to_market_research(self) -> None:
        agent = IntentAgent()

        decision = await agent.decide(KnowledgeChatRequest(
            question="男频都市脑洞新书榜Top10有哪些书？",
            mode="research",
        ))
        envelope = agent.to_envelope(decision)

        self.assertEqual("LIST", decision.entities.get("marketRequestLevel"))
        self.assertEqual("latest", decision.sourcePolicy.get("freshness"))
        self.assertNotIn("market_research", envelope.operations)

    async def test_legacy_market_decision_with_vector_evidence_keeps_market_research(self) -> None:
        agent = IntentAgent()
        decision = IntentDecision(
            primaryIntent=Intent.market_scan,
            toolNeeds=ToolNeeds(needsRankData=True, needsVectorEvidence=True),
        )

        envelope = agent.to_envelope(decision)

        self.assertIn("market_research", envelope.operations)

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

    async def test_model_first_mode_calls_llm_for_high_confidence_domain_request(self) -> None:
        calls: list[str] = []

        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            calls.append(_request.question)
            return IntentDecision(
                primaryIntent=Intent.market_scan,
                confidence=0.97,
                routingNotes=["llm:model-first"],
            )

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            llm_min_confidence=0.82,
            model_first_enabled=True,
        )

        decision = await agent.decide(KnowledgeChatRequest(
            question="男频都市脑洞新书榜最近热门题材",
            mode="research",
        ))

        self.assertEqual(["男频都市脑洞新书榜最近热门题材"], calls)
        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertEqual("llm_primary", agent.to_envelope(decision).classificationSource)

    async def test_model_first_preserves_explicit_previous_answer_reference_with_history(self) -> None:
        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            return IntentDecision(
                primaryIntent=Intent.book_breakdown,
                confidence=0.98,
                entities={"bookName": "沿用上一问"},
                toolNeeds=ToolNeeds(
                    needsBookResearch=True,
                    needsVectorEvidence=True,
                    needsChapterEvidence=True,
                ),
                answerBoundary=AnswerBoundary.book_evidence_plus_craft_extraction,
                routingNotes=["llm:model-first"],
            )

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            model_first_enabled=True,
        )
        request = KnowledgeChatRequest(
            question="沿用上一问的设定，只用一句话回答：第二章章末收到的陌生短信原文是什么？",
            mode="research",
            contextSummary="上一轮完成了都市脑洞开篇方案。",
            history=[
                {"role": "user", "content": "请设计都市脑洞开篇方案。"},
                {"role": "assistant", "content": "第二章末收到短信：你用的系统，是我扔掉的。"},
            ],
        )

        decision = await agent.decide(request)

        self.assertEqual(Intent.followup_context, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsOutlineMemory)
        self.assertFalse(decision.toolNeeds.needsBookResearch)
        self.assertIn("supervisor:explicit_context_reference_preserved", decision.routingNotes)

        without_history = await agent.decide(request.model_copy(update={
            "contextSummary": None,
            "history": [],
        }))
        self.assertEqual(Intent.followup_context, without_history.primaryIntent)
        self.assertFalse(without_history.toolNeeds.needsBookResearch)

    async def test_context_bundle_thread_summary_is_used_for_followup_routing(self) -> None:
        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            return IntentDecision(
                primaryIntent=Intent.book_breakdown,
                confidence=0.98,
                entities={"bookName": "刚才这个题材"},
                toolNeeds=ToolNeeds(needsBookResearch=True, needsVectorEvidence=True),
                answerBoundary=AnswerBoundary.book_evidence_plus_craft_extraction,
                routingNotes=["llm:model-first"],
            )

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            model_first_enabled=True,
        )
        decision = await agent.decide(KnowledgeChatRequest(
            question="刚才这个题材还能怎么升级？",
            contextBundle={
                "threadSummary": {
                    "scope": "thread",
                    "content": {
                        "summary": "上一轮讨论番茄男频都市脑洞开书方向。",
                        "history": [
                            {"role": "assistant", "content": "已经确定福娃+家庭群像的题材方向。"},
                        ],
                    },
                },
            },
        ))

        self.assertEqual(Intent.followup_context, decision.primaryIntent)
        self.assertFalse(decision.toolNeeds.needsBookResearch)
        self.assertIn("supervisor:explicit_context_reference_preserved", decision.routingNotes)

    async def test_model_first_preserves_contextual_market_taxonomy_question(self) -> None:
        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            return IntentDecision(
                primaryIntent=Intent.book_breakdown,
                confidence=0.98,
                entities={"bookName": "福娃"},
                toolNeeds=ToolNeeds(needsBookResearch=True, needsVectorEvidence=True),
                answerBoundary=AnswerBoundary.book_evidence_plus_craft_extraction,
                routingNotes=["llm:model-first"],
            )

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            model_first_enabled=True,
        )
        decision = await agent.decide(KnowledgeChatRequest(
            question="为什么这次没看到福娃，是不火吗？",
            contextBundle={
                "threadSummary": {
                    "scope": "thread",
                    "content": {
                        "summary": "上一轮范围是番茄男频都市脑洞新书榜。",
                    },
                },
            },
        ))

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertEqual("taxonomy_absence", decision.entities.get("marketQuestionType"))
        self.assertFalse(decision.toolNeeds.needsBookResearch)
        self.assertIn("supervisor:market_taxonomy_preserved", decision.routingNotes)

    async def test_model_first_mode_still_skips_clear_out_of_scope_request(self) -> None:
        calls: list[str] = []

        async def fallback(_request: KnowledgeChatRequest, _decision: IntentDecision) -> IntentDecision:
            calls.append(_request.question)
            return IntentDecision(primaryIntent=Intent.market_scan)

        agent = IntentAgent(
            llm_fallback=fallback,
            llm_fallback_enabled=True,
            model_first_enabled=True,
        )

        decision = await agent.decide(KnowledgeChatRequest(question="上海今天天气如何？", mode="research"))

        self.assertEqual([], calls)
        self.assertEqual(Intent.out_of_scope, decision.primaryIntent)

    def test_to_envelope_keeps_only_audit_codes_and_normalizes_string_constraints(self) -> None:
        agent = IntentAgent()
        decision = IntentDecision(
            primaryIntent=Intent.market_scan,
            confidence=0.91,
            entities={
                "constraints": "只分析男频新书榜",
                "category": "都市脑洞",
            },
            routingNotes=[
                "rule:market-scan",
                "llm:fallback-classifier",
                "supervisor:source-policy-repaired",
                "模型认为用户可能还想看其他内容",
                "task_graph:rescued_scope",
            ],
        )

        envelope = agent.to_envelope(decision)

        self.assertEqual(("只分析男频新书榜",), envelope.constraints)
        self.assertEqual(
            (
                "rule:market-scan",
                "llm:fallback-classifier",
                "supervisor:source-policy-repaired",
            ),
            envelope.notes,
        )
        self.assertEqual("llm_fallback", envelope.classificationSource)
        self.assertNotIn("constraints", envelope.entities)


if __name__ == "__main__":
    unittest.main()
