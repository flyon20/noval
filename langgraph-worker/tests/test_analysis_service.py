from __future__ import annotations

import unittest

from app.models.analysis import PromptConfigPayload, RunRequest
from app.services.analysis_service import LangGraphAnalysisService


class FakeProviderClient:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.invoke_calls.append(kwargs)
        marker = "chunk" if "分段分析" in kwargs["messages"][0]["content"] else "final"
        return {
            "model_name": kwargs["model"],
            "content": '{"summary":"%s summary","detailContent":"%s detail"}' % (marker, marker),
            "token_used": 123,
        }

    async def stream(self, **kwargs):
        yield {"event": "delta", "delta": '{"summary":"stream summary","detailContent":"stream '}
        yield {"event": "delta", "delta": 'detail"}'}
        yield {"event": "done", "tokenUsed": 66}


class AnalysisServiceTest(unittest.IsolatedAsyncioTestCase):
    async def test_should_run_direct_graph_analysis(self) -> None:
        service = LangGraphAnalysisService(provider_client=FakeProviderClient())
        request = RunRequest(
            taskId="task-1",
            agentType="deconstruct",
            promptConfig=PromptConfigPayload(promptContent="JSON ONLY {{content}}", modelName="deepseek-chat"),
            sourcePayload={"inputText": "short content"},
            limits={},
        )

        response = await service.run(request)

        self.assertEqual("deepseek-chat", response.modelName)
        self.assertEqual("deconstruct", response.resultJson["analysisType"])
        self.assertIn("summary", response.resultJson)
        self.assertEqual("langgraph", response.resultJson["meta"]["runtime"]["runtimeMode"])
        self.assertEqual("deconstruct", response.resultJson["meta"]["runtime"]["agentType"])
        self.assertGreaterEqual(response.resultJson["meta"]["runtime"]["providerLatencyMillis"], 0)

    async def test_should_switch_to_chunk_mode_for_long_book_input(self) -> None:
        fake_client = FakeProviderClient()
        service = LangGraphAnalysisService(provider_client=fake_client)
        request = RunRequest(
            taskId="task-2",
            agentType="deconstruct",
            promptConfig=PromptConfigPayload(promptContent="JSON ONLY {{content}}", modelName="deepseek-chat"),
            sourcePayload={
                "book": {"bookName": "Book", "author": "Author", "intro": "Intro"},
                "chapters": [
                    {"chapterTitle": "c1", "content": "A" * 4000},
                    {"chapterTitle": "c2", "content": "B" * 4000},
                ],
                "inputText": "A" * 9000,
            },
            limits={"chunkMaxInputTokens": 1000, "chunkTargetInputTokens": 1000, "chunkParallelism": 2},
        )

        response = await service.run(request)

        self.assertEqual("chunk_merge", response.resultJson["analysisMode"])
        self.assertGreaterEqual(len(fake_client.invoke_calls), 3)
        self.assertTrue(response.resultJson["meta"]["runtime"]["useChunking"])
        self.assertGreaterEqual(response.resultJson["meta"]["runtime"]["chunkCount"], 2)

    async def test_should_emit_runtime_metrics_before_done_in_stream_mode(self) -> None:
        service = LangGraphAnalysisService(provider_client=FakeProviderClient())
        request = RunRequest(
            taskId="task-3",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(promptContent="JSON ONLY {{content}}", modelName="deepseek-chat"),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}],
            },
            limits={},
        )

        events = [event async for event in service.stream(request)]

        self.assertEqual("start", events[0]["event"])
        self.assertEqual("metrics", events[-2]["event"])
        self.assertEqual("done", events[-1]["event"])
        self.assertEqual("langgraph", events[-2]["metrics"]["runtimeMode"])
        self.assertEqual("theme", events[-1]["data"]["resultJson"]["analysisType"])
        self.assertEqual(
            events[-2]["metrics"]["providerLatencyMillis"],
            events[-1]["data"]["resultJson"]["meta"]["runtime"]["providerLatencyMillis"],
        )


if __name__ == "__main__":
    unittest.main()
