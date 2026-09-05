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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

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
        return embedAll(Collections.singletonList(text)).get(0);
    }

    public List<List<Double>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "embedding text is required");
        }
        try {
            KnowledgeEmbeddingRuntimeResolver.RuntimeEmbeddingConfig embedding = runtimeResolver.resolve();
            if ("dashscope-multimodal".equalsIgnoreCase(embedding.provider())) {
                return embedDashscopeMultimodal(texts, embedding);
            }
            return embedOpenAiCompatible(texts, embedding);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("embedding call failed: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding call failed");
        }
    }

    private List<List<Double>> embedOpenAiCompatible(List<String> texts,
                                                     KnowledgeEmbeddingRuntimeResolver.RuntimeEmbeddingConfig embedding) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
            "model", embedding.model(),
            "input", texts,
            "dimensions", embedding.dimension(),
            "encoding_format", "float"
        );
        HttpResponse<String> response = postJson(trimTrailingSlash(embedding.baseUrl()) + "/embeddings", embedding.apiKey(), body);
        ensureSuccess(response.statusCode(), response.body(), "embedding call failed");
        Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> data = objectMapper.convertValue(payload.get("data"), new TypeReference<>() {});
        if (data == null || data.size() != texts.size()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding response is empty");
        }
        List<IndexedEmbedding> indexed = new ArrayList<>(data.size());
        for (int position = 0; position < data.size(); position++) {
            Map<String, Object> item = data.get(position);
            Object rawIndex = item.get("index");
            int index = rawIndex instanceof Number number ? number.intValue() : position;
            indexed.add(new IndexedEmbedding(
                index,
                objectMapper.convertValue(item.get("embedding"), new TypeReference<List<Double>>() {})
            ));
        }
        indexed.sort(Comparator.comparingInt(IndexedEmbedding::index));
        if (indexed.stream().anyMatch(item -> item.index() < 0 || item.index() >= texts.size()
            || item.vector() == null || item.vector().isEmpty())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding response is invalid");
        }
        return indexed.stream().map(IndexedEmbedding::vector).toList();
    }

    private List<List<Double>> embedDashscopeMultimodal(List<String> texts,
                                                        KnowledgeEmbeddingRuntimeResolver.RuntimeEmbeddingConfig embedding) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
            "model", embedding.model(),
            "input", Map.of("contents", texts.stream().map(text -> Map.of("text", text)).toList()),
            "parameters", Map.of("dimension", embedding.dimension())
        );
        String url = trimTrailingSlash(embedding.baseUrl()) + "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
        HttpResponse<String> response = postJson(url, embedding.apiKey(), body);
        ensureSuccess(response.statusCode(), response.body(), "embedding call failed");
        Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
        Map<String, Object> output = objectMapper.convertValue(payload.get("output"), new TypeReference<>() {});
        List<Map<String, Object>> embeddings = output == null
            ? null
            : objectMapper.convertValue(output.get("embeddings"), new TypeReference<>() {});
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding response is empty");
        }
        List<List<Double>> vectors = new ArrayList<>(embeddings.size());
        for (Map<String, Object> item : embeddings) {
            List<Double> vector = objectMapper.convertValue(item.get("embedding"), new TypeReference<List<Double>>() {});
            if (vector == null || vector.isEmpty()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding response is invalid");
            }
            vectors.add(vector);
        }
        return List.copyOf(vectors);
    }

    private HttpResponse<String> postJson(String url, String apiKey, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        return sendWithRetry(request);
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

    private record IndexedEmbedding(int index, List<Double> vector) {
    }
}
