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
    void shouldRejectBlankQdrantMemoryCollection() {
        KnowledgeProperties properties = validProperties();
        properties.getQdrant().setMemoryCollection(" ");

        KnowledgeConfigValidator validator = new KnowledgeConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Qdrant memory collection");
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
    void shouldRejectNonPositiveResourceConcurrency() {
        KnowledgeProperties properties = validProperties();
        properties.getResourcePolicy().setMaxActiveLlmCalls(0);

        assertThatThrownBy(() -> new KnowledgeConfigValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("resource concurrency");
    }

    @Test
    void shouldRejectInvalidMemoryThresholdOrder() {
        KnowledgeProperties properties = validProperties();
        properties.getResourcePolicy().setMemoryPausePercent(92);
        properties.getResourcePolicy().setMemoryRejectDeepPercent(92);

        assertThatThrownBy(() -> new KnowledgeConfigValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory thresholds");
    }

    @Test
    void shouldRejectInvalidDiskThresholdOrder() {
        KnowledgeProperties properties = validProperties();
        properties.getResourcePolicy().setDiskWarnPercent(91);
        properties.getResourcePolicy().setDiskStopImportPercent(90);

        assertThatThrownBy(() -> new KnowledgeConfigValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disk thresholds");
    }

    @Test
    void shouldRejectNonPositiveQueueThresholds() {
        KnowledgeProperties properties = validProperties();
        properties.getResourcePolicy().setQueueBacklogWarnCount(0);

        assertThatThrownBy(() -> new KnowledgeConfigValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("queue thresholds");
    }

    @Test
    void shouldRejectNonPositiveChatRunWorkerConcurrency() {
        KnowledgeProperties properties = validProperties();
        properties.getChatRun().setWorkerConcurrency(0);

        assertThatThrownBy(() -> new KnowledgeConfigValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("chat run worker concurrency");
    }

    @Test
    void shouldRejectChatRunLeaseNotGreaterThanHeartbeat() {
        KnowledgeProperties properties = validProperties();
        properties.getChatRun().setHeartbeatSeconds(10);
        properties.getChatRun().setLeaseSeconds(10);

        assertThatThrownBy(() -> new KnowledgeConfigValidator(properties).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lease");
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
        properties.getQdrant().setMemoryCollection("noval_ai_memory");
        properties.getEmbedding().setProvider("siliconflow");
        properties.getEmbedding().setModel("BAAI/bge-m3");
        properties.getEmbedding().setDimension(1024);
        properties.getIndex().setMaxChapters(10);
        properties.getIndex().setMaxActiveJobs(1);
        return properties;
    }
}
