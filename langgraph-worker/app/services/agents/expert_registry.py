from __future__ import annotations

from dataclasses import dataclass, field, replace
from contextlib import contextmanager
from contextvars import ContextVar
from enum import Enum
import hashlib
import json
from math import isfinite
from typing import Any

from app.services.harness.budget import current_run_budget
from app.services.harness.contracts import (
    CapabilityPlan,
    ExpertExecutionKind,
    expert_bindings_hash,
)
from app.services.intents.domain_intents import Intent, IntentDecision


_EVAL_DELEGATION_OVERRIDE: ContextVar[tuple[str, str | None] | None] = ContextVar(
    "eval_delegation_override",
    default=None,
)


@contextmanager
def eval_delegation_scope(mode: str | None, eval_config_fingerprint: str | None = None):
    normalized_mode = str(mode or "normal").strip().lower()
    if normalized_mode not in {"normal", "control", "candidate"}:
        raise ValueError(f"unsupported eval delegation mode: {mode!r}")
    token = _EVAL_DELEGATION_OVERRIDE.set((normalized_mode, eval_config_fingerprint))
    try:
        yield
    finally:
        _EVAL_DELEGATION_OVERRIDE.reset(token)


def current_eval_delegation() -> tuple[str, str | None]:
    return _EVAL_DELEGATION_OVERRIDE.get() or ("normal", None)


class ExpertCategory(str, Enum):
    SKILL = "Skill"
    DETERMINISTIC = "Deterministic"
    DELEGATED = "Delegated"

    @classmethod
    def parse(cls, value: "ExpertCategory | str") -> "ExpertCategory":
        if isinstance(value, cls):
            return value
        normalized = str(value or "").strip().lower()
        for category in cls:
            if normalized in {category.name.lower(), category.value.lower()}:
                return category
        raise ValueError(f"unsupported expert category: {value!r}")


@dataclass(frozen=True, slots=True)
class ExpertProfile:
    name: str
    displayName: str
    agentClass: type | None = None
    category: ExpertCategory = ExpertCategory.DELEGATED
    enabled: bool = True
    defaultMode: str = "both"
    costClass: str = "medium"
    maxTokens: int = 900
    maxToolCalls: int = 3
    capabilityIds: tuple[str, ...] = ()
    requestedToolCapabilities: tuple[str, ...] = ()
    defaultSkillIds: tuple[str, ...] = ()
    outputContract: str | None = None
    executionKind: ExpertExecutionKind | None = None
    triggerIntents: tuple[Intent, ...] = ()
    triggerTaskTypes: tuple[str, ...] = ()
    priority: int = 100
    promptVersion: str = "default"
    evalSuite: str | None = None
    guardrail: bool = False
    expectedQualityGain: float = 0.0
    qualityGainVerified: bool = False
    qualityGainSource: str | None = None
    qualityGainEvalRunId: int | None = None
    latencyCost: float = 0.0
    tokenCost: float = 0.0
    resourceCost: float = 0.0

    def __post_init__(self) -> None:
        object.__setattr__(self, "category", ExpertCategory.parse(self.category))
        kind = self.executionKind
        if kind is None:
            if self.category is ExpertCategory.SKILL:
                kind = ExpertExecutionKind.INLINE
            elif self.category is ExpertCategory.DETERMINISTIC:
                kind = ExpertExecutionKind.DETERMINISTIC
            else:
                kind = ExpertExecutionKind.DELEGATED
        elif not isinstance(kind, ExpertExecutionKind):
            kind = ExpertExecutionKind(str(kind))
        object.__setattr__(self, "executionKind", kind)
        for field_name in ("expectedQualityGain", "latencyCost", "tokenCost", "resourceCost"):
            object.__setattr__(self, field_name, self._non_negative_float(getattr(self, field_name)))

    @property
    def qualityGain(self) -> float:
        return round(
            self.expectedQualityGain - self.latencyCost - self.tokenCost - self.resourceCost,
            6,
        )

    def eval_config_fingerprint(self) -> str:
        payload = {
            "capabilityIds": sorted(self.capabilityIds),
            "category": self.category.value,
            "costClass": self.costClass,
            "defaultMode": self.defaultMode,
            "defaultSkillIds": sorted(self.defaultSkillIds),
            "displayName": self.displayName,
            "enabled": self.enabled,
            "evalSuite": self.evalSuite,
            "executionKind": (self.executionKind.value if self.executionKind is not None else None),
            "guardrail": self.guardrail,
            "latencyCost": self.latencyCost,
            "maxTokens": self.maxTokens,
            "maxToolCalls": self.maxToolCalls,
            "name": self.name,
            "outputContract": self.outputContract,
            "priority": self.priority,
            "promptVersion": self.promptVersion,
            "requestedToolCapabilities": sorted(self.requestedToolCapabilities),
            "resourceCost": self.resourceCost,
            "tokenCost": self.tokenCost,
            "triggerIntents": sorted(intent.value for intent in self.triggerIntents),
            "triggerTaskTypes": sorted(self.triggerTaskTypes),
        }
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def runtime_binding_fingerprint(self) -> str:
        payload = {
            "evalConfigFingerprint": self.eval_config_fingerprint(),
            "expectedQualityGain": self.expectedQualityGain,
            "qualityGainEvalRunId": self.qualityGainEvalRunId,
            "qualityGainSource": self.qualityGainSource,
            "qualityGainVerified": self.qualityGainVerified,
        }
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @staticmethod
    def _non_negative_float(value: Any) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return 0.0
        return parsed if isfinite(parsed) and parsed >= 0 else 0.0


