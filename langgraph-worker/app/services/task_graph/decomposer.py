from __future__ import annotations

from app.models.agent_task import Perspective, TaskGraph, TaskNode, TaskType
from app.services.intents.domain_intents import Intent, IntentDecision


class TaskGraphDecomposer:
    def decompose(
        self,
        question: str,
        *,
        intent_decision: IntentDecision | None = None,
    ) -> TaskGraph:
        text = (question or "").strip()
        normalized = text.lower()

        if self._has_skill_governance(normalized):
            return TaskGraph(
                userGoal=text,
                adminOperationRequested=True,
                answerBoundary="admin_only_skill_governance",
                tasks=[
                    TaskNode(
                        id="task_skill_governance",
                        type=TaskType.skill_governance,
                        goal="Skill governance request must be handled by an administrator only.",
                        perspective=Perspective.supervisor,
                        tools=[],
                        evidencePolicy="admin_only",
                    )
                ],
            )

        task_types = self._task_types_from_rules(normalized)
        if intent_decision is not None:
            task_types.update(self._task_types_from_intent(intent_decision))
        if not task_types:
            task_types.add(TaskType.followup_context)
        source_policy = dict(getattr(intent_decision, "sourcePolicy", {}) or {})
        memory_policy = dict(getattr(intent_decision, "memoryPolicy", {}) or {})

        ordered_types = [task_type for task_type in self._stable_order() if task_type in task_types]
        tasks: list[TaskNode] = []
        completed_ids: dict[TaskType, str] = {}
        for index, task_type in enumerate(ordered_types, start=1):
            task_id = f"task_{index}_{task_type.value}"
            tasks.append(
                TaskNode(
                    id=task_id,
                    type=task_type,
                    goal=self._goal_for(task_type, text),
                    perspective=self._perspective_for(task_type),
                    tools=self._default_tools_for(task_type),
                    dependsOn=self._dependencies_for(task_type, completed_ids),
                    evidencePolicy=self._evidence_policy_for(task_type),
                    freshnessPolicy=self._freshness_policy_for(task_type, source_policy),
                    memoryPolicy=memory_policy,
                )
            )
            completed_ids[task_type] = task_id

        return TaskGraph(
            userGoal=text,
            tasks=tasks,
            answerBoundary=self._answer_boundary_for(set(ordered_types)),
        )

    def _task_types_from_rules(self, normalized: str) -> set[TaskType]:
        task_types: set[TaskType] = set()
        if self._has_market(normalized):
            task_types.add(TaskType.market_scan)
        if self._has_reference_book(normalized):
            task_types.add(TaskType.book_breakdown)
        if self._has_topic_strategy(normalized):
            task_types.add(TaskType.topic_strategy)
        if self._has_outline(normalized):
            task_types.add(TaskType.outline_building)
        if self._has_chapter_outline(normalized):
            task_types.add(TaskType.chapter_outline)
        if self._has_character(normalized):
            task_types.add(TaskType.character_design)
        if self._has_worldbuilding(normalized):
            task_types.add(TaskType.worldbuilding)
        if self._has_revision(normalized):
            task_types.add(TaskType.revision_advice)
        if self._has_reader_risk(normalized):
            task_types.add(TaskType.reader_risk)
        if self._has_editor_risk(normalized):
            task_types.add(TaskType.editor_risk)
        return task_types

    def _task_types_from_intent(self, decision: IntentDecision) -> set[TaskType]:
        intent_map = {
            Intent.market_scan: TaskType.market_scan,
            Intent.book_breakdown: TaskType.book_breakdown,
            Intent.opening_strategy: TaskType.topic_strategy,
            Intent.outline_building: TaskType.outline_building,
            Intent.chapter_outline: TaskType.chapter_outline,
            Intent.character_design: TaskType.character_design,
            Intent.worldbuilding: TaskType.worldbuilding,
            Intent.revision_advice: TaskType.revision_advice,
            Intent.followup_context: TaskType.followup_context,
        }
        intents = [decision.primaryIntent, *decision.subIntents]
        return {intent_map[intent] for intent in intents if intent in intent_map}

    def _has_market(self, text: str) -> bool:
        return any(term in text for term in ("榜单", "榜一", "排行", "排名", "趋势", "风向", "热门", "top", "市场", "扫榜", "新书榜"))

    def _has_reference_book(self, text: str) -> bool:
        return any(term in text for term in ("参考", "对标", "榜一卖点", "拆书", "拆解", "热书", "爆款", "卖点"))

    def _has_topic_strategy(self, text: str) -> bool:
        return any(term in text for term in ("题材", "选题", "开文", "开书", "新题材", "不撞车", "方向", "定位", "脑洞"))

    def _has_outline(self, text: str) -> bool:
        return any(term in text for term in ("大纲", "卷纲", "主线", "结构", "三幕"))

    def _has_chapter_outline(self, text: str) -> bool:
        return any(term in text for term in ("细纲", "前三章", "前3章", "章节", "分章", "每章"))

    def _has_character(self, text: str) -> bool:
        return any(term in text for term in ("人设", "角色", "主角", "配角", "反派", "人物"))

    def _has_worldbuilding(self, text: str) -> bool:
        return any(term in text for term in ("世界观", "设定", "体系", "规则", "势力"))

    def _has_revision(self, text: str) -> bool:
        return any(term in text for term in ("改稿", "润色", "重写", "优化", "修改建议"))

    def _has_reader_risk(self, text: str) -> bool:
        return any(term in text for term in ("毒点", "劝退", "读者", "爽点不足", "风险"))

    def _has_editor_risk(self, text: str) -> bool:
        return any(term in text for term in ("编辑", "签约", "商业性", "过稿"))

    def _has_skill_governance(self, text: str) -> bool:
        if "skill" not in text and "技能" not in text:
            return False
        return any(term in text for term in ("新增", "安装", "修改", "发布", "禁用", "删除", "创建", "approve", "publish"))

    def _has_webnovel_scope(self, text: str) -> bool:
        return any(term in text for term in ("网文", "小说", "男频", "女频", "修仙文", "都市文", "玄幻文", "开文", "开书"))

    def _stable_order(self) -> list[TaskType]:
        return [
            TaskType.market_scan,
            TaskType.book_breakdown,
            TaskType.topic_strategy,
            TaskType.outline_building,
            TaskType.chapter_outline,
            TaskType.character_design,
            TaskType.worldbuilding,
            TaskType.revision_advice,
            TaskType.reader_risk,
            TaskType.editor_risk,
            TaskType.followup_context,
        ]

    def _goal_for(self, task_type: TaskType, question: str) -> str:
        goals = {
            TaskType.market_scan: "Extract current market/ranking signals relevant to the user's web-novel goal.",
            TaskType.book_breakdown: "Break down reference books or ranked samples into reusable craft signals.",
            TaskType.topic_strategy: "Create a differentiated topic and opening strategy from the available signals.",
            TaskType.outline_building: "Build a macro outline that can sustain serial writing.",
            TaskType.chapter_outline: "Draft chapter-level beats, hooks, conflict, and escalation.",
            TaskType.character_design: "Design characters with motivation, contrast, and serial pressure.",
            TaskType.worldbuilding: "Design rules and factions that can generate repeatable conflicts.",
            TaskType.revision_advice: "Identify rewrite actions and priority fixes.",
            TaskType.reader_risk: "Evaluate reader-side poison points and drop-off risks.",
            TaskType.editor_risk: "Evaluate editor-side marketability and submission risks.",
            TaskType.followup_context: "Recover the user's project context before answering.",
        }
        return f"{goals[task_type]} User goal: {question[:160]}"

    def _perspective_for(self, task_type: TaskType) -> Perspective:
        if task_type is TaskType.market_scan:
            return Perspective.market
        if task_type is TaskType.book_breakdown:
            return Perspective.book
        if task_type in {TaskType.reader_risk}:
            return Perspective.reader
        if task_type in {TaskType.editor_risk, TaskType.revision_advice}:
            return Perspective.editor
        return Perspective.author

    def _default_tools_for(self, task_type: TaskType) -> list[str]:
        return {
            TaskType.market_scan: ["rank.lookup", "rank.research_pack"],
            TaskType.book_breakdown: ["book.research_pack", "knowledge.vector_search"],
            TaskType.topic_strategy: ["knowledge.vector_search", "skill.lookup"],
            TaskType.outline_building: ["skill.lookup", "memory.project_context"],
            TaskType.chapter_outline: ["skill.lookup", "memory.project_context"],
            TaskType.character_design: ["skill.lookup", "memory.project_context"],
            TaskType.worldbuilding: ["skill.lookup", "memory.project_context"],
            TaskType.revision_advice: ["knowledge.vector_search", "editor.risk_check"],
            TaskType.reader_risk: ["reader.simulate_feedback"],
            TaskType.editor_risk: ["editor.risk_check"],
            TaskType.followup_context: ["memory.project_context"],
        }.get(task_type, [])

    def _dependencies_for(self, task_type: TaskType, completed_ids: dict[TaskType, str]) -> list[str]:
        deps: list[str] = []
        if task_type in {TaskType.book_breakdown, TaskType.topic_strategy} and TaskType.market_scan in completed_ids:
            deps.append(completed_ids[TaskType.market_scan])
        if task_type in {TaskType.outline_building, TaskType.chapter_outline} and TaskType.topic_strategy in completed_ids:
            deps.append(completed_ids[TaskType.topic_strategy])
        if task_type in {TaskType.reader_risk, TaskType.editor_risk}:
            for candidate in (TaskType.chapter_outline, TaskType.outline_building, TaskType.topic_strategy):
                if candidate in completed_ids:
                    deps.append(completed_ids[candidate])
                    break
        return deps

    def _evidence_policy_for(self, task_type: TaskType) -> str:
        if task_type is TaskType.market_scan:
            return "rank_facts_required"
        if task_type is TaskType.book_breakdown:
            return "book_examples_required"
        if task_type in {TaskType.reader_risk, TaskType.editor_risk}:
            return "inference_signal"
        return "author_inference"

    def _freshness_policy_for(self, task_type: TaskType, source_policy: dict) -> dict:
        if task_type is TaskType.market_scan:
            return dict(source_policy or {
                "freshness": "latest",
                "allowHistorical": False,
                "requireSnapshotTime": True,
            })
        return dict(source_policy or {})

    def _answer_boundary_for(self, task_types: set[TaskType]) -> str:
        if TaskType.market_scan in task_types and task_types & {
            TaskType.topic_strategy,
            TaskType.outline_building,
            TaskType.chapter_outline,
        }:
            return "market_evidence_plus_author_inference"
        if TaskType.market_scan in task_types:
            return "market_evidence"
        if TaskType.book_breakdown in task_types:
            return "book_evidence_plus_craft_extraction"
        return "creative_inference"
