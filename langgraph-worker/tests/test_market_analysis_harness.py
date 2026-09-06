from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone
from typing import Any

from app.config import settings
from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource, RankLookupResult
from app.services.novel_research_agent import NovelResearchAgent


class TwoSnapshotRankClient:
    def __init__(self) -> None:
        self.lookup_rank_calls: list[dict[str, Any]] = []
        self.current_time = datetime.now(timezone.utc).replace(microsecond=0)
        self.previous_time = self.current_time - timedelta(days=14)

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
        current = [
            self._row(
                snapshot_id=200,
                snapshot_time=self.current_time,
                rank_no=index,
                book_id=1000 + index,
                book_name=f"当前作品{index:02d}",
                freshness="FRESH",
                historical_reference=freshness == "time_window",
            )
            for index in range(1, 31)
        ]
        if freshness != "time_window" or not allow_historical:
            return current[:limit]
        previous = [
            self._row(
                snapshot_id=100,
                snapshot_time=self.previous_time,
                rank_no=index,
                book_id=(1000 + index) if index <= 10 else (2000 + index),
                book_name=f"当前作品{index:02d}" if index <= 10 else f"历史作品{index:02d}",
                freshness="STALE",
                historical_reference=True,
            )
            for index in range(1, 31)
        ]
        return (current + previous)[:limit]

    async def search_evidence(self, **_kwargs: Any) -> list[Any]:
        return []

    def _row(
        self,
        *,
        snapshot_id: int,
        snapshot_time: datetime,
        rank_no: int,
        book_id: int,
        book_name: str,
        freshness: str,
        historical_reference: bool,
    ) -> RankLookupResult:
        return RankLookupResult(
            rankId=snapshot_id * 100 + rank_no,
            snapshotId=snapshot_id,
            snapshotTime=snapshot_time.isoformat(),
            platform="fanqie",
            channelCode="male-new",
            boardCode="262",
            channelName="男频新书榜",
            boardName="都市脑洞",
            category="都市脑洞",
            rankNo=rank_no,
            bookId=book_id,
            bookName=book_name,
            author=f"作者{rank_no:02d}",
            intro=f"第{rank_no}本作品的题材简介与核心钩子。",
            sourceLabel=f"男频新书榜 / 都市脑洞 #{rank_no}",
            freshness=freshness,
            historicalReference=historical_reference,
        )


class MarketHarnessProvider:
    def __init__(self, *, list_mode: bool = False) -> None:
        self.list_mode = list_mode
        self.invoke_calls: list[dict[str, Any]] = []
        self.returned_contents: list[str] = []

    async def invoke(self, **kwargs: Any) -> dict[str, Any]:
        self.invoke_calls.append(kwargs)
        prompt = self._prompt_text(kwargs.get("messages") or [])
        if "MARKET_EVIDENCE_ANALYSIS_CONTRACT" in prompt:
            content = (
                '{"coverage":{"current":30,"previous":30},'
                '"topicGroups":[{"name":"校园/高考","count":8},{"name":"公共职业/国家合作","count":6}],'
                '"retentionRate":0.333,"stableMechanic":"低身份误判+不可能结果+群体反馈"}'
            )
        elif self.list_mode:
            rows = "\n".join(
                f"{index}. 《当前作品{index:02d}》，作者{index:02d}。[{index}]"
                for index in range(1, 11)
            )
            content = (
                f"## 榜单结果\n{rows}\n\n"
                "## 热度观察\n当前展示同一最新快照 Top10，排名不等同于长期阅读量。[1]\n\n"
                "## 总结\n当前榜首为《当前作品01》。[1]"
            )
        else:
            content = (
                "## 热门题材分布\n"
                "| 题材主壳 | 数量 | 判断 |\n| --- | ---: | --- |\n"
                "| 校园/高考 | 8 本 | 当前连续性最强 |\n"
                "| 公共职业/国家合作 | 6 本 | 适合扩大公共事件 |\n\n"
                "## 跨快照变化\n"
                "当前 Top30 与上一快照共有 10 本，留存率 33.3%；《当前作品01》仍在头部。[1][2]\n\n"
                "## 可迁移结构\n"
                "稳定结构是低身份或被误判的主角，当场做出不可能结果，再由明确人群形成集体反馈。\n\n"
                "当前更值得吸收的是校园高考的强反差，以及公共职业、国家合作的扩张能力，而不是照抄题材外壳。[1]"
            )
        self.returned_contents.append(content)
        return {
            "model_name": "deepseek-v4-pro",
            "content": content,
            "token_used": 160,
            "usage": {"promptTokens": 120, "completionTokens": 40, "totalTokens": 160},
        }

    def _prompt_text(self, messages: list[Any]) -> str:
        parts: list[str] = []
        for message in messages:
            if isinstance(message, dict):
                parts.append(str(message.get("content") or ""))
            else:
                parts.append(str(getattr(message, "content", "")))
        return "\n".join(parts)


