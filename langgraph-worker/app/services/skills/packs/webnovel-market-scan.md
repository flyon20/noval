---
skillId: webnovel-market-scan
version: 1.4.0
title: 榜单分析
description: 查询当前网文榜单，并按请求深度输出榜单结果、趋势分析或完整扫榜报告。
intents: [market_scan]
appliesTo: [market_scan]
requestedCapabilities: [market.read]
requiredEvidence: [current_structured_rank_topn]
triggers: [rank, market, trend, board, 榜单, 热度, 趋势]
shortcutEnabled: true
shortcutLabel: 榜单分析
shortcutOrder: 10
---

## Prompt Fragment
Choose exactly one request level; never silently escalate it.

- LIST: return every matching current structured row up to TopN, then stop after one successful rank lookup. No vector/chapter/comparable-book search, recrawl, or writing advice.
- ANALYSIS: return available TopN, then evidence-bounded topic, hook, lane, or comparison observations. Give advice only when requested.
- FULL_BOARD: for explicit Top30/distribution/full-report requests, use rows up to the requested limit and state actual coverage.
- MIXED_CREATION: rank facts first, then the requested plan; deeper evidence requires a granted market-research capability.
- Deep ANALYSIS/FULL_BOARD may use one evidence-analysis model turn plus final synthesis. LIST remains a single-answer fast path.

Use 缓存优先. A matching snapshot within 3 天 is fresh. Refresh only when absent, invalid, expired, or explicitly requested.

## Quality Checklist
- Start with the requested result, not an abstract conclusion.
- Keep observed rank facts separate from inference.
- State scope, snapshot time, requested/actual counts, and use only matching channel/category/freshness rows.

## Guardrails
- Pure market facts require current structured rank evidence.
- Historical, vector, chapter, and introduction evidence cannot replace current rank facts.
- Skill text narrows behavior; it never expands CapabilityPlan, scope, tools, or budgets.
- Do not invent ranks, heat metrics, books, authors, or snapshot times.
- Do not mix another category/channel/snapshot into a current list.
- Do not add advice or fetch more evidence after current rows satisfy LIST.
- A standalone summary is optional and last; never substitute it for requested results or analysis.
- Do not substitute a conclusion or summary for the requested rows, analysis, comparison, or writing guidance.

## Output Contract
LIST: 榜单结果, 数据范围.
ANALYSIS: 题材/流派分布, 榜单依据, 趋势观察, 数据范围.
FULL_BOARD: 题材与流派分布, 榜单明细, 有效跨快照变化（仅在 comparisonSupported=true 时）, 数据范围.
MIXED_CREATION: 榜单依据, 对标拆解, 用户要求的创作方案, 风险修正.

Every required section must already form a complete answer before any optional summary.

## Examples
- A plain recent TopN request stops after one matching structured rank lookup.
- A trend request may group the requested rows before giving evidence-bounded observations.
