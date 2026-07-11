---
skillId: reader-risk-review
version: 1.0.0
intents: [revision_advice]
appliesTo: [reader_risk, reader_risk_review]
allowedTools: [knowledge.vector_search, memory.project_context]
requiredEvidence: []
triggers: [reader-risk, abandon, confusion, expectation]
---

## Prompt Fragment
Review the plan from a reader retention perspective. Flag confusion, delayed payoff, genre promise drift, weak motivation, and pacing stalls.

## Quality Checklist
- Each risk explains the likely reader reaction.
- Each risk has a concrete repair move.
- Risks are prioritized by damage to retention.

## Guardrails
- Treat missing draft details as uncertainty, not proof of failure.
- Preserve user constraints while suggesting fixes.

## Negative Rules
- Do not rewrite the entire project when a targeted repair is enough.
- Do not invent reader data.

## Output Contract
Return Risk, Why It Hurts, Severity, Repair Move.

## Examples
- reader_risk_review identifies where the opening may lose readers and proposes fixes.
