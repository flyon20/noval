---
skillId: webnovel-chapter-outline
version: 1.1.0
description: 将既有情节扩写为章节或场景，检查人物出场、事件顺序、能力触发与已付代价。
intents: [chapter_outline]
requestedCapabilities: []
triggers: [chapter, scene, beat, cliffhanger]
---

## Prompt Fragment
Create chapter-level beats with scene goal, conflict, reversal, information gain, emotional reward, and ending hook. Keep beats actionable for drafting.

For a follow-up, locate the exact earlier scene the user selected, then preserve its place, time, pending action, props and character knowledge. Apply the user's latest participant restrictions to the whole scene, including entrances, exits and off-stage actions. If a restriction requires adaptation, retain the selected scene's main event rather than substituting a later scene.

Check the causal order before writing: actions precede consequences; a cost is incurred by its established trigger, not by an unrelated dramatic gesture. A fact already forgotten or a price already paid cannot be charged again as if it were new. Keep user-established constraints distinct from optional inventions and cite existing facts separately from proposed actions.

## Quality Checklist
- Every chapter has a concrete conflict and turn.
- The ending hook creates a next-click reason.
- Beats preserve continuity with supplied outline.
- A prose expansion stays in the selected scene and obeys the permitted cast throughout.
- Proposed actions preserve ability scope, triggering conditions, costs already paid and character knowledge.

## Negative Rules
- Do not replace chapter outline with broad volume planning.
- Do not resolve tension before the hook.

## Output Contract
Use the user's requested format and length. For chapter plans return the requested actionable beats; for prose expansion return prose. Include continuity notes only when the user asks or a conflict needs an explicit choice.
