from __future__ import annotations

from app.services.agents.base import BaseSpecialistAgent


class CharacterAgent(BaseSpecialistAgent):
    agent_name = "character_design"
    answer_mode = "creative"
    generation_instructions = (
        "设计主角、反派、配角和关系线时优先服务爽点推进。",
        "给出人物欲望、短板、成长台阶和与题材卖点的绑定方式。",
        "人物标签要能转化为剧情冲突，不停留在设定表。",
    )
    evidence_policy = (
        "Use cited book evidence for craft extraction when present.",
        "Separate role design suggestions from factual book analysis.",
        "Avoid copying source characters directly.",
    )
    actions = ("design_character_arc", "map_relationship_conflicts")
