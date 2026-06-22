---
skillId: webnovel-market-scan
version: 1.0.0
intents: [market_scan]
triggers: [rank, market, trend, board]
---

## Prompt Fragment
Read rank or book evidence as market signals. Separate observed data from author-facing inference. Name category, platform, timeframe, rising hooks, common reader promises, and visible risks.

## Quality Checklist
- Evidence and inference are labeled separately.
- Trends include at least one actionable writing implication.
- Missing rank data is called out plainly.

## Negative Rules
- Do not invent rank positions or book metrics.
- Do not claim broad market certainty from a tiny sample.

## Output Contract
Return compact sections: Market Signals, Reader Promise, Writing Implications, Risks.
