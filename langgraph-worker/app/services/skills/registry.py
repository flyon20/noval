from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from app.services.harness.validators import PromptInjectionValidator
from app.services.intents import Intent, IntentDecision
from app.services.skills.runtime_skill import RuntimeSkill

RUNTIME_SKILL_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,119}$")
RUNTIME_SKILL_HASH_PATTERN = re.compile(r"^[a-f0-9]{64}$")
RUNTIME_SKILL_SCHEMA_MAX_CHARS = 32_768
RUNTIME_SKILL_SCHEMA_MAX_DEPTH = 12
RUNTIME_SKILL_SCHEMA_MAX_NODES = 512
RUNTIME_SKILL_SCHEMA_MAX_ENTRIES = 128
JSON_SCHEMA_TYPES = frozenset({"array", "boolean", "integer", "null", "number", "object", "string"})
GOVERNED_RUNTIME_CAPABILITY_ALLOWLIST = frozenset({
    "market.read",
    "market.refresh",
    "book.read",
    "project.resolve",
    "project.retrieve",
    "project.continuity.read",
    "memory.project.read",
    "skill.activate",
    "review.reader",
    "review.editor",
})
SECTION_TITLES = {
    "applies to": "appliesTo",
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
        runtime_skills: list[dict[str, Any]] | None = None,
    ) -> None:
        self.packs_dir = packs_dir or Path(__file__).resolve().parent / "packs"
        self.runtime_skills = list(runtime_skills or [])
        self.runtime_skill_rejections: list[dict[str, str]] = []

    @classmethod
    def clear_cache(cls) -> None:
        cls._cache = None

    def load_all(self) -> tuple[RuntimeSkill, ...]:
        if SkillRegistry._cache is None:
            pack_paths = set(self.packs_dir.glob("*.md"))
            pack_paths.update(self.packs_dir.rglob("SKILL.md"))
            skills = [
                self._load_pack(path)
                for path in sorted(pack_paths, key=lambda item: item.as_posix())
            ]
            SkillRegistry._cache = tuple(sorted(skills, key=lambda skill: skill.skillId))
        self.runtime_skill_rejections = []
        if not self.runtime_skills:
            return SkillRegistry._cache
        merged = {skill.skillId: skill for skill in SkillRegistry._cache}
        for payload in self.runtime_skills:
            skill = self._runtime_skill_from_payload(payload)
            if skill is not None:
                merged[skill.skillId] = skill
        return tuple(sorted(merged.values(), key=lambda skill: skill.skillId))

    def query_for_intent(self, decision: IntentDecision) -> tuple[RuntimeSkill, ...]:
        selected_intents = self._decision_intents(decision)
        selected_tokens = {intent.value for intent in selected_intents}
        return tuple(
            skill
            for skill in self.load_all()
            if self._skill_matches_intent(skill, selected_intents, selected_tokens)
        )

    def query_for_task(self, task_context: dict[str, Any]) -> tuple[RuntimeSkill, ...]:
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
        return tuple(skills)

    def _decision_intents(self, decision: IntentDecision) -> set[Intent]:
        intents = {decision.primaryIntent, *decision.subIntents}
        if decision.toolNeeds.needsRankData:
            intents.add(Intent.market_scan)
        return intents

    def _load_pack(self, path: Path) -> RuntimeSkill:
        content = path.read_text(encoding="utf-8")
        metadata, body = self._split_front_matter(content)
        skill_id = str(metadata["skillId"]).strip()
        if not RUNTIME_SKILL_ID_PATTERN.fullmatch(skill_id):
            raise ValueError(f"{path.name}: invalid skillId/name")
        sections = self._parse_sections(body)
        requested_capabilities = self._local_requested_capabilities(
            metadata.get("requestedCapabilities", []),
            path=path,
        )
        return RuntimeSkill(
            skillId=skill_id,
            version=str(metadata["version"]),
            intents=tuple(Intent(value) for value in self._as_list(metadata["intents"])),
            triggers=tuple(str(value) for value in self._as_list(metadata.get("triggers", []))),
            title=str(metadata.get("title") or metadata["skillId"]),
            description=str(metadata.get("description") or metadata["skillId"]),
            appliesTo=tuple(str(value) for value in self._as_list(metadata.get("appliesTo", [])))
            or tuple(self._parse_bullets(sections.get("appliesTo", ""))),
            requestedCapabilities=requested_capabilities,
            metadata=self._local_skill_metadata(metadata, path=path),
            requiredEvidence=tuple(str(value) for value in self._as_list(metadata.get("requiredEvidence", [])))
            or tuple(self._parse_bullets(sections.get("requiredEvidence", ""))),
            instructions=sections.get("promptFragment", ""),
            qualityChecklist=tuple(self._parse_bullets(sections.get("qualityChecklist", ""))),
            negativeRules=tuple(self._parse_bullets(sections.get("negativeRules", ""))),
            outputContract=sections.get("outputContract", ""),
            guardrails=tuple(self._parse_bullets(sections.get("guardrails", ""))),
            examples=tuple(self._parse_bullets(sections.get("examples", ""))),
            source="local",
            status="ACTIVE",
            contentHash=self._content_hash(content),
        )

    def _runtime_skill_from_payload(self, payload: dict[str, Any]) -> RuntimeSkill | None:
        skill_id = str(payload.get("skillId") or "").strip()
        if not RUNTIME_SKILL_ID_PATTERN.fullmatch(skill_id):
            self._reject_runtime_skill(skill_id, "skill_id_invalid")
            return None
        if str(payload.get("status") or "").strip().upper() != "ACTIVE":
            self._reject_runtime_skill(skill_id, "status_not_active")
            return None
        raw_version = payload.get("version")
        if isinstance(raw_version, (dict, list, tuple, set)):
            self._reject_runtime_skill(skill_id, "version_invalid")
            return None
        version = str(raw_version or "").strip()
        if not version:
            self._reject_runtime_skill(skill_id, "version_missing")
            return None
        content_value = payload.get("content")
        if not isinstance(content_value, str):
            self._reject_runtime_skill(skill_id, "content_invalid")
            return None
        content = content_value
        if not content.strip():
            self._reject_runtime_skill(skill_id, "content_missing")
            return None
        content_hash = str(payload.get("contentHash") or "").strip().lower()
        if not RUNTIME_SKILL_HASH_PATTERN.fullmatch(content_hash):
            self._reject_runtime_skill(skill_id, "content_hash_invalid")
            return None
        if self._content_hash(content) != content_hash:
            self._reject_runtime_skill(skill_id, "content_hash_mismatch")
            return None
        intent_values: list[Intent] = []
        raw_intents = payload.get("intents")
        if not isinstance(raw_intents, list) or not raw_intents:
            self._reject_runtime_skill(skill_id, "intents_missing")
            return None
        for value in self._as_list(raw_intents):
            try:
                intent_values.append(Intent(value))
            except ValueError:
                self._reject_runtime_skill(skill_id, "intent_not_allowlisted")
                return None
        if not intent_values:
            self._reject_runtime_skill(skill_id, "intents_missing")
            return None
        description = payload.get("description")
        if not isinstance(description, str) or not description.strip() or len(description.strip()) > 1_000:
            self._reject_runtime_skill(skill_id, "description_invalid")
            return None
        requested_capabilities = payload.get("requestedCapabilities")
        if not isinstance(requested_capabilities, list):
            self._reject_runtime_skill(skill_id, "requested_capabilities_invalid")
            return None
        normalized_capabilities = tuple(dict.fromkeys(
            str(value).strip() for value in requested_capabilities if str(value).strip()
        ))
        if len(normalized_capabilities) != len(requested_capabilities) or any(
            value not in GOVERNED_RUNTIME_CAPABILITY_ALLOWLIST for value in normalized_capabilities
        ):
            self._reject_runtime_skill(skill_id, "capability_not_allowlisted")
            return None
        skill_metadata = payload.get("skillMetadata")
        if not isinstance(skill_metadata, dict) or not self._metadata_is_bounded(skill_metadata):
            self._reject_runtime_skill(skill_id, "skill_metadata_invalid")
            return None
        required_evidence = self._prompt_text_list(
            payload.get("requiredEvidence"),
            field_name="required_evidence",
            allow_scalar=False,
        )
        if required_evidence is None:
            self._reject_runtime_skill(skill_id, "required_evidence_invalid")
            return None
        quality_checklist = self._prompt_text_list(
            payload.get("qualityChecklist"),
            field_name="quality_checklist",
            allow_scalar=False,
        )
        if quality_checklist is None:
            self._reject_runtime_skill(skill_id, "quality_checklist_invalid")
            return None
        examples = self._prompt_text_list(
            payload.get("examples"),
            field_name="examples",
            allow_scalar=False,
        )
        if examples is None:
            self._reject_runtime_skill(skill_id, "examples_invalid")
            return None
        guardrails = self._prompt_text_list(
            payload.get("guardrails"),
            field_name="guardrails",
            allow_scalar=True,
        )
        if guardrails is None:
            self._reject_runtime_skill(skill_id, "guardrails_invalid")
            return None
        negative_rules = self._prompt_text_list(
            payload.get("negativeRules"),
            field_name="negative_rules",
            allow_scalar=True,
        )
        if negative_rules is None:
            self._reject_runtime_skill(skill_id, "negative_rules_invalid")
            return None
        output_contract = payload.get("outputContract")
        if output_contract is None:
            output_contract = ""
        if not isinstance(output_contract, str):
            self._reject_runtime_skill(skill_id, "output_contract_invalid")
            return None
        triggers = self._prompt_text_list(
            payload.get("triggers"),
            field_name="triggers",
            allow_scalar=False,
        )
        if triggers is None:
            self._reject_runtime_skill(skill_id, "triggers_invalid")
            return None
        applies_to = self._prompt_text_list(
            payload.get("appliesTo"),
            field_name="applies_to",
            allow_scalar=False,
        )
        if applies_to is None:
            self._reject_runtime_skill(skill_id, "applies_to_invalid")
            return None
        input_schema = self._runtime_schema_from_payload(payload, "inputSchema", skill_id)
        if input_schema is None:
            return None
        output_schema = self._runtime_schema_from_payload(payload, "outputSchema", skill_id)
        if output_schema is None:
            return None
        prompt_material = self._prompt_material(
            version=version,
            content=content,
            description=description,
            requested_capabilities=normalized_capabilities,
            required_evidence=required_evidence,
            quality_checklist=quality_checklist,
            guardrails=guardrails,
            negative_rules=negative_rules,
            output_contract=output_contract,
            examples=examples,
        )
        injection = PromptInjectionValidator().validate(prompt_material)
        if not injection.valid:
            self._reject_runtime_skill(skill_id, injection.reason)
            return None
        return RuntimeSkill(
            skillId=skill_id,
            version=version,
            intents=tuple(intent_values),
            triggers=triggers,
            title=str(payload.get("title") or skill_id).strip(),
            description=description.strip(),
            appliesTo=applies_to,
            requestedCapabilities=normalized_capabilities,
            metadata=dict(skill_metadata),
            requiredEvidence=required_evidence,
            instructions=content,
            qualityChecklist=quality_checklist,
            negativeRules=negative_rules,
            outputContract=output_contract,
            guardrails=guardrails,
            examples=examples,
            source=str(payload.get("source") or "backend"),
            status="ACTIVE",
            contentHash=content_hash,
            candidateId=self._optional_int(payload.get("candidateId")),
            sourceTraceId=self._optional_text(payload.get("sourceTraceId")),
            inputSchema=input_schema,
            outputSchema=output_schema,
        )

    def _prompt_text_list(
        self,
        value: Any,
        *,
        field_name: str,
        allow_scalar: bool,
    ) -> tuple[str, ...] | None:
        if value is None:
            return ()
        if isinstance(value, str):
            if not allow_scalar:
                return None
            values = [value]
        elif isinstance(value, list):
            if any(not isinstance(item, str) for item in value):
                return None
            values = value
        else:
            return None
        max_items = 32 if field_name == "examples" else 64
        if len(values) > max_items:
            return None
        normalized = tuple(item.strip() for item in values)
        if any(not item or len(item) > 4_000 for item in normalized):
            return None
        if sum(len(item) for item in normalized) > 16_000:
            return None
        return normalized

    def _prompt_material(
        self,
        *,
        version: str,
        content: str,
        description: str,
        requested_capabilities: tuple[str, ...],
        required_evidence: tuple[str, ...],
        quality_checklist: tuple[str, ...],
        guardrails: tuple[str, ...],
        negative_rules: tuple[str, ...],
        output_contract: str,
        examples: tuple[str, ...],
    ) -> str:
        values = (
            version,
            description,
            content,
            *requested_capabilities,
            *required_evidence,
            *quality_checklist,
            *guardrails,
            *negative_rules,
            output_contract,
            *examples,
        )
        return " ".join(" ".join(value.split()) for value in values if value.strip())

    def _local_requested_capabilities(self, value: Any, *, path: Path) -> tuple[str, ...]:
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            raise ValueError(f"{path.name}: requestedCapabilities must be a string list")
        capabilities = tuple(dict.fromkeys(item.strip() for item in value if item.strip()))
        unknown = sorted(set(capabilities) - GOVERNED_RUNTIME_CAPABILITY_ALLOWLIST)
        if unknown:
            raise ValueError(f"{path.name}: unsupported requestedCapabilities: {', '.join(unknown)}")
        return capabilities

    def _local_skill_metadata(self, value: dict[str, Any], *, path: Path) -> dict[str, Any]:
        metadata: dict[str, Any] = {"legacyFormat": False}
        raw_category = value.get("category")
        if raw_category is not None:
            category = str(raw_category).strip().upper()
            if category not in {"TASK", "STYLE"}:
                raise ValueError(f"{path.name}: category must be TASK or STYLE")
            metadata["category"] = category

        raw_style_mode = value.get("styleMode")
        if raw_style_mode is not None:
            style_mode = str(raw_style_mode).strip().lower()
            if style_mode not in {"default", "skill"}:
                raise ValueError(f"{path.name}: styleMode must be default or skill")
            metadata["styleMode"] = style_mode

        raw_tags = value.get("tags")
        if raw_tags is not None:
            if not isinstance(raw_tags, list) or any(not isinstance(tag, str) for tag in raw_tags):
                raise ValueError(f"{path.name}: tags must be a string list")
            tags = tuple(dict.fromkeys(tag.strip() for tag in raw_tags if tag.strip()))
            if len(tags) > 12 or any(len(tag) > 32 for tag in tags):
                raise ValueError(f"{path.name}: tags must contain at most 12 short values")
            metadata["tags"] = list(tags)

        raw_enabled = value.get("shortcutEnabled")
        if raw_enabled is not None:
            normalized_enabled = str(raw_enabled).strip().lower()
            if normalized_enabled not in {"true", "false"}:
                raise ValueError(f"{path.name}: shortcutEnabled must be true or false")
            metadata["shortcutEnabled"] = normalized_enabled == "true"

        raw_label = value.get("shortcutLabel")
        if raw_label is not None:
            if isinstance(raw_label, list):
                raise ValueError(f"{path.name}: shortcutLabel must be text")
            label = str(raw_label).strip()
            if not label or len(label) > 40:
                raise ValueError(f"{path.name}: shortcutLabel must contain 1-40 characters")
            metadata["shortcutLabel"] = label

        raw_order = value.get("shortcutOrder")
        if raw_order is not None:
            try:
                order = int(str(raw_order).strip())
            except (TypeError, ValueError) as error:
                raise ValueError(f"{path.name}: shortcutOrder must be an integer") from error
            if order < 0 or order > 10_000:
                raise ValueError(f"{path.name}: shortcutOrder must be between 0 and 10000")
            metadata["shortcutOrder"] = order
        return metadata

    def _metadata_is_bounded(self, value: dict[str, Any]) -> bool:
        try:
            serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        except (TypeError, ValueError):
            return False
        if len(serialized) > 16_384:
            return False

        nodes = 0

        def visit(item: Any, depth: int) -> bool:
            nonlocal nodes
            nodes += 1
            if depth > 4 or nodes > 128:
                return False
            if isinstance(item, dict):
                return len(item) <= 64 and all(
                    isinstance(key, str) and len(key) <= 128 and visit(child, depth + 1)
                    for key, child in item.items()
                )
            if isinstance(item, list):
                return len(item) <= 64 and all(visit(child, depth + 1) for child in item)
            return item is None or isinstance(item, (str, int, float, bool))

        return visit(value, 0)

    def _runtime_schema_from_payload(
        self,
        payload: dict[str, Any],
        field_name: str,
        skill_id: str,
    ) -> dict[str, Any] | None:
        value = payload.get(field_name)
        if value is None:
            return {}
        reason = "input_schema_invalid" if field_name == "inputSchema" else "output_schema_invalid"
        if not isinstance(value, dict):
            self._reject_runtime_skill(skill_id, reason)
            return None
        try:
            serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            normalized = json.loads(serialized)
        except (TypeError, ValueError):
            self._reject_runtime_skill(skill_id, reason)
            return None
        if len(serialized) > RUNTIME_SKILL_SCHEMA_MAX_CHARS or not self._json_schema_is_valid(normalized):
            self._reject_runtime_skill(skill_id, reason)
            return None
        return normalized

    def _json_schema_is_valid(self, schema: dict[str, Any]) -> bool:
        node_count = 0

        def tree_is_bounded(value: Any, depth: int) -> bool:
            nonlocal node_count
            if depth > RUNTIME_SKILL_SCHEMA_MAX_DEPTH:
                return False
            node_count += 1
            if node_count > RUNTIME_SKILL_SCHEMA_MAX_NODES:
                return False
            if isinstance(value, dict):
                if len(value) > RUNTIME_SKILL_SCHEMA_MAX_ENTRIES or any(
                    not isinstance(key, str) for key in value
                ):
                    return False
                return all(tree_is_bounded(item, depth + 1) for item in value.values())
            if isinstance(value, list):
                if len(value) > RUNTIME_SKILL_SCHEMA_MAX_ENTRIES:
                    return False
                return all(tree_is_bounded(item, depth + 1) for item in value)
            if isinstance(value, str) and len(value) > 4_096:
                return False
            return value is None or isinstance(value, (str, int, float, bool))

        def references_are_local(value: Any) -> bool:
            if isinstance(value, dict):
                for key, item in value.items():
                    if key.startswith("$") and key.lower().endswith("ref"):
                        if not isinstance(item, str) or not item.startswith("#"):
                            return False
                    if not references_are_local(item):
                        return False
                return True
            if isinstance(value, list):
                return all(references_are_local(item) for item in value)
            return True

        def schema_node_is_valid(value: Any) -> bool:
            if isinstance(value, bool):
                return True
            if not isinstance(value, dict):
                return False
            for ref_key in ("$ref", "$dynamicRef"):
                ref = value.get(ref_key)
                if ref is not None and (not isinstance(ref, str) or not ref.startswith("#")):
                    return False
            schema_type = value.get("type")
            if isinstance(schema_type, str):
                if schema_type not in JSON_SCHEMA_TYPES:
                    return False
            elif isinstance(schema_type, list):
                if not schema_type or any(
                    not isinstance(item, str) or item not in JSON_SCHEMA_TYPES
                    for item in schema_type
                ):
                    return False
            elif schema_type is not None:
                return False
            required = value.get("required")
            if required is not None and (
                not isinstance(required, list)
                or any(not isinstance(item, str) or not item for item in required)
            ):
                return False
            enum = value.get("enum")
            if enum is not None and not isinstance(enum, list):
                return False
            for key in ("properties", "patternProperties", "$defs", "definitions", "dependentSchemas"):
                children = value.get(key)
                if children is None:
                    continue
                if not isinstance(children, dict) or any(
                    not isinstance(name, str) or not schema_node_is_valid(child)
                    for name, child in children.items()
                ):
                    return False
            dependent_required = value.get("dependentRequired")
            if dependent_required is not None and (
                not isinstance(dependent_required, dict)
                or any(
                    not isinstance(name, str)
                    or not isinstance(items, list)
                    or any(not isinstance(item, str) or not item for item in items)
                    for name, items in dependent_required.items()
                )
            ):
                return False
            for key in (
                "additionalProperties",
                "unevaluatedProperties",
                "unevaluatedItems",
                "items",
                "contains",
                "propertyNames",
                "not",
                "if",
                "then",
                "else",
            ):
                child = value.get(key)
                if child is None:
                    continue
                if key == "items" and isinstance(child, list):
                    if any(not schema_node_is_valid(item) for item in child):
                        return False
                elif not schema_node_is_valid(child):
                    return False
            for key in ("allOf", "anyOf", "oneOf", "prefixItems"):
                children = value.get(key)
                if children is None:
                    continue
                if not isinstance(children, list) or any(
                    not schema_node_is_valid(child) for child in children
                ):
                    return False
            return True

        return (
            schema.get("type") == "object"
            and tree_is_bounded(schema, 0)
            and references_are_local(schema)
            and schema_node_is_valid(schema)
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
        skill_id = str(metadata.get("skillId") or "").strip()
        standard_name = str(metadata.get("name") or "").strip()
        if skill_id and standard_name and skill_id != standard_name:
            raise ValueError("Skill pack name conflicts with skillId")
        if not skill_id and standard_name:
            metadata["skillId"] = standard_name
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
        if value is None:
            return []
        if isinstance(value, list):
            return [str(item) for item in value]
        if isinstance(value, tuple):
            return [str(item) for item in value]
        return [str(value)]

    def _reject_runtime_skill(self, skill_id: str, reason: str) -> None:
        self.runtime_skill_rejections.append({
            "skillId": skill_id or "(missing)",
            "reason": reason,
        })

    @staticmethod
    def _content_hash(content: str) -> str:
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    @staticmethod
    def _optional_int(value: Any) -> int | None:
        try:
            return int(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _optional_text(value: Any) -> str | None:
        normalized = str(value or "").strip()
        return normalized or None

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
        if applies_to:
            return bool(applies_to.intersection(tokens))
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
