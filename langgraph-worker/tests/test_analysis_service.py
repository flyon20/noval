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


class StaticProviderClient:
    def __init__(self, content: str) -> None:
        self.content = content

    async def invoke(self, **kwargs):
        return {
            "model_name": kwargs["model"],
            "content": self.content,
            "token_used": 45,
        }

    async def stream(self, **kwargs):
        yield {"event": "delta", "delta": self.content}
        yield {"event": "done", "tokenUsed": 45}


class TimeoutCapturingProviderClient:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.stream_calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.invoke_calls.append(kwargs)
        return {
            "model_name": kwargs["model"],
            "content": '{"summary":"timeout summary","detailContent":"timeout detail"}',
            "token_used": 12,
        }

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        yield {"event": "delta", "delta": '{"summary":"stream timeout summary","detailContent":"stream timeout detail"}'}
        yield {"event": "done", "tokenUsed": 21}


class SequencedProviderClient:
    def __init__(self, contents: list[str]) -> None:
        self.contents = contents
        self.invoke_calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.invoke_calls.append(kwargs)
        content = self.contents[min(len(self.invoke_calls) - 1, len(self.contents) - 1)]
        return {
            "model_name": kwargs["model"],
            "content": content,
            "token_used": 20,
        }

    async def stream(self, **kwargs):
        raise AssertionError("stream should not be used in this test")


class StreamRepairProviderClient:
    def __init__(self, stream_content: str, repaired_content: str) -> None:
        self.stream_content = stream_content
        self.repaired_content = repaired_content
        self.invoke_calls: list[dict] = []
        self.stream_calls: list[dict] = []

    async def invoke(self, **kwargs):
        self.invoke_calls.append(kwargs)
        return {
            "model_name": kwargs["model"],
            "content": self.repaired_content,
            "token_used": 13,
        }

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        yield {"event": "delta", "delta": self.stream_content}
        yield {"event": "done", "tokenUsed": 11}


