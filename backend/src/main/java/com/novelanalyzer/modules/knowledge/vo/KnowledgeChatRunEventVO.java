package com.novelanalyzer.modules.knowledge.vo;

import java.time.LocalDateTime;

public class KnowledgeChatRunEventVO {

    private Long eventId;
    private String runId;
    private Long sequenceNo;
    private String eventType;
    private String eventIdempotencyKey;
    private String payload;
    private LocalDateTime createdAt;

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public Long getSequence() {
        return sequenceNo;
    }

    public void setSequence(Long sequence) {
        this.sequenceNo = sequence;
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

    public String getIdempotencyKey() {
        return eventIdempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.eventIdempotencyKey = idempotencyKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
