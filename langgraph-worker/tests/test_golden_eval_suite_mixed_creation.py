"""
Golden Eval Suite: Mixed Creation Research

Tests complex multi-intent tasks combining market scan + book breakdown + creative generation.
"""
from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone
from pathlib import Path

from app.models.knowledge import (
    BookProfile,
    ChapterMaterial,
    KnowledgeChatRequest,
    KnowledgeSource,
    RankLookupResult,
    RankResearchPack,
)
from app.services.evaluation import (
    GoldenEvalCase,
    GoldenEvalExpectedTrace,
    GoldenEvalRunner,
    RetrievalEvalThresholds,
    RuleBasedFaithfulnessEvaluator,
)
from app.services.novel_research_agent import NovelResearchAgent


CURRENT_RANK_SNAPSHOT_TIME = datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _is_specialist_call(call: dict) -> bool:
    messages = call.get("messages") or []
    text = "\n".join(str(message.get("content") or "") for message in messages if isinstance(message, dict))
    return "agent:" in text and "task summary:" in text


class GoldenEvalMixedCreationTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cases_file = Path(__file__).parent / "golden_cases" / "mixed_creation_cases.json"
        with cases_file.open("r", encoding="utf-8") as f:
            raw_cases = json.load(f)
        cls.cases = [cls._parse_case(raw) for raw in raw_cases]

    @classmethod
    def _parse_case(cls, raw: dict) -> GoldenEvalCase:
        return GoldenEvalCase(
            case_id=raw["case_id"],
            question=raw["question"],
            request_payload=raw.get("request_payload", {}),
            expected_intent=raw.get("expected_intent"),
            expected_answer_mode=raw.get("expected_answer_mode"),
            expected_sub_intents=set(raw.get("expected_sub_intents", [])),
            relevant_source_ids=set(raw.get("relevant_source_ids", [])),
            forbidden_claims=raw.get("forbidden_claims", []),
            retrieval_thresholds=RetrievalEvalThresholds(**raw.get("retrieval_thresholds", {})),
            expected_trace=cls._parse_expected_trace(raw.get("expected_trace", {})),
        )

    @classmethod
    def _parse_expected_trace(cls, raw: dict | None) -> GoldenEvalExpectedTrace:
        if not isinstance(raw, dict):
            return GoldenEvalExpectedTrace()
        return GoldenEvalExpectedTrace(
            required_tool_names=set(raw.get("required_tool_names", [])),
            required_source_types=set(raw.get("required_source_types", [])),
            required_trace_fields=set(raw.get("required_trace_fields", [])),
            required_source_policy_fields=set(raw.get("required_source_policy_fields", [])),
            required_evidence_statuses=set(raw.get("required_evidence_statuses", [])),
            required_answer_terms=set(raw.get("required_answer_terms", [])),
            forbidden_answer_patterns=set(raw.get("forbidden_answer_patterns", [])),
            require_valid_answer_boundary=bool(raw.get("require_valid_answer_boundary", False)),
            require_citations=bool(raw.get("require_citations", False)),
            forbid_memory_cross_project=bool(raw.get("forbid_memory_cross_project", False)),
            forbid_fallback=bool(raw.get("forbid_fallback", False)),
            require_provider_success=bool(raw.get("require_provider_success", False)),
            require_selected_experts=bool(raw.get("require_selected_experts", False)),
        )

    async def test_mixed_creation_001_rank_imitation_and_chapter_outline(self) -> None:
        """根据当前男频都市脑洞新书榜第一的书，模仿题材并给前三章细纲。"""
        case = self._find_case("mixed-creation-001")

        client = MixedCreationGoldenKnowledgeClient()
        provider = MixedCreationGoldenAnswerProvider()
        runner = GoldenEvalRunner(
            agent=NovelResearchAgent(knowledge_client=client, provider_client=provider),
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        )

        result = await runner.run_case(case)

        self.assertEqual("passed", result.status, result.failures)
        self.assertEqual("mixed_creation_research", result.intent)
        self.assertEqual("mixed_creation", result.answer_mode)
        self.assertGreaterEqual(result.retrieval_metrics["hit_rate_at_k"], 0.7)
        self.assertGreaterEqual(result.retrieval_metrics["context_recall_at_k"], 0.6)
        self.assertTrue(result.faithfulness["passed"])
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual(1, len(client.rank_pack_calls))
        self.assertEqual("latest", client.lookup_rank_calls[0]["freshness"])
        self.assertEqual("latest", client.rank_pack_calls[0]["freshness"])
        self.assertGreaterEqual(len(client.search_evidence_calls), 1)
        specialist_calls = [call for call in provider.invoke_calls if _is_specialist_call(call)]
        answer_calls = [call for call in provider.invoke_calls if not _is_specialist_call(call)]
        self.assertGreaterEqual(len(specialist_calls), 5)
        self.assertGreaterEqual(len(answer_calls), 1)

    async def test_production_trace_b41_mixed_snapshot_rank_tools_answers_with_contract(self) -> None:
        client = MixedSnapshotGoldenKnowledgeClient()
        provider = MixedCreationGoldenAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            traceId="b41ae9117abb4285b2ec17433749ebcc",
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势给我一些大纲，"
                "金手指采用三端一体的形态。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertNotEqual("insufficient_evidence", response.status)
        self.assertEqual("mixed_creation_research", response.resultJson["domainIntent"])
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        source_policy = response.resultJson["sourcePolicy"]
        contract = source_policy["evidenceContract"]
        self.assertFalse(source_policy["trendGateFailed"])
        self.assertEqual("mixed_structured_rank_snapshot", source_policy["trendGateOriginalReason"])
        self.assertIn(contract["status"], {"degraded_directional", "verified_latest"})
        self.assertEqual(9201, contract["selectedSnapshotGroup"]["snapshotId"])
        self.assertGreaterEqual(len(client.lookup_rank_calls), 1)
        self.assertGreaterEqual(len(client.rank_pack_calls), 1)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)

    async def test_production_trace_206_lookup_only_snapshotless_rows_answer_degraded(self) -> None:
        client = LookupOnlySnapshotlessGoldenKnowledgeClient()
        provider = MixedCreationGoldenAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            traceId="206bc0e218dc4c9ea0c3e7fbd88965a1",
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，"
                "可以给出我一些大纲吗，我设计是都市里有诸天万界外包来做特效，"
                "金手指采用“三端一体”的形态。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertNotEqual("insufficient_evidence", response.status)
        self.assertEqual("mixed_creation_research", response.resultJson["domainIntent"])
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        source_policy = response.resultJson["sourcePolicy"]
        contract = source_policy["evidenceContract"]
        self.assertTrue(source_policy["latestRankEvidenceDegraded"])
        self.assertEqual("missing_structured_rank_snapshot", source_policy["trendGateReason"])
        self.assertEqual("degraded_directional", contract["status"])
        self.assertTrue(any(warning["code"] == "missing_structured_rank_snapshot" for warning in contract["warnings"]))
        self.assertGreaterEqual(len(client.lookup_rank_calls), 1)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)

    async def test_reported_bottom_occupation_outsourcing_prompt_passes_quality_gate(self) -> None:
        case = self._find_case("mixed-creation-production-bottom-occupation-outsourcing")
        client = MixedSnapshotGoldenKnowledgeClient()
        provider = ProductionMixedCreationQualityProvider()
        runner = GoldenEvalRunner(
            agent=NovelResearchAgent(knowledge_client=client, provider_client=provider),
            faithfulness_evaluator=RuleBasedFaithfulnessEvaluator(),
        )

        result = await runner.run_case(case)

        self.assertEqual("passed", result.status, result.failures)
        self.assertEqual("mixed_creation_research", result.intent)
        self.assertEqual("mixed_creation", result.answer_mode)
        answer_calls = [call for call in provider.invoke_calls if not _is_specialist_call(call)]
        self.assertGreaterEqual(len(answer_calls), 2)

    async def test_mixed_creation_002_trend_scan_and_topic_strategy(self) -> None:
        """看一下最近男频都市脑洞风向，参考榜一卖点，给我一个不撞车的新题材。"""
        case = self._find_case("mixed-creation-002")

        self.assertEqual("mixed_creation_research", case.expected_intent)
        self.assertIn("opening_strategy", case.expected_sub_intents)
        self.assertIn("绝对不撞车", case.forbidden_claims)

    async def test_mixed_creation_003_character_worldbuilding_with_risk(self) -> None:
        """参考番茄男频都市脑洞榜前三，设计一个主角、金手指和世界观，再指出可能的毒点。"""
        case = self._find_case("mixed-creation-003")

        self.assertIn("character_design", case.expected_sub_intents)
        self.assertIn("worldbuilding", case.expected_sub_intents)
        self.assertGreaterEqual(len(case.relevant_source_ids), 3)

    def _find_case(self, case_id: str) -> GoldenEvalCase:
        for case in self.cases:
            if case.case_id == case_id:
                return case
        raise ValueError(f"Case {case_id} not found")