class AnalysisServiceTest(unittest.IsolatedAsyncioTestCase):
    async def test_should_not_force_json_for_single_book_analysis_with_legacy_output_schema(self) -> None:
        provider = TimeoutCapturingProviderClient()
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-0",
            agentType="deconstruct",
            promptConfig=PromptConfigPayload(
                promptType="deconstruct",
                promptContent="请分析这本小说的卖点与节奏。{{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
                outputExampleJson='{"summary":"example"}',
            ),
            sourcePayload={"inputText": "这是一段普通正文，不是 JSON。"},
            limits={},
        )

        response = await service.run(request)

        self.assertEqual("timeout summary", response.resultJson["summary"])
        self.assertFalse(provider.invoke_calls[0]["require_json"])
        self.assertNotIn("Return exactly one JSON object", provider.invoke_calls[0]["messages"][0]["content"])

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

    async def test_should_extract_json_from_fenced_block_with_wrapper_text(self) -> None:
        service = LangGraphAnalysisService(
            provider_client=StaticProviderClient(
                """分析完成，请使用下方 JSON：
```json
{"summary":"wrapped summary","detailContent":"wrapped detail","historicalWordCloud":[{"name":"urban-brain","value":12}]}
```
以上。"""
            )
        )
        request = RunRequest(
            taskId="task-4",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}],
            },
            limits={},
        )

        response = await service.run(request)

        self.assertEqual("wrapped summary", response.resultJson["summary"])
        self.assertEqual("wrapped detail", response.resultJson["detailContent"])
        self.assertEqual(
            [{"name": "urban-brain", "value": 12}],
            response.resultJson["historicalWordCloud"],
        )

    async def test_should_extract_json_object_embedded_in_plain_text(self) -> None:
        service = LangGraphAnalysisService(
            provider_client=StaticProviderClient(
                '前置说明 {"summary":"embedded summary","detailContent":"embedded detail","themeTable":[{"theme":"urban-brain","count":3}]} 后置说明'
            )
        )
        request = RunRequest(
            taskId="task-5",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}],
            },
            limits={},
        )

        response = await service.run(request)

        self.assertEqual("embedded summary", response.resultJson["summary"])
        self.assertEqual("embedded detail", response.resultJson["detailContent"])
        self.assertEqual(
            [{"theme": "urban-brain", "count": 3}],
            response.resultJson["themeTable"],
        )

    async def test_should_raise_when_required_json_output_is_unrecoverable(self) -> None:
        service = LangGraphAnalysisService(
            provider_client=StaticProviderClient('{"summary":"broken"')
        )
        request = RunRequest(
            taskId="task-6",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}],
            },
            limits={},
        )

        with self.assertRaisesRegex(ValueError, "valid JSON"):
            await service.run(request)

    async def test_should_repair_theme_json_when_first_response_is_not_valid_json(self) -> None:
        provider = SequencedProviderClient([
            "当前榜单主赛道集中在都市脑洞与系统微创新，代表热书是《脑洞之王》。",
            '{"summary":"都市脑洞继续领跑","boardSummary":"当前榜单围绕都市脑洞与系统变体集中","trendPreview":"主赛道仍是都市脑洞","comparisonSummary":"三次样本主线稳定","detailContent":"结构化修复后的趋势正文","historicalWordCloud":[],"themeDistribution":[],"themeTable":[],"hotBooks":[],"systemArchetypes":[],"microInnovationSignals":[],"insightCards":[{"label":"主赛道","value":"都市脑洞","note":"来自当前榜单"}],"snapshotComparisons":[]}',
        ])
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-repair",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptType="theme",
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
                outputExampleJson='{"summary":"example"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}, {"snapshotId": 3}],
            },
            limits={},
        )

        response = await service.run(request)

        self.assertEqual("都市脑洞继续领跑", response.resultJson["summary"])
        self.assertEqual(40, response.tokenUsed)
        self.assertEqual(2, len(provider.invoke_calls))
        self.assertEqual(2, response.resultJson["meta"]["runtime"]["providerCallCount"])
        self.assertTrue(provider.invoke_calls[1]["require_json"])
        self.assertIn("JSON repair task", provider.invoke_calls[1]["messages"][0]["content"])

    async def test_stream_should_repair_theme_json_when_stream_payload_is_not_valid_json(self) -> None:
        provider = StreamRepairProviderClient(
            "Current board still clusters around urban brain-hole and system twists.",
            '{"summary":"Urban brain-hole remains the lead lane","detailContent":"Repaired trend detail","historicalWordCloud":[],"themeDistribution":[],"themeTable":[],"hotBooks":[],"systemArchetypes":[],"microInnovationSignals":[],"insightCards":[],"snapshotComparisons":[]}',
        )
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-stream-repair",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptType="theme",
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
                outputExampleJson='{"summary":"example"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}, {"snapshotId": 3}],
            },
            limits={},
        )

        events = [event async for event in service.stream(request)]

        self.assertEqual("done", events[-1]["event"])
        self.assertEqual("Urban brain-hole remains the lead lane", events[-1]["data"]["resultJson"]["summary"])
        self.assertEqual(24, events[-1]["data"]["tokenUsed"])
        self.assertEqual(2, events[-1]["data"]["resultJson"]["meta"]["runtime"]["providerCallCount"])
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertIn("JSON repair task", provider.invoke_calls[0]["messages"][0]["content"])

    async def test_theme_guidance_should_constrain_output_size_for_json_reliability(self) -> None:
        provider = TimeoutCapturingProviderClient()
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-guidance",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptType="theme",
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
                outputExampleJson='{"summary":"example"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}, {"snapshotId": 3}],
            },
            limits={},
        )

        await service.run(request)

        prompt = provider.invoke_calls[0]["messages"][0]["content"]
        self.assertIn("Keep summary, boardSummary, trendPreview, and comparisonSummary concise", prompt)
        self.assertIn("Keep hotBooks, themeTable, themeDistribution, systemArchetypes, microInnovationSignals", prompt)

    async def test_should_not_salvage_nested_json_object_from_truncated_outer_json(self) -> None:
        service = LangGraphAnalysisService(
            provider_client=StaticProviderClient(
                '{"summary":"broken outer","historicalWordCloud":[{"name":"urban-brain","value":12}]'
            )
        )
        request = RunRequest(
            taskId="task-6b",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={
                "inputText": "trend content",
                "snapshots": [{"snapshotId": 1}, {"snapshotId": 2}],
            },
            limits={},
        )

        with self.assertRaisesRegex(ValueError, "valid JSON"):
            await service.run(request)

    async def test_should_forward_request_timeout_to_blocking_provider_call(self) -> None:
        provider = TimeoutCapturingProviderClient()
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-7",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={"inputText": "trend content"},
            limits={"timeoutMillis": 54321},
        )

        await service.run(request)

        self.assertEqual(54321, provider.invoke_calls[0]["timeout_millis"])

    async def test_should_forward_request_timeout_to_streaming_provider_call(self) -> None:
        provider = TimeoutCapturingProviderClient()
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-8",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="JSON ONLY {{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={"inputText": "trend content"},
            limits={"timeoutMillis": 65432},
        )

        events = [event async for event in service.stream(request)]

        self.assertEqual("done", events[-1]["event"])
        self.assertEqual(65432, provider.stream_calls[0]["timeout_millis"])

    async def test_should_force_json_contract_even_when_admin_prompt_mentions_markdown(self) -> None:
        provider = TimeoutCapturingProviderClient()
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-9",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="# Output Format\nUse Markdown report with tables.\n\n{{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
                outputExampleJson='{"summary":"example"}',
            ),
            sourcePayload={"inputText": "trend content"},
            limits={"timeoutMillis": 54321},
        )

        await service.run(request)

        system_prompt = provider.invoke_calls[0]["messages"][0]["content"]
        self.assertIn("Return exactly one JSON object", system_prompt)
        self.assertIn("Do not output markdown", system_prompt)
        self.assertIn("systemArchetypes", system_prompt)
        self.assertIn("都市脑洞-直播算命-惩恶扬善", system_prompt)


    async def test_should_require_deeper_trend_lane_and_system_constraints(self) -> None:
        provider = TimeoutCapturingProviderClient()
        service = LangGraphAnalysisService(provider_client=provider)
        request = RunRequest(
            taskId="task-10",
            agentType="trend_theme",
            promptConfig=PromptConfigPayload(
                promptContent="# Trend Prompt\n{{content}}",
                modelName="deepseek-chat",
                outputJsonSchema='{"type":"object"}',
            ),
            sourcePayload={"inputText": "trend content"},
            limits={},
        )

        await service.run(request)

        system_prompt = provider.invoke_calls[0]["messages"][0]["content"]
        self.assertIn("Never use broad labels such as", system_prompt)
        self.assertIn("3 or 4 Chinese segments joined by '-'", system_prompt)
        self.assertIn("systemPresence", system_prompt)
        self.assertIn("antiRoutineDesign", system_prompt)
        self.assertIn("avoidedPoisonPoints", system_prompt)


if __name__ == "__main__":
    unittest.main()
