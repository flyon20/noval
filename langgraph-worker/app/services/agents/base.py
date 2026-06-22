from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Any, TypeAlias

from app.models.knowledge import KnowledgeChatRequest, KnowledgeSource
from app.services.intents.domain_intents import Intent, IntentDecision


@dataclass(slots=True)
class AgentRunContext:
    request: KnowledgeChatRequest
    intent_decision: IntentDecision
    sources: list[KnowledgeSource] = field(default_factory=list)
    skill_fragments: list[Any] = field(default_factory=list)
    actions: list[str] = field(default_factory=list)
    diagnostics: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class AgentRunResult:
    agentName: str
    answerMode: str
    generationInstructions: list[str]
    evidencePolicy: list[str]
    actions: list[str]
    diagnostics: dict[str, Any]


class BaseSpecialistAgent:
    agent_name = "base"
    answer_mode = "creative"
    generation_instructions: tuple[str, ...] = ()
    evidence_policy: tuple[str, ...] = ()
    actions: tuple[str, ...] = ()

    def run(self, context: AgentRunContext) -> AgentRunResult:
        diagnostics = {
            **context.diagnostics,
            "primaryIntent": context.intent_decision.primaryIntent.value,
            "subIntents": [intent.value for intent in context.intent_decision.subIntents],
            "sourceCount": len(context.sources),
            "materialSourceCount": sum(1 for source in context.sources if bool(source.material)),
            "skillFragmentCount": len(context.skill_fragments),
        }
        if context.sources:
            diagnostics["sourceKinds"] = sorted(
                {
                    source.sourceType
                    or source.analysisType
                    or ("rank" if source.rankNo is not None else "unknown")
                    for source in context.sources
                }
            )

        return AgentRunResult(
            agentName=self.agent_name,
            answerMode=self.answer_mode,
            generationInstructions=list(self.generation_instructions),
            evidencePolicy=list(self.evidence_policy),
            actions=[*context.actions, *self.actions],
            diagnostics=diagnostics,
        )


SpecialistAgentClass: TypeAlias = type[BaseSpecialistAgent]


def create_context(
    *,
    request: KnowledgeChatRequest,
    intent_decision: IntentDecision,
    sources: list[KnowledgeSource] | None = None,
    skill_fragments: list[Any] | None = None,
    actions: list[str] | None = None,
    diagnostics: dict[str, Any] | None = None,
) -> AgentRunContext:
    return AgentRunContext(
        request=request,
        intent_decision=intent_decision,
        sources=list(sources or []),
        skill_fragments=list(skill_fragments or []),
        actions=list(actions or []),
        diagnostics=dict(diagnostics or {}),
    )


def select_agents(decision: IntentDecision) -> list[SpecialistAgentClass]:
    from app.services.agents.book_breakdown_agent import BookBreakdownAgent
    from app.services.agents.chapter_outline_agent import ChapterOutlineAgent
    from app.services.agents.character_agent import CharacterAgent
    from app.services.agents.inspiration_agent import InspirationAgent
    from app.services.agents.market_scan_agent import MarketScanAgent
    from app.services.agents.opening_strategy_agent import OpeningStrategyAgent
    from app.services.agents.outline_agent import OutlineAgent
    from app.services.agents.revision_agent import RevisionAgent
    from app.services.agents.worldbuilding_agent import WorldbuildingAgent

    agent_by_intent: dict[Intent, SpecialistAgentClass] = {
        Intent.market_scan: MarketScanAgent,
        Intent.opening_strategy: OpeningStrategyAgent,
        Intent.book_breakdown: BookBreakdownAgent,
        Intent.outline_building: OutlineAgent,
        Intent.chapter_outline: ChapterOutlineAgent,
        Intent.inspiration_expand: InspirationAgent,
        Intent.character_design: CharacterAgent,
        Intent.worldbuilding: WorldbuildingAgent,
        Intent.revision_advice: RevisionAgent,
    }
    stable_order = [
        Intent.market_scan,
        Intent.opening_strategy,
        Intent.book_breakdown,
        Intent.outline_building,
        Intent.chapter_outline,
        Intent.inspiration_expand,
        Intent.character_design,
        Intent.worldbuilding,
        Intent.revision_advice,
    ]

    if decision.primaryIntent is Intent.mixed_creation_research:
        requested = {intent for intent in decision.subIntents if intent in agent_by_intent}
        if not requested:
            requested = {Intent.market_scan, Intent.opening_strategy, Intent.outline_building}
        else:
            requested.update({Intent.market_scan, Intent.opening_strategy, Intent.outline_building})
        return [agent_by_intent[intent] for intent in stable_order if intent in requested]

    agent_class = agent_by_intent.get(decision.primaryIntent)
    return [agent_class] if agent_class else []


def run_specialists(context: AgentRunContext) -> list[AgentRunResult]:
    return [agent_class().run(context) for agent_class in select_agents(context.intent_decision)]


async def run_specialists_parallel(
    context: AgentRunContext,
    *,
    max_parallel: int = 3,
) -> list[AgentRunResult]:
    agent_classes = select_agents(context.intent_decision)
    if not agent_classes:
        return []
    semaphore = asyncio.Semaphore(max(1, max_parallel))

    async def run_one(index: int, agent_class: SpecialistAgentClass) -> tuple[int, AgentRunResult]:
        async with semaphore:
            result = await asyncio.to_thread(agent_class().run, context)
            result.diagnostics["runner"] = "parallel"
            result.diagnostics["parallelLimit"] = max(1, max_parallel)
            result.diagnostics["parallelIndex"] = index
            return index, result

    indexed_results = await asyncio.gather(
        *(run_one(index, agent_class) for index, agent_class in enumerate(agent_classes))
    )
    return [result for _, result in sorted(indexed_results, key=lambda item: item[0])]
