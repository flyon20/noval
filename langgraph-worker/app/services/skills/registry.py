from __future__ import annotations

from pathlib import Path
from typing import Any

from app.config import settings
from app.services.intents import Intent, IntentDecision
from app.services.skills.runtime_skill import RuntimeSkill, SkillSelection

DEFAULT_MAX_SKILL_CHARS = 6000
SECTION_TITLES = {
    "prompt fragment": "promptFragment",
    "quality checklist": "qualityChecklist",
    "negative rules": "negativeRules",
    "output contract": "outputContract",
}


class SkillRegistry:
    _cache: tuple[RuntimeSkill, ...] | None = None

    def __init__(self, packs_dir: Path | None = None, max_skill_chars: int | None = None) -> None:
        self.packs_dir = packs_dir or Path(__file__).resolve().parent / "packs"
        self.max_skill_chars = max_skill_chars or int(
            getattr(settings, "agent_max_skill_chars", DEFAULT_MAX_SKILL_CHARS)
        )

    @classmethod
    def clear_cache(cls) -> None:
        cls._cache = None

    def load_all(self) -> tuple[RuntimeSkill, ...]:
        if SkillRegistry._cache is None:
            skills = [self._load_pack(path) for path in sorted(self.packs_dir.glob("*.md"))]
            SkillRegistry._cache = tuple(sorted(skills, key=lambda skill: skill.skillId))
        return SkillRegistry._cache

    def select_for_intent(self, decision: IntentDecision, max_chars: int | None = None) -> SkillSelection:
        budget = self.max_skill_chars if max_chars is None else max(0, int(max_chars))
        selected_intents = self._decision_intents(decision)
        skills = tuple(
            skill
            for skill in self.load_all()
            if any(intent in selected_intents for intent in skill.intents)
        )
        prompt = self._render_with_budget(skills, budget)
        return SkillSelection(skills=skills, prompt=prompt)

    def _decision_intents(self, decision: IntentDecision) -> set[Intent]:
        intents = {decision.primaryIntent, *decision.subIntents}
        if decision.toolNeeds.needsRankData:
            intents.add(Intent.market_scan)
        return intents

    def _render_with_budget(self, skills: tuple[RuntimeSkill, ...], budget: int) -> str:
        fragments: list[str] = []
        used = 0
        for skill in skills:
            fragment = skill.compact_prompt()
            separator = "\n\n" if fragments else ""
            available = budget - used - len(separator)
            if available <= 0:
                break
            if len(fragment) > available:
                fragment = fragment[:available].rstrip()
            fragments.append(fragment)
            used += len(separator) + len(fragment)
        return "\n\n".join(fragment for fragment in fragments if fragment)

    def _load_pack(self, path: Path) -> RuntimeSkill:
        metadata, body = self._split_front_matter(path.read_text(encoding="utf-8"))
        sections = self._parse_sections(body)
        return RuntimeSkill(
            skillId=str(metadata["skillId"]),
            version=str(metadata["version"]),
            intents=tuple(Intent(value) for value in self._as_list(metadata["intents"])),
            triggers=tuple(str(value) for value in self._as_list(metadata.get("triggers", []))),
            promptFragment=sections.get("promptFragment", ""),
            qualityChecklist=tuple(self._parse_bullets(sections.get("qualityChecklist", ""))),
            negativeRules=tuple(self._parse_bullets(sections.get("negativeRules", ""))),
            outputContract=sections.get("outputContract", ""),
        )

    def _split_front_matter(self, text: str) -> tuple[dict[str, Any], str]:
        if not text.startswith("---\n"):
            raise ValueError("Skill pack missing YAML front matter")
        _, front_matter, body = text.split("---", 2)
        return self._parse_front_matter(front_matter), body

    def _parse_front_matter(self, text: str) -> dict[str, Any]:
        metadata: dict[str, Any] = {}
        current_key: str | None = None
        for raw_line in text.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            if line.startswith("- ") and current_key:
                metadata.setdefault(current_key, []).append(line[2:].strip().strip("\"'"))
                continue
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            current_key = key.strip()
            value = value.strip()
            if value.startswith("[") and value.endswith("]"):
                metadata[current_key] = [
                    item.strip().strip("\"'")
                    for item in value[1:-1].split(",")
                    if item.strip()
                ]
            elif value:
                metadata[current_key] = value.strip("\"'")
            else:
                metadata[current_key] = []
        for required in ("skillId", "version", "intents"):
            if required not in metadata:
                raise ValueError(f"Skill pack missing {required}")
        return metadata

    def _parse_sections(self, body: str) -> dict[str, str]:
        sections: dict[str, list[str]] = {}
        current: str | None = None
        for line in body.splitlines():
            normalized = line.strip().lstrip("#").strip().lower()
            if normalized in SECTION_TITLES:
                current = SECTION_TITLES[normalized]
                sections.setdefault(current, [])
                continue
            if current:
                sections[current].append(line)
        return {key: "\n".join(value).strip() for key, value in sections.items()}

    def _as_list(self, value: Any) -> list[str]:
        if isinstance(value, list):
            return [str(item) for item in value]
        return [str(value)]

    def _parse_bullets(self, text: str) -> list[str]:
        items = []
        for line in text.splitlines():
            cleaned = line.strip()
            if cleaned.startswith("- "):
                cleaned = cleaned[2:].strip()
            if cleaned:
                items.append(cleaned)
        return items
