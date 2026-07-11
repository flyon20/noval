from __future__ import annotations

from dataclasses import dataclass, field

from app.services.intents import Intent


@dataclass(frozen=True)
class RuntimeSkill:
    skillId: str
    version: str
    intents: tuple[Intent, ...]
    triggers: tuple[str, ...]
    promptFragment: str
    appliesTo: tuple[str, ...] = field(default_factory=tuple)
    allowedTools: tuple[str, ...] = field(default_factory=tuple)
    requiredEvidence: tuple[str, ...] = field(default_factory=tuple)
    qualityChecklist: tuple[str, ...] = field(default_factory=tuple)
    negativeRules: tuple[str, ...] = field(default_factory=tuple)
    outputContract: str = ""
    guardrails: tuple[str, ...] = field(default_factory=tuple)
    examples: tuple[str, ...] = field(default_factory=tuple)

    @property
    def id(self) -> str:
        return self.skillId

    def compact_prompt(self) -> str:
        sections = [
            f"### Skill: {self.skillId} v{self.version}",
            self.promptFragment.strip(),
        ]
        if self.allowedTools:
            sections.append("Allowed Tools:\n" + "\n".join(f"- {item}" for item in self.allowedTools))
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


@dataclass(frozen=True)
class SkillSelection:
    skills: tuple[RuntimeSkill, ...]
    prompt: str

    @property
    def skill_ids(self) -> tuple[str, ...]:
        return tuple(skill.skillId for skill in self.skills)
