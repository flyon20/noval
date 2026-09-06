from __future__ import annotations

import inspect
import re
from collections.abc import Awaitable, Callable
from typing import Any

from app.models.knowledge import KnowledgeChatRequest
from app.services.conversation_context import project_conversation_context
from app.services.harness.contracts import DomainStatus, IntentEnvelope
from app.services.intents import AnswerBoundary, Intent, IntentDecision, IntentRouter, MarketRequestLevel


_AUDIT_REASON_PREFIXES = ("rule:", "llm:", "supervisor:")


IntentLlmFallback = Callable[[KnowledgeChatRequest, IntentDecision], Awaitable[IntentDecision | None] | IntentDecision | None]


class FastIntentClassifier:
    def __init__(self, router: IntentRouter | None = None) -> None:
        self.router = router or IntentRouter()

    def classify(self, request: KnowledgeChatRequest) -> IntentDecision:
        context = project_conversation_context(request)
        return self.router.classify(
            request.question or "",
            context_summary=context.summary,
            history=context.history_texts,
            book_id=request.bookId,
            book_name=request.bookName,
            selected_candidate=request.selectedCandidate,
        )


class LLMIntentAgent:
    def __init__(
        self,
        fallback: IntentLlmFallback | None = None,
        *,
        enabled: bool = False,
        min_confidence: float = 0.82,
        model_first_enabled: bool = False,
    ) -> None:
        self.fallback = fallback
        self.enabled = enabled
        self.min_confidence = max(0.0, min(1.0, float(min_confidence)))
        self.model_first_enabled = bool(model_first_enabled)

    def should_call(self, request: KnowledgeChatRequest, decision: IntentDecision) -> bool:
        if not self.enabled or self.fallback is None:
            return False
        if decision.primaryIntent is Intent.out_of_scope:
            return False
        if self.model_first_enabled:
            return True
        if request.bookId is not None or request.selectedCandidate is not None:
            return False
        if request.bookName and request.bookName.strip():
            return False
        question = (request.question or "").strip().lower()
        has_market_or_creation_cue = any(
            marker in question
            for marker in (
                "recent",
                "trend",
                "topics",
                "write next",
                "opening",
                "market",
                "榜",
                "趋势",
                "题材",
                "开书",
                "开篇",
                "大纲",
                "人设",
            )
        )
        if not has_market_or_creation_cue:
            return False
        notes = set(decision.routingNotes or [])
        return (
            float(decision.confidence or 0.0) < self.min_confidence
            or "rule:ambiguous-intent" in notes
            or "fallback:no-webnovel-signal" in notes
        )

    async def decide(self, request: KnowledgeChatRequest, decision: IntentDecision) -> IntentDecision | None:
        if not self.should_call(request, decision):
            return None
        result = self.fallback(request, decision)
        if inspect.isawaitable(result):
            result = await result
        return result


