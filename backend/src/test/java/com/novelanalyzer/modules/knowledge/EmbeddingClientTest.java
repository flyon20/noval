package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.service.KnowledgeEmbeddingRuntimeResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private CapturedRequest capturedRequest;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendOpenAiCompatibleEmbeddingRequestWithApiKeyOnlyInHeader() throws Exception {
        String baseUrl = startServer("""
            {
              "data": [
                {"embedding": [0.1, 0.2, 0.3]}
              ]
            }
            """);
        KnowledgeProperties properties = knowledgeProperties(baseUrl);
        EmbeddingClient client = new EmbeddingClient(
            HttpClient.newHttpClient(),
            objectMapper,
            new KnowledgeEmbeddingRuntimeResolver(properties, supplier("unused-key"))
        );

        List<Double> embedding = client.embed("opening rhythm");

        assertThat(embedding).containsExactly(0.1, 0.2, 0.3);
        assertThat(capturedRequest.method()).isEqualTo("POST");
        assertThat(capturedRequest.path()).isEqualTo("/v1/embeddings");
        assertThat(capturedRequest.authorization()).isEqualTo("Bearer test-embedding-key");
        Map<String, Object> body = objectMapper.readValue(capturedRequest.body(), new TypeReference<>() {});
        assertThat(body).containsEntry("model", "BAAI/bge-m3");
        assertThat(body.get("input")).isEqualTo(List.of("opening rhythm"));
        assertThat(body).containsEntry("dimensions", 3);
        assertThat(body).containsEntry("encoding_format", "float");
        assertThat(capturedRequest.body()).doesNotContain("test-embedding-key");
    }

    @Test
    void shouldFallbackToDashscopeApiKeyWhenKnowledgeEmbeddingApiKeyIsBlank() throws Exception {
        String baseUrl = startServer("""
            {
              "data": [
                {"embedding": [0.4, 0.5, 0.6]}
              ]
            }
            """);
        KnowledgeProperties properties = knowledgeProperties(baseUrl);
        properties.getEmbedding().setApiKey("   ");
        properties.getEmbedding().setApiKeyRef("DASHSCOPE_API_KEY");
        EmbeddingClient client = new EmbeddingClient(
            HttpClient.newHttpClient(),
            objectMapper,
            new KnowledgeEmbeddingRuntimeResolver(properties, supplier("dashscope-fallback-key"))
        );

        List<Double> embedding = client.embed("hero goal");

        assertThat(embedding).containsExactly(0.4, 0.5, 0.6);
        assertThat(capturedRequest.authorization()).isEqualTo("Bearer dashscope-fallback-key");
        assertThat(capturedRequest.body()).doesNotContain("dashscope-fallback-key");
    }

    @Test
    void shouldSendDashscopeMultimodalEmbeddingRequestForVisionFlashModel() throws Exception {
        String baseUrl = startServer("""
            {
              "output": {
                "embeddings": [
                  {"embedding": [0.7, 0.8, 0.9]}
                ]
              }
            }
            """);
        KnowledgeProperties properties = knowledgeProperties(baseUrl);
        properties.getEmbedding().setProvider("dashscope-multimodal");
        properties.getEmbedding().setModel("tongyi-embedding-vision-flash-2026-03-06");
        EmbeddingClient client = new EmbeddingClient(
            HttpClient.newHttpClient(),
            objectMapper,
            new KnowledgeEmbeddingRuntimeResolver(properties, supplier("unused-key"))
        );

        List<Double> embedding = client.embed("通用多模态表征模型示例");

        assertThat(embedding).containsExactly(0.7, 0.8, 0.9);
        assertThat(capturedRequest.method()).isEqualTo("POST");
        assertThat(capturedRequest.path()).isEqualTo("/v1/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding");
        assertThat(capturedRequest.authorization()).isEqualTo("Bearer test-embedding-key");
        Map<String, Object> body = objectMapper.readValue(capturedRequest.body(), new TypeReference<>() {});
        assertThat(body).containsEntry("model", "tongyi-embedding-vision-flash-2026-03-06");
        assertThat(body).containsEntry("parameters", Map.of("dimension", 3));
        assertThat(body.get("input")).isEqualTo(Map.of("contents", List.of(Map.of("text", "通用多模态表征模型示例"))));
    }

    private String startServer(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            capturedRequest = capture(exchange);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding", exchange -> {
            capturedRequest = capture(exchange);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private CapturedRequest capture(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), authorization, body);
    }

    private KnowledgeProperties knowledgeProperties(String baseUrl) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getEmbedding().setBaseUrl(baseUrl);
        properties.getEmbedding().setApiKey("test-embedding-key");
        properties.getEmbedding().setModel("BAAI/bge-m3");
        properties.getEmbedding().setDimension(3);
        return properties;
    }

    private java.util.function.Function<String, String> supplier(String value) {
        return key -> value;
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