@dataclass(frozen=True, slots=True)
class ExpertRoute:
    name: str
    displayName: str
    category: ExpertCategory
    reason: str
    reasonTags: tuple[str, ...]
    costClass: str
    maxTokens: int
    maxToolCalls: int
    executionKind: ExpertExecutionKind
    capabilityIds: tuple[str, ...]
    requestedToolCapabilities: tuple[str, ...]
    promptVersion: str
    evalSuite: str | None
    priority: int
    expectedQualityGain: float
    latencyCost: float
    tokenCost: float
    resourceCost: float
    qualityGain: float
    qualityGainThreshold: float | None
    qualityGainVerified: bool
    qualityGainSource: str | None
    qualityGainEvalRunId: int | None
    evalConfigFingerprint: str
    runtimeBindingFingerprint: str

    @classmethod
    def from_profile(
        cls,
        profile: ExpertProfile,
        *,
        reason_tags: list[str],
        quality_gain_threshold: float | None = None,
    ) -> "ExpertRoute":
        reason = ", ".join(reason_tags) if reason_tags else "matched default route"
        return cls(
            name=profile.name,
            displayName=profile.displayName,
            category=profile.category,
            reason=reason,
            reasonTags=tuple(reason_tags),
            costClass=profile.costClass,
            maxTokens=profile.maxTokens,
            maxToolCalls=profile.maxToolCalls,
            executionKind=profile.executionKind or ExpertExecutionKind.DELEGATED,
            capabilityIds=profile.capabilityIds,
            requestedToolCapabilities=profile.requestedToolCapabilities,
            promptVersion=profile.promptVersion,
            evalSuite=profile.evalSuite,
            priority=profile.priority,
            expectedQualityGain=profile.expectedQualityGain,
            latencyCost=profile.latencyCost,
            tokenCost=profile.tokenCost,
            resourceCost=profile.resourceCost,
            qualityGain=profile.qualityGain,
            qualityGainThreshold=quality_gain_threshold,
            qualityGainVerified=profile.qualityGainVerified,
            qualityGainSource=profile.qualityGainSource,
            qualityGainEvalRunId=profile.qualityGainEvalRunId,
            evalConfigFingerprint=profile.eval_config_fingerprint(),
            runtimeBindingFingerprint=profile.runtime_binding_fingerprint(),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "displayName": self.displayName,
            "category": self.category.value,
            "reason": self.reason,
            "reasonTags": list(self.reasonTags),
            "costClass": self.costClass,
            "maxTokens": self.maxTokens,
            "maxToolCalls": self.maxToolCalls,
            "executionKind": self.executionKind.value,
            "capabilityIds": list(self.capabilityIds),
            "requestedToolCapabilities": list(self.requestedToolCapabilities),
            "promptVersion": self.promptVersion,
            "evalSuite": self.evalSuite,
            "priority": self.priority,
            "expectedQualityGain": self.expectedQualityGain,
            "latencyCost": self.latencyCost,
            "tokenCost": self.tokenCost,
            "resourceCost": self.resourceCost,
            "qualityGain": self.qualityGain,
            "qualityGainThreshold": self.qualityGainThreshold,
            "qualityGainVerified": self.qualityGainVerified,
            "qualityGainSource": self.qualityGainSource,
            "qualityGainEvalRunId": self.qualityGainEvalRunId,
            "evalConfigFingerprint": self.evalConfigFingerprint,
            "runtimeBindingFingerprint": self.runtimeBindingFingerprint,
        }


