from __future__ import annotations

import inspect
import re
from collections.abc import Awaitable, Callable
from typing import Any

from app.models.knowledge import KnowledgeChatRequest
from app.services.intents import AnswerBoundary, Intent, IntentDecision, IntentRouter


IntentLlmFallback = Callable[[KnowledgeChatRequest, IntentDecision], Awaitable[IntentDecision | None] | IntentDecision | None]


class FastIntentClassifier:
    def __init__(self, router: IntentRouter | None = None) -> None:
        self.router = router or IntentRouter()

    def classify(self, request: KnowledgeChatRequest) -> IntentDecision:
        history = [
            str(message.get("content") or "")
            for message in (request.history or [])
            if str(message.get("content") or "").strip()
        ]
        return self.router.classify(
            request.question or "",
            context_summary=request.contextSummary,
            history=history,
        )


class LLMIntentAgent:
    def __init__(
        self,
        fallback: IntentLlmFallback | None = None,
        *,
        enabled: bool = False,
        min_confidence: float = 0.82,
    ) -> None:
        self.fallback = fallback
        self.enabled = enabled
        self.min_confidence = max(0.0, min(1.0, float(min_confidence)))

    def should_call(self, request: KnowledgeChatRequest, decision: IntentDecision) -> bool:
        if not self.enabled or self.fallback is None:
            return False
        if request.bookId is not None or request.selectedCandidate is not None:
            return False
        if request.bookName and request.bookName.strip():
            return False
        if decision.primaryIntent is Intent.out_of_scope:
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
    ) -> None:
        self.fast_classifier = FastIntentClassifier(router)
        self.llm_agent = LLMIntentAgent(
            llm_fallback,
            enabled=llm_fallback_enabled,
            min_confidence=llm_min_confidence,
        )
        self.supervisor = IntentSupervisor()

    @property
    def router(self) -> IntentRouter:
        return self.fast_classifier.router

    async def decide(self, request: KnowledgeChatRequest) -> IntentDecision:
        rule_decision = self.fast_classifier.classify(request)
        fallback_decision = await self.llm_agent.decide(request, rule_decision)
        decision = fallback_decision or rule_decision
        return self.supervisor.repair(decision, request)