class MixedCreationGoldenKnowledgeClient:
    def __init__(self) -> None:
        self.lookup_rank_calls: list[dict] = []
        self.rank_pack_calls: list[dict] = []
        self.search_evidence_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        return [self._rank_one()]

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> RankResearchPack:
        self.rank_pack_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "chapter_limit_per_book": chapter_limit_per_book,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        return RankResearchPack(
            ranks=[self._rank_one()],
            books=[
                BookProfile(
                    bookId=101,
                    platform="fanqie",
                    bookName="我下午才营业",
                    author="测试作者",
                    intro="直播运营被裁后获得神级早餐系统，用现实职业压力反转成都市爽点。",
                    category="都市脑洞",
                    latestRankNo=1,
                    latestRankLabel="男频新书榜 / 都市脑洞 #1",
                )
            ],
            chapters=[
                ChapterMaterial(
                    sourceRefId=1001,
                    bookId=101,
                    bookName="我下午才营业",
                    platform="fanqie",
                    chapterNo=1,
                    title="第一章 被裁后的早餐摊",
                    content="第一章先给失业压力，再用早餐系统制造反差钩子。",
                ),
                ChapterMaterial(
                    sourceRefId=1002,
                    bookId=101,
                    bookName="我下午才营业",
                    platform="fanqie",
                    chapterNo=2,
                    title="第二章 差评变订单",
                    content="第二章让金手指解决现实痛点，同时放大围观传播。",
                ),
            ],
        )

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return []

    def _rank_one(self) -> RankLookupResult:
        return RankLookupResult(
            rankId=101,
            snapshotId=9001,
            snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
            platform="fanqie",
            channelCode="male-new",
            boardCode="urban-brain",
            channelName="男频新书榜",
            boardName="都市脑洞",
            category="都市脑洞",
            rankNo=1,
            bookId=101,
            bookName="我下午才营业",
            author="测试作者",
            intro="直播运营被裁后获得神级早餐系统，用现实职业压力反转成都市爽点。",
            sourceLabel="男频新书榜 / 都市脑洞 #1",
        )


