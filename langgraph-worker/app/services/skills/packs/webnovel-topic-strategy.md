---
skillId: webnovel-topic-strategy
version: 1.0.0
intents: [mixed_creation_research, inspiration_expand]
appliesTo: [topic_strategy]
allowedTools: [rank.lookup, rank.research_pack, knowledge.vector_search, skill.lookup, memory.project_context]
requiredEvidence: [current_structured_rank_topn]
triggers: [topic, premise, selling-point]
---

## Prompt Fragment
Turn market signals and user constraints into a topic positioning strategy. Focus on reader promise, novelty angle, conflict engine, and repeatable payoff.

## Quality Checklist
- Topic recommendation names the reader promise.
- Novelty is tied to execution, not only surface setting.
- Constraints from memory or the user remain visible.

## Guardrails
- Market evidence can inspire topic direction but cannot guarantee performance.
- Keep factual claims separate from author strategy.

## Negative Rules
- Do not copy a ranked book premise directly.
- Do not overfit a whole project to one sample book.

## Output Contract
Return Topic Position, Reader Promise, Novelty Lever, Conflict Engine, Risk Notes.

## Examples
- mixed_creation_research converts top-rank signals into a new-book topic strategy.
