from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Literal

from app.services.harness.contracts import SkillUseRecord, SkillUseState
from app.services.skills.runtime_skill import RuntimeSkill


@dataclass(frozen=True)
class SkillDescriptor:
    skillId: str
    version: str
    title: str
    description: str
    source: str
    requestedCapabilities: tuple[str, ...]

    @classmethod
    def from_skill(cls, skill: RuntimeSkill) -> "SkillDescriptor":
        return cls(
            skillId=skill.skillId,
            version=skill.version,
            title=skill.title or skill.skillId,
            description=skill.description or skill.skillId,
            source=skill.source,
            requestedCapabilities=skill.requestedCapabilities,
        )


@dataclass(frozen=True)
class SkillCandidateDecision:
    descriptor: SkillDescriptor
    candidateReasons: tuple[str, ...]
    eligible: bool
    activated: bool
    rejectionReason: str | None = None

    def trace_record(self, skill: RuntimeSkill) -> SkillUseRecord:
        state = SkillUseState.ACTIVATED if self.activated else SkillUseState.REJECTED
        return SkillUseRecord(
            skillId=skill.skillId,
            version=skill.version,
            contentHash=skill.contentHash,
            state=state,
            source=skill.source,
            provenanceRef=skill.sourceTraceId,
            candidateReasons=self.candidateReasons,
            rejectionReasons=(self.rejectionReason,) if self.rejectionReason else (),
            bodyInjected=self.activated,
            requestedCapabilityIds=skill.requestedCapabilities,
        )


@dataclass(frozen=True)
class SkillActivation:
    skill: RuntimeSkill
    prompt: str

    @property
    def promptChars(self) -> int:
        return len(self.prompt)


@dataclass(frozen=True)
class RuntimeSkillBom:
    skills: tuple[dict[str, str | int | None], ...]


@dataclass(frozen=True)
class SkillMediationResult:
    decisions: tuple[SkillCandidateDecision, ...]
    activations: tuple[SkillActivation, ...]
    records: tuple[SkillUseRecord, ...]
    prompt: str
    bom: RuntimeSkillBom
    preferredSkillId: str | None = None
    preferredSkillStatus: str | None = None

    @property
    def activatedSkillIds(self) -> tuple[str, ...]:
        return tuple(activation.skill.skillId for activation in self.activations)

    def trace_summary(self) -> dict[str, object]:
        return {
            "candidateCount": len(self.decisions),
            "eligibleCount": sum(1 for decision in self.decisions if decision.eligible),
            "activatedCount": len(self.activations),
            "rejectedCount": sum(1 for decision in self.decisions if not decision.activated),
            "eligibleSkillIds": [
                decision.descriptor.skillId
                for decision in self.decisions
                if decision.eligible
            ],
            "activatedSkillIds": list(self.activatedSkillIds),
            "preferredSkillId": self.preferredSkillId,
            "preferredSkillStatus": self.preferredSkillStatus,
            "records": [record.model_dump(mode="json") for record in self.records],
            "bom": {"skills": list(self.bom.skills)},
        }


class SkillMediator:
    @staticmethod
    def project_stage(
        catalog: list[dict[str, Any]], *, stage: Literal["research", "compose", "review"],
        loaded_ids: list[str], reload_count: int, initialized: bool,
        preferred_skill_id: str | None = None,
    ) -> dict[str, Any]:
        research_intents = {"market_scan", "book_breakdown", "followup_context"}
        review_intents = {"revision_advice", "chapter_outline", "outline_building"}
        selected = []
        for activation in catalog:
            intents = set(activation["intents"])
            relevant = (
                not intents or activation["skillId"] == preferred_skill_id
                or stage == "compose"
                or (stage == "research" and bool(intents & research_intents))
                or (stage == "review" and bool(intents & review_intents))
            )
            if relevant:
                selected.append(activation)
        additions = {item["skillId"] for item in selected} - set(loaded_ids)
        if initialized and additions:
            if reload_count >= 1:
                selected = [item for item in selected if item["skillId"] in loaded_ids]
            else:
                reload_count += 1
        activated = [item["skillId"] for item in selected]
        return {
            "stage": stage, "loadedIds": sorted(set(loaded_ids) | set(activated)),
            "reloadCount": reload_count, "activatedIds": activated,
            "prompt": "\n\n".join(item["prompt"] for item in selected),
            "pins": [dict(item["pin"]) for item in selected],
        }

    def mediate(
        self,
        candidates: Iterable[tuple[RuntimeSkill, tuple[str, ...]]],
        *,
        max_chars: int,
        eligible_skill_ids: set[str] | frozenset[str] | None = None,
        preferred_skill_id: str | None = None,
    ) -> SkillMediationResult:
        configured_budget = int(max_chars)
        budget = None if configured_budget <= 0 else configured_budget
        unique: list[tuple[RuntimeSkill, tuple[str, ...]]] = []
        indexes: dict[tuple[str, str], int] = {}
        for skill, reasons in candidates:
            key = (skill.skillId, skill.version)
            normalized_reasons = tuple(dict.fromkeys(str(reason) for reason in reasons if str(reason)))
            if key in indexes:
                index = indexes[key]
                previous_skill, previous_reasons = unique[index]
                unique[index] = (previous_skill, tuple(dict.fromkeys((*previous_reasons, *normalized_reasons))))
                continue
            indexes[key] = len(unique)
            unique.append((skill, normalized_reasons))

        normalized_preferred = str(preferred_skill_id or "").strip() or None
        unique.sort(key=lambda item: (
            0 if normalized_preferred and item[0].skillId == normalized_preferred else 1,
            0 if item[0].source == "backend" else 1,
            item[0].skillId,
        ))
        decisions: list[SkillCandidateDecision] = []
        activations: list[SkillActivation] = []
        records: list[SkillUseRecord] = []
        used = 0
        for skill, reasons in unique:
            prompt = skill.activation_prompt()
            separator_chars = 2 if activations else 0
            required_chars = separator_chars + len(prompt)
            eligible = eligible_skill_ids is None or skill.skillId in eligible_skill_ids
            activated = eligible and bool(prompt) and (
                budget is None or used + required_chars <= budget
            )
            rejection_reason = None if activated else ("capability_plan" if not eligible else "budget")
            decision = SkillCandidateDecision(
                descriptor=SkillDescriptor.from_skill(skill),
                candidateReasons=reasons,
                eligible=eligible,
                activated=activated,
                rejectionReason=rejection_reason,
            )
            decisions.append(decision)
            records.append(decision.trace_record(skill))
            if activated:
                activations.append(SkillActivation(skill=skill, prompt=prompt))
                used += required_chars

        prompt = "\n\n".join(activation.prompt for activation in activations)
        bom = RuntimeSkillBom(skills=tuple(activation.skill.trace_pin() for activation in activations))
        candidate_ids = {decision.descriptor.skillId for decision in decisions}
        eligible_ids = {
            decision.descriptor.skillId
            for decision in decisions
            if decision.eligible
        }
        if normalized_preferred is None:
            preferred_status = None
        elif normalized_preferred not in candidate_ids:
            preferred_status = "not_matched"
        elif normalized_preferred not in eligible_ids:
            preferred_status = "not_eligible"
        elif normalized_preferred in {activation.skill.skillId for activation in activations}:
            preferred_status = "activated"
        else:
            preferred_status = "budget_rejected"
        return SkillMediationResult(
            decisions=tuple(decisions),
            activations=tuple(activations),
            records=tuple(records),
            prompt=prompt,
            bom=bom,
            preferredSkillId=normalized_preferred,
            preferredSkillStatus=preferred_status,
        )
