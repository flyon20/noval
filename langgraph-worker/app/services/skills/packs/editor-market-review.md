---
skillId: editor-market-review
version: 1.0.0
intents: [market_scan, mixed_creation_research, revision_advice]
appliesTo: [editor_market_review, editor_risk]
requestedCapabilities: [market.read, book.read]
requiredEvidence: [current_structured_rank_topn]
triggers: [editor, market-review, commercialization]
---

## Prompt Fragment
Review the concept like a webnovel editor. Focus on category fit, commercial hook, update durability, comparable signals, and signing risk.

## Quality Checklist
- Review separates market fit from execution suggestions.
- Comparable signals are grounded in supplied evidence.
- Commercial risks include mitigation.

## Guardrails
- Do not promise publication or monetization outcomes.
- Keep rejected evidence groups out of direct market claims.

## Negative Rules
- Do not use generic praise as review.
- Do not turn editorial risk into unsupported rank facts.

## Output Contract
Return Category Fit, Commercial Hook, Comparable Signal, Signing Risks, Revision Priority.

## Examples
- editor_market_review uses selected rank evidence to judge a new-book positioning.
