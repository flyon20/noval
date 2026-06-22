from __future__ import annotations

import unittest

from app.services.intents import IntentRouter, classify
from app.services.intents.domain_intents import (
    AnswerBoundary,
    Intent,
    IntentDecision,
)


class IntentRouterTest(unittest.TestCase):
    def test_contract_exposes_required_fields_and_defaults(self) -> None:
        decision = IntentDecision(primaryIntent=Intent.market_scan)

        payload = decision.model_dump()

        self.assertEqual("market_scan", payload["primaryIntent"])
        self.assertEqual([], payload["subIntents"])
        self.assertEqual({}, payload["entities"])
        self.assertIn("needsRankData", payload["toolNeeds"])
        self.assertIn("routingNotes", payload)

    def test_classifies_market_scan_with_entities_and_rank_tool(self) -> None:
        decision = classify("帮我看番茄男频都市脑洞新书榜Top10，榜一有什么趋势？")

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertGreaterEqual(decision.confidence, 0.8)
        self.assertEqual("番茄", decision.entities.get("platform"))
        self.assertEqual("男频", decision.entities.get("channel"))
        self.assertEqual("都市脑洞", decision.entities.get("category"))
        self.assertEqual("Top10", decision.entities.get("chapterScope"))
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertEqual(AnswerBoundary.market_evidence, decision.answerBoundary)
        self.assertEqual("latest", decision.sourcePolicy.get("freshness"))
        self.assertFalse(decision.sourcePolicy.get("allowHistorical"))
        self.assertTrue(decision.sourcePolicy.get("requireSnapshotTime"))

    def test_recent_market_question_uses_latest_source_policy(self) -> None:
        decision = classify("最近都市脑洞还能不能写？")

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertEqual("latest", decision.sourcePolicy.get("freshness"))
        self.assertFalse(decision.sourcePolicy.get("allowHistorical"))
        self.assertTrue(decision.sourcePolicy.get("requireSnapshotTime"))

    def test_history_market_question_allows_time_window_source_policy(self) -> None:
        decision = classify("近30天都市脑洞有什么变化？")

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertEqual("time_window", decision.sourcePolicy.get("freshness"))
        self.assertTrue(decision.sourcePolicy.get("allowHistorical"))
        self.assertEqual(30, decision.sourcePolicy.get("timeWindowDays"))

    def test_exact_rank_question_prefers_market_scan_over_inspiration(self) -> None:
        decision = classify("最近男频新书榜都市脑洞排名第一的书是什么")

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertFalse(decision.toolNeeds.needsCreativeGeneration)
        self.assertEqual("都市脑洞", decision.entities.get("category"))
        self.assertIn("rule:market_scan", decision.routingNotes)

    def test_market_trend_with_character_dimension_stays_market_scan(self) -> None:
        decision = classify("女频甜宠榜单里Top10人设趋势如何？")

        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertFalse(decision.toolNeeds.needsCreativeGeneration)

    def test_opening_strategy_strong_markers_do_not_request_clarification(self) -> None:
        for question in [
            "女频现言怎么开文更容易进新书榜？",
            "给我三个新书方向，目标番茄男频小白读者",
            "这个脑洞怎么变成可写的新书项目？",
            "番茄女频开文，前三章应该怎么抓读者？",
        ]:
            with self.subTest(question=question):
                decision = classify(question)
                self.assertEqual(Intent.opening_strategy, decision.primaryIntent)
                self.assertNotIn("rule:ambiguous-intent", decision.routingNotes)
                self.assertTrue(decision.toolNeeds.needsCreativeGeneration)

    def test_opening_strategy_topic_recommendation_does_not_request_clarification(self) -> None:
        decision = classify("我如果要开新书都市脑洞的，你推荐什么题材")

        self.assertEqual(Intent.opening_strategy, decision.primaryIntent)
        self.assertNotIn("rule:ambiguous-intent", decision.routingNotes)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertTrue(decision.toolNeeds.needsSkillPack)
        self.assertEqual(AnswerBoundary.creative_inference, decision.answerBoundary)

    def test_pure_inspiration_with_platform_audience_is_not_mixed_market(self) -> None:
        decision = classify("发散20个适合番茄男频的爽文点子")

        self.assertEqual(Intent.inspiration_expand, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertFalse(decision.toolNeeds.needsRankData)

    def test_outline_request_with_broad_brainstorm_marker_prefers_outline(self) -> None:
        decision = classify("我要写都市脑洞，先搭一个30万字大纲")

        self.assertEqual(Intent.outline_building, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsOutlineMemory)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)

    def test_followup_markers_with_context_prefer_followup_over_new_research(self) -> None:
        cases = [
            ("这本再拆一下前十章钩子", "上文讨论一本番茄热书", ["用户要求拆书"]),
            ("这本书的卖点还能再提炼吗？", "上文拆解了一本热书", ["拆书"]),
        ]

        for question, context_summary, history in cases:
            with self.subTest(question=question):
                decision = classify(question, context_summary=context_summary, history=history)
                self.assertEqual(Intent.followup_context, decision.primaryIntent)
                self.assertTrue(decision.toolNeeds.needsOutlineMemory)

    def test_longform_opening_request_with_first_three_chapters_routes_to_chapter_outline(self) -> None:
        decision = classify(
            "帮我写3000字开头（前三章），按这个大纲设计："
            "书名示例：《我的逆袭模拟器》，一句话简介：被所有人当成废物的林北，激活了人生模拟器。"
        )

        self.assertEqual(Intent.chapter_outline, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertFalse(decision.toolNeeds.needsBookResearch)
        self.assertFalse(decision.toolNeeds.needsRankData)

    def test_split_outline_into_numbered_chapters_routes_to_chapter_outline(self) -> None:
        decision = classify("按大纲拆成10章，每章冲突和转折写清楚")

        self.assertEqual(Intent.chapter_outline, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsChapterEvidence)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)

    def test_book_reference_chapter_hook_question_stays_book_breakdown(self) -> None:
        decision = classify("这本书每章卡点和章末钩子怎么做的？")

        self.assertEqual(Intent.book_breakdown, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsBookResearch)
        self.assertTrue(decision.toolNeeds.needsVectorEvidence)

    def test_multintent_creation_and_research_routes_to_mixed(self) -> None:
        decision = classify("先看番茄女频新书榜Top10，再帮我开一本同题材新书")

        self.assertEqual(Intent.mixed_creation_research, decision.primaryIntent)
        self.assertIn(Intent.market_scan, decision.subIntents)
        self.assertIn(Intent.opening_strategy, decision.subIntents)
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertEqual(AnswerBoundary.market_evidence_plus_author_inference, decision.answerBoundary)

    def test_book_breakdown_then_creation_or_revision_routes_to_mixed(self) -> None:
        cases = [
            ("拆《直播算命》的爽点，再帮我扩一个同类脑洞", Intent.inspiration_expand),
            ("拆热书毒点，再给我改稿建议", Intent.revision_advice),
        ]

        for question, expected_sub_intent in cases:
            with self.subTest(question=question):
                decision = classify(question)
                self.assertEqual(Intent.mixed_creation_research, decision.primaryIntent)
                self.assertIn(Intent.book_breakdown, decision.subIntents)
                self.assertIn(expected_sub_intent, decision.subIntents)
                self.assertTrue(decision.toolNeeds.needsCreativeGeneration)

    def test_near_tie_scores_request_clarification_instead_of_guessing(self) -> None:
        class AmbiguousRouter(IntentRouter):
            def _score_intents(self, normalized, context_summary, history):  # type: ignore[override]
                scores = {
                    intent: 0
                    for intent in Intent
                    if intent not in {Intent.mixed_creation_research, Intent.out_of_scope}
                }
                scores[Intent.market_scan] = 3
                scores[Intent.opening_strategy] = 3
                return scores

        decision = AmbiguousRouter().classify("ambiguous webnovel trend or opening task")

        self.assertEqual(Intent.followup_context, decision.primaryIntent)
        self.assertLess(decision.confidence, 0.7)
        self.assertEqual(AnswerBoundary.needs_more_data, decision.answerBoundary)
        self.assertIn("rule:ambiguous-intent", decision.routingNotes)

    def test_outline_opening_with_example_title_routes_to_creative_chapter_outline(self) -> None:
        decision = classify(
            "帮我写3000字开头（前三章）\n"
            "大纲设计（以“模拟器+逆袭”为例）\n"
            "书名示例：《我的逆袭模拟器》\n"
            "第1章：主角林北被公司开除，女友提出分手，绝望之际激活人生模拟器。\n"
            "第2章：现实中使用高级编程技能解决前公司技术难题。\n"
            "第3章：前女友想复合，主角冷漠拒绝。"
        )

        self.assertEqual(Intent.chapter_outline, decision.primaryIntent)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)
        self.assertFalse(decision.toolNeeds.needsBookResearch)
        self.assertFalse(decision.toolNeeds.needsRankData)
        self.assertEqual(AnswerBoundary.outline_generation, decision.answerBoundary)

    def test_rank_reference_plus_outline_routes_to_mixed_creation_research(self) -> None:
        decision = classify("根据当前男频新书榜都市脑洞第一的书，我要模仿出对应的题材和大纲，该怎么设计")

        self.assertEqual(Intent.mixed_creation_research, decision.primaryIntent)
        self.assertIn(Intent.market_scan, decision.subIntents)
        self.assertIn(Intent.outline_building, decision.subIntents)
        self.assertTrue(decision.toolNeeds.needsRankData)
        self.assertTrue(decision.toolNeeds.needsCreativeGeneration)

    def test_followup_context_uses_context_and_history_markers(self) -> None:
        decision = classify(
            "刚才这个题材还能怎么升级？",
            context_summary="上文讨论番茄男频都市脑洞开书方向",
            history=["用户问过市场榜单和开书定位"],
        )

        self.assertEqual(Intent.followup_context, decision.primaryIntent)
        self.assertEqual("刚才这个题材", decision.entities.get("currentTopic"))
        self.assertTrue(decision.toolNeeds.needsOutlineMemory)

    def test_out_of_scope_has_high_confidence_for_non_webnovel_domains(self) -> None:
        for question in [
            "今天上海天气怎么样？",
            "帮我写一个 Python 快排函数",
            "苹果股票现在能买吗？",
            "感冒发烧吃什么药？",
            "北京三日游怎么安排？",
            "附近有什么好吃的火锅？",
        ]:
            with self.subTest(question=question):
                decision = classify(question)
                self.assertEqual(Intent.out_of_scope, decision.primaryIntent)
                self.assertGreaterEqual(decision.confidence, 0.9)
                self.assertEqual(AnswerBoundary.out_of_scope, decision.answerBoundary)

    def test_llm_json_fallback_is_structured_and_injectable_without_network(self) -> None:
        calls: list[str] = []

        def fake_llm(question: str, context_summary: str | None, history: list[str] | None) -> dict:
            calls.append(question)
            self.assertIsNone(context_summary)
            self.assertIsNone(history)
            return {
                "primaryIntent": "worldbuilding",
                "subIntents": [],
                "confidence": 0.67,
                "entities": {"stylePreference": "赛博修仙"},
                "toolNeeds": {"needsCreativeGeneration": True, "needsSkillPack": True},
                "answerBoundary": "creative_inference",
                "routingNotes": ["fallback"],
            }

        router = IntentRouter(llm_fallback=fake_llm)
        decision = router.classify("设计一个灵气网络化的门派生态")

        self.assertEqual(["设计一个灵气网络化的门派生态"], calls)
        self.assertEqual(Intent.worldbuilding, decision.primaryIntent)
        self.assertEqual("赛博修仙", decision.entities.get("stylePreference"))
        self.assertTrue(decision.toolNeeds.needsSkillPack)


    def test_v3_low_confidence_example_calls_injected_llm_fallback(self) -> None:
        calls: list[str] = []

        def fake_llm(question: str, context_summary: str | None, history: list[str] | None) -> dict:
            calls.append(question)
            return {
                "primaryIntent": "market_scan",
                "subIntents": ["opening_strategy"],
                "confidence": 0.86,
                "entities": {"category": "urban"},
                "toolNeeds": {"needsRankData": True, "needsCreativeGeneration": True},
                "answerBoundary": "market_evidence_plus_author_inference",
                "sourcePolicy": {"freshness": "latest", "requireSnapshotTime": True},
                "memoryPolicy": {"useProjectProfile": True, "useThreadSummary": True},
                "missingSlots": ["platform"],
                "routingNotes": ["llm:v3-fallback"],
            }

        router = IntentRouter(llm_fallback=fake_llm)
        decision = router.classify("which recent urban web novel topics should I write next")

        self.assertEqual(["which recent urban web novel topics should I write next"], calls)
        self.assertEqual(Intent.market_scan, decision.primaryIntent)
        self.assertIn(Intent.opening_strategy, decision.subIntents)
        self.assertEqual("latest", decision.sourcePolicy.get("freshness"))
        self.assertTrue(decision.memoryPolicy.get("useProjectProfile"))
        self.assertEqual(["platform"], decision.missingSlots)

    def test_v3_invalid_json_fallback_reverts_to_rule_decision(self) -> None:
        calls: list[str] = []

        def invalid_llm(question: str, context_summary: str | None, history: list[str] | None) -> str:
            calls.append(question)
            return "{not valid json"

        router = IntentRouter(llm_fallback=invalid_llm)
        decision = router.classify("which recent urban web novel topics should I write next")

        self.assertEqual(["which recent urban web novel topics should I write next"], calls)
        self.assertEqual(Intent.book_breakdown, decision.primaryIntent)
        self.assertIn("example:book_breakdown", decision.routingNotes)


if __name__ == "__main__":
    unittest.main()
