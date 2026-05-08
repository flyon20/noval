package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class QdrantClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldEnsureCollectionWithConfiguredVectorDimension() throws Exception {
        String baseUrl = startServer();
        QdrantClient client = new QdrantClient(HttpClient.newHttpClient(), objectMapper, knowledgeProperties(baseUrl));

        client.ensureCollection();

        CapturedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.path()).isEqualTo("/collections/novel_knowledge_chunks");
        Map<String, Object> body = objectMapper.readValue(request.body(), new TypeReference<>() {});
        Map<String, Object> vectors = objectMapper.convertValue(body.get("vectors"), new TypeReference<>() {});
        assertThat(vectors).containsEntry("size", 3);
        assertThat(vectors).containsEntry("distance", "Cosine");
    }

    @Test
    void shouldTreatExistingCollectionAsSuccess() throws Exception {
        String baseUrl = startServerWithStatus(409, "{\"status\":\"error\",\"result\":\"already exists\"}");
        QdrantClient client = new QdrantClient(HttpClient.newHttpClient(), objectMapper, knowledgeProperties(baseUrl));

        assertThatNoException().isThrownBy(client::ensureCollection);
    }

    @Test
    void shouldUpsertPointWithVectorAndPayloadMetadata() throws Exception {
        String baseUrl = startServer();
        QdrantClient client = new QdrantClient(HttpClient.newHttpClient(), objectMapper, knowledgeProperties(baseUrl));

        client.upsertPoint("1", List.of(0.1, 0.2, 0.3), Map.of(
            "bookId", 101,
            "sourceType", "CHAPTER",
            "chapterNo", 1
        ));

        CapturedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.path()).isEqualTo("/collections/novel_knowledge_chunks/points");
        Map<String, Object> body = objectMapper.readValue(request.body(), new TypeReference<>() {});
        List<Map<String, Object>> points = objectMapper.convertValue(body.get("points"), new TypeReference<>() {});
        assertThat(points).hasSize(1);
        assertThat(points.get(0).get("id")).isEqualTo(1);
        assertThat(points.get(0).get("vector")).isEqualTo(List.of(0.1, 0.2, 0.3));
        Map<String, Object> payload = objectMapper.convertValue(points.get(0).get("payload"), new TypeReference<>() {});
        assertThat(payload).containsEntry("bookId", 101).containsEntry("sourceType", "CHAPTER");
    }

    @Test
    void shouldSearchWithMetadataFiltersAndReturnPointIds() throws Exception {
        String baseUrl = startServer("""
            {
              "result": [
                {"id": "point-1", "score": 0.91, "payload": {"bookId": 101}}
              ]
            }
            """);
        QdrantClient client = new QdrantClient(HttpClient.newHttpClient(), objectMapper, knowledgeProperties(baseUrl));

        List<QdrantClient.SearchResult> results = client.search(List.of(0.1, 0.2, 0.3), Map.of("bookId", 101), 5);

        assertThat(results).extracting(QdrantClient.SearchResult::id).containsExactly("point-1");
        CapturedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/collections/novel_knowledge_chunks/points/search");
        Map<String, Object> body = objectMapper.readValue(request.body(), new TypeReference<>() {});
        assertThat(body).containsEntry("limit", 5);
        assertThat(body.get("vector")).isEqualTo(List.of(0.1, 0.2, 0.3));
        Map<String, Object> filter = objectMapper.convertValue(body.get("filter"), new TypeReference<>() {});
        assertThat(filter.toString()).contains("bookId").contains("101");
    }

    private String startServer() throws IOException {
        return startServer("{\"result\":{\"status\":\"ok\"}}");
    }

    private String startServer(String responseBody) throws IOException {
        return startServerWithStatus(200, responseBody);
    }

    private String startServerWithStatus(int statusCode, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(capture(exchange));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private CapturedRequest capture(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body);
    }

    private KnowledgeProperties knowledgeProperties(String baseUrl) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getQdrant().setBaseUrl(baseUrl);
        properties.getQdrant().setCollection("novel_knowledge_chunks");
        properties.getEmbedding().setDimension(3);
        return properties;
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
