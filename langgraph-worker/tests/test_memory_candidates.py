from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.services.intents.domain_intents import AnswerBoundary, Intent, IntentDecision, ToolNeeds
from app.services.novel_research_agent import NovelResearchAgent
from app.services.runtime.memory_candidates import MemoryCandidateExtractor


class MemoryCandidateExtractorTest(unittest.TestCase):
    def test_project_constraints_are_extracted_from_explicit_writing_preferences(self) -> None:
        candidates = MemoryCandidateExtractor().extract(
            KnowledgeChatRequest(
                question="这个项目不后宫，前三章快节奏一点。",
                projectId=900,
                traceId="trace-1",
            )
        )

        self.assertEqual(1, len(candidates))
        self.assertEqual("project", candidates[0].scope)
        self.assertEqual("constraint", candidates[0].type)
        self.assertIn("不后宫", candidates[0].content)
        self.assertIn("前三章快节奏", candidates[0].content)
        self.assertEqual("trace-1", candidates[0].sourceTraceId)

    def test_long_term_user_preference_requires_future_or_long_term_marker(self) -> None:
        candidates = MemoryCandidateExtractor().extract(
            KnowledgeChatRequest(
                question="我以后都想写番茄男频都市脑洞，不想写后宫。",
                projectId=900,
                traceId="trace-2",
            )
        )

        user_candidates = [candidate for candidate in candidates if candidate.scope == "user"]
        self.assertEqual(1, len(user_candidates))
        self.assertEqual("preference", user_candidates[0].type)
        self.assertIn("番茄男频都市脑洞", user_candidates[0].content)

    def test_temporary_preference_is_not_promoted_to_user_profile(self) -> None:
        candidates = MemoryCandidateExtractor().extract(
            KnowledgeChatRequest(
                question="这次试试女频甜宠，节奏可以慢一点。",
                projectId=900,
                traceId="trace-3",
            )
        )

        self.assertTrue(candidates)
        self.assertTrue(all(candidate.scope != "user" for candidate in candidates))
        self.assertIn(candidates[0].scope, {"project", "thread"})


class MemoryCandidateAgentIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_agent_attaches_memory_candidates_to_result_and_trace(self) -> None:
        class Provider:
            async def invoke(self, **_kwargs):
                return {
                    "content": "前三章按快节奏推进，并保持不后宫约束。",
                    "token_used": 64,
                }

        class Client:
            async def search_books(self, **_kwargs) -> list:
                return []

        class Router:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.opening_strategy,
                    confidence=0.91,
                    toolNeeds=ToolNeeds(needsCreativeGeneration=True),
                    answerBoundary=AnswerBoundary.creative_inference,
                )

        agent = NovelResearchAgent(knowledge_client=Client(), provider_client=Provider())
        agent.intent_router = Router()
        response = await agent.run(
            KnowledgeChatRequest(
                question="这个项目不后宫，前三章快节奏一点。",
                projectId=900,
                traceId="trace-memory-1",
            )
        )

        candidates = response.resultJson["memoryCandidates"]
        self.assertEqual("project", candidates[0]["scope"])
        self.assertEqual("constraint", candidates[0]["type"])
        self.assertEqual(candidates, response.resultJson["trace"]["memoryCandidates"])


if __name__ == "__main__":
    unittest.main()
