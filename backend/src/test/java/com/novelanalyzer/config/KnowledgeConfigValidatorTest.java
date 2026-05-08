package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeConfigValidatorTest {

    @Test
    void shouldRejectBlankQdrantBaseUrl() {
        KnowledgeProperties properties = validProperties();
        properties.getQdrant().setBaseUrl(" ");

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Qdrant base URL");
    }

    @Test
    void shouldRejectBlankQdrantCollection() {
        KnowledgeProperties properties = validProperties();
        properties.getQdrant().setCollection(" ");

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Qdrant collection");
    }

    @Test
    void shouldRejectMissingEmbeddingRuntime() {
        KnowledgeProperties properties = validProperties();
        properties.getEmbedding().setProvider(" ");

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("embedding provider");
    }

    @Test
    void shouldRejectInvalidEmbeddingDimension() {
        KnowledgeProperties properties = validProperties();
        properties.getEmbedding().setDimension(0);

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("embedding dimension");
    }

    @Test
    void shouldRejectUnsafeIndexLimits() {
        KnowledgeProperties properties = validProperties();
        properties.getIndex().setMaxActiveJobs(0);

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active indexing jobs");
    }

    @Test
    void shouldAcceptProductionShapedKnowledgeConfig() {
        KnowledgeProperties properties = validProperties();

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatNoException().isThrownBy(validator::validate);
    }

    private KnowledgeProperties validProperties() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getQdrant().setBaseUrl("http://qdrant:6333");
        properties.getQdrant().setCollection("novel_knowledge_chunks");
        properties.getEmbedding().setProvider("siliconflow");
        properties.getEmbedding().setModel("BAAI/bge-m3");
        properties.getEmbedding().setDimension(1024);
        properties.getIndex().setMaxChapters(10);
        properties.getIndex().setMaxActiveJobs(1);
        return properties;
    }
}
