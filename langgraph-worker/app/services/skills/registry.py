from __future__ import annotations

from pathlib import Path
from typing import Any

from app.config import settings
from app.services.intents import Intent, IntentDecision
from app.services.skills.runtime_skill import RuntimeSkill, SkillSelection

DEFAULT_MAX_SKILL_CHARS = 6000
SECTION_TITLES = {
    "applies to": "appliesTo",
    "allowed tools": "allowedTools",
    "required evidence": "requiredEvidence",
    "prompt fragment": "promptFragment",
    "quality checklist": "qualityChecklist",
    "guardrails": "guardrails",
    "negative rules": "negativeRules",
    "output contract": "outputContract",
    "examples": "examples",
}


class SkillRegistry:
    _cache: tuple[RuntimeSkill, ...] | None = None

    def __init__(
        self,
        packs_dir: Path | None = None,
        max_skill_chars: int | None = None,
        runtime_skills: list[dict[str, Any]] | None = None,
    ) -> None:
        self.packs_dir = packs_dir or Path(__file__).resolve().parent / "packs"
        self.max_skill_chars = max_skill_chars or int(
            getattr(settings, "agent_max_skill_chars", DEFAULT_MAX_SKILL_CHARS)
        )
        self.runtime_skills = list(runtime_skills or [])

    @classmethod
    def clear_cache(cls) -> None:
        cls._cache = None

    def load_all(self) -> tuple[RuntimeSkill, ...]:
        if SkillRegistry._cache is None:
            skills = [self._load_pack(path) for path in sorted(self.packs_dir.glob("*.md"))]
            SkillRegistry._cache = tuple(sorted(skills, key=lambda skill: skill.skillId))
        if not self.runtime_skills:
            return SkillRegistry._cache
        merged = {skill.skillId: skill for skill in SkillRegistry._cache}
        for payload in self.runtime_skills:
            skill = self._runtime_skill_from_payload(payload)
            if skill is not None:
                merged[skill.skillId] = skill
        return tuple(sorted(merged.values(), key=lambda skill: skill.skillId))

    def select_for_intent(self, decision: IntentDecision, max_chars: int | None = None) -> SkillSelection:
        budget = self.max_skill_chars if max_chars is None else max(0, int(max_chars))
        selected_intents = self._decision_intents(decision)
        selected_tokens = {intent.value for intent in selected_intents}
        skills = tuple(
            skill
            for skill in self.load_all()
            if self._skill_matches_intent(skill, selected_intents, selected_tokens)
        )
        prompt = self._render_with_budget(skills, budget)
        return SkillSelection(skills=skills, prompt=prompt)

    def select_for_task(self, task_context: dict[str, Any], max_chars: int | None = None) -> SkillSelection:
        budget = self.max_skill_chars if max_chars is None else max(0, int(max_chars))
        tokens = self._task_tokens(task_context)
        skills = [
            skill
            for skill in self.load_all()
            if self._skill_matches_task(skill, tokens)
        ]
        skills.sort(key=lambda skill: (0 if set(skill.appliesTo or ()).intersection(tokens) else 1, skill.skillId))
        if self._needs_rank_arbitration(task_context):
            arbitration = self._find_skill("rank-evidence-arbitration")
            if arbitration is not None and arbitration not in skills:
                skills.insert(0, arbitration)
        prompt = self._render_with_budget(tuple(skills), budget)
        return SkillSelection(skills=tuple(skills), prompt=prompt)

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
            appliesTo=tuple(str(value) for value in self._as_list(metadata.get("appliesTo", [])))
            or tuple(self._parse_bullets(sections.get("appliesTo", ""))),
            allowedTools=tuple(str(value) for value in self._as_list(metadata.get("allowedTools", [])))
            or tuple(self._parse_bullets(sections.get("allowedTools", ""))),
            requiredEvidence=tuple(str(value) for value in self._as_list(metadata.get("requiredEvidence", [])))
            or tuple(self._parse_bullets(sections.get("requiredEvidence", ""))),
            promptFragment=sections.get("promptFragment", ""),
            qualityChecklist=tuple(self._parse_bullets(sections.get("qualityChecklist", ""))),
            negativeRules=tuple(self._parse_bullets(sections.get("negativeRules", ""))),
            outputContract=sections.get("outputContract", ""),
            guardrails=tuple(self._parse_bullets(sections.get("guardrails", ""))),
            examples=tuple(self._parse_bullets(sections.get("examples", ""))),
        )

    def _runtime_skill_from_payload(self, payload: dict[str, Any]) -> RuntimeSkill | None:
        skill_id = str(payload.get("skillId") or "").strip()
        if not skill_id:
            return None
        intent_values: list[Intent] = []
        for value in self._as_list(payload.get("intents", [])):
            try:
                intent_values.append(Intent(value))
            except ValueError:
                continue
        if not intent_values:
            return None
        prompt_fragment = str(
            payload.get("promptFragment")
            or payload.get("content")
            or ""
        )
        guardrails = payload.get("guardrails")
        negative_rules = payload.get("negativeRules")
        output_contract = payload.get("outputContract") or ""
        return RuntimeSkill(
            skillId=skill_id,
            version=str(payload.get("version") or "backend"),
            intents=tuple(intent_values),
            triggers=tuple(str(value) for value in self._as_list(payload.get("triggers", []))),
            appliesTo=tuple(str(value) for value in self._as_list(payload.get("appliesTo", []))),
            allowedTools=tuple(str(value) for value in self._as_list(payload.get("allowedTools", []))),
            requiredEvidence=tuple(str(value) for value in self._as_list(payload.get("requiredEvidence", []))),
            promptFragment=prompt_fragment,
            qualityChecklist=tuple(str(value) for value in self._as_list(payload.get("qualityChecklist", []))),
            negativeRules=tuple(str(value) for value in self._as_list(negative_rules or [])),
            outputContract=str(output_contract),
            guardrails=tuple(str(value) for value in self._as_list(guardrails or [])),
            examples=tuple(str(value) for value in self._as_list(payload.get("examples", []))),
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

    def _task_tokens(self, task_context: dict[str, Any]) -> set[str]:
        tokens: set[str] = set()
        intent = str(task_context.get("intent") or "").strip()
        if intent:
            tokens.add(intent)
        graph = task_context.get("taskGraph")
        if isinstance(graph, dict):
            for key in ("nodes", "tasks"):
                for node in list(graph.get(key) or []):
                    if not isinstance(node, dict):
                        continue
                    for field in ("type", "intent", "id", "perspective"):
                        value = str(node.get(field) or "").strip()
                        if value:
                            tokens.add(value)
        contract = task_context.get("evidenceContract")
        if isinstance(contract, dict):
            status = str(contract.get("status") or "").strip()
            if status:
                tokens.add(status)
            for warning in list(contract.get("warnings") or []):
                if isinstance(warning, dict):
                    code = str(warning.get("code") or "").strip()
                    if code:
                        tokens.add(code)
                elif warning:
                    tokens.add(str(warning))
            if contract.get("rejectedGroups"):
                tokens.add("rejected_snapshot_groups")
                tokens.add("mixed_structured_rank_snapshot")
        return tokens

    def _skill_matches_task(self, skill: RuntimeSkill, tokens: set[str]) -> bool:
        applies_to = set(skill.appliesTo or ())
        if applies_to and applies_to.intersection(tokens):
            return True
        intent_values = {intent.value for intent in skill.intents}
        return bool(intent_values.intersection(tokens))

    def _skill_matches_intent(
        self,
        skill: RuntimeSkill,
        selected_intents: set[Intent],
        selected_tokens: set[str],
    ) -> bool:
        applies_to = set(skill.appliesTo or ())
        if applies_to:
            return bool(applies_to.intersection(selected_tokens))
        return any(intent in selected_intents for intent in skill.intents)

    def _needs_rank_arbitration(self, task_context: dict[str, Any]) -> bool:
        tokens = self._task_tokens(task_context)
        return bool(
            {
                "mixed_structured_rank_snapshot",
                "degraded_directional",
                "conflict",
                "rejected_snapshot_groups",
            }.intersection(tokens)
        )

    def _find_skill(self, skill_id: str) -> RuntimeSkill | None:
        for skill in self.load_all():
            if skill.skillId == skill_id:
                return skill
        return None
