from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class MarketScanAgent(BaseSpecialistAgent):
    agent_name = "market_scan"
    answer_mode = "trend"
    llm_enabled = True
    tool_route = "market_scan"
    generation_instructions = (
        "先归纳榜单、排名、赛道和平台信号，再给趋势判断。",
        "区分可由证据直接支持的市场事实与作者侧推断。",
        "优先输出可用于开书决策的题材、卖点、读者期待和风险。",
    )
    evidence_policy = (
        "Rank and market evidence first.",
        "Use retrieved sources before inference.",
        "Mark unsupported trend claims as inference.",
    )
    evidence_refs = ("rank", "market_signal")
    actions = ("prioritize_rank_market_evidence",)
