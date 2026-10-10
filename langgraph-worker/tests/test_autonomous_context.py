from __future__ import annotations

import unittest
from unittest.mock import patch

from app.models.knowledge import KnowledgeChatRequest, KnowledgeChatResponse, KnowledgeSource
from app.services.harness.autonomous_context import AutonomousConversationContext
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.novel_research_agent import NovelResearchAgent
from app.services.skills.runtime_skill import RuntimeSkill
from test_autonomous_research import ResearchBackend, ScriptedProvider, reply


def request(question="核对项目章节", **updates):
    return KnowledgeChatRequest(**{"question": question, "userId": 7, "projectId": 91, "workId": 911,
                                    "conversationId": "context-test", "limits": {"modelName": "test-model"}, **updates})


def followup(first, response, **updates):
    return first.model_copy(update={"question": "基于刚才的资料设计后续，保留原设定",
                                    "history": [*first.history, {"role": "user", "content": first.question},
                                                {"role": "assistant", "content": response.answer}], **updates})


def source(**updates):
    return KnowledgeSource(**{"projectId": 91, "workId": 911, "documentId": 71, "chapterId": 3,
                              "generationId": 1, "chapterVersion": 1, "contentHash": "version-one",
                              "preview": "UNIQUE_PRIOR_SOURCE", **updates})


class ContextStoreTest(unittest.TestCase):
    def test_scope_and_history_changes_discard_saved_evidence(self):
        first = request()
        answer = KnowledgeChatResponse(status="answered", answer="核对完成[1]", sources=[source()])
        for changes in ({"userId": 8}, {"projectId": 92}, {"workId": 912}, {"conversationId": "other"},
                        {"history": []}, {"contextSummary": "new branch"}, {"bookId": 9}):
            with self.subTest(changes=changes):
                memory = AutonomousConversationContext()
                memory.remember(first, answer, skill_pins=[])
                self.assertIsNone(memory.restore(followup(first, answer, **changes)))
                self.assertIsNone(memory.restore(followup(first, answer)))

    def test_expiry_is_not_extended_by_repeated_continuations(self):
        now = [0.0]
        memory = AutonomousConversationContext(ttl_seconds=10, clock=lambda: now[0])
        first = request()
        answer = KnowledgeChatResponse(status="answered", answer="done", sources=[source()])
        memory.remember(first, answer, skill_pins=[])
        second = followup(first, answer)
        now[0] = 9
        previous = memory.restore(second)
        self.assertIsNotNone(previous)
        memory.remember(second, answer, skill_pins=[], previous=previous)
        now[0] = 10
        self.assertIsNone(memory.restore(followup(second, answer)))

    def test_unversioned_partial_or_oversize_evidence_is_not_renumbered(self):
        first = request()
        for sources in ([source(), source(contentHash=None)], [source()] * 21, [source(preview="x" * 17000)]):
            memory = AutonomousConversationContext()
            answer = KnowledgeChatResponse(status="answered", answer="done", sources=sources)
            memory.remember(first, answer, skill_pins=[])
            self.assertEqual((), memory.restore(followup(first, answer)).sources)

    def test_snapshots_do_not_alias_response_or_restore_objects(self):
        memory = AutonomousConversationContext()
        first = request()
        answer = KnowledgeChatResponse(status="answered", answer="done", sources=[source()])
        memory.remember(first, answer, skill_pins=[])
        answer.sources[0].preview = "changed response"
        second = followup(first, answer)
        restored = memory.restore(second)
        self.assertEqual("UNIQUE_PRIOR_SOURCE", restored.sources[0].preview)
        restored.sources[0].preview = "changed restore"
        self.assertEqual("UNIQUE_PRIOR_SOURCE", memory.restore(second).sources[0].preview)

    def test_missing_owner_or_conversation_does_not_create_state(self):
        for changes in ({"userId": None}, {"conversationId": None}):
            memory = AutonomousConversationContext()
            first = request(**changes)
            answer = KnowledgeChatResponse(status="answered", answer="done", sources=[source()])
            memory.remember(first, answer, skill_pins=[])
            self.assertIsNone(memory.restore(followup(first, answer)))


