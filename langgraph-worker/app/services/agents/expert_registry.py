from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any

from app.services.intents.domain_intents import Intent, IntentDecision


@dataclass(frozen=True, slots=True)
class ExpertProfile:
    name: str
    displayName: str
    agentClass: type
    enabled: bool = True
    defaultMode: str = "both"
    costClass: str = "medium"
    maxTokens: int = 900
    maxToolCalls: int = 3
    allowedTools: tuple[str, ...] = ()
    triggerIntents: tuple[Intent, ...] = ()
    triggerTaskTypes: tuple[str, ...] = ()
    priority: int = 100
    promptVersion: str = "default"
    evalSuite: str | None = None
    guardrail: bool = False


@dataclass(frozen=True, slots=True)
class ExpertRoute:
    name: str
    displayName: str
    reason: str
    reasonTags: tuple[str, ...]
    costClass: str
    maxTokens: int
    maxToolCalls: int
    allowedTools: tuple[str, ...]
    promptVersion: str
    evalSuite: str | None
    priority: int

    @classmethod
    def from_profile(cls, profile: ExpertProfile, *, reason_tags: list[str]) -> "ExpertRoute":
        reason = ", ".join(reason_tags) if reason_tags else "matched default route"
        return cls(
            name=profile.name,
            displayName=profile.displayName,
            reason=reason,
            reasonTags=tuple(reason_tags),
            costClass=profile.costClass,
            maxTokens=profile.maxTokens,
            maxToolCalls=profile.maxToolCalls,
            allowedTools=profile.allowedTools,
            promptVersion=profile.promptVersion,
            evalSuite=profile.evalSuite,
            priority=profile.priority,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "displayName": self.displayName,
            "reason": self.reason,
            "reasonTags": list(self.reasonTags),
            "costClass": self.costClass,
            "maxTokens": self.maxTokens,
            "maxToolCalls": self.maxToolCalls,
            "allowedTools": list(self.allowedTools),
            "promptVersion": self.promptVersion,
            "evalSuite": self.evalSuite,
            "priority": self.priority,
        }


