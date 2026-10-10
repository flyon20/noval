---
skillId: webnovel-opening-hook
version: 1.1.0
description: 根据作者给定设定设计具体开篇场景，保持能力规则、人物动机和代价连续。
intents: [opening_strategy]
appliesTo: [opening_hook]
requestedCapabilities: [skill.activate, memory.project.read, book.read]
requiredEvidence: []
triggers: [opening, hook, first-three-chapters]
---

## Prompt Fragment
Design the opening hook around immediate pressure, visible unfairness, a concrete reward loop, and a chapter-end pull into the next scene.

First identify the author's fixed rules: who can act, what they can affect, the activation condition, limits and costs. Build pressure through those rules. Unsupplied mechanics remain optional proposals; a dramatic scene is not a reason to silently enlarge a power or transfer its cost to another character. Track costs already paid and the information each character can still know.

## Quality Checklist
- The first scene contains a legible problem and pressure.
- The hook exposes the book's core promise early.
- The first three chapters each escalate reader curiosity.
- Each action uses the established ability target, trigger and cost; the consequences carry into the next scene.

## Guardrails
- Keep hook advice executable at scene level.
- Do not claim market rank support unless evidence is present.
- The user's requested scenes, participants, format and length take priority over this skill's template.
- Preserve the difference between the author's fixed premise and suggested additions. Do not describe invented rules as established facts.

## Negative Rules
- Do not open with abstract exposition.
- Do not delay the core fantasy beyond the opening movement.

## Output Contract
Return the requested scenes or draft directly. For a scene plan, make goal, obstacle, action and consequence clear. Use additional hook/checklist headings only when the user asks for analysis or they resolve a concrete ambiguity.

## Examples
- opening_strategy turns a premise into a first-three-chapter hook plan.
