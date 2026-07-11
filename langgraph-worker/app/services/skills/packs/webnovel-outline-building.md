---
skillId: webnovel-outline-building
version: 1.0.0
intents: [outline_building]
appliesTo: [outline_building]
allowedTools: [skill.lookup, memory.project_context]
requiredEvidence: []
triggers: [outline, volume, arc, structure]
---

## Prompt Fragment
Build a durable story outline with premise, mainline stages, volume goals, escalation logic, antagonist pressure, and payoff cadence. Keep it broad enough for long-form drafting.
When market/rank evidence is present, convert the evidence into a concrete author-side outline instead of summarizing the board.

## Quality Checklist
- Each stage changes stakes or relationship dynamics.
- Main plot and side lines have clear functions.
- Ending payoff answers the opening promise.
- Mixed market-plus-outline answers include premise lock, golden-finger boundary, first-three-chapter beats, 20-chapter runway, volume arcs, antagonist pressure, and repeatable chapter task loop.

## Guardrails
- Preserve explicit user/project constraints before adding new plot.
- Label generated structure as creative inference.

## Negative Rules
- Do not drift into per-chapter detail unless asked.
- Do not add unrelated lore that does not drive conflict.
- Do not replace the requested outline with a short market summary.

## Output Contract
Return Premise Lock, Market-Informed Positioning, First-Three-Chapter Beats, Stage Outline, Character/Antagonist Lines, Payoff Plan, Risk Fixes.

## Examples
- outline_building returns a staged long-form plan using memory and market signals.
