---
skillId: project-memory-extract
version: 1.0.0
intents: [followup_context]
appliesTo: [project_memory_extract, memory_extract]
requestedCapabilities: [memory.project.read]
requiredEvidence: []
triggers: [memory, project, preference, constraint]
---

## Prompt Fragment
Extract only durable project facts, constraints, decisions, and preferences that should help future turns. Keep raw chat out of long-term memory.

## Quality Checklist
- Candidate memory is scoped to project, thread, or user.
- Temporary preferences remain thread/project candidates.
- Long-term user memory is conservative.

## Guardrails
- Do not store secrets, credentials, or raw full conversation text.
- Use candidate status unless explicit confirmation exists.

## Negative Rules
- Do not convert every chat message into memory.
- Do not recall across projects without authorization.

## Output Contract
Return Memory Candidate, Scope, Type, Confidence, Reason.

## Examples
- project_memory_extract turns an explicit setting constraint into a project candidate.