class ContextIntegrationTest(unittest.IsolatedAsyncioTestCase):
    def make_agent(self, replies, backend=None):
        backend = backend or ResearchBackend()
        provider = ScriptedProvider(replies)
        memory = AutonomousConversationContext()
        agent = NovelResearchAgent(knowledge_client=backend, provider_client=provider,
                                   runtime_mode="autonomous", autonomous_context=memory)
        return agent, provider, backend, memory

    async def test_followup_reuses_versioned_evidence_and_skill_with_one_model_call(self):
        agent, provider, backend, _ = self.make_agent([
            reply(name="project_resolve"), reply(name="skill_lookup", arguments={"skillId": "webnovel-chapter-outline"}),
            reply(name="project_retrieve", arguments={"query": "怀表"}), reply("已核对[1]"),
            reply("根据上轮资料[1]，设计后续方案。"),
        ])
        first = request()
        answer = await agent.run(first)
        self.assertEqual(1, len(answer.sources))
        with run_budget_scope(RunBudget(mode="fast", max_total_tokens=10000, max_tool_calls=1, max_delegations=0)):
            result = await agent.run(followup(first, answer))
        self.assertEqual("answered", result.status)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(3, len(backend.project_calls))
        self.assertEqual(1, result.resultJson["continuationContext"]["restoredSkills"])
        self.assertEqual(1, result.resultJson["continuationContext"]["historicalSources"])
        self.assertTrue(result.sources[0].historicalReference)
        self.assertEqual("prior_turn_snapshot", result.sources[0].freshness)
        self.assertEqual("synthetic", result.sources[0].contentHash)
        self.assertEqual(["project.resolve"], [call["name"] for call in result.resultJson["toolRuns"]])

    async def test_scope_revalidation_failure_does_not_disclose_snapshot(self):
        agent, provider, backend, _ = self.make_agent([
            reply(name="project_resolve"), reply(name="project_retrieve", arguments={"query": "怀表"}),
            reply("已核对[1]"), reply("当前作品范围无法确认。"),
        ])
        first = request()
        answer = await agent.run(first)
        backend.ambiguous = True
        result = await agent.run(followup(first, answer))
        self.assertFalse(result.sources)
        self.assertFalse(result.resultJson["continuationContext"]["projectRevalidated"])
        prompt = str(provider.calls[-1]["messages"])
        self.assertNotIn("historicalEvidenceSnapshots", prompt)
        self.assertNotIn(answer.sources[0].preview, prompt)

    async def test_no_tool_budget_does_not_restore_project_evidence(self):
        agent, provider, backend, _ = self.make_agent([
            reply(name="project_resolve"), reply(name="project_retrieve", arguments={"query": "怀表"}),
            reply("已核对[1]"), reply("当前预算无法确认作品范围。"),
        ])
        first = request()
        answer = await agent.run(first)
        with run_budget_scope(RunBudget(mode="fast", max_total_tokens=10000, max_tool_calls=0, max_delegations=0)):
            result = await agent.run(followup(first, answer))
        self.assertFalse(result.sources)
        self.assertEqual(2, len(backend.project_calls))

    async def test_fresh_retrieval_does_not_relabel_old_version_as_current(self):
        class UpdatedBackend(ResearchBackend):
            revision = 1

            async def retrieve_project_knowledge(self, **kwargs):
                result = await super().retrieve_project_knowledge(**kwargs)
                result["evidence"][0].update({"chapterVersion": self.revision, "contentHash": f"v{self.revision}"})
                return result

        backend = UpdatedBackend()
        agent, _, _, _ = self.make_agent([
            reply(name="project_resolve"), reply(name="project_retrieve", arguments={"query": "怀表"}), reply("上轮事实[1]"),
            reply(name="project_retrieve", arguments={"query": "修改后的最新章节"}), reply("根据新版本[2]。"),
        ], backend)
        first = request()
        answer = await agent.run(first)
        backend.revision = 2
        result = await agent.run(followup(first, answer, question="章节改过了，请重新核对最新内容"))
        self.assertEqual([1, 2], [item.chapterVersion for item in result.sources])
        self.assertTrue(result.sources[0].historicalReference)
        self.assertIsNot(result.sources[1].historicalReference, True)
        self.assertEqual("answered", result.status)

    async def test_changed_skill_is_loaded_again_instead_of_restoring_old_body(self):
        old = RuntimeSkill(skillId="test-skill", version="1", intents=(), triggers=(), instructions="OLD_METHOD")
        new = RuntimeSkill(skillId="test-skill", version="2", intents=(), triggers=(), instructions="NEW_METHOD")
        agent, provider, _, _ = self.make_agent([
            reply(name="skill_lookup", arguments={"skillId": old.skillId}), reply("第一场景"),
            reply(name="skill_lookup", arguments={"skillId": new.skillId}), reply("续写场景"),
        ])
        first = request(projectId=None, workId=None)
        with patch.object(agent.skill_registry, "load_all", return_value=(old,)):
            answer = await agent.run(first)
        with patch.object(agent.skill_registry, "load_all", return_value=(new,)):
            result = await agent.run(followup(first, answer))
        self.assertNotIn("OLD_METHOD", str(provider.calls[2]["messages"]))
        self.assertIn("NEW_METHOD", str(provider.calls[3]["messages"]))
        self.assertEqual(0, result.resultJson["continuationContext"]["restoredSkills"])
