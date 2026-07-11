package com.novelanalyzer.modules.analysis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.knowledge.dto.AgentEvalRunRequest;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalRunVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.RuntimeSkillVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangGraphWorkerClientTest {

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final LangGraphWorkerClient client = new LangGraphWorkerClient(
        mock(HttpClient.class),
        new ObjectMapper(),
        new AiProperties(),
        systemConfigService
    );

    @Test
    void shouldExtractReadableMessageFromWorkerJsonErrorBody() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            client,
            "ensureSuccess",
            502,
            "{\"detail\":\"AI provider connection failed, please retry\"}"
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("AI provider connection failed, please retry");
    }

    @Test
    void shouldUseRequestTimeoutWhenItIsLongerThanWorkerDefault() {
        when(systemConfigService.getIntValueOrDefault("ai.langgraph-worker.timeout-millis", 30000))
            .thenReturn(30000);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeoutMillis", 180000);
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("limits", limits);

        Integer timeoutMillis = ReflectionTestUtils.invokeMethod(client, "resolveTimeoutMillis", requestPayload);

        assertThat(timeoutMillis).isEqualTo(190000);
    }

    @Test
    void shouldBuildRequestWithTaskContractSourcePayloadAndExecutionSections() throws Exception {
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.base-url", null))
            .thenReturn("http://127.0.0.1:18001");
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null))
            .thenReturn("test-langgraph-key");
        when(systemConfigService.getIntValueOrDefault("ai.langgraph-worker.timeout-millis", 30000))
            .thenReturn(30000);

        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("task", Map.of(
            "taskId", "task-1",
            "traceId", "trace-1",
            "stream", true,
            "agentType", "deconstruct"
        ));
        requestPayload.put("contract", Map.of(
            "contractVersion", 1,
            "contractHash", "contract-abc",
            "analysisType", "deconstruct",
            "systemPrompt", "prompt"
        ));
        requestPayload.put("sourcePayload", Map.of(
            "platform", "fanqie",
            "bookId", 1001
        ));
        requestPayload.put("execution", Map.of(
            "timeoutMillis", 60000,
            "chunkParallelism", 2
        ));
        requestPayload.put("limits", Map.of(
            "timeoutMillis", 60000
        ));

        HttpRequest request = ReflectionTestUtils.invokeMethod(
            client,
            "buildRequest",
            "/internal/analysis/run",
            requestPayload
        );

        String body = request.bodyPublisher()
            .orElseThrow()
            .contentLength() > 0 ? "present" : "empty";

        assertThat(request.uri().toString()).isEqualTo("http://127.0.0.1:18001/internal/analysis/run");
        assertThat(request.headers().firstValue("X-Internal-Service-Token")).contains("test-langgraph-key");
        assertThat(body).isEqualTo("present");
    }

    @Test
    void shouldBuildRuntimeSkillsRequestWithInternalToken() throws Exception {
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.base-url", null))
            .thenReturn("http://127.0.0.1:18001");
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null))
            .thenReturn("test-langgraph-key");

        HttpRequest request = ReflectionTestUtils.invokeMethod(
            client,
            "buildGetRequest",
            "/internal/knowledge/runtime-skills"
        );

        assertThat(request.uri().toString()).isEqualTo("http://127.0.0.1:18001/internal/knowledge/runtime-skills");
        assertThat(request.headers().firstValue("X-Internal-Service-Token")).contains("test-langgraph-key");
    }

    @Test
    void shouldListRuntimeSkillsFromWorker() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.base-url", null))
            .thenReturn("http://127.0.0.1:18001");
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null))
            .thenReturn("test-langgraph-key");
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            [
              {
                "skillId": "webnovel-market-scan",
                "version": "1.0.0",
                "intents": ["market_scan"],
                "triggers": ["榜单", "趋势"]
              }
            ]
            """);
        when(httpClient.send(org.mockito.ArgumentMatchers.any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);
        LangGraphWorkerClient runtimeClient = new LangGraphWorkerClient(
            httpClient,
            new ObjectMapper(),
            new AiProperties(),
            systemConfigService
        );

        List<RuntimeSkillVO> skills = runtimeClient.listRuntimeSkills();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getSkillId()).isEqualTo("webnovel-market-scan");
        assertThat(skills.get(0).getIntents()).containsExactly("market_scan");
        assertThat(skills.get(0).getTriggers()).containsExactly("榜单", "趋势");
    }

    @Test
    void shouldStartAgentEvalRunOnWorkerWithAdminPayload() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.base-url", null))
            .thenReturn("http://127.0.0.1:18001");
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null))
            .thenReturn("test-langgraph-key");
        when(systemConfigService.getIntValueOrDefault("ai.langgraph-worker.timeout-millis", 30000))
            .thenReturn(30000);
        when(response.statusCode()).thenReturn(202);
        when(response.body()).thenReturn("""
            {
              "runId": 42,
              "runKey": "agent-runtime:manual-001",
              "suiteName": "agent-runtime",
              "runnerName": "admin-trigger",
              "evaluatorName": "rule-based",
              "modelName": "deepseek-chat",
              "status": "RUNNING",
              "totalCases": 10
            }
            """);
        when(httpClient.send(org.mockito.ArgumentMatchers.any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);
        LangGraphWorkerClient evalClient = new LangGraphWorkerClient(
            httpClient,
            new ObjectMapper(),
            new AiProperties(),
            systemConfigService
        );
        AgentEvalRunRequest request = new AgentEvalRunRequest();
        request.setSuiteName("agent-runtime");
        request.setRunKey("agent-runtime:manual-001");
        request.setRunnerName("admin-trigger");
        request.setEvaluatorName("rule-based");
        request.setModelName("deepseek-chat");
        request.setCaseLimit(10);

        AgentEvalRunVO run = evalClient.startKnowledgeEvalRun(request);

        assertThat(run.getId()).isEqualTo(42L);
        assertThat(run.getRunKey()).isEqualTo("agent-runtime:manual-001");
        assertThat(run.getSuiteName()).isEqualTo("agent-runtime");
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getTotalCases()).isEqualTo(10);
    }

    @Test
    void shouldPreserveRankSourceMetadataFromWorkerKnowledgeResponse() throws Exception {
        KnowledgeChatResponseVO response = new ObjectMapper().readValue("""
            {
              "status": "answered",
              "answer": "榜单第一是《测试书》。[1]",
              "candidates": [],
              "sources": [
                {
                  "sourceType": "RANK",
                  "bookName": "测试书",
                  "rankNo": 1,
                  "author": "测试作者",
                  "category": "都市脑洞",
                  "retrievalBackend": "rank_lookup",
                  "title": "男频新书榜 / 都市脑洞 #1"
                }
              ],
              "actions": [],
              "resultJson": {}
            }
            """, KnowledgeChatResponseVO.class);

        Map<String, Object> serialized = new ObjectMapper().convertValue(
            response.getSources().get(0),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        );

        assertThat(serialized).containsEntry("rankNo", 1);
        assertThat(serialized).containsEntry("author", "测试作者");
        assertThat(serialized).containsEntry("category", "都市脑洞");
        assertThat(serialized).containsEntry("retrievalBackend", "rank_lookup");
    }

    @Test
    void shouldPreferWorkerContentAndPreserveResultJsonMetadata() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("runtimeMode", "legacy-compatible-python");
        runtime.put("totalDurationMillis", 654L);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runtime", runtime);
        meta.put("chapterCount", Map.of("requested", 2, "actual", 2));
        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("analysisType", "deconstruct");
        resultJson.put("summary", "worker summary");
        resultJson.put("detailContent", "worker detail content");
        resultJson.put("meta", meta);
        resultJson.put("promptRuntime", Map.of("promptType", "deconstruct"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelName", "langgraph-worker:deepseek-chat");
        payload.put("content", "worker top-level content");
        payload.put("tokenUsed", 156);
        payload.put("resultJson", resultJson);

        Object invokeResult = ReflectionTestUtils.invokeMethod(client, "toAiInvokeResult", payload);

        assertThat(invokeResult).isNotNull();
        assertThat(ReflectionTestUtils.getField(invokeResult, "modelName")).isEqualTo("langgraph-worker:deepseek-chat");
        assertThat(ReflectionTestUtils.getField(invokeResult, "content")).isEqualTo("worker top-level content");
        assertThat(ReflectionTestUtils.getField(invokeResult, "tokenUsed")).isEqualTo(156);
        assertThat(ReflectionTestUtils.getField(invokeResult, "resultJson")).isEqualTo(resultJson);
    }

    @Test
    void shouldIgnoreProgressEventsWhenAccumulatingAnalysisStreamContent() {
        StringBuilder accumulatedContent = new StringBuilder("answer");
        List<String> deltas = new ArrayList<>();

        Object result = ReflectionTestUtils.invokeMethod(
            client,
            "processEvent",
            "progress",
            "{\"event\":\"progress\",\"message\":\"preparing\"}",
            accumulatedContent,
            new LinkedHashMap<String, Object>(),
            (Consumer<String>) deltas::add
        );

        assertThat(result).isNull();
        assertThat(accumulatedContent).hasToString("answer");
        assertThat(deltas).isEmpty();
    }
}