class MixedSnapshotGoldenKnowledgeClient(MixedCreationGoldenKnowledgeClient):
    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        return [self._rank_item(index=index, snapshot_id=9101, prefix="Lookup") for index in range(1, 11)]

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> RankResearchPack:
        self.rank_pack_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "chapter_limit_per_book": chapter_limit_per_book,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        ranks = [self._rank_item(index=index, snapshot_id=9201, prefix="Pack") for index in range(1, 11)]
        return RankResearchPack(
            ranks=ranks,
            books=[
                BookProfile(
                    bookId=rank.bookId,
                    platform=rank.platform,
                    bookName=rank.bookName,
                    author=rank.author,
                    intro=rank.intro,
                    category=rank.category,
                )
                for rank in ranks
            ],
        )

    def _rank_item(self, *, index: int, snapshot_id: int, prefix: str) -> RankLookupResult:
        return RankLookupResult(
            rankId=snapshot_id * 100 + index,
            snapshotId=snapshot_id,
            snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
            platform="fanqie",
            channelCode="male-new",
            boardCode="urban-brain",
            channelName="男频新书榜",
            boardName="都市脑洞",
            category="都市脑洞",
            rankNo=index,
            bookId=snapshot_id + index,
            bookName=f"{prefix} Mixed Snapshot Book {index}",
            author=f"{prefix} Author",
            intro=f"{prefix} rank sample {index}",
            sourceLabel=f"男频新书榜 / 都市脑洞 #{index}",
        )


