from __future__ import annotations

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import httpx

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.api import knowledge as knowledge_api
from app.models.knowledge import KnowledgeChatRequest
from app.models.knowledge import KnowledgeChatResponse
from app.services.evaluation.golden import GoldenEvalCase
from app.services.harness.provider_dispatch_scope import ProviderDispatch, current_provider_dispatch_scope


class KnowledgeApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = "langgraph-test-key-123456"

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def test_should_reject_knowledge_chat_without_internal_token(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/internal/knowledge/chat",
                json={"question": "星河旧梦有什么卖点？", "bookName": "星河旧梦"},
            )

        self.assertEqual(401, response.status_code)

    def test_should_return_knowledge_chat_response(self) -> None:
        payload = {
            "question": "星河旧梦有什么卖点？",
            "bookId": 101,
            "bookName": "星河旧梦",
            "projectId": 91,
            "workId": 911,
        }

        with patch(
            "app.api.knowledge.research_agent.run",
            AsyncMock(return_value=KnowledgeChatResponse(
                status="answered",
                answer="开篇卖点来自星门线索。[1]",
                candidates=[],
                sources=[],
                actions=[],
                resultJson={"status": "answered"},
            )),
        ) as run_mock:
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/chat",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json=payload,
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual("answered", response.json()["status"])
        self.assertEqual("星河旧梦", run_mock.await_args.args[0].bookName)
        self.assertEqual(91, run_mock.await_args.args[0].projectId)
        self.assertEqual(911, run_mock.await_args.args[0].workId)

    def test_should_return_runtime_skills_for_admin_dashboard(self) -> None:
        with TestClient(app) as client:
            response = client.get(
                "/internal/knowledge/runtime-skills",
                headers={"X-Internal-Service-Token": settings.internal_api_key},
            )

        self.assertEqual(200, response.status_code)
        skill_ids = [item["skillId"] for item in response.json()]
        self.assertIn("webnovel-market-scan", skill_ids)
        self.assertTrue(all("intents" in item for item in response.json()))
        self.assertTrue(all(item["status"] == "ACTIVE" for item in response.json()))
        self.assertTrue(all(len(item["contentHash"]) == 64 for item in response.json()))
        self.assertTrue(all(isinstance(item["requestedCapabilities"], list) for item in response.json()))
        self.assertTrue(all(isinstance(item["description"], str) for item in response.json()))
        self.assertTrue(all(isinstance(item["inputSchema"], dict) for item in response.json()))
        self.assertTrue(all(isinstance(item["outputSchema"], dict) for item in response.json()))

    def test_should_probe_saved_provider_profile_without_returning_content_or_secret(self) -> None:
        route = {
            "profileKey": "gateway-primary",
            "profileVersion": "version-1",
            "endpoint": "https://api.deepseek.com/v1",
            "model": "deepseek-chat",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "providerCapabilities": {
                "schemaVersion": 1,
                "supportsStreaming": True,
                "supportsTools": True,
                "supportsJsonObject": True,
                "supportsReasoning": True,
                "reportsUsage": True,
                "reportsCacheUsage": True,
            },
            "enabled": True,
            "isDefault": True,
        }
        dispatch = ProviderDispatch(
            profile_key="gateway-primary",
            profile_version="version-1",
            endpoint="https://api.deepseek.com/v1",
            model="deepseek-chat",
            provider_type="openai-compatible",
            protocol="responses",
            api_key="provider-secret-must-not-leak",
            provider_capabilities=route["providerCapabilities"],
        )
        provider_profile = SimpleNamespace(
            profile_key="gateway-primary",
            profile_version="version-1",
            model="deepseek-chat",
            protocol="responses",
        )

        with patch.object(
            knowledge_api.research_agent.knowledge_client,
            "get_agent_runtime_config",
            AsyncMock(return_value={"providerProfiles": [route]}),
        ), patch.object(
            knowledge_api.research_agent.knowledge_client,
            "resolve_provider_dispatch",
            AsyncMock(return_value=dispatch),
        ) as resolve_mock, patch.object(
            knowledge_api.research_agent.provider_client,
            "resolve_provider_profile",
            Mock(return_value=provider_profile),
        ), patch.object(
            knowledge_api.research_agent.provider_client,
            "invoke",
            AsyncMock(return_value={
                "model_name": "deepseek-chat",
                "content": "generated provider content must not leak",
                "wire_api": "responses",
                "token_used": 9,
                "usage": {
                    "totalTokens": 9,
                    "usageReported": True,
                    "cacheUsageReported": True,
                },
            }),
        ) as invoke_mock:
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/agent/provider-probe",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={"profileKey": "gateway-primary", "profileVersion": "version-1"},
                )

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("SUCCEEDED", body["status"])
        self.assertEqual("gateway-primary", body["profileKey"])
        self.assertEqual("version-1", body["profileVersion"])
        self.assertEqual("deepseek-chat", body["model"])
        self.assertEqual("responses", body["protocol"])
        self.assertTrue(body["usageReported"])
        self.assertTrue(body["cacheUsageReported"])
        self.assertNotIn("content", body)
        self.assertNotIn("apiKey", body)
        self.assertNotIn("generated provider content", response.text)
        self.assertNotIn("provider-secret-must-not-leak", response.text)
        resolve_mock.assert_awaited_once_with("gateway-primary", "version-1")
        invoke_mock.assert_awaited_once()
        self.assertIsNone(current_provider_dispatch_scope())

    def test_should_sanitize_provider_probe_failure_and_forbid_inline_route_fields(self) -> None:
        route = {
            "profileKey": "gateway-primary",
            "profileVersion": "version-1",
            "endpoint": "https://api.deepseek.com/v1",
            "model": "deepseek-chat",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "enabled": True,
        }
        dispatch = ProviderDispatch(
            profile_key="gateway-primary",
            profile_version="version-1",
            endpoint="https://api.deepseek.com/v1",
            model="deepseek-chat",
            provider_type="openai-compatible",
            protocol="responses",
            api_key="provider-secret-must-not-leak",
        )
        provider_error = httpx.HTTPStatusError(
            "upstream rejected provider-secret-must-not-leak",
            request=httpx.Request("POST", "https://api.deepseek.com/v1/responses"),
            response=httpx.Response(401, text="raw upstream error provider-secret-must-not-leak"),
        )

        with patch.object(
            knowledge_api.research_agent.knowledge_client,
            "get_agent_runtime_config",
            AsyncMock(return_value={"providerProfiles": [route]}),
        ), patch.object(
            knowledge_api.research_agent.knowledge_client,
            "resolve_provider_dispatch",
            AsyncMock(return_value=dispatch),
        ), patch.object(
            knowledge_api.research_agent.provider_client,
            "resolve_provider_profile",
            Mock(return_value=SimpleNamespace(
                profile_key="gateway-primary",
                profile_version="version-1",
                model="deepseek-chat",
                protocol="responses",
            )),
        ), patch.object(
            knowledge_api.research_agent.provider_client,
            "invoke",
            AsyncMock(side_effect=provider_error),
        ):
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/agent/provider-probe",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={"profileKey": "gateway-primary", "profileVersion": "version-1"},
                )
                inline_response = client.post(
                    "/internal/knowledge/agent/provider-probe",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "profileKey": "gateway-primary",
                        "profileVersion": "version-1",
                        "apiKey": "inline-secret",
                        "endpoint": "https://other.example/v1",
                    },
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual("FAILED", response.json()["status"])
        self.assertEqual("AUTHENTICATION_FAILED", response.json()["errorCode"])
        self.assertNotIn("provider-secret-must-not-leak", response.text)
        self.assertNotIn("raw upstream error", response.text)
        self.assertEqual(422, inline_response.status_code)
        self.assertIsNone(current_provider_dispatch_scope())

    def test_should_reject_invalid_provider_probe_result(self) -> None:
        route = {
            "profileKey": "gateway-primary",
            "profileVersion": "version-1",
            "endpoint": "https://api.deepseek.com/v1",
            "model": "deepseek-chat",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "enabled": True,
        }
        dispatch = ProviderDispatch(
            profile_key="gateway-primary",
            profile_version="version-1",
            endpoint="https://api.deepseek.com/v1",
            model="deepseek-chat",
            provider_type="openai-compatible",
            protocol="responses",
            api_key="provider-secret-must-not-leak",
        )

        with patch.object(
            knowledge_api.research_agent.knowledge_client,
            "get_agent_runtime_config",
            AsyncMock(return_value={"providerProfiles": [route]}),
        ), patch.object(
            knowledge_api.research_agent.knowledge_client,
            "resolve_provider_dispatch",
            AsyncMock(return_value=dispatch),
        ), patch.object(
            knowledge_api.research_agent.provider_client,
            "resolve_provider_profile",
            Mock(return_value=SimpleNamespace(
                profile_key="gateway-primary",
                profile_version="version-1",
                model="deepseek-chat",
                protocol="responses",
            )),
        ), patch.object(
            knowledge_api.research_agent.provider_client,
            "invoke",
            AsyncMock(side_effect=[
                {
                    "model_name": "deepseek-chat",
                    "content": "",
                    "wire_api": "responses",
                    "usage": {},
                },
                {
                    "model_name": "deepseek-chat",
                    "content": {"unexpected": "shape"},
                    "wire_api": "responses",
                    "usage": {},
                },
            ]),
        ):
            with TestClient(app) as client:
                responses = [
                    client.post(
                        "/internal/knowledge/agent/provider-probe",
                        headers={"X-Internal-Service-Token": settings.internal_api_key},
                        json={"profileKey": "gateway-primary", "profileVersion": "version-1"},
                    )
                    for _ in range(2)
                ]

        for response in responses:
            self.assertEqual(200, response.status_code)
            self.assertEqual("FAILED", response.json()["status"])
            self.assertEqual("RESPONSE_INVALID", response.json()["errorCode"])
            self.assertNotIn("provider-secret-must-not-leak", response.text)
        self.assertIsNone(current_provider_dispatch_scope())

    def test_should_bound_entire_provider_probe_and_reject_insecure_endpoint(self) -> None:
        async def slow_runtime_config():
            await asyncio.sleep(0.05)
            return {"providerProfiles": []}

        resolve_mock = AsyncMock()
        with patch.object(knowledge_api, "_PROVIDER_PROBE_DEADLINE_SECONDS", 0.001), patch.object(
            knowledge_api.research_agent.knowledge_client,
            "get_agent_runtime_config",
            AsyncMock(side_effect=slow_runtime_config),
        ), patch.object(
            knowledge_api.research_agent.knowledge_client,
            "resolve_provider_dispatch",
            resolve_mock,
        ):
            with TestClient(app) as client:
                timeout_response = client.post(
                    "/internal/knowledge/agent/provider-probe",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={"profileKey": "gateway-primary", "profileVersion": "version-1"},
                )

        self.assertEqual("FAILED", timeout_response.json()["status"])
        self.assertEqual("PROVIDER_UNAVAILABLE", timeout_response.json()["errorCode"])
        resolve_mock.assert_not_awaited()

        insecure_route = {
            "profileKey": "gateway-primary",
            "profileVersion": "version-1",
            "endpoint": "http://gateway.example/v1",
            "model": "deepseek-chat",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "enabled": True,
        }
        resolve_mock = AsyncMock()
        with patch.object(
            knowledge_api.research_agent.knowledge_client,
            "get_agent_runtime_config",
            AsyncMock(return_value={"providerProfiles": [insecure_route]}),
        ), patch.object(
            knowledge_api.research_agent.knowledge_client,
            "resolve_provider_dispatch",
            resolve_mock,
        ):
            with TestClient(app) as client:
                insecure_response = client.post(
                    "/internal/knowledge/agent/provider-probe",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={"profileKey": "gateway-primary", "profileVersion": "version-1"},
                )

        self.assertEqual("FAILED", insecure_response.json()["status"])
        self.assertEqual("PROFILE_INSECURE_ENDPOINT", insecure_response.json()["errorCode"])
        resolve_mock.assert_not_awaited()
        self.assertIsNone(current_provider_dispatch_scope())

    def test_should_accept_admin_eval_run_and_schedule_suite_execution(self) -> None:
        case = GoldenEvalCase(
            case_id="market-001",
            question="What is trending?",
            request_payload={"question": "What is trending?"},
        )
        repository = SimpleNamespace(
            list_active_cases=unittest.mock.Mock(return_value=[case]),
            create_run=unittest.mock.Mock(return_value=42),
        )

        async def fake_run_suite(cases, **kwargs):
            self.assertEqual([case], cases)
            self.assertEqual("agent-runtime", kwargs["suite_name"])
            self.assertEqual(repository, kwargs["repository"])
            self.assertEqual(42, kwargs["persisted_run_id"])

        with patch("app.api.knowledge.MySqlGoldenEvalRepository", return_value=repository), \
            patch("app.api.knowledge.GoldenEvalRunner") as runner_class:
            runner_class.return_value.run_suite = AsyncMock(side_effect=fake_run_suite)
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/eval-runs",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "suiteName": "agent-runtime",
                        "runKey": "agent-runtime:manual-001",
                        "runnerName": "admin-trigger",
                        "evaluatorName": "rule-based",
                        "modelName": "deepseek-chat",
                        "caseLimit": 10,
                    },
                )

        self.assertEqual(202, response.status_code)
        self.assertEqual(42, response.json()["runId"])
        self.assertEqual("RUNNING", response.json()["status"])
        repository.list_active_cases.assert_called_once_with("agent-runtime", limit=10)
        repository.create_run.assert_called_once()
        runner_class.return_value.run_suite.assert_awaited_once()

    def test_should_reject_unsupported_admin_eval_evaluator(self) -> None:
        with patch("app.api.knowledge.MySqlGoldenEvalRepository") as repository_class:
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/eval-runs",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "suiteName": "agent-runtime",
                        "evaluatorName": "llm-judge",
                        "caseLimit": 10,
                    },
                )

        self.assertEqual(400, response.status_code)
        self.assertIn("Unsupported evaluatorName", response.json()["detail"])
        repository_class.assert_not_called()

    def test_should_execute_synchronous_backend_eval_run_with_progress_fields(self) -> None:
        case = GoldenEvalCase(
            case_id="market-001",
            question="What is trending?",
            request_payload={"question": "What is trending?"},
        )
        repository = SimpleNamespace(
            list_active_cases=unittest.mock.Mock(return_value=[case]),
            create_run=unittest.mock.Mock(return_value=99),
            update_run_progress=unittest.mock.Mock(),
        )

        async def fake_run_suite(cases, **kwargs):
            self.assertEqual([case], cases)
            self.assertEqual(77, kwargs["persisted_run_id"])
            return {
                "runId": 77,
                "status": "passed",
                "totalCases": 1,
                "passedCases": 1,
                "failedCases": 0,
            }

        with patch("app.api.knowledge.MySqlGoldenEvalRepository", return_value=repository), \
            patch("app.api.knowledge.GoldenEvalRunner") as runner_class:
            runner_class.return_value.run_suite = AsyncMock(side_effect=fake_run_suite)
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/eval-runs",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={
                        "runId": 77,
                        "suiteName": "agent-runtime",
                        "runKey": "agent-runtime:queued-77",
                        "runnerName": "rabbit-consumer",
                        "evaluatorName": "rule-based",
                        "modelName": "deepseek-chat",
                        "caseLimit": 10,
                        "synchronous": True,
                        "cancelKey": "ai:agent:eval:cancel:77",
                        "progressKey": "ai:agent:eval:progress:77",
                    },
                )

        self.assertEqual(200, response.status_code)
        self.assertEqual(77, response.json()["runId"])
        self.assertEqual("PASSED", response.json()["status"])
        self.assertEqual(1, response.json()["progressCurrent"])
        self.assertEqual(1, response.json()["progressTotal"])
        self.assertFalse(response.json()["queued"])
        repository.create_run.assert_not_called()
        repository.update_run_progress.assert_called()
        runner_class.return_value.run_suite.assert_awaited_once()

    def test_should_stream_knowledge_chat_events(self) -> None:
        payload = {"question": "Book Alpha setting?", "bookId": 101, "bookName": "Book Alpha"}

        async def fake_stream(_request):
            yield {"event": "start", "phase": "langgraph"}
            yield {"event": "delta", "delta": "Setting "}
            yield {"event": "delta", "delta": "answer[1]"}
            yield {
                "event": "done",
                "data": KnowledgeChatResponse(
                    status="answered",
                    answer="Setting answer[1]",
                    candidates=[],
                    sources=[],
                    actions=[],
                    resultJson={"status": "answered", "source": "rag"},
                ).model_dump(),
            }

        with patch("app.api.knowledge.research_agent.stream", fake_stream):
            with TestClient(app) as client:
                response = client.post(
                    "/internal/knowledge/chat/stream",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json=payload,
                )

        self.assertEqual(200, response.status_code)
        self.assertIn("text/event-stream", response.headers["content-type"])
        self.assertIn("event: start", response.text)
        self.assertIn("event: delta", response.text)
        self.assertIn("Setting answer[1]", response.text)
        self.assertIn("event: done", response.text)

    def test_should_close_research_agent_client_on_app_shutdown(self) -> None:
        with patch("app.api.knowledge.research_agent.aclose", AsyncMock()) as close_mock:
            with TestClient(app):
                pass

        close_mock.assert_awaited_once()

    def _upstream_rejection(self) -> httpx.HTTPStatusError:
        request = httpx.Request("POST", "https://provider.example/v1/responses")
        return httpx.HTTPStatusError(
            "upstream rejected",
            request=request,
            response=httpx.Response(400, request=request, json={
                "error": {
                    "message": "Unsupported value: 'minimal' is not supported.",
                    "type": "invalid_request_error",
                    "param": "reasoning.effort",
                    "code": "unsupported_value",
                }
            }),
        )

    def test_blocking_chat_failure_should_report_the_upstream_code_as_json(self) -> None:
        with patch(
            "app.api.knowledge.research_agent.run",
            AsyncMock(side_effect=self._upstream_rejection()),
        ):
            with TestClient(app, raise_server_exceptions=False) as client:
                response = client.post(
                    "/internal/knowledge/chat",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={"question": "上游 400"},
                )

        # 默认行为是 500 + text/plain "Internal Server Error"，调用方一个码位都拿不到。
        self.assertEqual(502, response.status_code)
        self.assertEqual(
            "knowledge chat failed: errorType=HTTPStatusError upstream=400 "
            "code=unsupported_value type=invalid_request_error param=reasoning.effort",
            response.json()["detail"],
        )
        self.assertNotIn("Unsupported value", response.text)

    def test_stream_failure_should_emit_a_terminal_error_event_with_the_code(self) -> None:
        error = self._upstream_rejection()

        async def failing_stream(_request):
            yield {"event": "start"}
            raise error

        with patch("app.api.knowledge.research_agent.stream", failing_stream):
            with TestClient(app, raise_server_exceptions=False) as client:
                response = client.post(
                    "/internal/knowledge/chat/stream",
                    headers={"X-Internal-Service-Token": settings.internal_api_key},
                    json={"question": "上游 400"},
                )

        # 响应头早就发出去了，只能靠终止事件带原因；没有它调用方只看到一条截断的流。
        self.assertEqual(200, response.status_code)
        self.assertIn("event: error", response.text)
        self.assertIn("upstream=400 code=unsupported_value", response.text)
        self.assertNotIn("Unsupported value", response.text)


class ProviderTiersApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_internal_api_key = settings.internal_api_key
        settings.internal_api_key = "langgraph-test-key-123456"

    def tearDown(self) -> None:
        settings.internal_api_key = self.original_internal_api_key

    def _resolve(self, models: list[dict]) -> dict:
        with TestClient(app) as client:
            response = client.post(
                "/internal/knowledge/provider-tiers",
                json={"models": models},
                headers={"X-Internal-Service-Token": settings.internal_api_key},
            )
        self.assertEqual(200, response.status_code)
        return {entry["modelKey"]: entry for entry in response.json()["models"]}

    def test_should_reject_provider_tiers_without_internal_token(self) -> None:
        with TestClient(app) as client:
            response = client.post("/internal/knowledge/provider-tiers", json={"models": []})

        self.assertEqual(401, response.status_code)

    def test_should_split_openai_tiers_by_model_name(self) -> None:
        resolved = self._resolve(
            [
                {"modelKey": "reasoning", "providerType": "openai", "modelName": "gpt-5"},
                {"modelKey": "chat", "providerType": "openai", "modelName": "gpt-4o"},
            ]
        )

        self.assertEqual(["minimal", "low", "medium", "high"], resolved["reasoning"]["reasoningTiers"])
        self.assertTrue(resolved["reasoning"]["supportsReasoning"])
        self.assertFalse(resolved["reasoning"]["acceptsTemperature"])

        self.assertEqual([], resolved["chat"]["reasoningTiers"])
        self.assertFalse(resolved["chat"]["supportsReasoning"])
        self.assertTrue(resolved["chat"]["acceptsTemperature"])

    def test_should_report_narrowed_tiers_for_kimi_and_glm(self) -> None:
        resolved = self._resolve(
            [
                {"modelKey": "kimi", "providerType": "moonshot", "modelName": "kimi-k2-thinking"},
                {"modelKey": "glm", "providerType": "zhipu", "modelName": "glm-4.6"},
            ]
        )

        for model_key in ("kimi", "glm"):
            self.assertEqual(["low", "high", "max"], resolved[model_key]["reasoningTiers"])
            self.assertTrue(resolved[model_key]["supportsReasoning"])

    def test_should_report_boolean_endpoints_for_qwen(self) -> None:
        resolved = self._resolve(
            [{"modelKey": "qwen", "providerType": "dashscope", "modelName": "qwen3-max"}]
        )

        self.assertEqual(["minimal", "high"], resolved["qwen"]["reasoningTiers"])

    def test_should_report_no_tiers_for_anthropic_and_unknown_vendors(self) -> None:
        resolved = self._resolve(
            [
                {"modelKey": "claude", "providerType": "anthropic", "modelName": "claude-sonnet-5"},
                {"modelKey": "mystery", "providerType": "openai-compatible", "modelName": "house-model-v1"},
            ]
        )

        for model_key in ("claude", "mystery"):
            self.assertEqual([], resolved[model_key]["reasoningTiers"])
            self.assertFalse(resolved[model_key]["supportsReasoning"])

    def test_should_resolve_family_from_model_name_when_provider_type_is_wire_only(self) -> None:
        resolved = self._resolve(
            [{"modelKey": "ds", "providerType": "openai-compatible", "modelName": "deepseek-chat"}]
        )

        self.assertEqual("deepseek", resolved["ds"]["family"])
        self.assertEqual(["minimal", "low", "medium", "high"], resolved["ds"]["reasoningTiers"])


class KnowledgeApiStreamLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_stream_body_close_closes_research_agent_generator_synchronously(self) -> None:
        closed = asyncio.Event()

        async def fake_stream(_request):
            try:
                yield {"event": "start", "phase": "langgraph"}
                await asyncio.Event().wait()
            finally:
                closed.set()

        with patch("app.api.knowledge.research_agent.stream", fake_stream):
            response = await knowledge_api.run_knowledge_chat_stream(
                KnowledgeChatRequest(question="test")
            )
            self.assertIn("event: start", await anext(response.body_iterator))
            await response.body_iterator.aclose()

        self.assertTrue(closed.is_set())


if __name__ == "__main__":
    unittest.main()