@dataclass(frozen=True, slots=True)
class ExpertRoutingResult:
    selectedExperts: list[ExpertRoute]
    agentClasses: list[type]
    reasoningMode: str
    maxParallel: int
    capabilityPlanStatus: str = "valid"
    reasonCodes: tuple[str, ...] = ()
    skippedExperts: dict[str, str] = field(default_factory=dict)
    selectedCapabilities: list[ExpertRoute] = field(default_factory=list)
    capabilityClasses: list[type | None] = field(default_factory=list, repr=False)
    qualityGainThreshold: float = 0.0

    @property
    def delegatedCount(self) -> int:
        return len(self.agentClasses)

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "selectedExperts": [expert.to_dict() for expert in self.selectedExperts],
            "selectedCapabilities": [capability.to_dict() for capability in self.selectedCapabilities],
            "delegatedCount": self.delegatedCount,
            "reasoningMode": self.reasoningMode,
            "maxParallel": self.maxParallel,
            "qualityGainThreshold": self.qualityGainThreshold,
            "capabilityPlanStatus": self.capabilityPlanStatus,
            "reasonCodes": list(self.reasonCodes),
            "skippedExperts": dict(self.skippedExperts),
        }
        payload["expertBindingsHash"] = expert_bindings_hash(
            capability.runtimeBindingFingerprint for capability in self.selectedCapabilities
        )
        return payload


