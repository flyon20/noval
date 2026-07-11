from __future__ import annotations

import json
import re
from collections.abc import Callable
from typing import Any

from app.services.intents.domain_intents import (
    AnswerBoundary,
    Intent,
    IntentDecision,
    ToolNeeds,
)
from app.services.intents.intent_examples import INTENT_EXAMPLES


LlmFallback = Callable[[str, str | None, list[str] | None], dict[str, Any] | str | IntentDecision | None]


class IntentRouter:
    def __init__(self, llm_fallback: LlmFallback | None = None) -> None:
        self.llm_fallback = llm_fallback

    def classify(
        self,
        question: str,
        context_summary: str | None = None,
        history: list[str] | None = None,
    ) -> IntentDecision:
        normalized = self._normalize(question)
        entities = self._extract_entities(question, context_summary)

        if self._is_out_of_scope(normalized):
            return self._decision(
                Intent.out_of_scope,
                confidence=0.96,
                entities=entities,
                answer_boundary=AnswerBoundary.out_of_scope,
                routing_notes=["rule:oos-domain"],
            )

        scores = self._score_intents(normalized, context_summary, history)
        if (
            self._has_strong_opening_strategy(normalized)
            and not self._is_rank_fact_question(normalized)
            and not self._has_mixed_task_marker(normalized)
            and not self._has_explicit_market_scan_request(normalized)
        ):
            return self._decision(
                Intent.opening_strategy,
                confidence=0.88,
                entities=entities,
                tool_needs=self._tool_needs_for(Intent.opening_strategy, []),
                answer_boundary=self._answer_boundary_for(Intent.opening_strategy),
                routing_notes=["rule:strong-opening-strategy"],
            )
        detected = [intent for intent, score in scores.items() if score >= 2]
        has_research = Intent.market_scan in detected or scores.get(Intent.book_breakdown, 0) >= 2
        has_creation = any(
            scores.get(intent, 0) >= 2
            for intent in (
                Intent.opening_strategy,
                Intent.outline_building,
                Intent.chapter_outline,
                Intent.inspiration_expand,
                Intent.character_design,
                Intent.worldbuilding,
                Intent.revision_advice,
            )
        )
        if self._should_use_followup_context(normalized, context_summary, history):
            return self._decision(
                Intent.followup_context,
                confidence=0.74,
                entities=entities,
                tool_needs=self._tool_needs_for(Intent.followup_context, []),
                answer_boundary=AnswerBoundary.needs_more_data,
                routing_notes=["rule:context-followup"],
            )
        if has_research and has_creation and self._has_mixed_task_marker(normalized):
            sub_intents = self._ordered_sub_intents(detected)
            return self._decision(
                Intent.mixed_creation_research,
                sub_intents=sub_intents,
                confidence=0.9,
                entities=entities,
                tool_needs=self._tool_needs_for(Intent.mixed_creation_research, sub_intents),
                answer_boundary=AnswerBoundary.market_evidence_plus_author_inference,
                routing_notes=["rule:mixed-research-creation"],
            )

        if detected:
            if (
                Intent.market_scan in detected
                and has_creation
                and self._has_explicit_creation_request(normalized)
            ):
                sub_intents = self._ordered_sub_intents(detected)
                return self._decision(
                    Intent.mixed_creation_research,
                    sub_intents=sub_intents,
                    confidence=0.88,
                    entities=entities,
                    tool_needs=self._tool_needs_for(Intent.mixed_creation_research, sub_intents),
                    answer_boundary=AnswerBoundary.market_evidence_plus_author_inference,
                    routing_notes=["rule:mixed-market-advice"],
                )
            ambiguous_intents = self._ambiguous_intents(scores)
            if ambiguous_intents:
                fallback_decision = self._try_llm_fallback(question, context_summary, history)
                if fallback_decision is not None:
                    return fallback_decision
                return self._decision(
                    Intent.followup_context,
                    sub_intents=ambiguous_intents,
                    confidence=0.58,
                    entities=entities,
                    tool_needs=self._tool_needs_for(Intent.followup_context, ambiguous_intents),
                    answer_boundary=AnswerBoundary.needs_more_data,
                    routing_notes=[
                        "rule:ambiguous-intent",
                        "candidates:" + ",".join(intent.value for intent in ambiguous_intents),
                    ],
                )
            primary = self._best_intent(scores)
            return self._decision(
                primary,
                confidence=self._confidence(scores[primary]),
                entities=entities,
                tool_needs=self._tool_needs_for(primary, []),
                answer_boundary=self._answer_boundary_for(primary),
                routing_notes=[f"rule:{primary.value}"],
            )

        semantic = self._semantic_example_match(normalized)
        if semantic is not None:
            decision = self._decision(
                semantic,
                confidence=0.62,
                entities=entities,
                tool_needs=self._tool_needs_for(semantic, []),
                answer_boundary=self._answer_boundary_for(semantic),
                routing_notes=[f"example:{semantic.value}"],
            )
            fallback_decision = self._try_llm_fallback(question, context_summary, history)
            return fallback_decision or decision

        fallback_decision = self._try_llm_fallback(question, context_summary, history)
        if fallback_decision is not None:
            return fallback_decision

        if context_summary or history:
            return self._decision(
                Intent.followup_context,
                confidence=0.55,
                entities=entities,
                tool_needs=self._tool_needs_for(Intent.followup_context, []),
                answer_boundary=AnswerBoundary.needs_more_data,
                routing_notes=["rule:context-only"],
            )
        return self._decision(
            Intent.out_of_scope,
            confidence=0.72,
            entities=entities,
            answer_boundary=AnswerBoundary.out_of_scope,
            routing_notes=["fallback:no-webnovel-signal"],
        )

    def _score_intents(
        self,
        normalized: str,
        context_summary: str | None,
        history: list[str] | None,
    ) -> dict[Intent, int]:
        scores: dict[Intent, int] = {intent: 0 for intent in Intent if intent not in {Intent.mixed_creation_research, Intent.out_of_scope}}
        for example in INTENT_EXAMPLES:
            scores[example.intent] = max(scores.get(example.intent, 0), self._phrase_score(normalized, example.phrases))

        has_revision_markers = any(term in normalized for term in ("改稿", "润色", "诊断", "修改", "优化", "重写"))
        if (
            not has_revision_markers
            and re.search(r"第?\s*\d+\s*[章节]|[一二三四五六七八九十百]+[章节]", normalized)
            and any(term in normalized for term in ("细纲", "章节纲", "分章", "章末", "钩子", "开篇", "开头", "冲突", "悬念", "每章", "单章"))
        ):
            scores[Intent.chapter_outline] += 3
        if "开头" in normalized and re.search(r"第?\s*\d+\s*[章节]|[一二三四五六七八九十百]+[章节]", normalized):
            scores[Intent.chapter_outline] += 2
        if "每章" in normalized or ("拆成" in normalized and re.search(r"第?\s*\d+\s*[章节]|[一二三四五六七八九十百]+[章节]", normalized)):
            scores[Intent.chapter_outline] += 2
        if "这本" in normalized or "本书" in normalized or re.search(r"《[^》]+》", normalized):
            if any(term in normalized for term in ("拆", "分析", "节奏", "爽点", "卖点")):
                scores[Intent.book_breakdown] += 3
            if "毒点" in normalized and not any(term in normalized for term in ("怎么改", "修改", "优化", "重写", "润色")):
                scores[Intent.book_breakdown] += 4
            if any(term in normalized for term in ("怎么做", "怎么做的", "如何做", "怎么排", "怎么安排")):
                scores[Intent.book_breakdown] += 3
        if any(marker in normalized for marker in ("刚才", "上面", "前面", "继续", "这个题材", "这本")) and (context_summary or history):
            scores[Intent.followup_context] += 4
        if any(term in normalized for term in ("榜单", "扫榜", "看榜", "新书榜", "畅销榜", "榜一", "top", "前三", "前十")):
            scores[Intent.market_scan] += 1
        if any(term in normalized for term in ("当前", "目前", "最近", "热门", "趋势", "风向", "市场", "热度", "扫榜", "看榜", "榜单", "新书榜", "畅销榜", "榜一", "top", "赛道")):
            if any(term in normalized for term in ("都市脑洞", "新书榜", "榜单", "番茄", "起点", "男频", "女频", "赛道")):
                scores[Intent.market_scan] += 4
        board_terms = (
            "男频",
            "女频",
            "都市",
            "脑洞",
            "\u9422\u70fd\ue576",
            "\u95ae\u85c9\u7af6",
            "\u9474\u621e\u790a",
            "\u93c2\u9881\u529f",
        )
        if any(term in normalized for term in ("top", "op10", "\u6dedop", "新书榜", "榜单")) and any(
            term in normalized for term in board_terms
        ):
            scores[Intent.market_scan] += 4
        if self._has_historical_market_window(normalized):
            scores[Intent.market_scan] += 4
        if self._is_rank_fact_question(normalized):
            scores[Intent.market_scan] += 4
        if "开" in normalized and any(term in normalized for term in ("书", "文", "一本")):
            scores[Intent.opening_strategy] += 2
        if any(term in normalized for term in ("立项", "开文", "开书", "开一本", "新书项目", "新书方向", "开书方向", "开文方向", "定位", "选题")):
            scores[Intent.opening_strategy] += 2
        if any(term in normalized for term in ("开一本", "同题材新书", "\u5bee\u20ac", "\u93c2\u9881\u529f")):
            scores[Intent.opening_strategy] += 2
        if self._has_strong_opening_strategy(normalized):
            scores[Intent.opening_strategy] += 2
        if any(term in normalized for term in ("大纲", "卷纲", "三幕", "主线框架", "剧情线")):
            scores[Intent.outline_building] += 2
        if "大纲" in normalized and any(term in normalized for term in ("搭", "写", "规划", "万字", "长篇")):
            scores[Intent.outline_building] += 2
        if any(term in normalized for term in ("细纲", "章节纲", "分章")):
            scores[Intent.chapter_outline] += 2
        if (
            any(term in normalized for term in ("点子", "脑洞", "灵感", "发散", "扩写"))
            and not self._is_rank_fact_question(normalized)
        ):
            scores[Intent.inspiration_expand] += 2
        if any(term in normalized for term in ("人设", "反派", "群像", "角色表")):
            scores[Intent.character_design] += 2
        if any(term in normalized for term in ("世界观", "体系", "势力", "阵营", "规则", "设定一个")):
            scores[Intent.worldbuilding] += 2
        if has_revision_markers:
            scores[Intent.revision_advice] += 3
        if any(term in normalized for term in ("读者说", "怎么改", "节奏太慢", "毒点")):
            scores[Intent.revision_advice] += 2
        if any(term in normalized for term in ("拆", "拆解", "分析这本", "这本书", "代表作", "craft extraction")):
            scores[Intent.book_breakdown] += 3
        return scores

    def _is_rank_fact_question(self, normalized: str) -> bool:
        has_rank_marker = any(marker in normalized for marker in ("排名", "榜一", "第一", "第1", "top1", "top 1"))
        has_board_context = any(marker in normalized for marker in ("榜", "男频", "女频", "都市脑洞", "畅销", "新书"))
        asks_fact = any(marker in normalized for marker in ("是什么", "哪本", "书名", "谁", "作品"))
        return has_rank_marker and has_board_context and asks_fact

    def _has_historical_market_window(self, normalized: str) -> bool:
        has_window = bool(re.search(r"近\s*\d+\s*天|最近\s*\d+\s*天|近\s*\d+\s*周|近\s*\d+\s*月|最近一个月|近一个月", normalized))
        has_change = any(marker in normalized for marker in ("变化", "走势", "对比", "趋势", "风向", "变了", "升降"))
        has_market_context = any(marker in normalized for marker in ("都市脑洞", "男频", "女频", "番茄", "起点", "榜", "赛道", "题材"))
        return has_window and has_change and has_market_context

    def _phrase_score(self, normalized: str, phrases: tuple[str, ...]) -> int:
        return sum(1 for phrase in phrases if phrase in normalized)

    def _best_intent(self, scores: dict[Intent, int]) -> Intent:
        priority = [
            Intent.followup_context,
            Intent.market_scan,
            Intent.opening_strategy,
            Intent.book_breakdown,
            Intent.revision_advice,
            Intent.chapter_outline,
            Intent.outline_building,
            Intent.character_design,
            Intent.worldbuilding,
            Intent.inspiration_expand,
        ]
        return max(priority, key=lambda intent: (scores.get(intent, 0), -priority.index(intent)))

    def _ambiguous_intents(self, scores: dict[Intent, int]) -> list[Intent]:
        ranked = [
            (intent, score)
            for intent, score in scores.items()
            if intent not in {Intent.followup_context, Intent.out_of_scope, Intent.mixed_creation_research}
            and score >= 2
        ]
        if len(ranked) < 2:
            return []
        top_score = max(score for _, score in ranked)
        top_intents = [intent for intent, score in ranked if score == top_score]
        if len(top_intents) < 2:
            return []
        strong_creation_intents = {
            Intent.opening_strategy,
            Intent.outline_building,
            Intent.chapter_outline,
            Intent.character_design,
            Intent.worldbuilding,
            Intent.revision_advice,
        }
        if Intent.market_scan in top_intents and not any(intent in strong_creation_intents for intent in top_intents):
            return []
        return self._ordered_sub_intents(top_intents)

    def _has_mixed_task_marker(self, normalized: str) -> bool:
        if any(marker in normalized for marker in ("同时", "并且")):
            return True
        if any(marker in normalized for marker in ("再帮我", "再给我")):
            return True
        if any(marker in normalized for marker in ("\u951b\u5c7d", "\u5540\u752f")):
            return True
        if "先" in normalized and any(marker in normalized for marker in ("再", "然后", "之后")):
            return True
        return any(marker in normalized for marker in ("参考", "根据", "基于", "后，", "后,", "之后"))

    def _has_explicit_creation_request(self, normalized: str) -> bool:
        if any(marker in normalized for marker in (
            "开书建议",
            "开文建议",
            "对应开书",
            "怎么写",
            "如何写",
            "设计",
            "大纲",
            "细纲",
            "世界观",
            "新题材",
        )):
            return True
        if "人设" in normalized and any(marker in normalized for marker in ("设计", "怎么写", "如何写", "搭", "生成")):
            return True
        return any(marker in normalized for marker in ("参考榜单", "根据榜单", "基于榜单"))

    def _has_explicit_market_scan_request(self, normalized: str) -> bool:
        return any(marker in normalized for marker in (
            "扫榜",
            "看榜",
            "榜单趋势",
            "整体",
            "最近热门",
            "热门题材",
            "目前都是哪些题材",
            "都是哪些题材",
            "哪些题材",
            "题材趋势",
            "市场趋势",
            "市场风向",
            "类似题材",
        ))

    def _has_strong_opening_strategy(self, normalized: str) -> bool:
        if any(marker in normalized for marker in ("新书项目", "新书方向", "开书方向", "开文方向", "立项", "选题", "定位")):
            return True
        if any(marker in normalized for marker in ("怎么开文", "如何开文", "怎么开书", "如何开书")):
            return True
        if any(marker in normalized for marker in ("开新书", "开一本新书", "开书", "开文")) and any(
            cue in normalized for cue in ("推荐", "题材", "方向", "选题", "写什么")
        ):
            return True
        if any(marker in normalized for marker in ("开文", "开书")) and any(
            cue in normalized for cue in ("怎么", "如何", "更容易", "应该", "抓读者", "进新书榜")
        ):
            return True
        return "变成可写" in normalized and any(marker in normalized for marker in ("脑洞", "新书"))

    def _should_use_followup_context(
        self,
        normalized: str,
        context_summary: str | None,
        history: list[str] | None,
    ) -> bool:
        if not (context_summary or history):
            return False
        if not any(marker in normalized for marker in ("刚才", "上面", "前面", "继续", "这个题材", "这本", "上一版", "再")):
            return False
        if any(marker in normalized for marker in ("大纲", "细纲", "开文", "开书", "开头", "三卷", "卷纲", "章节", "篇章", "扩成", "补全", "设计")):
            return False
        return not any(marker in normalized for marker in ("当前榜", "最新榜", "新书榜", "畅销榜", "排名", "榜一", "top"))

    def _ordered_sub_intents(self, intents: list[Intent]) -> list[Intent]:
        order = [
            Intent.market_scan,
            Intent.book_breakdown,
            Intent.opening_strategy,
            Intent.outline_building,
            Intent.chapter_outline,
            Intent.inspiration_expand,
            Intent.character_design,
            Intent.worldbuilding,
            Intent.revision_advice,
            Intent.followup_context,
        ]
        return [intent for intent in order if intent in intents and intent is not Intent.mixed_creation_research]

    def _semantic_example_match(self, normalized: str) -> Intent | None:
        best_intent: Intent | None = None
        best_overlap = 0
        chars = set(normalized)
        for example in INTENT_EXAMPLES:
            example_chars = set("".join(example.phrases))
            overlap = len(chars & example_chars)
            if overlap > best_overlap:
                best_overlap = overlap
                best_intent = example.intent
        return best_intent if best_overlap >= 8 else None

    def _extract_entities(self, question: str, context_summary: str | None) -> dict[str, Any]:
        text = f"{question}\n{context_summary or ''}"
        entities: dict[str, Any] = {}
        if "番茄" in text:
            entities["platform"] = "番茄"
        if "起点" in text:
            entities["platform"] = "起点"
        if "男频" in text:
            entities["channel"] = "男频"
        if "女频" in text:
            entities["channel"] = "女频"
        for category in ("都市脑洞", "玄幻", "现言", "古言", "悬疑", "科幻", "仙侠", "娱乐圈", "年代文"):
            if category in text:
                entities["category"] = category
                break
        board_match = re.search(r"([A-Za-z0-9_-]{2,})榜", question)
        if board_match:
            entities["boardCode"] = board_match.group(1)
        book_match = re.search(r"《([^》]+)》", question)
        if book_match:
            prefix = question[max(0, book_match.start() - 12):book_match.start()]
            if not self._is_example_title_prefix(prefix):
                entities["bookName"] = book_match.group(1)
        book_id_match = re.search(r"(?:bookId|书号|作品id)[:：]?\s*([A-Za-z0-9_-]+)", question, flags=re.IGNORECASE)
        if book_id_match:
            entities["bookId"] = book_id_match.group(1)
        author_match = re.search(r"(?:作者|笔名)[:：]?\s*([\u4e00-\u9fa5A-Za-z0-9_-]{2,12})", question)
        if author_match:
            entities["author"] = author_match.group(1)
        top_match = re.search(r"(top\s*\d+|前\s*\d+\s*名|榜一|第一名)", question, flags=re.IGNORECASE)
        if top_match:
            raw_scope = top_match.group(1).replace(" ", "")
            entities["chapterScope"] = "Top10" if raw_scope.lower() == "top10" else raw_scope
        chapter_match = re.search(r"(第?\s*\d+\s*[章节]|[一二三四五六七八九十百]+[章节]|前\s*\d+\s*章)", question)
        if chapter_match and "chapterScope" not in entities:
            entities["chapterScope"] = chapter_match.group(1).replace(" ", "")
        length_match = re.search(r"(\d+\s*[万千]字|\d+\s*字|短篇|长篇|中篇)", question)
        if length_match:
            entities["targetLength"] = length_match.group(1).replace(" ", "")
        window_match = re.search(r"(?:近|最近)\s*(\d+)\s*天", question)
        if window_match:
            entities["timeWindowDays"] = int(window_match.group(1))
        elif "最近一个月" in question or "近一个月" in question:
            entities["timeWindowDays"] = 30
        if "小白" in question or "老书虫" in question or "女性读者" in question or "男性读者" in question:
            entities["targetAudience"] = self._first_present(question, ("小白", "老书虫", "女性读者", "男性读者"))
        style = self._first_present(question, ("轻松", "沙雕", "爽文", "群像", "赛博修仙", "悬疑", "甜宠", "克苏鲁"))
        if style:
            entities["stylePreference"] = style
        constraints = [term for term in ("不要系统", "别太虐", "不后宫", "少说明", "快节奏") if term in question]
        if constraints:
            entities["constraints"] = constraints
        if any(marker in question for marker in ("刚才", "这个题材", "这本")):
            entities["currentTopic"] = self._first_present(question, ("刚才这个题材", "这个题材", "这本", "刚才"))
        if "细纲" in question:
            entities["outlineStage"] = "chapter_outline"
        elif "大纲" in question or "卷纲" in question:
            entities["outlineStage"] = "outline_building"
        premise_match = re.search(r"(?:前提|设定|脑洞)[:：]?\s*([^，。！？\n]+)", question)
        if premise_match:
            entities["currentPremise"] = premise_match.group(1).strip()
        return entities

    def _first_present(self, text: str, candidates: tuple[str, ...]) -> str | None:
        return next((candidate for candidate in candidates if candidate in text), None)

    def _is_example_title_prefix(self, prefix: str) -> bool:
        return any(marker in prefix for marker in ("书名示例", "书名例", "标题示例", "作品示例", "例如", "比如", "以", "为例"))

    def _is_out_of_scope(self, normalized: str) -> bool:
        webnovel_terms = (
            "小说", "网文", "男频", "女频", "番茄", "起点", "开书", "开文", "拆书", "大纲", "细纲",
            "人设", "世界观", "爽点", "榜单", "新书榜", "都市脑洞",
        )
        if any(term in normalized for term in webnovel_terms):
            return False
        oos_terms = (
            "天气", "气温", "下雨", "python", "java", "代码", "函数", "股票", "基金", "财经", "投资",
            "感冒", "发烧", "吃什么药", "医疗", "医院", "旅行", "旅游", "酒店", "机票", "美食", "火锅",
            "餐厅", "菜谱", "减肥", "健身", "三日游",
        )
        return any(term in normalized for term in oos_terms)

    def _tool_needs_for(self, intent: Intent, sub_intents: list[Intent]) -> ToolNeeds:
        intents = set(sub_intents or [intent])
        return ToolNeeds(
            needsRankData=Intent.market_scan in intents or intent is Intent.market_scan or intent is Intent.mixed_creation_research,
            needsBookResearch=Intent.book_breakdown in intents or intent is Intent.book_breakdown,
            needsVectorEvidence=Intent.book_breakdown in intents or intent is Intent.book_breakdown,
            needsCreativeGeneration=bool(intents & {
                Intent.opening_strategy,
                Intent.outline_building,
                Intent.chapter_outline,
                Intent.inspiration_expand,
                Intent.character_design,
                Intent.worldbuilding,
                Intent.revision_advice,
            }) or intent in {
                Intent.opening_strategy,
                Intent.outline_building,
                Intent.chapter_outline,
                Intent.inspiration_expand,
                Intent.character_design,
                Intent.worldbuilding,
                Intent.revision_advice,
                Intent.mixed_creation_research,
            },
            needsOutlineMemory=Intent.followup_context in intents or intent in {
                Intent.followup_context,
                Intent.outline_building,
                Intent.chapter_outline,
                Intent.revision_advice,
            },
            needsChapterEvidence=intent is Intent.chapter_outline or Intent.chapter_outline in intents or intent is Intent.revision_advice,
            needsSkillPack=intent in {
                Intent.opening_strategy,
                Intent.inspiration_expand,
                Intent.character_design,
                Intent.worldbuilding,
                Intent.mixed_creation_research,
            } or bool(intents & {Intent.opening_strategy, Intent.inspiration_expand, Intent.character_design, Intent.worldbuilding}),
            needsCandidateSelection=intent is Intent.opening_strategy or Intent.opening_strategy in intents,
        )

    def _answer_boundary_for(self, intent: Intent) -> AnswerBoundary:
        for example in INTENT_EXAMPLES:
            if example.intent is intent:
                return example.answer_boundary
        if intent is Intent.mixed_creation_research:
            return AnswerBoundary.market_evidence_plus_author_inference
        if intent is Intent.out_of_scope:
            return AnswerBoundary.out_of_scope
        return AnswerBoundary.needs_more_data

    def _decision(
        self,
        primary: Intent,
        *,
        sub_intents: list[Intent] | None = None,
        confidence: float,
        entities: dict[str, Any] | None = None,
        tool_needs: ToolNeeds | None = None,
        answer_boundary: AnswerBoundary,
        routing_notes: list[str] | None = None,
    ) -> IntentDecision:
        effective_sub_intents = sub_intents or []
        return IntentDecision(
            primaryIntent=primary,
            subIntents=effective_sub_intents,
            confidence=confidence,
            entities=entities or {},
            toolNeeds=tool_needs or self._tool_needs_for(primary, effective_sub_intents),
            answerBoundary=answer_boundary,
            routingNotes=routing_notes or [],
            sourcePolicy=self._source_policy_for(primary, effective_sub_intents, entities or {}),
            memoryPolicy=self._memory_policy_for(primary, effective_sub_intents),
        )

    def _source_policy_for(self, primary: Intent, sub_intents: list[Intent], entities: dict[str, Any] | None = None) -> dict[str, Any]:
        intents = {primary, *sub_intents}
        entity_payload = entities or {}
        time_window_days = entity_payload.get("timeWindowDays")
        if Intent.market_scan in intents or primary is Intent.mixed_creation_research:
            if time_window_days is not None:
                return {
                    "freshness": "time_window",
                    "allowHistorical": True,
                    "timeWindowDays": time_window_days,
                    "requireSnapshotTime": True,
                    "sourcePriority": ["RANK_HISTORY", "RANK", "CHAPTER", "CHAPTER_PACK", "INTRO", "ANALYSIS"],
                }
            return {
                "freshness": "latest",
                "allowHistorical": False,
                "timeWindowDays": None,
                "requireSnapshotTime": True,
                "sourcePriority": ["RANK", "CHAPTER", "CHAPTER_PACK", "INTRO", "ANALYSIS"],
            }
        if Intent.book_breakdown in intents or primary is Intent.book_breakdown:
            return {
                "freshness": "any",
                "allowHistorical": False,
                "timeWindowDays": None,
                "requireSnapshotTime": False,
                "sourcePriority": ["CHAPTER", "CHAPTER_PACK", "ANALYSIS", "INTRO", "RANK"],
            }
        return {
            "freshness": "any",
            "allowHistorical": False,
            "timeWindowDays": None,
            "requireSnapshotTime": False,
            "sourcePriority": ["PROJECT_MEMORY", "THREAD_MEMORY", "SKILL", "CHAPTER", "ANALYSIS"],
        }

    def _memory_policy_for(self, primary: Intent, sub_intents: list[Intent]) -> dict[str, Any]:
        intents = {primary, *sub_intents}
        return {
            "useUserProfile": False,
            "useProjectProfile": bool(intents & {
                Intent.opening_strategy,
                Intent.outline_building,
                Intent.chapter_outline,
                Intent.inspiration_expand,
                Intent.character_design,
                Intent.worldbuilding,
                Intent.revision_advice,
                Intent.followup_context,
                Intent.mixed_creation_research,
            }),
            "useThreadSummary": True,
            "writeCandidates": primary is not Intent.out_of_scope,
        }

    def _confidence(self, score: int) -> float:
        return max(0.58, min(0.96, 0.52 + score * 0.12))

    def _coerce_decision(self, fallback: dict[str, Any] | str | IntentDecision) -> IntentDecision:
        if isinstance(fallback, IntentDecision):
            return fallback
        payload = json.loads(fallback) if isinstance(fallback, str) else dict(fallback)
        if "toolNeeds" not in payload:
            primary = Intent(payload.get("primaryIntent", Intent.out_of_scope.value))
            payload["toolNeeds"] = self._tool_needs_for(primary, [])
        if "answerBoundary" not in payload:
            payload["answerBoundary"] = self._answer_boundary_for(Intent(payload["primaryIntent"]))
        return IntentDecision(**payload)

    def coerce_fallback(self, fallback: dict[str, Any] | str | IntentDecision) -> IntentDecision:
        return self._coerce_decision(fallback)

    def _try_llm_fallback(
        self,
        question: str,
        context_summary: str | None,
        history: list[str] | None,
    ) -> IntentDecision | None:
        if self.llm_fallback is None:
            return None
        try:
            fallback = self.llm_fallback(question, context_summary, history)
            if fallback is None:
                return None
            return self._coerce_decision(fallback)
        except Exception:
            return None

    def _normalize(self, question: str) -> str:
        return (question or "").strip().lower().replace("ｔｏｐ", "top")


_DEFAULT_ROUTER = IntentRouter()


def classify(
    question: str,
    context_summary: str | None = None,
    history: list[str] | None = None,
) -> IntentDecision:
    return _DEFAULT_ROUTER.classify(question, context_summary=context_summary, history=history)
