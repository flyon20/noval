package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public class ProjectMemoryRequest {

    @NotNull(message = "userId is required")
    private Long userId;
    private Map<String, String> memories;
    private String sourceTraceId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Map<String, String> getMemories() {
        return memories;
    }

    public void setMemories(Map<String, String> memories) {
        this.memories = memories;
    }

    public String getSourceTraceId() {
        return sourceTraceId;
    }

    public void setSourceTraceId(String sourceTraceId) {
        this.sourceTraceId = sourceTraceId;
    }
}
