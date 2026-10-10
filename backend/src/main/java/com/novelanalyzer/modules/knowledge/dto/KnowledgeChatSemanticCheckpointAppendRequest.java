package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public class KnowledgeChatSemanticCheckpointAppendRequest {

    @NotBlank
    @Size(max = 64)
    private String runId;

    @NotNull
    @Positive
    private Long userId;

    @NotBlank
    @Size(max = 20)
    private String eventType;

    @NotBlank
    @Size(max = 200)
    private String eventIdempotencyKey;

    private Map<String, Object> payload = new LinkedHashMap<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventIdempotencyKey() {
        return eventIdempotencyKey;
    }

    public void setEventIdempotencyKey(String eventIdempotencyKey) {
        this.eventIdempotencyKey = eventIdempotencyKey;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }
}
