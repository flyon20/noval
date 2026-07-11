from __future__ import annotations

import unittest

from app.models.agent_runtime import MemoryCandidate
from app.models.knowledge import KnowledgeChatRequest
from app.services.runtime.memory_extractor import MemoryExtractor


class MemoryWriter:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def create_memory_candidate(self, **kwargs) -> dict:
        self.calls.append(dict(kwargs))
        return {"id": len(self.calls)}


class PartiallyFailingMemoryWriter(MemoryWriter):
    async def create_memory_candidate(self, **kwargs) -> dict:
        self.calls.append(dict(kwargs))
        if len(self.calls) == 2:
            raise RuntimeError("write failed")
        return {"id": len(self.calls)}


class MemoryExtractorTest(unittest.IsolatedAsyncioTestCase):
    async def test_project_setting_becomes_project_candidate(self) -> None:
        request = KnowledgeChatRequest(
            question="project setting: the cheat system uses reality, mind, and mirror terminals",
            userId=7,
            projectId=900,
            conversationId="conv-1",
            traceId="trace-1",
        )

        candidates = MemoryExtractor().extract(request)

        self.assertEqual("project", candidates[0].scope)
        self.assertEqual("fact", candidates[0].type)
        self.assertIn("reality, mind, and mirror terminals", candidates[0].content)

    async def test_temporary_wording_becomes_thread_candidate(self) -> None:
        request = KnowledgeChatRequest(
            question="temporary preference: try a slower opening for this conversation",
            userId=7,
            conversationId="conv-2",
            traceId="trace-2",
        )

        candidates = MemoryExtractor().extract(request)

        self.assertEqual("thread", candidates[0].scope)
        self.assertEqual("preference", candidates[0].type)

    async def test_long_term_preference_becomes_user_candidate(self) -> None:
        request = KnowledgeChatRequest(
            question="long-term preference: I prefer urban fantasy and male-channel pacing",
            userId=7,
            projectId=900,
            conversationId="conv-3",
            traceId="trace-3",
        )

        candidates = MemoryExtractor().extract(request)

        self.assertEqual("user", candidates[0].scope)
        self.assertEqual("preference", candidates[0].type)
        self.assertIn("urban fantasy", candidates[0].content)

    async def test_persists_candidates_with_request_scope(self) -> None:
        writer = MemoryWriter()
        request = KnowledgeChatRequest(
            question="project setting: no harem, fast first three chapters",
            userId=7,
            projectId=900,
            conversationId="conv-4",
            traceId="trace-4",
        )
        extractor = MemoryExtractor()
        candidates = extractor.extract(request)

        result = await extractor.persist_candidates(writer, request, candidates)

        self.assertEqual(1, result["saved"])
        self.assertEqual(0, result["failed"])
        self.assertEqual({
            "user_id": 7,
            "project_id": 900,
            "conversation_id": "conv-4",
            "scope": "project",
            "memory_type": "fact",
            "content": "no harem, fast first three chapters",
            "summary": None,
            "confidence": candidates[0].confidence,
            "source_trace_id": "trace-4",
            "ttl_days": 30,
        }, writer.calls[0])

    async def test_reports_candidate_persistence_failures(self) -> None:
        writer = PartiallyFailingMemoryWriter()
        request = KnowledgeChatRequest(
            question="project setting: no harem",
            userId=7,
            projectId=900,
            conversationId="conv-5",
            traceId="trace-5",
        )
        extractor = MemoryExtractor()
        candidates = extractor.extract(request) + [
            MemoryCandidate(
                scope="user",
                type="preference",
                content="fast public feedback",
                confidence=0.8,
                sourceTraceId="trace-5",
            )
        ]

        result = await extractor.persist_candidates(writer, request, candidates)

        self.assertEqual(1, result["saved"])
        self.assertEqual(1, result["failed"])
        self.assertEqual("RuntimeError", result["failures"][0]["reason"])


if __name__ == "__main__":
    unittest.main()
