from __future__ import annotations

from dataclasses import dataclass

from app.services.intents.domain_intents import AnswerBoundary, Intent


@dataclass(frozen=True)
class IntentExample:
    intent: Intent
    phrases: tuple[str, ...]
    answer_boundary: AnswerBoundary


INTENT_EXAMPLES: tuple[IntentExample, ...] = (
    IntentExample(
        Intent.market_scan,
        ("市场", "趋势", "榜单", "新书榜", "畅销榜", "榜一", "top10", "赛道", "风向", "热度"),
        AnswerBoundary.market_evidence,
    ),
    IntentExample(
        Intent.opening_strategy,
        ("开书", "开文", "开一本", "新书", "切入点", "卖点", "立项", "定位", "方向", "人群定位", "首章钩子", "期待感"),
        AnswerBoundary.creative_inference,
    ),
    IntentExample(
        Intent.book_breakdown,
        ("拆书", "拆解", "拆一下", "分析这本", "这本书", "热书", "代表作", "节奏", "爽点", "结构", "金手指", "人设弧线", "留存", "提炼", "复用", "craft extraction"),
        AnswerBoundary.book_evidence_plus_craft_extraction,
    ),
    IntentExample(
        Intent.outline_building,
        ("大纲", "主线", "卷纲", "剧情线", "阶段", "篇章", "故事骨架", "三幕", "框架", "规划", "前提"),
        AnswerBoundary.outline_generation,
    ),
    IntentExample(
        Intent.chapter_outline,
        ("细纲", "章节纲", "单章", "第", "章", "分章", "卡点", "章末钩子", "场景", "承接"),
        AnswerBoundary.outline_generation,
    ),
    IntentExample(
        Intent.inspiration_expand,
        ("灵感", "脑洞", "扩写", "发散", "梗", "点子", "素材", "设定能不能", "怎么变新", "方向", "不俗套"),
        AnswerBoundary.creative_inference,
    ),
    IntentExample(
        Intent.character_design,
        ("人设", "主角", "女主", "男主", "反派", "配角", "人物", "性格", "动机", "群像", "角色"),
        AnswerBoundary.creative_inference,
    ),
    IntentExample(
        Intent.worldbuilding,
        ("世界观", "体系", "地图", "势力", "阵营", "门派", "规则", "职业", "等级", "设定", "社会秩序"),
        AnswerBoundary.creative_inference,
    ),
    IntentExample(
        Intent.revision_advice,
        ("改稿", "润色", "重写", "诊断", "问题", "毒点", "哪里不好", "优化", "修改", "修改建议", "读者说", "节奏太慢"),
        AnswerBoundary.creative_inference,
    ),
    IntentExample(
        Intent.followup_context,
        ("刚才", "上面", "前面", "这本", "这个题材", "它", "继续", "上一版"),
        AnswerBoundary.needs_more_data,
    ),
)
