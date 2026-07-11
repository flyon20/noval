package com.novelanalyzer.modules.knowledge.dto;

import java.util.ArrayList;
import java.util.List;

public class AgentTelemetryRequest {

    private String traceId;
    private List<CacheEvent> cacheEvents = new ArrayList<>();
    private List<TokenMetric> tokenMetrics = new ArrayList<>();

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<CacheEvent> getCacheEvents() {
        return cacheEvents;
    }

    public void setCacheEvents(List<CacheEvent> cacheEvents) {
        this.cacheEvents = cacheEvents == null ? new ArrayList<>() : new ArrayList<>(cacheEvents);
    }

    public List<TokenMetric> getTokenMetrics() {
        return tokenMetrics;
    }

    public void setTokenMetrics(List<TokenMetric> tokenMetrics) {
        this.tokenMetrics = tokenMetrics == null ? new ArrayList<>() : new ArrayList<>(tokenMetrics);
    }

    public static class CacheEvent {
        private String cacheScope;
        private String nodeName;
        private String expertName;
        private String cacheKeyHash;
        private String cacheStatus;
        private String promptPrefixHash;
        private Boolean promptPrefixStable;
        private Integer durationMs;

        public String getCacheScope() {
            return cacheScope;
        }

        public void setCacheScope(String cacheScope) {
            this.cacheScope = cacheScope;
        }

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }

        public String getExpertName() {
            return expertName;
        }

        public void setExpertName(String expertName) {
            this.expertName = expertName;
        }

        public String getCacheKeyHash() {
            return cacheKeyHash;
        }

        public void setCacheKeyHash(String cacheKeyHash) {
            this.cacheKeyHash = cacheKeyHash;
        }

        public String getCacheStatus() {
            return cacheStatus;
        }

        public void setCacheStatus(String cacheStatus) {
            this.cacheStatus = cacheStatus;
        }

        public String getPromptPrefixHash() {
            return promptPrefixHash;
        }

        public void setPromptPrefixHash(String promptPrefixHash) {
            this.promptPrefixHash = promptPrefixHash;
        }

        public Boolean getPromptPrefixStable() {
            return promptPrefixStable;
        }

        public void setPromptPrefixStable(Boolean promptPrefixStable) {
            this.promptPrefixStable = promptPrefixStable;
        }

        public Integer getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(Integer durationMs) {
            this.durationMs = durationMs;
        }
    }

    public static class TokenMetric {
        private String nodeName;
        private String expertName;
        private String modelName;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer tokenCount;

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }

        public String getExpertName() {
            return expertName;
        }

        public void setExpertName(String expertName) {
            this.expertName = expertName;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public Integer getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(Integer promptTokens) {
            this.promptTokens = promptTokens;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(Integer completionTokens) {
            this.completionTokens = completionTokens;
        }

        public Integer getTokenCount() {
            return tokenCount;
        }

        public void setTokenCount(Integer tokenCount) {
            this.tokenCount = tokenCount;
        }
    }
}
