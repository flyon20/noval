package com.novelanalyzer.modules.knowledge.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.service.KnowledgeEmbeddingRuntimeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_ATTEMPTS = 2;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeEmbeddingRuntimeResolver runtimeResolver;

    @Autowired
    public EmbeddingClient(HttpClient httpClient,
                           ObjectMapper objectMapper,
                           KnowledgeEmbeddingRuntimeResolver runtimeResolver) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.runtimeResolver = runtimeResolver;
    }

    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "embedding text is required");
        }
        try {
            KnowledgeEmbeddingRuntimeResolver.RuntimeEmbeddingConfig embedding = runtimeResolver.resolve();
            Map<String, Object> body = Map.of(
                "model", embedding.model(),
                "input", List.of(text),
                "dimensions", embedding.dimension(),
                "encoding_format", "float"
            );
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(embedding.baseUrl()) + "/embeddings"))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + embedding.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = sendWithRetry(request);
            ensureSuccess(response.statusCode(), response.body(), "embedding call failed");
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> data = objectMapper.convertValue(payload.get("data"), new TypeReference<>() {});
            if (data == null || data.isEmpty()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding response is empty");
            }
            return objectMapper.convertValue(data.get(0).get("embedding"), new TypeReference<List<Double>>() {});
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("embedding call failed: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding call failed");
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                lastException = ex;
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
            }
        }
        throw lastException == null ? new IOException("embedding request failed") : lastException;
    }

    private void ensureSuccess(int statusCode, String responseBody, String message) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        LOGGER.warn("embedding call returned status {}: {}", statusCode, abbreviate(responseBody));
        throw new BusinessException(ResultCode.INTERNAL_ERROR, message);
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