class MarketAnalysisHarnessTest(unittest.IsolatedAsyncioTestCase):
    def test_external_chapter_analysis_defaults_to_five_and_honors_user_limit(self) -> None:
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        state = {"source_policy": {"freshness": "any"}}

        default_context = agent._task_tool_context(
            KnowledgeChatRequest(question="分析这本书前几章", bookName="测试书", mode="research"),
            state,
        )
        explicit_context = agent._task_tool_context(
            KnowledgeChatRequest(
                question="分析这本书前十章",
                bookName="测试书",
                mode="research",
                limits={"chapterLimit": 10, "analysisLimit": 10},
            ),
            state,
        )

        self.assertEqual(5, default_context["chapterLimit"])
        self.assertEqual(5, default_context["analysisLimit"])
        self.assertEqual(10, explicit_context["chapterLimit"])
        self.assertEqual(10, explicit_context["analysisLimit"])

    def test_incomplete_current_snapshot_disables_historical_comparison(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        current = [
            self._source(300, now, index, f"当前作品{index}", "校园高考系统")
            for index in range(1, 13)
        ]
        previous = [
            self._source(200, now - timedelta(days=7), index, f"历史作品{index}", "国家合作异能")
            for index in range(1, 31)
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())

        payload = agent._market_snapshot_analysis_payload(current + previous, requested_current_count=30)

        self.assertEqual(12, payload["currentCount"])
        self.assertEqual(18, payload["coverageGap"])
        self.assertFalse(payload["currentCoverageComplete"])
        self.assertFalse(payload["comparisonSupported"])
        self.assertIsNone(payload["retentionRate"])
        self.assertEqual([], payload["rankChanges"])

    def test_default_top30_incomplete_latest_snapshot_requires_refresh(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        current = [
            self._source(300, now, index, f"当前作品{index}", "校园高考系统")
            for index in range(1, 11)
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        request = KnowledgeChatRequest(
            question="男频都市脑洞新书榜最近热门题材",
            reasoningMode="deep",
        )

        policy = agent._build_trend_source_policy(
            request,
            current,
            state={
                "request": request,
                "source_policy": {
                    "freshness": "time_window",
                    "allowHistorical": True,
                    "timeWindowDays": 30,
                    "requireSnapshotTime": True,
                    "currentRankLimit": 30,
                    "snapshotCount": 2,
                },
            },
        )

        self.assertTrue(policy["trendGateFailed"])
        self.assertEqual("incomplete_structured_rank_snapshot", policy["trendGateReason"])
        self.assertEqual(10, policy["structuredRankCount"])
        self.assertEqual(30, policy["currentRankLimit"])

    def test_latest_policy_rejects_unexpected_historical_snapshot(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        current = [
            self._source(300, now, index, f"当前作品{index}", "校园高考系统")
            for index in range(1, 4)
        ]
        previous = [
            self._source(200, now - timedelta(days=7), index, f"历史作品{index}", "国家合作异能")
            for index in range(1, 4)
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        request = KnowledgeChatRequest(
            question="男频都市脑洞新书榜Top3有哪些书？",
            mode="research",
        )

        policy = agent._build_trend_source_policy(
            request,
            current + previous,
            state={
                "request": request,
                "source_policy": {
                    "freshness": "latest",
                    "allowHistorical": False,
                    "currentRankLimit": 3,
                    "requestedSnapshotCount": 1,
                },
            },
        )

        self.assertTrue(policy["trendGateFailed"])
        self.assertEqual("mixed_structured_rank_snapshot", policy["trendGateReason"])

    def test_historical_projection_keeps_current_and_one_baseline(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        sources = [
            *[
                self._source(300, now, index, f"当前作品{index}", "校园高考系统")
                for index in range(1, 4)
            ],
            *[
                self._source(200, now - timedelta(days=7), index, f"基线作品{index}", "国家合作异能")
                for index in range(1, 4)
            ],
            *[
                self._source(100, now - timedelta(days=14), index, f"更早作品{index}", "传统都市异能")
                for index in range(1, 4)
            ],
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        request = KnowledgeChatRequest(
            question="近30天男频都市脑洞新书榜有什么变化？",
            mode="research",
        )
        policy = agent._build_trend_source_policy(
            request,
            sources,
            state={
                "request": request,
                "source_policy": {
                    "freshness": "time_window",
                    "allowHistorical": True,
                    "timeWindowDays": 30,
                    "currentRankLimit": 3,
                    "requestedSnapshotCount": 2,
                },
            },
        )

        projected = agent._project_current_rank_snapshot_sources(sources, policy)

        self.assertFalse(policy["trendGateFailed"], policy)
        self.assertEqual({200, 300}, {source.snapshotId for source in projected})
        self.assertEqual(
            {False},
            {source.historicalReference for source in projected if source.snapshotId == 300},
        )
        self.assertEqual(
            {True},
            {source.historicalReference for source in projected if source.snapshotId == 200},
        )

    def test_market_snapshot_payload_skips_incomplete_intermediate_baseline(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        current = [
            self._source(300, now, index, f"共同作品{index}", "校园高考系统")
            for index in range(1, 31)
        ]
        incomplete = [
            self._source(250, now - timedelta(days=3), index, f"中间作品{index}", "职场神豪")
            for index in range(1, 11)
        ]
        baseline = [
            self._source(200, now - timedelta(days=14), index, f"共同作品{index}", "校园高考系统")
            for index in range(1, 31)
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())

        payload = agent._market_snapshot_analysis_payload(
            current + incomplete + baseline,
            requested_current_count=30,
        )

        self.assertEqual([300, 200], [snapshot["snapshotId"] for snapshot in payload["snapshots"]])
        self.assertTrue(payload["comparisonSupported"])
        self.assertEqual(1.0, payload["retentionRate"])

    def test_market_topic_group_counts_are_deterministic_and_cover_current_rows(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        sources = [
            self._source(300, now, 1, "高考出分前一晚", "老师和学生共同见证"),
            self._source(300, now, 2, "系统想害我，我选择上交国家", "国家合作"),
            self._source(300, now, 3, "只因我写了诛仙", "文娱创作全网爆火"),
            self._source(300, now, 4, "给员工发超跑", "公司经营神豪系统"),
            self._source(300, now, 5, "全球灵气复苏", "超凡异能曝光"),
            self._source(300, now, 6, "仙尊心声被全家听见", "反派真少爷"),
            self._source(300, now, 7, "重生后，成了妹妹的专属爱物", "她的世界崩塌，病娇妹妹开始反转"),
            self._source(300, now, 8, "三棍打散兄妹情", "家族兄妹冲突持续升级"),
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())

        payload = agent._market_snapshot_analysis_payload(sources, requested_current_count=8)
        counts = {item["name"]: item["count"] for item in payload["topicGroups"]}

        self.assertEqual(8, sum(counts.values()))
        self.assertEqual(1, counts["校园/高考/教师"])
        self.assertEqual(1, counts["公共职业/国家合作"])
        self.assertEqual(1, counts["文娱/内容创作"])
        self.assertEqual(1, counts["经营/神豪/职场"])
        self.assertEqual(1, counts["超凡/全球异常"])
        self.assertEqual(3, counts["身份/家族/感情反转"])

    def test_answer_quality_rejects_cross_snapshot_claims_without_valid_baseline(self) -> None:
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        source = self._source(
            300,
            datetime.now(timezone.utc).replace(microsecond=0),
            1,
            "高考出分前一晚",
            "校园高考系统",
        )
        answer = (
            "## 热门题材分布\n| 题材 | 数量 |\n| --- | ---: |\n| 校园高考 | 1 |\n\n"
            "当前只覆盖 1 本同一快照作品，但仍宣称跨快照留存率 33.3%，并判断该方向持续上升。"
            "这是没有历史基线支持的趋势结论，不应通过质量门。[1]" * 3
        )

        self.assertFalse(agent._market_analysis_answer_quality(
            answer,
            [source],
            "ANALYSIS",
            {"comparisonSupported": False, "currentCount": 1, "requestedCurrentCount": 30},
        ))

    def test_answer_quality_rejects_raw_rank_dump_and_malformed_gfm_table(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        sources = [
            self._source(300, now, index, f"当前作品{index:02d}", "校园高考系统")
            for index in range(1, 31)
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        raw_rows = "\n".join(
            f"{index}. 《当前作品{index:02d}》：榜单原始简介。[{index}]"
            for index in range(1, 31)
        )
        raw_dump = (
            "## 题材分布\n| 题材 | 数量 |\n| --- | ---: |\n| 校园高考 | 11 |[1]\n\n"
            "## 跨快照变化\nTop30 留存率为 40%，存在明确排名变化。[1]\n\n"
            f"## 榜单明细\n{raw_rows}\n\n## 数据范围\n覆盖当前 Top30 与历史快照。[1]"
        )
        malformed_table = (
            "## 题材分布\n| 题材主壳 | 数量 | 代表作 |[1]\n| --- | ---: | --- |\n"
            "| 校园高考 | 11 | 《当前作品01》 |\n\n"
            "## 跨快照变化\nTop30 留存率为 40%，排名变化明确。[1]\n\n"
            "## 数据范围\n覆盖当前 30 本与一个完整历史快照。[1]"
        )
        metrics = {
            "comparisonSupported": True,
            "currentCount": 30,
            "requestedCurrentCount": 30,
        }

        self.assertFalse(agent._market_analysis_answer_quality(raw_dump, sources, "ANALYSIS", metrics))
        self.assertFalse(agent._market_analysis_answer_quality(malformed_table, sources, "ANALYSIS", metrics))

    def test_citation_repair_keeps_gfm_table_structure_valid(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        source = self._source(300, now, 1, "当前作品01", "校园高考系统")
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        answer = (
            "## 题材分布\n"
            "| 题材主壳 | 数量 | 代表作 |\n"
            "| --- | ---: | --- |\n"
            "| 校园/高考 | 11 | 《当前作品01》 |"
        )

        repaired = agent._repair_citations_in_place(answer, [source])
        table_lines = [line for line in repaired.splitlines() if line.startswith("|")]

        self.assertEqual("| 题材主壳 | 数量 | 代表作 |", table_lines[0])
        self.assertTrue(all(line.endswith("|") for line in table_lines))
        self.assertIn("《当前作品01》 [1] |", table_lines[2])

    def test_low_quality_composition_reuses_market_analysis_without_raw_top30_dump(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        sources = [
            self._source(300, now, index, f"当前作品{index:02d}", "校园高考系统")
            for index in range(1, 31)
        ]
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        market_content = (
            "## 热门题材分布\n"
            "| 题材主壳 | 数量 | 代表作 |\n| --- | ---: | --- |\n"
            "| 校园/高考/教师 | 11 | 《当前作品01》、《当前作品03》 |\n"
            "| 公共职业/国家合作 | 6 | 《当前作品06》、《当前作品12》 |\n\n"
            "## 跨快照变化\n当前 Top30 与上一完整快照共有 12 本，留存率 40%；"
            "《当前作品01》保持头部，《当前作品06》上升明显。\n\n"
            "## 可迁移结构\n高频结构是低身份或被误判的主角，当场做出不可能结果，"
            "再让学校、机构或公众形成可持续升级的群体反馈。\n\n"
            "## 数据范围\n本次覆盖同一榜单当前 30 本和一个更早的完整 30 本快照；"
            "排名仅代表快照位置，不等同于长期阅读热度。"
        )
        state = {
            "intent_decision": {
                "primaryIntent": "market_scan",
                "entities": {"marketRequestLevel": "ANALYSIS"},
            },
            "market_evidence_analysis": {
                "status": "succeeded",
                "content": market_content,
                "currentCount": 30,
                "requestedCurrentCount": 30,
                "previousCount": 30,
                "comparisonSupported": True,
                "retentionRate": 0.4,
            },
        }

        answer = agent._ensure_rank_lead_for_trend_answer(
            "当前榜单值得关注。[1]",
            sources,
            request=KnowledgeChatRequest(question="男频都市脑洞新书榜最近热门题材"),
            state=state,
        )

        self.assertIn("## 热门题材分布", answer)
        self.assertIn("## 可迁移结构", answer)
        self.assertNotIn("## 榜单明细", answer)
        self.assertNotIn("30. 《当前作品30》", answer)
        self.assertTrue(all(
            not line.startswith("|") or line.endswith("|")
            for line in answer.splitlines()
        ))

    def test_analysis_tool_context_uses_two_bounded_top30_snapshots(self) -> None:
        agent = NovelResearchAgent(knowledge_client=TwoSnapshotRankClient())
        request = KnowledgeChatRequest(
            question="男频都市脑洞新书榜最近热门题材",
            mode="research",
            reasoningMode="deep",
        )
        state = {
            "intent_decision": {
                "primaryIntent": "market_scan",
                "entities": {"marketRequestLevel": "ANALYSIS"},
            },
            "source_policy": {
                "freshness": "time_window",
                "allowHistorical": True,
                "timeWindowDays": 30,
                "currentRankLimit": 30,
                "snapshotCount": 2,
                "requireSnapshotTime": True,
            },
        }

        context = agent._task_tool_context(request, state)

        self.assertEqual(100, context["limit"])
        self.assertEqual("time_window", context["freshness"])
        self.assertTrue(context["allowHistorical"])
        self.assertEqual(30, context["timeWindowDays"])

    async def test_deep_analysis_uses_two_model_turns_and_preserves_analysis_answer(self) -> None:
        client = TwoSnapshotRankClient()
        provider = MarketHarnessProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="男频都市脑洞新书榜最近热门题材",
            mode="research",
            reasoningMode="deep",
            reasoningEffort="high",
            contextSummary="用户项目是五毛特效订单召唤诸天人物打工。",
        )

        events = [event async for event in agent.stream(request)]
        done = events[-1]["data"]

        self.assertEqual("answered", done["status"], done["resultJson"])
        self.assertEqual("answered_with_evidence", done["resultJson"]["answerStatus"])
        self.assertEqual(2, len(provider.invoke_calls))
        self.assertEqual(settings.intent_model, provider.invoke_calls[0]["model"])
        self.assertTrue(all(call["reasoning_mode"] == "deep" for call in provider.invoke_calls))
        self.assertTrue(all(call["reasoning_effort"] == "high" for call in provider.invoke_calls))
        self.assertEqual(100, client.lookup_rank_calls[0]["limit"])
        self.assertEqual("time_window", client.lookup_rank_calls[0]["freshness"])
        self.assertTrue(client.lookup_rank_calls[0]["allow_historical"])
        self.assertEqual(30, client.lookup_rank_calls[0]["time_window_days"])
        self.assertGreater(len(done["sources"]), 30)
        self.assertLessEqual(len(done["sources"]), 60)
        self.assertEqual({100, 200}, {source["snapshotId"] for source in done["sources"]})
        self.assertEqual(
            {False},
            {
                source["historicalReference"]
                for source in done["sources"]
                if source["snapshotId"] == 200
            },
        )
        self.assertEqual(
            {True},
            {
                source["historicalReference"]
                for source in done["sources"]
                if source["snapshotId"] == 100
            },
        )
        self.assertEqual(
            "ANALYSIS",
            done["resultJson"]["intentDecision"]["entities"]["marketRequestLevel"],
        )
        candidate = provider.returned_contents[1]
        self.assertGreaterEqual(len(candidate), 180)
        self.assertTrue(agent._has_valid_citation(candidate, len(done["sources"])))
        self.assertTrue(any(marker in candidate for marker in ("题材", "流派", "赛道", "分布")))
        self.assertTrue(any(marker in candidate for marker in ("跨快照", "留存率", "趋势", "可迁移结构")))
        self.assertTrue(any(marker in candidate for marker in ("Top30", "本", "覆盖", "快照")))
        self.assertIn("## 热门题材分布", done["answer"])
        self.assertIn("校园/高考 | 8 本", done["answer"])
        self.assertIn("留存率 33.3%", done["answer"])
        self.assertNotIn("## 榜单结果", done["answer"])
        provider_nodes = [call["node"] for call in done["resultJson"]["providerCalls"]]
        self.assertEqual(["market_evidence_analysis", "compose_answer"], provider_nodes)
        self.assertEqual(2, done["resultJson"]["marketEvidenceAnalysis"]["snapshotCount"])
        analysis_prompt = MarketHarnessProvider()._prompt_text(provider.invoke_calls[0]["messages"])
        self.assertIn("当前作品30", analysis_prompt)
        self.assertIn("历史作品30", analysis_prompt)
        final_prompt = MarketHarnessProvider()._prompt_text(provider.invoke_calls[1]["messages"])
        self.assertIn("用户项目是五毛特效订单召唤诸天人物打工", final_prompt)
        self.assertIn("stableMechanic", final_prompt)
        self.assertIn("citationMap", final_prompt)
        self.assertNotIn("material:", final_prompt)
        progress = [event for event in events if event.get("event") == "progress"]
        self.assertTrue(any(event.get("phase") == "analysis" for event in progress))
        self.assertFalse(any("思维链" in str(event.get("message") or "") for event in progress))

    @staticmethod
    def _source(
        snapshot_id: int,
        snapshot_time: datetime,
        rank_no: int,
        book_name: str,
        intro: str,
    ) -> KnowledgeSource:
        return KnowledgeSource(
            sourceType="RANK",
            sourceRefId=snapshot_id * 100 + rank_no,
            snapshotId=snapshot_id,
            snapshotTime=snapshot_time.isoformat(),
            channelCode="male-new",
            boardCode="262",
            category="都市脑洞",
            rankNo=rank_no,
            bookId=rank_no,
            bookName=book_name,
            title=f"男频新书榜 / 都市脑洞 #{rank_no}",
            preview=intro,
        )

    async def test_list_request_keeps_single_model_turn(self) -> None:
        client = TwoSnapshotRankClient()
        provider = MarketHarnessProvider(list_mode=True)
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(KnowledgeChatRequest(
            question="男频都市脑洞新书榜Top10有哪些书？",
            mode="research",
            reasoningMode="deep",
        ))

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertEqual(10, client.lookup_rank_calls[0]["limit"])
        self.assertEqual("latest", client.lookup_rank_calls[0]["freshness"])
        self.assertEqual(["compose_answer"], [
            call["node"] for call in response.resultJson["providerCalls"]
        ])


if __name__ == "__main__":
    unittest.main()
