from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.services.intents import Intent


@dataclass(frozen=True)
class RuntimeSkill:
    skillId: str
    version: str
    intents: tuple[Intent, ...]
    triggers: tuple[str, ...]
    instructions: str
    title: str = ""
    description: str = ""
    appliesTo: tuple[str, ...] = field(default_factory=tuple)
    requestedCapabilities: tuple[str, ...] = field(default_factory=tuple)
    metadata: dict[str, Any] = field(default_factory=dict)
    requiredEvidence: tuple[str, ...] = field(default_factory=tuple)
    qualityChecklist: tuple[str, ...] = field(default_factory=tuple)
    negativeRules: tuple[str, ...] = field(default_factory=tuple)
    outputContract: str = ""
    guardrails: tuple[str, ...] = field(default_factory=tuple)
    examples: tuple[str, ...] = field(default_factory=tuple)
    source: str = "local"
    status: str = "ACTIVE"
    contentHash: str = ""
    candidateId: int | None = None
    sourceTraceId: str | None = None
    inputSchema: dict[str, Any] = field(default_factory=dict)
    outputSchema: dict[str, Any] = field(default_factory=dict)

    @property
    def id(self) -> str:
        return self.skillId

    def activation_prompt(self) -> str:
        sections = [
            f"### Skill: {self.skillId} v{self.version}",
            self.instructions.strip(),
        ]
        if self.requiredEvidence:
            sections.append("Required Evidence:\n" + "\n".join(f"- {item}" for item in self.requiredEvidence))
        if self.qualityChecklist:
            sections.append("Checklist:\n" + "\n".join(f"- {item}" for item in self.qualityChecklist))
        if self.guardrails:
            sections.append("Guardrails:\n" + "\n".join(f"- {item}" for item in self.guardrails))
        if self.negativeRules:
            sections.append("Negative Rules:\n" + "\n".join(f"- {item}" for item in self.negativeRules))
        if self.outputContract.strip():
            sections.append("Output Contract:\n" + self.outputContract.strip())
        if self.examples:
            sections.append("Examples:\n" + "\n".join(f"- {item}" for item in self.examples[:3]))
        return "\n".join(section for section in sections if section.strip())

    def trace_pin(self) -> dict[str, str | int | None]:
        return {
            "skillId": self.skillId,
            "version": self.version,
            "contentHash": self.contentHash,
            "status": self.status,
            "source": self.source,
            "candidateId": self.candidateId,
            "sourceTraceId": self.sourceTraceId,
        }
