package com.novelanalyzer.modules.knowledge.vo;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentCacheTokenStatsVO {

    private long traceCount;
    private long cacheHits;
    private long cacheMisses;
    private long totalTokens;
    private double promptPrefixStableRate;
    private Map<String, Long> tokenByNode = new LinkedHashMap<>();
    private Map<String, Long> tokenByExpert = new LinkedHashMap<>();

    public long getTraceCount() {
        return traceCount;
    }

    public void setTraceCount(long traceCount) {
        this.traceCount = traceCount;
    }

    public long getCacheHits() {
        return cacheHits;
    }

    public void setCacheHits(long cacheHits) {
        this.cacheHits = cacheHits;
    }

    public long getCacheMisses() {
        return cacheMisses;
    }

    public void setCacheMisses(long cacheMisses) {
        this.cacheMisses = cacheMisses;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public double getPromptPrefixStableRate() {
        return promptPrefixStableRate;
    }

    public void setPromptPrefixStableRate(double promptPrefixStableRate) {
        this.promptPrefixStableRate = promptPrefixStableRate;
    }

    public Map<String, Long> getTokenByNode() {
        return tokenByNode;
    }

    public void setTokenByNode(Map<String, Long> tokenByNode) {
        this.tokenByNode = tokenByNode == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tokenByNode);
    }

    public Map<String, Long> getTokenByExpert() {
        return tokenByExpert;
    }

    public void setTokenByExpert(Map<String, Long> tokenByExpert) {
        this.tokenByExpert = tokenByExpert == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tokenByExpert);
    }
}