class LookupOnlySnapshotlessGoldenKnowledgeClient:
    def __init__(self) -> None:
        self.lookup_rank_calls: list[dict] = []
        self.search_evidence_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        freshness: str | None = None,
        allow_historical: bool | None = None,
        time_window_days: int | None = None,
        require_snapshot_time: bool | None = None,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "freshness": freshness,
            "allow_historical": allow_historical,
            "time_window_days": time_window_days,
            "require_snapshot_time": require_snapshot_time,
        })
        return [
            RankLookupResult(
                rankId=9800 + index,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=index,
                bookId=5800 + index,
                bookName=f"Lookup Only Snapshotless Rank Book {index}",
                author="Test Author",
                intro=f"rank lookup sample {index} has no snapshot metadata",
                sourceLabel=f"男频新书榜 / 都市脑洞 #{index}",
            )
            for index in range(1, 11)
        ]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return []


class MixedCreationGoldenAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": (
                "《我下午才营业》的可见卖点是把失业压力和早餐系统做成快节奏反转。[1]\n"
                "新题材可以改成外卖员听见差评未来：第一章失业危机，第二章能力首用，"
                "第三章用一次公开事件建立后续升级线。[3]"
            ),
            "token_used": 256,
        }


class ProductionMixedCreationQualityProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.answer_calls = 0

    async def invoke(self, **kwargs) -> dict:
        self.invoke_calls.append(kwargs)
        if _is_specialist_call(kwargs):
            return {
                "model_name": "deepseek-chat",
                "content": "agent summary: market and outline signal ready.",
                "token_used": 96,
            }
        self.answer_calls += 1
        if self.answer_calls == 1:
            return {
                "model_name": "deepseek-chat",
                "content": "围绕用户问题拆出主角困境，然后给前三章。[1]",
                "token_used": 64,
            }
        return {
            "model_name": "deepseek-chat",
            "content": (
                "## 市场判断\n"
                "这个底层职业都市脑洞方向可以做：榜单信号偏向现实压力、异常系统、公开事件传播，"
                "你的诸天万界外包做特效能把职业烟火气和高概念反差扣在一起。[1]\n\n"
                "## 核心钩子\n"
                "主角是影棚或短剧公司的底层职业特效小工，现实里接五毛特效单，夜里收到诸天万界外包工单；"
                "客户要的是仙门大战、深渊魔潮、末日机甲，但交付物必须伪装成都市片场特效。[2]\n\n"
                "## 三端一体\n"
                "创作者端拆镜头和预算，诸天万界生产端承接外包，观众/交付端把播放热度、甲方回款和异常反馈转成系统权限，"
                "三端一体让每次特效交付都能升级工具、召唤工种和议价权。[3]\n\n"
                "## 前三章\n"
                "第1章：主角被拖欠工资，接到第一份异界特效外包单。\n"
                "第2章：诸天万界临时工误以为这是低等幻境，情绪冲突带出爽点。\n"
                "第3章：样片爆火，甲方以为是廉价后期，业内开始追查素材来源。[4]\n\n"
                "## 十章方向\n"
                "前十章完成小单爆火、二单翻车、工种扩容、甲方压价、同行质疑、平台传播和第一次源文件危机，"
                "让特效外包变成持续商业闭环。"
            ),
            "token_used": 512,
        }


if __name__ == "__main__":
    unittest.main()
