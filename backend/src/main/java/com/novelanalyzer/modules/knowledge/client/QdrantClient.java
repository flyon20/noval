package com.novelanalyzer.modules.knowledge.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class QdrantClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(QdrantClient.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeProperties knowledgeProperties;

    public QdrantClient(HttpClient httpClient,
                        ObjectMapper objectMapper,
                        KnowledgeProperties knowledgeProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.knowledgeProperties = knowledgeProperties;
    }

    public void ensureCollection() {
        Map<String, Object> request = Map.of(
            "vectors", Map.of(
                "size", knowledgeProperties.getEmbedding().getDimension(),
                "distance", "Cosine"
            )
        );
        send("PUT", collectionPath(), request, "qdrant collection ensure failed");
    }

    public void upsertPoint(String pointId, List<Double> vector, Map<String, Object> payload) {
        LOGGER.info("qdrant upsert request: pointId={}, vectorSize={}, payload={}",
            pointId,
            vector == null ? 0 : vector.size(),
            payload);
        Map<String, Object> point = Map.of(
            "id", normalizePointId(pointId),
            "vector", vector,
            "payload", payload == null ? Map.of() : payload
        );
        send("PUT", collectionPath() + "/points", Map.of("points", List.of(point)), "qdrant point upsert failed");
    }

    public List<SearchResult> search(List<Double> vector, Map<String, Object> filters, int limit) {
        try {
            Map<String, Object> request = Map.of(
                "vector", vector,
                "filter", buildFilter(filters),
                "limit", Math.max(1, limit),
                "with_payload", true
            );
            HttpResponse<String> response = send(
                "POST",
                collectionPath() + "/points/search",
                request,
                "qdrant search failed"
            );
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Map<String, Object>> results = objectMapper.convertValue(payload.get("result"), new TypeReference<>() {});
            if (results == null) {
                return List.of();
            }
            return results.stream()
                .map(item -> new SearchResult(
                    String.valueOf(item.get("id")),
                    parseDouble(item.get("score")),
                    objectMapper.convertValue(item.get("payload"), new TypeReference<Map<String, Object>>() {})
                ))
                .toList();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("qdrant search failed: {}", ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "qdrant search failed");
        }
    }

    private HttpResponse<String> send(String method, String path, Map<String, Object> body, String errorMessage) {
        try {
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body == null ? Map.of() : body),
                StandardCharsets.UTF_8
            );
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(knowledgeProperties.getQdrant().getBaseUrl()) + path))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if ("PUT".equals(method) && collectionPath().equals(path) && response.statusCode() == 409) {
                return response;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("{} returned status {}: {}", errorMessage, response.statusCode(), abbreviate(response.body()));
                throw new BusinessException(ResultCode.INTERNAL_ERROR, errorMessage);
            }
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("{}: {}", errorMessage, ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, errorMessage);
        }
    }

    private Map<String, Object> buildFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> must = filters.entrySet().stream()
            .map(entry -> Map.of(
                "key", entry.getKey(),
                "match", Map.of("value", entry.getValue())
            ))
            .toList();
        return Map.of("must", must);
    }

    private String collectionPath() {
        return "/collections/" + knowledgeProperties.getQdrant().getCollection();
    }

    private double parseDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Object normalizePointId(String pointId) {
        if (pointId != null && pointId.matches("\\d+")) {
            try {
                return Long.parseLong(pointId);
            } catch (NumberFormatException ignored) {
                return pointId;
            }
        }
        return pointId;
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    public record SearchResult(String id, double score, Map<String, Object> payload) {
    }
}
