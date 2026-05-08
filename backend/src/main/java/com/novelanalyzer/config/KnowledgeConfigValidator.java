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
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
