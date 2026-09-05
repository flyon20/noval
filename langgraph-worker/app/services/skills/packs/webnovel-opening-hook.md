---
skillId: webnovel-opening-hook
version: 1.0.0
intents: [opening_strategy]
appliesTo: [opening_hook]
requestedCapabilities: [skill.activate, memory.project.read, book.read]
requiredEvidence: []
triggers: [opening, hook, first-three-chapters]
---

## Prompt Fragment
Design the opening hook around immediate pressure, visible unfairness, a concrete reward loop, and a chapter-end pull into the next scene.

## Quality Checklist
- The first scene contains a legible problem and pressure.
- The hook exposes the book's core promise early.
- The first three chapters each escalate reader curiosity.

## Guardrails
- Keep hook advice executable at scene level.
- Do not claim market rank support unless evidence is present.

## Negative Rules
- Do not open with abstract exposition.
- Do not delay the core fantasy beyond the opening movement.

## Output Contract
Return Opening Hook, First Scene Beat, Chapter 1-3 Pull, Promise Check.

## Examples
- opening_strategy turns a premise into a first-three-chapter hook plan.
