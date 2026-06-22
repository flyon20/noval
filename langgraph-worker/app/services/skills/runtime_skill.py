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
    qualityChecklist: tuple[str, ...] = field(default_factory=tuple)
    negativeRules: tuple[str, ...] = field(default_factory=tuple)
    outputContract: str = ""

    def compact_prompt(self) -> str:
        sections = [
            f"### Skill: {self.skillId} v{self.version}",
            self.promptFragment.strip(),
        ]
        if self.qualityChecklist:
            sections.append("Checklist:\n" + "\n".join(f"- {item}" for item in self.qualityChecklist))
        if self.negativeRules:
            sections.append("Negative Rules:\n" + "\n".join(f"- {item}" for item in self.negativeRules))
        if self.outputContract.strip():
            sections.append("Output Contract:\n" + self.outputContract.strip())
        return "\n".join(section for section in sections if section.strip())


@dataclass(frozen=True)
class SkillSelection:
    skills: tuple[RuntimeSkill, ...]
    prompt: str

    @property
    def skill_ids(self) -> tuple[str, ...]:
        return tuple(skill.skillId for skill in self.skills)
