---
skillId: webnovel-market-scan
version: 1.1.0
intents: [market_scan]
appliesTo: [market_scan]
allowedTools: [rank.lookup, rank.research_pack, knowledge.vector_search]
requiredEvidence: [current_structured_rank_topn]
triggers: [rank, market, trend, board]
---

## Prompt Fragment
Read rank or book evidence as market signals, not as a tiny anecdotal sample. Separate observed data from author-facing inference. Name category, platform, timeframe, sample size, coverage level, rising hooks, common reader promises, outliers, and visible risks.

Use 缓存优先 for榜单 and chapter evidence. A matching rank snapshot within 3 天 is fresh enough for ordinary trend/选题/大纲 questions; do not trigger a full board recrawl just because the user asks a similar market question again. Reuse stored chapter samples once fetched, and fetch only missing chapter ranges when deeper comparable-book evidence is required. If refresh happens, explain the reason as absent, expired, manual refresh, invalid snapshot, or partial fill.

For full-board requests, prefer Top30 coverage. If Top30 is available, compute and report:
1. 核心关键词萃取: Top30 title/intro/tag keywords, including核心意象词,情绪词,身份词. Return frequency and percentage so the user can build a word cloud.
2. 题材与流派细分统计: classify into second/third-level lanes such as都市脑洞-奇葩系统流,都市文娱-跨界降维,高考逆袭-社会传播,科研医学-专业碾压. Include count, percentage, representative books, and trend status.
3. 读者情绪锚点: extract Top5 emotion promises, such as降维打击,反转打脸,沙雕解压,身份翻盘,全网震惊,专业爽感, and map them to books.
4. 爆款微创新点: identify black-horse or differentiated mechanisms worth following in the next 3-6 months.
5. 作者落地方向: convert evidence into concrete opening, golden-finger, protagonist identity, task loop, and poison-point avoidance suggestions.

## Quality Checklist
- Evidence and inference are labeled separately.
- Board analysis covers at least Top30 when available; if fewer rows are available, state the actual sample size.
- Full-board requests use all available rank rows up to the runtime limit and segment the board into front-rank, mid-rank, and long-tail signals.
- Trends include at least one actionable writing implication.
- Analysis compares hook patterns, protagonist identity pressure, golden-finger mechanics, setting wrappers, title/intro promises, and updateable chapter tasks across the sample.
- Missing rank data is called out plainly.
- Top30 analysis includes keyword frequency, subgenre distribution, emotion anchors, micro-innovation, and author-side recommendations.
- Cache status is surfaced: hit, miss, expired, manual refresh, invalid snapshot, or partial fill when trace/tool evidence provides it.
- Similar-title/comparable-book chapter evidence is reused when already stored.

## Guardrails
- Pure market conclusions require verified latest rank evidence.
- Mixed creation may use degraded directional rank evidence only with a caveat.
- Do not request a full recrawl when a matching snapshot is within 3 天 unless the user explicitly asks for real-time refresh or the cached rows are insufficient for the requested coverage.
- If only Top10 is available for a Top30 question, state the limitation and avoid pretending to have full-board statistics.

## Negative Rules
- Do not invent rank positions or book metrics.
- Do not claim broad market certainty from a tiny sample.
- Do not call a 5-10 book sample a board trend when Top30/full-board evidence was requested.
- Do not use broad labels like "都市/玄幻" when the evidence supports finer lanes.
- Do not let market evidence replace the user's current project premise; compare and adapt it.
- Do not repeatedly fetch already stored chapters or full rank boards inside one answer path.

## Output Contract
Return sections: 覆盖范围, 缓存与刷新状态, Top30关键词频率, 题材/流派分布, 头部信号, 情绪公约数, 黑马微创新, 对标拆解, 写作落地方向, 风险与毒点.

## Examples
- market_scan with verified_latest evidence returns trend facts before writing advice.
- A repeated urban-brain Top30 question with a 3 天内 snapshot uses cached rank rows and may only fill missing chapter samples.
