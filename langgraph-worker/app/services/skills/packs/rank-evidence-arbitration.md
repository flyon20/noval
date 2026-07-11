---
skillId: rank-evidence-arbitration
version: 1.0.0
intents: [market_scan, mixed_creation_research]
appliesTo: [mixed_structured_rank_snapshot, degraded_directional, conflict, rejected_snapshot_groups]
allowedTools: [rank.lookup, rank.research_pack, knowledge.vector_search]
requiredEvidence: [current_structured_rank_topn]
triggers: [snapshot, arbitration, evidence-contract]
---

## Prompt Fragment
Use the EvidenceContract as the boundary for rank claims. Treat the selected snapshot group as factual rank evidence and all rejected groups as reference signals only.

## Quality Checklist
- The selected snapshot group is named or summarized before market interpretation.
- Rejected snapshot groups are not merged into direct factual claims.
- Mixed creative advice can proceed with degraded directional caveats.

## Guardrails
- Pure market conclusions must not use conflicting snapshot groups as a single latest truth.
- Do not hide evidence degradation when the contract status is degraded_directional or conflict.

## Negative Rules
- Do not average ranks across snapshot groups.
- Do not call stale or rejected rank groups latest evidence.

## Output Contract
Return Evidence Boundary, Selected Snapshot Signal, Reference Signals, Creative Use Boundary.

## Examples
- mixed_structured_rank_snapshot selects one group, demotes others, and labels advice as inference.