@dataclass(frozen=True, slots=True)
class ExpertRoutingResult:
    selectedExperts: list[ExpertRoute]
    agentClasses: list[type]
    reasoningMode: str
    maxParallel: int
    skippedExperts: dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "selectedExperts": [expert.to_dict() for expert in self.selectedExperts],
            "reasoningMode": self.reasoningMode,
            "maxParallel": self.maxParallel,
            "skippedExperts": dict(self.skippedExperts),
        }


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
                triggerIntents=(Intent.market_scan,),
                triggerTaskTypes=("market_scan",),
                priority=10,
                costClass="high",
                allowedTools=("rank.lookup", "rank.research_pack"),
                evalSuite="market",
            ),
            ExpertProfile(
                name="author_strategy",
                displayName="Author Strategy Agent",
                agentClass=AuthorStrategyAgent,
                triggerTaskTypes=("topic_strategy",),
                priority=20,
                costClass="medium",
                evalSuite="mixed_creation",
            ),
            ExpertProfile(
                name="opening_strategy",
                displayName="Opening Strategy Agent",
                agentClass=OpeningStrategyAgent,
                triggerIntents=(Intent.opening_strategy,),
                triggerTaskTypes=("opening_strategy", "topic_strategy"),
                priority=30,
            ),
            ExpertProfile(
                name="book_breakdown",
                displayName="Book Analyst Agent",
                agentClass=BookBreakdownAgent,
                triggerIntents=(Intent.book_breakdown,),
                triggerTaskTypes=("book_breakdown",),
                priority=40,
                costClass="high",
            ),
            ExpertProfile(
                name="outline",
                displayName="Outline Agent",
                agentClass=OutlineAgent,
                triggerIntents=(Intent.outline_building,),
                triggerTaskTypes=("outline_building",),
                priority=50,
            ),
            ExpertProfile(
                name="chapter_outline",
                displayName="Chapter Outline Agent",
                agentClass=ChapterOutlineAgent,
                triggerIntents=(Intent.chapter_outline,),
                triggerTaskTypes=("chapter_outline",),
                priority=60,
            ),
            ExpertProfile(
                name="inspiration",
                displayName="Inspiration Agent",
                agentClass=InspirationAgent,
                triggerIntents=(Intent.inspiration_expand,),
                triggerTaskTypes=("inspiration_expand", "topic_strategy"),
                priority=70,
            ),
            ExpertProfile(
                name="character",
                displayName="Character Agent",
                agentClass=CharacterAgent,
                triggerIntents=(Intent.character_design,),
                triggerTaskTypes=("character_design",),
                priority=80,
            ),
            ExpertProfile(
                name="worldbuilding",
                displayName="Worldbuilding Agent",
                agentClass=WorldbuildingAgent,
                triggerIntents=(Intent.worldbuilding,),
                triggerTaskTypes=("worldbuilding",),
                priority=90,
            ),
            ExpertProfile(
                name="revision",
                displayName="Revision Agent",
                agentClass=RevisionAgent,
                triggerIntents=(Intent.revision_advice,),
                triggerTaskTypes=("revision_advice",),
                priority=100,
            ),
            ExpertProfile(
                name="reader_risk",
                displayName="Reader Risk Agent",
                agentClass=ReaderRiskAgent,
                triggerTaskTypes=("reader_risk",),
                priority=900,
                guardrail=True,
            ),
            ExpertProfile(
                name="editor",
                displayName="Editor Agent",
                agentClass=EditorAgent,
                triggerTaskTypes=("editor_risk",),
                priority=910,
                guardrail=True,
            ),
            ExpertProfile(
                name="supervisor",
                displayName="Supervisor Agent",
                agentClass=SupervisorAgent,
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
        if "allowedTools" in payload:
            updates["allowedTools"] = self._string_tuple(payload.get("allowedTools"))
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
        return replace(profile, **updates)

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


class ExpertRouter:
    def __init__(
        self,
        registry: ExpertRegistry,
        *,
        max_experts_fast: int = 4,
        max_experts_deep: int = 8,
        max_parallel: int = 3,
    ) -> None:
        self.registry = registry
        self.max_experts_fast = max(1, max_experts_fast)
        self.max_experts_deep = max(1, max_experts_deep)
        self.max_parallel = max(1, max_parallel)

    def route(
        self,
        *,
        intent_decision: IntentDecision,
        reasoning_mode: str | None = None,
        task_graph: dict[str, Any] | Any | None = None,
    ) -> ExpertRoutingResult:
        mode = self._reasoning_mode(reasoning_mode)
        task_types = self._task_types(task_graph)
        requested_intents = {intent_decision.primaryIntent, *intent_decision.subIntents}
        candidates = self._candidate_names(intent_decision, task_types)
        skipped: dict[str, str] = {}
        non_guard_limit = self.max_experts_deep if mode == "deep" else self.max_experts_fast
        selected_routes: list[ExpertRoute] = []
        selected_classes: list[type] = []
        non_guard_count = 0

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
            if not profile.guardrail and non_guard_count >= non_guard_limit:
                skipped[profile.name] = f"top_k:{mode}"
                continue
            if profile.guardrail and "guardrail" not in reason_tags:
                reason_tags.append("guardrail")
            selected_routes.append(ExpertRoute.from_profile(profile, reason_tags=reason_tags))
            selected_classes.append(profile.agentClass)
            if not profile.guardrail:
                non_guard_count += 1

        return ExpertRoutingResult(
            selectedExperts=selected_routes,
            agentClasses=selected_classes,
            reasoningMode=mode,
            maxParallel=self.max_parallel,
            skippedExperts=skipped,
        )

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
        if not requested:
            requested = {"market_scan", "opening_strategy", "outline"}
        else:
            requested.update({"market_scan", "opening_strategy", "outline"})
        if "market_scan" in requested:
            requested.add("author_strategy")
        tags = {name: ["intent:mixed_creation_research"] for name in requested}
        for task_type in task_types:
            for profile in self.registry.profiles:
                if task_type in profile.triggerTaskTypes:
                    tags.setdefault(profile.name, []).append(f"task:{task_type}")
        for guard_name in ("reader_risk", "editor", "supervisor"):
            tags.setdefault(guard_name, []).append("guardrail:mixed_creation")
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