class IntentSupervisor:
    def repair(self, decision: IntentDecision, request: KnowledgeChatRequest) -> IntentDecision:
        updates: dict[str, Any] = {}
        sub_intents = list(decision.subIntents or [])
        routing_notes = list(decision.routingNotes or [])
        question = request.question or ""

        active_goal_intent = self._active_creation_intent(request)
        if (
            decision.primaryIntent is Intent.market_scan
            and active_goal_intent is not None
            and self._continues_creation_goal(question)
            and "rule:standalone-market-only" not in routing_notes
        ):
            inherited_sub_intents = [Intent.market_scan, active_goal_intent]
            entities = dict(decision.entities or {})
            entities["conversationTaskMode"] = "supporting_research"
            entities["activeGoalIntent"] = active_goal_intent.value
            tool_needs = decision.toolNeeds.model_copy(update={
                "needsRankData": True,
                "needsCreativeGeneration": True,
                "needsOutlineMemory": True,
                "needsChapterEvidence": (
                    decision.toolNeeds.needsChapterEvidence
                    or active_goal_intent in {Intent.chapter_outline, Intent.revision_advice}
                ),
                "needsSkillPack": (
                    decision.toolNeeds.needsSkillPack
                    or active_goal_intent in {
                        Intent.opening_strategy,
                        Intent.inspiration_expand,
                        Intent.character_design,
                        Intent.worldbuilding,
                    }
                ),
                "needsCandidateSelection": False,
            })
            memory_policy = dict(decision.memoryPolicy or {})
            memory_policy["useProjectProfile"] = True
            memory_policy["useThreadSummary"] = True
            updates.update({
                "primaryIntent": Intent.mixed_creation_research,
                "subIntents": inherited_sub_intents,
                "entities": entities,
                "toolNeeds": tool_needs,
                "answerBoundary": AnswerBoundary.market_evidence_plus_author_inference,
                "memoryPolicy": memory_policy,
            })
            sub_intents = inherited_sub_intents
            routing_notes.append("supervisor:active_goal_inherited")

        if decision.primaryIntent is Intent.mixed_creation_research:
            if Intent.market_scan not in sub_intents and decision.toolNeeds.needsRankData:
                sub_intents.insert(0, Intent.market_scan)
            if decision.answerBoundary is not AnswerBoundary.market_evidence_plus_author_inference:
                updates["answerBoundary"] = AnswerBoundary.market_evidence_plus_author_inference

        if decision.primaryIntent is Intent.followup_context and request.projectId is not None:
            memory_policy = dict(decision.memoryPolicy or {})
            memory_policy["useProjectProfile"] = True
            memory_policy["useThreadSummary"] = True
            updates["memoryPolicy"] = memory_policy

        if decision.primaryIntent is Intent.followup_context and self._looks_like_chapter_outline_followup(question):
            updates["primaryIntent"] = Intent.chapter_outline
            updates["answerBoundary"] = AnswerBoundary.outline_generation
            tool_needs = decision.toolNeeds.model_copy(update={
                "needsCreativeGeneration": True,
                "needsChapterEvidence": True,
                "needsOutlineMemory": True,
            })
            updates["toolNeeds"] = tool_needs
            routing_notes.append("supervisor:chapter_followup_repaired")

        if sub_intents != list(decision.subIntents or []):
            updates["subIntents"] = sub_intents
            routing_notes.append("supervisor:sub_intents_repaired")

        if routing_notes != list(decision.routingNotes or []):
            updates["routingNotes"] = routing_notes

        if updates:
            return decision.model_copy(update=updates)
        return decision

    def _active_creation_intent(self, request: KnowledgeChatRequest) -> Intent | None:
        summary = project_conversation_context(request).summary or ""
        if not summary.strip():
            return None
        values: list[str] = []
        labels = (
            "最近意图",
            "最近用户目标",
            "用户目标",
            "current intent",
            "current goal",
            "previous goal",
        )
        boundaries = (*labels, "上一轮结论", "最近回答", "previous answer")
        label_pattern = "|".join(re.escape(label) for label in boundaries)
        # Summaries may flatten goal and answer slots onto one line.
        for match in re.finditer(
            rf"(?P<label>{label_pattern})\s*[:：]\s*(?P<value>.*?)(?=(?:{label_pattern})\s*[:：]|\n|$)",
            summary.replace("\r\n", "\n"),
            flags=re.IGNORECASE,
        ):
            if match.group("label").lower() not in labels:
                break
            value = match.group("value").strip().lower()
            if value:
                values.append(value)
        for value in values:
            intent = self._creation_intent_from_context_value(value)
            if intent is not None:
                return intent
        return None

    def _creation_intent_from_context_value(self, value: str) -> Intent | None:
        mappings = (
            (Intent.chapter_outline, ("chapter_outline", "chapter_creation", "细纲", "章节纲", "分章")),
            (Intent.outline_building, ("outline_building", "outline_creation", "outline_generation", "大纲", "卷纲", "主线框架")),
            (Intent.opening_strategy, ("opening_strategy", "开书", "开文", "开篇", "立项", "选题")),
            (Intent.character_design, ("character_design", "人设", "角色设计", "人物设计")),
            (Intent.worldbuilding, ("worldbuilding", "世界观", "设定体系", "势力设计")),
            (Intent.revision_advice, ("revision_advice", "改稿", "修订", "润色", "重写")),
            (Intent.inspiration_expand, ("inspiration_expand", "扩展脑洞", "脑洞扩展", "灵感扩展", "题材发散")),
        )
        for intent, markers in mappings:
            if any(marker in value for marker in markers):
                return intent
        return None

    @staticmethod
    def _continues_creation_goal(question: str) -> bool:
        return bool(re.search(
            r"(?:结合|围绕|针对|用于).{0,12}(?:大纲|设定|故事|创作|项目|方向)"
            r"|(?:这个|上述|刚才的|我的)(?:方向|设定|大纲|故事|项目)"
            r"|\b(?:for|given|continue)\s+(?:my|this|that|our|the previous)\s+(?:story|outline|premise|project)\b",
            question,
            flags=re.IGNORECASE,
        ))

    def _looks_like_chapter_outline_followup(self, question: str) -> bool:
        if not any(marker in question for marker in ("这本", "本书", "该书", "这个")):
            return False
        has_chapter_scope = bool(re.search(r"前\s*\d+\s*章|第?\s*\d+\s*章|[一二三四五六七八九十百]+章", question))
        has_writing_request = any(marker in question for marker in ("怎么写", "如何写", "抓人", "钩子", "开头", "细纲"))
        return has_chapter_scope and has_writing_request


