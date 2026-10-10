package com.novelanalyzer.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeConfigValidator {

    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeConfigValidator(KnowledgeProperties knowledgeProperties) {
        this.knowledgeProperties = knowledgeProperties;
    }

    @PostConstruct
    public void validate() {
        if (knowledgeProperties.getQdrant() == null) {
            throw new IllegalStateException("Qdrant config must be configured");
        }
        requireText(knowledgeProperties.getQdrant().getBaseUrl(), "Qdrant base URL must be configured");
        requireText(knowledgeProperties.getQdrant().getCollection(), "Qdrant collection must be configured");
        requireText(knowledgeProperties.getQdrant().getMemoryCollection(), "Qdrant memory collection must be configured");

        if (knowledgeProperties.getEmbedding() == null) {
            throw new IllegalStateException("embedding config must be configured");
        }
        requireText(knowledgeProperties.getEmbedding().getProvider(), "embedding provider must be configured");
        requireText(knowledgeProperties.getEmbedding().getBaseUrl(), "embedding base URL must be configured");
        requireText(knowledgeProperties.getEmbedding().getModel(), "embedding model must be configured");
        if (knowledgeProperties.getEmbedding().getDimension() <= 0) {
            throw new IllegalStateException("embedding dimension must be positive");
        }

        if (knowledgeProperties.getIndex() == null) {
            throw new IllegalStateException("knowledge index config must be configured");
        }
        if (knowledgeProperties.getIndex().getMaxChapters() <= 0) {
            throw new IllegalStateException("max indexed chapters must be positive");
        }
        if (knowledgeProperties.getIndex().getMaxActiveJobs() <= 0) {
            throw new IllegalStateException("active indexing jobs must be positive");
        }

        KnowledgeProperties.ResourcePolicy resourcePolicy = knowledgeProperties.getResourcePolicy();
        if (resourcePolicy == null) {
            throw new IllegalStateException("resource policy must be configured");
        }
        if (resourcePolicy.getMaxActiveDeepRuns() <= 0
            || resourcePolicy.getMaxActiveFastRuns() <= 0
            || resourcePolicy.getMaxActiveLlmCalls() <= 0
            || resourcePolicy.getMaxDelegatedAgentConcurrency() <= 0
            || resourcePolicy.getMaxIndexConcurrency() <= 0
            || resourcePolicy.getMaxCrawlerConcurrency() <= 0) {
            throw new IllegalStateException("resource concurrency limits must be positive");
        }
        if (resourcePolicy.getMemoryPausePercent() <= 0
            || resourcePolicy.getMemoryPausePercent() >= resourcePolicy.getMemoryRejectDeepPercent()
            || resourcePolicy.getMemoryRejectDeepPercent() > 100) {
            throw new IllegalStateException("memory thresholds must satisfy 0 < pause < reject <= 100");
        }
        if (resourcePolicy.getDiskWarnPercent() <= 0
            || resourcePolicy.getDiskWarnPercent() >= resourcePolicy.getDiskStopImportPercent()
            || resourcePolicy.getDiskStopImportPercent() > 100) {
            throw new IllegalStateException("disk thresholds must satisfy 0 < warn < stop <= 100");
        }
        if (resourcePolicy.getQueueBacklogWarnCount() <= 0
            || resourcePolicy.getQueueOldestWarnMinutes() <= 0) {
            throw new IllegalStateException("queue thresholds must be positive");
        }
        if (knowledgeProperties.getChatRun() == null
            || knowledgeProperties.getChatRun().getWorkerConcurrency() <= 0) {
            throw new IllegalStateException("chat run worker concurrency must be positive");
        }
        if (knowledgeProperties.getChatRun().getHeartbeatSeconds() <= 0
            || knowledgeProperties.getChatRun().getLeaseSeconds()
                <= knowledgeProperties.getChatRun().getHeartbeatSeconds()) {
            throw new IllegalStateException("chat run lease must be greater than heartbeat interval");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
