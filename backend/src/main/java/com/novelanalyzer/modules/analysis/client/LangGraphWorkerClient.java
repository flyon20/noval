package com.novelanalyzer.modules.analysis.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component
public class LangGraphWorkerClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(LangGraphWorkerClient.class);
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Service-Token";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final SystemConfigService systemConfigService;

    public LangGraphWorkerClient(HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 AiProperties aiProperties,
                                 SystemConfigService systemConfigService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.systemConfigService = systemConfigService;
    }

    public AiInvokeResult run(Map<String, Object> requestPayload) {
        try {
            HttpRequest request = buildRequest("/internal/analysis/run", requestPayload);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            return toAiInvokeResult(payload);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("langgraph worker blocking call failed: {}", ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "langgraph worker call failed");
        }
    }

    public AiInvokeResult stream(Map<String, Object> requestPayload,
                                 Consumer<String> onDelta,
                                 BooleanSupplier cancelledSupplier) {
        try {
            HttpRequest request = buildRequest("/internal/analysis/run/stream", requestPayload);
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            ensureSuccess(response.statusCode(), "");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                StringBuilder accumulatedContent = new StringBuilder();
                Map<String, Object> runtimeMetrics = new LinkedHashMap<>();
                String currentEvent = null;
                StringBuilder currentData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelledSupplier != null && cancelledSupplier.getAsBoolean()) {
                        return null;
                    }
                    if (line.isBlank()) {
                        AiInvokeResult done = processEvent(
                            currentEvent,
                            currentData.toString(),
                            accumulatedContent,
                            runtimeMetrics,
                            onDelta
                        );
                        if (done != null) {
                            if ((done.getContent() == null || done.getContent().isBlank()) && !accumulatedContent.isEmpty()) {
                                done.setContent(accumulatedContent.toString());
                            }
                            return done;
                        }
                        currentEvent = null;
                        currentData.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        currentEvent = line.substring("event:".length()).trim();
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (!currentData.isEmpty()) {
                            currentData.append('\n');
                        }
                        currentData.append(line.substring("data:".length()).trim());
                    }
                }
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "langgraph worker stream ended without result");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("langgraph worker streaming call failed: {}", ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "langgraph worker stream failed");
        }
    }

    private AiInvokeResult processEvent(String eventName,
                                        String rawData,
                                        StringBuilder accumulatedContent,
                                        Map<String, Object> runtimeMetrics,
                                        Consumer<String> onDelta) throws Exception {
        if (rawData == null || rawData.isBlank()) {
            return null;
        }
        Map<String, Object> payload = objectMapper.readValue(rawData, new TypeReference<Map<String, Object>>() {});
        String effectiveEvent = firstNonBlank(asString(payload.get("event")), eventName);
        if ("start".equalsIgnoreCase(effectiveEvent)) {
            return null;
        }
        if ("progress".equalsIgnoreCase(effectiveEvent)) {
            String message = firstNonBlank(asString(payload.get("message")), asString(payload.get("delta")));
            if (message != null && !message.isBlank()) {
                accumulatedContent.append(message);
                if (onDelta != null) {
                    onDelta.accept(message);
                }
            }
            return null;
        }
        if ("delta".equalsIgnoreCase(effectiveEvent)) {
            String delta = asString(payload.get("delta"));
            if (delta != null && !delta.isBlank()) {
                accumulatedContent.append(delta);
                if (onDelta != null) {
                    onDelta.accept(delta);
                }
            }
            return null;
        }
        if ("metrics".equalsIgnoreCase(effectiveEvent)) {
            runtimeMetrics.clear();
            runtimeMetrics.putAll(convertMap(payload.get("metrics")));
            return null;
        }
        if ("error".equalsIgnoreCase(effectiveEvent)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, firstNonBlank(asString(payload.get("message")), "langgraph worker returned error"));
        }
        if ("done".equalsIgnoreCase(effectiveEvent)) {
            Object data = payload.get("data");
            Map<String, Object> result = data == null
                ? payload
                : objectMapper.convertValue(data, new TypeReference<Map<String, Object>>() {});
            mergeRuntimeMetrics(result, runtimeMetrics);
            return toAiInvokeResult(result);
        }
        return null;
    }

    private HttpRequest buildRequest(String path, Map<String, Object> requestPayload) throws Exception {
        String body = objectMapper.writeValueAsString(requestPayload == null ? Map.of() : requestPayload);
        return HttpRequest.newBuilder()
            .uri(URI.create(resolveBaseUrl() + path))
            .timeout(Duration.ofMillis(resolveTimeoutMillis(requestPayload)))
            .header("Content-Type", "application/json")
            .header(INTERNAL_API_KEY_HEADER, resolveInternalApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    private AiInvokeResult toAiInvokeResult(Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        Map<String, Object> resultJson = convertMap(safePayload.get("resultJson"));
        String modelName = firstNonBlank(asString(safePayload.get("modelName")), "langgraph-worker");
        String content = firstNonBlank(
            asString(safePayload.get("content")),
            asString(safePayload.get("resultContent")),
            asString(resultJson.get("detailContent")),
            asString(resultJson.get("summary"))
        );
        int tokenUsed = parseInteger(safePayload.get("tokenUsed"), 0);
        return AiInvokeResult.of(modelName, content == null ? "" : content, tokenUsed, resultJson);
    }

    private Map<String, Object> convertMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private void mergeRuntimeMetrics(Map<String, Object> payload, Map<String, Object> runtimeMetrics) {
        if (payload == null || runtimeMetrics == null || runtimeMetrics.isEmpty()) {
            return;
        }
        Map<String, Object> resultJson = convertMap(payload.get("resultJson"));
        Map<String, Object> meta = convertMap(resultJson.get("meta"));
        Map<String, Object> runtime = convertMap(meta.get("runtime"));
        runtime.putAll(runtimeMetrics);
        meta.put("runtime", runtime);
        resultJson.put("meta", meta);
        payload.put("resultJson", resultJson);
    }

    private void ensureSuccess(int statusCode, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR,
            firstNonBlank(extractErrorMessage(body), body, "langgraph worker returned HTTP " + statusCode));
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            return firstNonBlank(asString(payload.get("message")), asString(payload.get("detail")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveBaseUrl() {
        return firstNonBlank(
            systemConfigService.getValueOrDefault("ai.langgraph-worker.base-url", null),
            aiProperties.getLanggraphWorker().getBaseUrl()
        );
    }

    private String resolveInternalApiKey() {
        String value = firstNonBlank(
            systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null),
            aiProperties.getLanggraphWorker().getInternalApiKey()
        );
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "langgraph worker internal api key not configured");
        }
        return value;
    }

    private int resolveTimeoutMillis(Map<String, Object> requestPayload) {
        int configuredTimeout = systemConfigService.getIntValueOrDefault(
            "ai.langgraph-worker.timeout-millis",
            aiProperties.getLanggraphWorker().getTimeoutMillis()
        );
        int requestedTimeout = extractRequestedTimeoutMillis(requestPayload);
        if (requestedTimeout <= 0) {
            return configuredTimeout;
        }
        return Math.max(configuredTimeout, requestedTimeout + 10000);
    }

    @SuppressWarnings("unchecked")
    private int extractRequestedTimeoutMillis(Map<String, Object> requestPayload) {
        if (requestPayload == null) {
            return 0;
        }
        Object limitsValue = requestPayload.get("limits");
        if (!(limitsValue instanceof Map<?, ?> limits)) {
            return 0;
        }
        Object timeoutValue = limits.get("timeoutMillis");
        return parseInteger(timeoutValue, 0);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int parseInteger(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