class IntentAgent:
    def __init__(
        self,
        *,
        router: IntentRouter | None = None,
        llm_fallback: IntentLlmFallback | None = None,
        llm_fallback_enabled: bool = False,
        llm_min_confidence: float = 0.82,
        model_first_enabled: bool = False,
    ) -> None:
        self.fast_classifier = FastIntentClassifier(router)
        self.llm_agent = LLMIntentAgent(
            llm_fallback,
            enabled=llm_fallback_enabled,
            min_confidence=llm_min_confidence,
            model_first_enabled=model_first_enabled,
        )
        self.supervisor = IntentSupervisor()

    @property
    def router(self) -> IntentRouter:
        return self.fast_classifier.router

    async def decide(self, request: KnowledgeChatRequest) -> IntentDecision:
        rule_decision = self.fast_classifier.classify(request)
        fallback_decision = await self.llm_agent.decide(request, rule_decision)
        return self.reconcile(
            request=request,
            rule_decision=rule_decision,
            fallback_decision=fallback_decision,
        )

    def reconcile(
        self,
        *,
        request: KnowledgeChatRequest,
        rule_decision: IntentDecision,
        fallback_decision: IntentDecision | None,
    ) -> IntentDecision:
        decision = fallback_decision or rule_decision
        context = project_conversation_context(request)
        is_context_followup = getattr(
            self.fast_classifier.router,
            "is_context_followup",
            None,
        )
        if self._should_preserve_specific_creative_rule(
            request=request,
            rule_decision=rule_decision,
            fallback_decision=fallback_decision,
            has_context=bool(context.summary or context.history_texts),
        ):
            routing_notes = list(rule_decision.routingNotes or [])
            routing_notes.append("supervisor:specific_creation_intent_preserved")
            decision = rule_decision.model_copy(update={
                "routingNotes": list(dict.fromkeys(routing_notes)),
            })
        elif (
            fallback_decision is not None
            and rule_decision.primaryIntent is Intent.followup_context
            and "rule:project-foreshadowing-query" in set(rule_decision.routingNotes or [])
        ):
            routing_notes = list(rule_decision.routingNotes or [])
            routing_notes.append("supervisor:project-foreshadowing-query-preserved")
            decision = rule_decision.model_copy(update={
                "routingNotes": list(dict.fromkeys(routing_notes)),
            })
        elif (
            fallback_decision is not None
            and rule_decision.primaryIntent is Intent.followup_context
            and callable(is_context_followup)
            and is_context_followup(
                request.question or "",
                context.summary,
                context.history_texts,
            )
        ):
            routing_notes = list(rule_decision.routingNotes or [])
            routing_notes.append("supervisor:explicit_context_reference_preserved")
            decision = rule_decision.model_copy(update={
                "routingNotes": list(dict.fromkeys(routing_notes)),
            })
        elif (
            fallback_decision is not None
            and rule_decision.primaryIntent is Intent.market_scan
            and (rule_decision.entities or {}).get("marketQuestionType")
        ):
            routing_notes = list(rule_decision.routingNotes or [])
            routing_notes.append("supervisor:market_taxonomy_preserved")
            decision = rule_decision.model_copy(update={
                "routingNotes": list(dict.fromkeys(routing_notes)),
            })
        elif (
            fallback_decision is not None
            and rule_decision.primaryIntent is Intent.market_scan
            and fallback_decision.primaryIntent is not Intent.market_scan
            and float(rule_decision.confidence or 0.0) >= 0.82
            and not self.supervisor._continues_creation_goal(request.question or "")
            and any(marker in (request.question or "") for marker in ("榜", "趋势", "排名"))
        ):
            decision = rule_decision.model_copy(update={
                "routingNotes": list(dict.fromkeys([
                    *rule_decision.routingNotes, "supervisor:explicit_market_scope_preserved",
                ])),
            })
        return self.supervisor.repair(decision, request)

    @staticmethod
    def _should_preserve_specific_creative_rule(
        *,
        request: KnowledgeChatRequest,
        rule_decision: IntentDecision,
        fallback_decision: IntentDecision | None,
        has_context: bool,
    ) -> bool:
        if fallback_decision is None or not has_context:
            return False
        specific_creation_intents = {
            Intent.opening_strategy,
            Intent.outline_building,
            Intent.chapter_outline,
            Intent.inspiration_expand,
            Intent.character_design,
            Intent.worldbuilding,
            Intent.revision_advice,
        }
        if rule_decision.primaryIntent not in specific_creation_intents:
            return False
        if float(rule_decision.confidence or 0.0) < 0.75:
            return False
        fallback_notes = set(fallback_decision.routingNotes or [])
        return (
            fallback_decision.primaryIntent is Intent.followup_context
            or "rule:ambiguous-intent" in fallback_notes
        )

    def to_envelope(
        self,
        decision: IntentDecision,
        *,
        request: KnowledgeChatRequest | None = None,
    ) -> IntentEnvelope:
        notes = tuple(
            note
            for note in decision.routingNotes or ()
            if note.startswith(_AUDIT_REASON_PREFIXES)
        )
        if decision.primaryIntent is Intent.out_of_scope:
            domain_status = DomainStatus.OUT_OF_SCOPE
        elif decision.missingSlots or "rule:ambiguous-intent" in notes:
            domain_status = DomainStatus.NEEDS_CLARIFICATION
        else:
            domain_status = DomainStatus.IN_SCOPE

        operations = [decision.primaryIntent.value]
        operations.extend(intent.value for intent in decision.subIntents or ())
        market_request_level = str((decision.entities or {}).get("marketRequestLevel") or "")
        if decision.primaryIntent is Intent.market_scan and (
            market_request_level in {
                MarketRequestLevel.ANALYSIS.value,
                MarketRequestLevel.FULL_BOARD.value,
            }
            or decision.toolNeeds.needsVectorEvidence
        ):
            operations.append("market_research")
        project_bound = bool(request is not None and request.projectId is not None)
        if project_bound and decision.primaryIntent is Intent.followup_context:
            operations.append("project_knowledge")

        entities = dict(decision.entities or {})
        constraints = entities.pop("constraints", ())
        if isinstance(constraints, str):
            normalized_constraints = (constraints,)
        elif isinstance(constraints, (list, tuple, set, frozenset)):
            normalized_constraints = tuple(str(item) for item in constraints)
        else:
            normalized_constraints = ()
        has_context = bool(
            request is not None
            and (request.contextSummary or request.history or request.conversationId)
        )
        if project_bound:
            conversation_mode = "project_operation"
        elif has_context or decision.primaryIntent is Intent.followup_context:
            conversation_mode = "context_followup"
        else:
            conversation_mode = "new_question"

        if "llm:model-first" in notes:
            classification_source = "llm_primary"
        elif any(note.startswith("llm:") for note in notes):
            classification_source = "llm_fallback"
        elif any(note.startswith("supervisor:") for note in notes):
            classification_source = "supervised_rules"
        else:
            classification_source = "rules"

        ambiguity = tuple(
            note
            for note in notes
            if "ambiguous" in note or "clarification" in note
        )
        return IntentEnvelope(
            domainStatus=domain_status,
            goal=decision.primaryIntent.value,
            operations=tuple(dict.fromkeys(operations)),
            entities=entities,
            conversationMode=conversation_mode,
            constraints=normalized_constraints,
            confidence=decision.confidence,
            ambiguity=ambiguity,
            missingSlots=tuple(decision.missingSlots or ()),
            classificationSource=classification_source,
            notes=notes,
        )