class ExpertRegistry:
    def __init__(self, profiles: list[ExpertProfile] | tuple[ExpertProfile, ...]) -> None:
        self._profiles = tuple(sorted(profiles, key=lambda profile: profile.priority))

    @classmethod
    def default(cls) -> "ExpertRegistry":
        from app.services.agents.author_strategy_agent import AuthorStrategyAgent
        from app.services.agents.book_breakdown_agent import BookBreakdownAgent
        from app.services.agents.chapter_outline_agent import ChapterOutlineAgent
        from app.services.agents.character_agent import CharacterAgent
        from app.services.agents.editor_agent import EditorAgent
        from app.services.agents.inspiration_agent import InspirationAgent
        from app.services.agents.market_scan_agent import MarketScanAgent
        from app.services.agents.opening_strategy_agent import OpeningStrategyAgent
        from app.services.agents.outline_agent import OutlineAgent
        from app.services.agents.reader_risk_agent import ReaderRiskAgent
        from app.services.agents.revision_agent import RevisionAgent
        from app.services.agents.supervisor_agent import SupervisorAgent
        from app.services.agents.worldbuilding_agent import WorldbuildingAgent

        return cls([
            ExpertProfile(
                name="market_scan",
                displayName="Market Agent",
                agentClass=MarketScanAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.market_scan,),
                triggerTaskTypes=("market_scan",),
                priority=10,
                costClass="high",
                capabilityIds=("market.read",),
                requestedToolCapabilities=("market.read",),
                evalSuite="market",
            ),
            ExpertProfile(
                name="author_strategy",
                displayName="Author Strategy Agent",
                agentClass=AuthorStrategyAgent,
                category=ExpertCategory.SKILL,
                triggerTaskTypes=("topic_strategy",),
                priority=20,
                costClass="medium",
                evalSuite="mixed_creation",
            ),
            ExpertProfile(
                name="opening_strategy",
                displayName="Opening Strategy Agent",
                agentClass=OpeningStrategyAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.opening_strategy,),
                triggerTaskTypes=("opening_strategy", "topic_strategy"),
                priority=30,
            ),
            ExpertProfile(
                name="book_breakdown",
                displayName="Book Analyst Agent",
                agentClass=BookBreakdownAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.book_breakdown,),
                triggerTaskTypes=("book_breakdown",),
                priority=40,
                costClass="high",
            ),
            ExpertProfile(
                name="outline",
                displayName="Outline Agent",
                agentClass=OutlineAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.outline_building,),
                triggerTaskTypes=("outline_building",),
                priority=50,
            ),
            ExpertProfile(
                name="chapter_outline",
                displayName="Chapter Outline Agent",
                agentClass=ChapterOutlineAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.chapter_outline,),
                triggerTaskTypes=("chapter_outline",),
                priority=60,
            ),
            ExpertProfile(
                name="inspiration",
                displayName="Inspiration Agent",
                agentClass=InspirationAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.inspiration_expand,),
                triggerTaskTypes=("inspiration_expand", "topic_strategy"),
                priority=70,
            ),
            ExpertProfile(
                name="character",
                displayName="Character Agent",
                agentClass=CharacterAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.character_design,),
                triggerTaskTypes=("character_design",),
                priority=80,
            ),
            ExpertProfile(
                name="worldbuilding",
                displayName="Worldbuilding Agent",
                agentClass=WorldbuildingAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.worldbuilding,),
                triggerTaskTypes=("worldbuilding",),
                priority=90,
            ),
            ExpertProfile(
                name="revision",
                displayName="Revision Agent",
                agentClass=RevisionAgent,
                category=ExpertCategory.SKILL,
                triggerIntents=(Intent.revision_advice,),
                triggerTaskTypes=("revision_advice",),
                priority=100,
            ),
            ExpertProfile(
                name="reader_risk",
                displayName="Reader Risk Agent",
                agentClass=ReaderRiskAgent,
                category=ExpertCategory.DETERMINISTIC,
                triggerTaskTypes=("reader_risk",),
                priority=900,
                guardrail=True,
            ),
            ExpertProfile(
                name="editor",
                displayName="Editor Agent",
                agentClass=EditorAgent,
                category=ExpertCategory.DETERMINISTIC,
                triggerTaskTypes=("editor_risk",),
                priority=910,
                guardrail=True,
            ),
            ExpertProfile(
                name="supervisor",
                displayName="Supervisor Agent",
                agentClass=SupervisorAgent,
                category=ExpertCategory.DELEGATED,
                priority=920,
                guardrail=True,
            ),
        ])

    @property
    def profiles(self) -> tuple[ExpertProfile, ...]:
        return self._profiles

    def get(self, name: str) -> ExpertProfile | None:
        return next((profile for profile in self._profiles if profile.name == name), None)

    def with_admin_profiles(self, admin_profiles: list[dict[str, Any]] | tuple[dict[str, Any], ...] | None) -> "ExpertRegistry":
        if not admin_profiles:
            return self
        profiles_by_name = {profile.name: profile for profile in self._profiles}
        for payload in admin_profiles:
            if not isinstance(payload, dict):
                continue
            name = str(payload.get("expertName") or payload.get("name") or "").strip()
            current = profiles_by_name.get(name)
            if current is None:
                continue
            profiles_by_name[name] = self._overlay_profile(current, payload)
        return ExpertRegistry(list(profiles_by_name.values()))

    def _overlay_profile(self, profile: ExpertProfile, payload: dict[str, Any]) -> ExpertProfile:
        updates: dict[str, Any] = {}
        if "displayName" in payload:
            updates["displayName"] = str(payload.get("displayName") or profile.displayName)
        if "enabled" in payload:
            updates["enabled"] = bool(payload.get("enabled"))
        if "defaultMode" in payload:
            updates["defaultMode"] = str(payload.get("defaultMode") or profile.defaultMode)
        if "costClass" in payload:
            updates["costClass"] = str(payload.get("costClass") or profile.costClass)
        if "maxTokens" in payload:
            updates["maxTokens"] = self._positive_int(payload.get("maxTokens"), profile.maxTokens)
        if "maxToolCalls" in payload:
            updates["maxToolCalls"] = self._non_negative_int(payload.get("maxToolCalls"), profile.maxToolCalls)
        if "capabilityIds" in payload:
            updates["capabilityIds"] = self._string_tuple(payload.get("capabilityIds"))
        if "requestedToolCapabilities" in payload:
            updates["requestedToolCapabilities"] = self._string_tuple(payload.get("requestedToolCapabilities"))
        if "requestedCapabilities" in payload:
            updates["requestedToolCapabilities"] = self._string_tuple(payload.get("requestedCapabilities"))
        if "defaultSkillIds" in payload:
            updates["defaultSkillIds"] = self._string_tuple(payload.get("defaultSkillIds"))
        if "outputContract" in payload:
            updates["outputContract"] = self._optional_string(payload.get("outputContract"))
        if "executionKind" in payload:
            try:
                updates["executionKind"] = ExpertExecutionKind(str(payload.get("executionKind")))
            except ValueError:
                pass
        if "triggerIntents" in payload:
            updates["triggerIntents"] = self._intent_tuple(payload.get("triggerIntents"))
        if "triggerTasks" in payload:
            updates["triggerTaskTypes"] = self._string_tuple(payload.get("triggerTasks"))
        if "triggerTaskTypes" in payload:
            updates["triggerTaskTypes"] = self._string_tuple(payload.get("triggerTaskTypes"))
        if "priority" in payload:
            updates["priority"] = self._positive_int(payload.get("priority"), profile.priority)
        if "promptVersion" in payload:
            updates["promptVersion"] = str(payload.get("promptVersion") or profile.promptVersion)
        if "evalSuiteId" in payload:
            updates["evalSuite"] = self._optional_string(payload.get("evalSuiteId"))
        if "evalSuite" in payload:
            updates["evalSuite"] = self._optional_string(payload.get("evalSuite"))
        if "guardrail" in payload:
            updates["guardrail"] = bool(payload.get("guardrail"))
        category_value = next(
            (
                payload[key]
                for key in ("category", "expertType", "capabilityType")
                if key in payload
            ),
            None,
        )
        if category_value is not None:
            try:
                updates["category"] = ExpertCategory.parse(category_value)
            except ValueError:
                pass
        for field_name in ("expectedQualityGain", "latencyCost", "tokenCost", "resourceCost"):
            if field_name in payload:
                updates[field_name] = self._non_negative_float(payload.get(field_name), getattr(profile, field_name))
        if "qualityGainVerified" in payload:
            updates["qualityGainVerified"] = bool(payload.get("qualityGainVerified"))
        if "qualityGainSource" in payload:
            updates["qualityGainSource"] = self._optional_string(payload.get("qualityGainSource"))
        if "qualityGainEvalRunId" in payload:
            updates["qualityGainEvalRunId"] = self._optional_positive_int(payload.get("qualityGainEvalRunId"))
        return replace(profile, **updates)

    def _optional_positive_int(self, value: Any) -> int | None:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None

    def _positive_int(self, value: Any, fallback: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return fallback
        return parsed if parsed > 0 else fallback

    def _non_negative_int(self, value: Any, fallback: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return fallback
        return parsed if parsed >= 0 else fallback

    def _string_tuple(self, value: Any) -> tuple[str, ...]:
        if not isinstance(value, (list, tuple)):
            return ()
        return tuple(str(item).strip() for item in value if str(item).strip())

    def _intent_tuple(self, value: Any) -> tuple[Intent, ...]:
        intents: list[Intent] = []
        for item in self._string_tuple(value):
            try:
                intents.append(Intent(item))
            except ValueError:
                continue
        return tuple(dict.fromkeys(intents))

    def _optional_string(self, value: Any) -> str | None:
        text = "" if value is None else str(value).strip()
        return text or None

    def _non_negative_float(self, value: Any, fallback: float) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return fallback
        return parsed if isfinite(parsed) and parsed >= 0 else fallback


class ExpertRouter:
    _FAST_DELEGATION_LIMIT = 1
    _DEEP_DELEGATION_LIMIT = 2
    _FAST_INLINE_LIMIT = 2
    _DEEP_INLINE_LIMIT = 4
    _PARALLEL_DELEGATION_LIMIT = 1

    def __init__(
        self,
        registry: ExpertRegistry,
        *,
        max_experts_fast: int = _FAST_DELEGATION_LIMIT,
        max_experts_deep: int = _DEEP_DELEGATION_LIMIT,
        max_parallel: int = _PARALLEL_DELEGATION_LIMIT,
        quality_gain_threshold: float | None = None,
        quality_gain_threshold_fast: float = 0.25,
        quality_gain_threshold_deep: float = 0.15,
    ) -> None:
        self.registry = registry
        self.max_experts_fast = self._bounded_limit(max_experts_fast, self._FAST_DELEGATION_LIMIT)
        self.max_experts_deep = self._bounded_limit(max_experts_deep, self._DEEP_DELEGATION_LIMIT)
        self.max_parallel = min(self._positive_limit(max_parallel), self._PARALLEL_DELEGATION_LIMIT)
        if quality_gain_threshold is not None:
            quality_gain_threshold_fast = quality_gain_threshold
            quality_gain_threshold_deep = quality_gain_threshold
        self.quality_gain_threshold_fast = self._threshold(quality_gain_threshold_fast, 0.25)
        self.quality_gain_threshold_deep = self._threshold(quality_gain_threshold_deep, 0.15)

    def route(
        self,
        *,
        intent_decision: IntentDecision,
        reasoning_mode: str | None = None,
        task_graph: dict[str, Any] | Any | None = None,
        capability_plan: CapabilityPlan | dict[str, Any] | None = None,
        eval_delegation_mode: str | None = None,
        eval_candidate_config_fingerprint: str | None = None,
    ) -> ExpertRoutingResult:
        mode = self._reasoning_mode(reasoning_mode)
        task_types = self._task_types(task_graph)
        requested_intents = {intent_decision.primaryIntent, *intent_decision.subIntents}
        candidates = self._candidate_names(intent_decision, task_types)
        plan, plan_status, plan_reason = self._coerce_capability_plan(capability_plan)
        if plan is None:
            skipped = {
                profile.name: plan_reason
                for profile in self.registry.profiles
                if candidates.get(profile.name) or self._profile_matches(profile, requested_intents, task_types)
            }
            return ExpertRoutingResult(
                selectedExperts=[],
                agentClasses=[],
                reasoningMode=mode,
                maxParallel=self.max_parallel,
                capabilityPlanStatus=plan_status,
                reasonCodes=(plan_reason,),
                skippedExperts=skipped,
            )
        plan_expert_ids, plan_capability_ids = self._plan_constraints(plan)
        skipped: dict[str, str] = {}
        mode_delegated_limit = self.max_experts_deep if mode == "deep" else self.max_experts_fast
        mode_inline_limit = self._DEEP_INLINE_LIMIT if mode == "deep" else self._FAST_INLINE_LIMIT
        budget = current_run_budget()
        budget_delegated_limit = max(0, budget.remaining[2]) if budget is not None else None
        delegated_limit = (
            mode_delegated_limit
            if budget_delegated_limit is None
            else min(mode_delegated_limit, budget_delegated_limit)
        )
        quality_gain_threshold = (
            self.quality_gain_threshold_deep if mode == "deep" else self.quality_gain_threshold_fast
        )
        scoped_mode, scoped_hash = current_eval_delegation()
        eval_mode = str(eval_delegation_mode or scoped_mode).strip().lower()
        eval_candidate_config_fingerprint = eval_candidate_config_fingerprint or scoped_hash
        selected_capabilities: list[ExpertRoute] = []
        capability_classes: list[type | None] = []
        selected_routes: list[ExpertRoute] = []
        selected_classes: list[type] = []

        for profile in self.registry.profiles:
            reason_tags = candidates.get(profile.name) or self._profile_matches(profile, requested_intents, task_types)
            if not reason_tags:
                continue
            if not profile.enabled:
                skipped[profile.name] = "disabled"
                continue
            if self._mode_disabled(profile, mode):
                skipped[profile.name] = f"mode:{mode}"
                continue
            if profile.name not in plan_expert_ids:
                # CapabilityPlan is sole expansion source; raw intent/task cannot add experts beyond plan.
                if not reason_tags:
                    continue
                skipped[profile.name] = "outside_capability_plan"
                continue
            if profile.capabilityIds:
                if not set(profile.capabilityIds).intersection(plan_capability_ids):
                    skipped[profile.name] = "capability_mismatch"
                    continue
            route_tags = [*reason_tags, f"category:{profile.category.value}", f"execution:{profile.executionKind.value if profile.executionKind else 'unknown'}"]
            if profile.category is ExpertCategory.DELEGATED:
                route_tags.append(f"quality_gain:{profile.qualityGain:.3f}")
            capability_route = ExpertRoute.from_profile(
                profile,
                reason_tags=route_tags,
                quality_gain_threshold=(
                    quality_gain_threshold if profile.category is ExpertCategory.DELEGATED else None
                ),
            )
            if profile.category is not ExpertCategory.DELEGATED:
                inline_selected = sum(
                    1
                    for item in selected_capabilities
                    if item.executionKind is ExpertExecutionKind.INLINE
                    or item.category is ExpertCategory.SKILL
                )
                if (
                    (profile.executionKind is ExpertExecutionKind.INLINE or profile.category is ExpertCategory.SKILL)
                    and inline_selected >= mode_inline_limit
                ):
                    skipped[profile.name] = f"inline_top_k:{mode}"
                    continue
                selected_capabilities.append(capability_route)
                capability_classes.append(profile.agentClass)
                continue
            if eval_mode == "control":
                skipped[profile.name] = "eval_control"
                continue
            eval_candidate = (
                eval_mode == "candidate"
                and bool(eval_candidate_config_fingerprint)
                and profile.eval_config_fingerprint() == eval_candidate_config_fingerprint
            )
            if eval_mode == "candidate" and not eval_candidate:
                skipped[profile.name] = "eval_other_candidate"
                continue
            if not eval_candidate and (
                not profile.qualityGainVerified or profile.qualityGainSource != "admin_configured_eval"
            ):
                skipped[profile.name] = "quality_gain_unverified"
                continue
            if not eval_candidate and profile.qualityGain < quality_gain_threshold:
                skipped[profile.name] = (
                    f"quality_gain:{profile.qualityGain:.3f}<{quality_gain_threshold:.3f}"
                )
                continue
            if len(selected_routes) >= delegated_limit:
                skipped[profile.name] = (
                    "delegation_budget"
                    if budget_delegated_limit is not None and delegated_limit < mode_delegated_limit
                    else f"top_k:{mode}"
                )
                continue
            if profile.agentClass is None:
                skipped[profile.name] = "missing_agent_class"
                continue
            selected_capabilities.append(capability_route)
            capability_classes.append(profile.agentClass)
            selected_routes.append(capability_route)
            selected_classes.append(profile.agentClass)

        return ExpertRoutingResult(
            selectedExperts=selected_routes,
            agentClasses=selected_classes,
            reasoningMode=mode,
            maxParallel=self.max_parallel,
            capabilityPlanStatus=plan_status,
            skippedExperts=skipped,
            selectedCapabilities=selected_capabilities,
            capabilityClasses=capability_classes,
            qualityGainThreshold=quality_gain_threshold,
        )

    def _coerce_capability_plan(
        self,
        capability_plan: CapabilityPlan | dict[str, Any] | None,
    ) -> tuple[CapabilityPlan | None, str, str]:
        if capability_plan is None:
            return None, "missing", "missing_capability_plan"
        if isinstance(capability_plan, CapabilityPlan):
            return capability_plan, "valid", ""
        if not isinstance(capability_plan, dict):
            return None, "invalid", "invalid_capability_plan"
        try:
            return CapabilityPlan.model_validate(capability_plan), "valid", ""
        except Exception:
            return None, "invalid", "invalid_capability_plan"

    def _plan_constraints(self, capability_plan: CapabilityPlan) -> tuple[set[str], set[str]]:
        expert_ids = set(capability_plan.expertCandidateIds)
        capability_ids = {
            request.capabilityId for request in capability_plan.capabilityRequests
        } | set(capability_plan.requestedToolCapabilities)
        return expert_ids, capability_ids

    def _candidate_names(
        self,
        decision: IntentDecision,
        task_types: set[str],
    ) -> dict[str, list[str]]:
        if decision.primaryIntent is not Intent.mixed_creation_research:
            return {}
        intent_to_name = {
            Intent.market_scan: "market_scan",
            Intent.opening_strategy: "opening_strategy",
            Intent.book_breakdown: "book_breakdown",
            Intent.outline_building: "outline",
            Intent.chapter_outline: "chapter_outline",
            Intent.inspiration_expand: "inspiration",
            Intent.character_design: "character",
            Intent.worldbuilding: "worldbuilding",
            Intent.revision_advice: "revision",
        }
        requested = {intent_to_name[intent] for intent in decision.subIntents if intent in intent_to_name}
        tags = {name: ["intent:mixed_creation_research"] for name in requested}
        for task_type in task_types:
            for profile in self.registry.profiles:
                if task_type in profile.triggerTaskTypes:
                    tags.setdefault(profile.name, []).append(f"task:{task_type}")
        return tags

    def _profile_matches(
        self,
        profile: ExpertProfile,
        requested_intents: set[Intent],
        task_types: set[str],
    ) -> list[str]:
        tags: list[str] = []
        for intent in profile.triggerIntents:
            if intent in requested_intents:
                tags.append(f"intent:{intent.value}")
        for task_type in profile.triggerTaskTypes:
            if task_type in task_types:
                tags.append(f"task:{task_type}")
        return tags

    def _task_types(self, task_graph: dict[str, Any] | Any | None) -> set[str]:
        if task_graph is None:
            return set()
        if hasattr(task_graph, "model_dump"):
            try:
                task_graph = task_graph.model_dump(mode="json")
            except Exception:
                task_graph = {}
        if not isinstance(task_graph, dict):
            return set()
        task_types: set[str] = set()
        for task in task_graph.get("tasks") or []:
            if isinstance(task, dict) and task.get("type"):
                task_types.add(str(task.get("type")))
        return task_types

    def _mode_disabled(self, profile: ExpertProfile, mode: str) -> bool:
        default_mode = (profile.defaultMode or "both").strip().lower()
        return default_mode not in {"both", mode}

    def _reasoning_mode(self, reasoning_mode: str | None) -> str:
        value = (reasoning_mode or "fast").strip().lower()
        return "deep" if value in {"deep", "reasoning", "thinking", "max"} else "fast"

    def _bounded_limit(self, value: Any, hard_limit: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return hard_limit
        return max(0, min(parsed, hard_limit))

    def _positive_limit(self, value: Any) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return 1
        return max(1, parsed)

    def _threshold(self, value: Any, fallback: float) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return fallback
        return parsed if isfinite(parsed) and parsed >= 0 else fallback
