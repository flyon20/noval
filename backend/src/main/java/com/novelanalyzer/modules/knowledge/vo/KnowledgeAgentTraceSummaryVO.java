package com.novelanalyzer.modules.knowledge.vo;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class KnowledgeAgentTraceSummaryVO {
    private Long id;
    private String traceId;
    private Long userId;
    private Long projectId;
    private String conversationId;
    private String question;
    private String status;
    private Map<String, String> healthSummary = new LinkedHashMap<>();
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, String> getHealthSummary() {
        return healthSummary;
    }

    public void setHealthSummary(Map<String, String> healthSummary) {
        this.healthSummary = healthSummary == null ? new LinkedHashMap<>() : new LinkedHashMap<>(healthSummary);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
