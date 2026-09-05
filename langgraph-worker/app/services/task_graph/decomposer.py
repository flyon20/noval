from __future__ import annotations

import re

from app.models.agent_task import Perspective, TaskGraph, TaskNode, TaskType
from app.services.harness.contracts import CapabilityPlan
from app.services.intents.domain_intents import Intent, IntentDecision


class TaskGraphDecomposer:
    def project_task_type(self, question: str) -> TaskType | None:
        return self._project_task_type((question or "").strip().lower())

    def decompose(
        self,
        question: str,
        *,
        intent_decision: IntentDecision | None = None,
        capability_plan: CapabilityPlan | None = None,
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

        if intent_decision is not None and getattr(intent_decision, "primaryIntent", None) is Intent.out_of_scope:
            return TaskGraph(
                userGoal=text,
                tasks=[],
                answerBoundary="out_of_scope",
            )

        project_task = self.project_task_type(text)
        task_types = {project_task} if project_task is not None else self._task_types_from_rules(normalized)
        if intent_decision is not None and project_task is None:
            task_types.update(self._task_types_from_intent(intent_decision))
        if capability_plan is not None:
            task_types = self._constrain_to_capability_plan(task_types, capability_plan, project_task)
        if not task_types:
            fallback_task = self._fallback_task_from_capability_plan(capability_plan)
            if fallback_task is not None:
                task_types.add(fallback_task)
            elif capability_plan is None:
                task_types.add(TaskType.followup_context)
        source_policy = dict(getattr(intent_decision, "sourcePolicy", {}) or {})
        memory_policy = dict(getattr(intent_decision, "memoryPolicy", {}) or {})

        ordered_types = [task_type for task_type in self._stable_order() if task_type in task_types]
        rank_sourced_book_breakdown = (
            TaskType.market_scan in ordered_types
            and TaskType.book_breakdown in ordered_types
            and self._has_rank_sourced_reference(normalized)
        )
        exact_foreshadowing_count = self._has_foreshadowing_count(normalized)
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
                    tools=self._default_tools_for(
                        task_type,
                        rank_sourced_book_breakdown=rank_sourced_book_breakdown,
                        exact_foreshadowing_count=exact_foreshadowing_count,
                    ),
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
        project_task = self._project_task_type(normalized)
        if project_task is not None:
            return {project_task}
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

    def _project_task_type(self, text: str) -> TaskType | None:
        if self._has_foreshadowing_audit(text):
            return TaskType.foreshadowing_audit
        if self._has_continuity_check(text):
            return TaskType.continuity_check
        if self._has_project_knowledge_question(text):
            return TaskType.project_knowledge_qa
        return None

    def _has_foreshadowing_audit(self, text: str) -> bool:
        has_clue = any(term in text for term in ("伏笔", "暗线", "铺垫", "悬念线"))
        asks_status = any(term in text for term in (
            "未回收", "没回收", "没有回收", "遗漏", "忘了", "忘记", "还有哪些", "埋过", "回收了吗", "没处理",
        ))
        return has_clue and (asks_status or self._has_foreshadowing_count(text))

    def _has_foreshadowing_count(self, text: str) -> bool:
        has_clue = any(term in text for term in ("伏笔", "暗线", "铺垫", "悬念线"))
        asks_count = any(term in text for term in ("多少", "几条", "几处", "一共", "总数", "数量"))
        return has_clue and asks_count

    def _has_continuity_check(self, text: str) -> bool:
        has_continuity_subject = any(term in text for term in (
            "时间线", "人物动机", "人物状态", "设定冲突", "设定矛盾", "前后不一", "连续性", "吃书",
        ))
        asks_check = any(term in text for term in (
            "冲突", "矛盾", "一致", "对不上", "有没有问题", "是否", "检查", "审查",
        ))
        return has_continuity_subject and asks_check

    def _has_project_knowledge_question(self, text: str) -> bool:
        has_project_reference = any(term in text for term in (
            "我这本书", "这本小说", "我写的", "我的小说", "当前项目", "这个项目", "本项目",
            "前文", "前面写过", "之前写过", "已导入",
        )) or bool(re.search(r"(?:第?\s*\d+|前\s*\d+|[一二三四五六七八九十百]+)\s*章", text))
        asks_recall = any(term in text for term in (
            "写了什么", "发生了什么", "提到过", "有没有", "有吗", "不是有", "在哪里", "哪一章",
            "回忆", "找出", "查一下", "设计得如何", "设计的如何", "写得如何", "写的如何",
            "评价", "评审", "点评", "诊断",
            "是否合理", "合理吗",
        ))
        return has_project_reference and asks_recall

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

    def _constrain_to_capability_plan(
        self,
        task_types: set[TaskType],
        plan: CapabilityPlan,
        project_task: TaskType | None,
    ) -> set[TaskType]:
        capability_ids = {
            request.capabilityId
            for request in plan.capabilityRequests
        }
        allowed: set[TaskType] = set()
        task_capabilities = {
            "market.read": {TaskType.market_scan},
            "market.research": {TaskType.market_scan},
            "book.read": {TaskType.book_breakdown},
            "creation.opening": {TaskType.topic_strategy},
            "creation.ideation": {TaskType.topic_strategy},
            "creation.outline": {TaskType.outline_building},
            "creation.chapter_outline": {TaskType.chapter_outline},
            "creation.character": {TaskType.character_design},
            "creation.worldbuilding": {TaskType.worldbuilding},
            "creation.revision": {
                TaskType.revision_advice,
                TaskType.reader_risk,
                TaskType.editor_risk,
            },
            "creation.followup": {TaskType.followup_context},
            "project.retrieve": {
                TaskType.project_knowledge_qa,
                TaskType.foreshadowing_audit,
                TaskType.continuity_check,
            },
        }
        for capability_id in capability_ids:
            allowed.update(task_capabilities.get(capability_id, set()))
        constrained = task_types.intersection(allowed)
        if project_task is not None and "project.retrieve" in capability_ids:
            constrained.add(project_task)
        return constrained

    def _fallback_task_from_capability_plan(self, plan: CapabilityPlan | None) -> TaskType | None:
        if plan is None:
            return None
        capability_ids = {
            request.capabilityId
            for request in plan.capabilityRequests
        }
        ordered_defaults = (
            ("market.read", TaskType.market_scan),
            ("book.read", TaskType.book_breakdown),
            ("creation.opening", TaskType.topic_strategy),
            ("creation.ideation", TaskType.topic_strategy),
            ("creation.outline", TaskType.outline_building),
            ("creation.chapter_outline", TaskType.chapter_outline),
            ("creation.character", TaskType.character_design),
            ("creation.worldbuilding", TaskType.worldbuilding),
            ("creation.revision", TaskType.revision_advice),
            ("creation.followup", TaskType.followup_context),
            ("project.retrieve", TaskType.project_knowledge_qa),
        )
        return next(
            (task_type for capability_id, task_type in ordered_defaults if capability_id in capability_ids),
            None,
        )

    def _has_market(self, text: str) -> bool:
        return any(term in text for term in ("榜单", "榜一", "排行", "排名", "趋势", "风向", "热门", "top", "市场", "扫榜", "新书榜"))

    def _has_reference_book(self, text: str) -> bool:
        return any(term in text for term in ("参考", "对标", "榜一卖点", "拆书", "拆解", "热书", "爆款", "卖点"))

    def _has_rank_sourced_reference(self, text: str) -> bool:
        if any(term in text for term in (
            "榜一", "榜首", "榜上热书", "榜上作品", "榜单热书", "上榜热书", "上榜作品",
        )):
            return True
        numbered_rank = r"(?:\d+|[一二三四五六七八九十]+)"
        return bool(
            re.search(rf"(?:榜单|新书榜|排行|排名)\s*(?:第\s*)?{numbered_rank}\s*名", text)
            or re.search(
                rf"(?:榜单|新书榜|排行|排名|榜)[^《》，。！？]{{0,24}}"
                rf"(?:第\s*)?{numbered_rank}\s*(?:名)?[^《》，。！？]{{0,8}}"
                rf"(?:书|作品|卖点|开篇|章节|题材)",
                text,
            )
            or re.search(
                rf"榜\s*(?:第\s*)?{numbered_rank}\s*(?:名)?\s*(?:的)?\s*(?:书|作品|卖点|开篇|章节|题材)",
                text,
            )
        )

    def _has_topic_strategy(self, text: str) -> bool:
        has_topic = any(term in text for term in (
            "题材",
            "选题",
            "开文",
            "开书",
            "方向",
            "定位",
            "脑洞",
        ))
        has_generation_action = any(term in text for term in (
            "给我",
            "给一个",
            "设计",
            "推荐",
            "构思",
            "生成",
            "想写",
            "要写",
            "怎么写",
            "怎么做成",
            "变成可写",
            "做一个",
            "模仿",
            "仿写",
            "新题材",
            "不撞车",
            "开文",
            "开书",
        ))
        has_taxonomy_question = any(term in text for term in (
            "为什么没有",
            "怎么没看到",
            "是不是不火",
            "不火吗",
            "分类",
            "归类",
            "属于什么",
            "算什么类型",
            "什么别名",
            "同类题材",
            "融合方向",
            "题材变体",
        ))
        has_market_analysis = any(term in text for term in (
            "有哪些题材",
            "都是哪些题材",
            "热门题材",
            "题材趋势",
            "题材分布",
            "只做市场扫描",
        ))
        has_risk_extension = any(term in text for term in (
            "读者毒点",
            "劝退风险",
            "签约",
            "商业性风险",
            "编辑风险",
            "过稿",
        ))
        has_explicit_design = any(term in text for term in (
            "设计",
            "构思",
            "生成",
            "怎么写",
            "怎么做成",
            "变成可写",
            "做一个",
            "模仿",
            "仿写",
            "新题材",
            "不撞车",
            "开文",
            "开书",
        ))
        if has_taxonomy_question and not has_explicit_design:
            return False
        if has_market_analysis and not has_generation_action and not has_risk_extension:
            return False
        return has_topic and (has_generation_action or has_risk_extension)

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
            TaskType.project_knowledge_qa,
            TaskType.foreshadowing_audit,
            TaskType.continuity_check,
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
            TaskType.project_knowledge_qa: "Retrieve project-scoped chapter and semantic evidence for the user's own novel.",
            TaskType.foreshadowing_audit: "Audit unresolved foreshadowing against structured state and chapter evidence.",
            TaskType.continuity_check: "Check character, timeline, and world-rule continuity against project evidence.",
        }
        return f"{goals[task_type]} User goal: {question[:160]}"

    def _perspective_for(self, task_type: TaskType) -> Perspective:
        if task_type is TaskType.market_scan:
            return Perspective.market
        if task_type is TaskType.book_breakdown:
            return Perspective.book
        if task_type in {TaskType.reader_risk}:
            return Perspective.reader
        if task_type in {
            TaskType.editor_risk,
            TaskType.revision_advice,
            TaskType.foreshadowing_audit,
            TaskType.continuity_check,
        }:
            return Perspective.editor
        return Perspective.author

    def _default_tools_for(
        self,
        task_type: TaskType,
        *,
        rank_sourced_book_breakdown: bool = False,
        exact_foreshadowing_count: bool = False,
    ) -> list[str]:
        if task_type is TaskType.book_breakdown and rank_sourced_book_breakdown:
            return ["rank.research_pack"]
        if task_type is TaskType.topic_strategy and rank_sourced_book_breakdown:
            return ["skill.lookup"]
        if task_type is TaskType.foreshadowing_audit and exact_foreshadowing_count:
            return ["project.resolve", "project.foreshadowing.aggregate", "project.retrieve"]
        return {
            TaskType.market_scan: ["rank.lookup"],
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
            TaskType.project_knowledge_qa: [
                "project.resolve",
                "project.retrieve",
            ],
            TaskType.foreshadowing_audit: [
                "project.resolve",
                "project.retrieve",
            ],
            TaskType.continuity_check: [
                "project.resolve",
                "project.retrieve",
            ],
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
        if task_type in {
            TaskType.project_knowledge_qa,
            TaskType.foreshadowing_audit,
            TaskType.continuity_check,
        }:
            return "project_bound_chapter_or_memory_evidence"
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
        if task_types & {
            TaskType.project_knowledge_qa,
            TaskType.foreshadowing_audit,
            TaskType.continuity_check,
        }:
            return "project_knowledge"
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
