package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeEmbeddingRuntimeResolver;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEmbeddingRuntimeResolverTest {

    @Test
    void shouldPreferExplicitKnowledgeEmbeddingApiKey() {
        KnowledgeProperties properties = baseProperties();
        properties.getEmbedding().setApiKey("explicit-embedding-key");
        properties.getEmbedding().setApiKeyRef("DASHSCOPE_API_KEY");

        KnowledgeEmbeddingRuntimeResolver resolver = new KnowledgeEmbeddingRuntimeResolver(properties, env("ref-key"));

        var runtime = resolver.resolve();

        assertThat(runtime.provider()).isEqualTo("dashscope");
        assertThat(runtime.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(runtime.model()).isEqualTo("text-embedding-v4");
        assertThat(runtime.dimension()).isEqualTo(1024);
        assertThat(runtime.apiKey()).isEqualTo("explicit-embedding-key");
    }

    @Test
    void shouldFallbackToApiKeyRefWhenExplicitKeyIsBlank() {
        KnowledgeProperties properties = baseProperties();
        properties.getEmbedding().setApiKey("   ");
        properties.getEmbedding().setApiKeyRef("DEEPSEEK_API_KEY");

        KnowledgeEmbeddingRuntimeResolver resolver = new KnowledgeEmbeddingRuntimeResolver(properties, env("deepseek-fallback-key"));

        var runtime = resolver.resolve();

        assertThat(runtime.apiKey()).isEqualTo("deepseek-fallback-key");
    }

    private KnowledgeProperties baseProperties() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getEmbedding().setProvider("dashscope");
        properties.getEmbedding().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getEmbedding().setModel("text-embedding-v4");
        properties.getEmbedding().setDimension(1024);
        return properties;
    }

    private Function<String, String> env(String value) {
        return key -> value;
    }
}
