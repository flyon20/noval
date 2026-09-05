"""
Test Tool Registry Integration with NovelResearchAgent
"""
from __future__ import annotations

import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.services.novel_research_agent import NovelResearchAgent
from tests.test_novel_research_agent import FakeAnswerProvider, StructuredRankTrendKnowledgeClient


class ToolRegistryIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_agent_initializes_with_tool_registry(self) -> None:
        """Agent should create and populate tool registry on initialization"""
        agent = NovelResearchAgent()

        # Verify registry exists
        self.assertIsNotNone(agent._tool_registry)

        # Verify tools are registered
        available = agent._tool_registry.available()
        tool_names = [tool.name for tool in available]

        # Should have rank tools
        self.assertIn("rank.lookup", tool_names)
        self.assertIn("rank.research_pack", tool_names)

        # Should have book tools
        self.assertIn("book.research_pack", tool_names)

        # Should have knowledge tools
        self.assertIn("knowledge.vector_search", tool_names)

    async def test_registry_tools_have_correct_toolsets(self) -> None:
        """Tools should be organized by toolset"""
        agent = NovelResearchAgent()

        rank_tools = agent._tool_registry.available(toolset="rank")
        self.assertEqual(2, len(rank_tools))

        book_tools = agent._tool_registry.available(toolset="book")
        self.assertEqual(1, len(book_tools))

        knowledge_tools = agent._tool_registry.available(toolset="knowledge")
        self.assertEqual(1, len(knowledge_tools))

    async def test_tools_have_check_functions(self) -> None:
        """All tools should have availability check functions"""
        agent = NovelResearchAgent()

        for tool in agent._tool_registry.available():
            self.assertIsNotNone(tool.check_fn)
            # Check function should be callable
            result = tool.check_fn()
            self.assertIsInstance(result, bool)

    async def test_agent_executes_task_graph_domain_tools_for_trace(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=StructuredRankTrendKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )

        response = await agent.run(KnowledgeChatRequest(
            question="看番茄男频都市脑洞新书榜Top10，拆榜一卖点，再给我题材方向",
            mode="research",
            limits={"rankLimit": 3, "evidenceLimit": 3},
        ))

        tool_runs = response.resultJson["trace"]["toolRuns"]
        tool_names = [run["name"] for run in tool_runs]
        self.assertIn("rank.lookup", tool_names)
        self.assertIn("rank.research_pack", tool_names)
        # Trace projection must not retain raw tool input bodies.
        self.assertTrue(all("input" not in run for run in tool_runs))
        self.assertTrue(any(run.get("inputHash") for run in tool_runs))
        full_runs = response.resultJson.get("toolRuns") or []
        self.assertTrue(any((run.get("input") or {}).get("taskType") == "market_scan" for run in full_runs))


if __name__ == "__main__":
    unittest.main()
