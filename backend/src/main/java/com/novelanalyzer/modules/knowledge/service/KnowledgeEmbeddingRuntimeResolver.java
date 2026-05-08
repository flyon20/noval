package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Service
public class KnowledgeEmbeddingRuntimeResolver {

    private final KnowledgeProperties knowledgeProperties;
    private final Function<String, String> envResolver;

    @Autowired
    public KnowledgeEmbeddingRuntimeResolver(KnowledgeProperties knowledgeProperties) {
        this(knowledgeProperties, System::getenv);
    }

    public KnowledgeEmbeddingRuntimeResolver(KnowledgeProperties knowledgeProperties,
                                             Function<String, String> envResolver) {
        this.knowledgeProperties = knowledgeProperties;
        this.envResolver = envResolver;
    }

    public RuntimeEmbeddingConfig resolve() {
        KnowledgeProperties.Embedding embedding = knowledgeProperties.getEmbedding();
        String provider = normalizeText(embedding.getProvider());
        String baseUrl = normalizeText(embedding.getBaseUrl());
        String model = normalizeText(embedding.getModel());
        int dimension = embedding.getDimension();
        String apiKey = resolveApiKey(embedding);

        if (provider.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding provider is required");
        }
        if (baseUrl.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding base url is required");
        }
        if (model.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding model is required");
        }
        if (dimension <= 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding dimension must be positive");
        }
        if (apiKey.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "embedding api key is required");
        }

        return new RuntimeEmbeddingConfig(provider, baseUrl, model, dimension, apiKey);
    }

    private String resolveApiKey(KnowledgeProperties.Embedding embedding) {
        String configured = normalizeText(embedding.getApiKey());
        if (!configured.isEmpty()) {
            return configured;
        }
        String keyRef = normalizeText(embedding.getApiKeyRef());
        if (!keyRef.isEmpty()) {
            return normalizeText(envResolver.apply(keyRef));
        }
        return "";
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public record RuntimeEmbeddingConfig(
        String provider,
        String baseUrl,
        String model,
        int dimension,
        String apiKey
    ) {
    }
}
