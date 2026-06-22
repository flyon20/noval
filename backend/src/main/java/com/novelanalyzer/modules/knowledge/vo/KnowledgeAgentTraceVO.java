package com.novelanalyzer.modules.knowledge.vo;

import java.time.LocalDateTime;

public class KnowledgeAgentTraceVO {
    private Long id;
    private String traceId;
    private Long userId;
    private Long projectId;
    private String conversationId;
    private String question;
    private String status;
    private String taskGraph;
    private String toolRuns;
    private String evidencePack;
    private String perspectiveResults;
    private String resultJson;
    private String intentDecision;
    private String contextUsed;
    private String memoryUsed;
    private String sourcePolicy;
    private String supervisorDecision;
    private String memoryCandidates;
    private String snapshotTime;
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

    public String getTaskGraph() {
        return taskGraph;
    }

    public void setTaskGraph(String taskGraph) {
        this.taskGraph = taskGraph;
    }

    public String getToolRuns() {
        return toolRuns;
    }

    public void setToolRuns(String toolRuns) {
        this.toolRuns = toolRuns;
    }

    public String getEvidencePack() {
        return evidencePack;
    }

    public void setEvidencePack(String evidencePack) {
        this.evidencePack = evidencePack;
    }

    public String getPerspectiveResults() {
        return perspectiveResults;
    }

    public void setPerspectiveResults(String perspectiveResults) {
        this.perspectiveResults = perspectiveResults;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getContextUsed() {
        return contextUsed;
    }

    public String getIntentDecision() {
        return intentDecision;
    }

    public void setIntentDecision(String intentDecision) {
        this.intentDecision = intentDecision;
    }

    public void setContextUsed(String contextUsed) {
        this.contextUsed = contextUsed;
    }

    public String getMemoryUsed() {
        return memoryUsed;
    }

    public void setMemoryUsed(String memoryUsed) {
        this.memoryUsed = memoryUsed;
    }

    public String getSourcePolicy() {
        return sourcePolicy;
    }

    public void setSourcePolicy(String sourcePolicy) {
        this.sourcePolicy = sourcePolicy;
    }

    public String getSupervisorDecision() {
        return supervisorDecision;
    }

    public void setSupervisorDecision(String supervisorDecision) {
        this.supervisorDecision = supervisorDecision;
    }

    public String getMemoryCandidates() {
        return memoryCandidates;
    }

    public void setMemoryCandidates(String memoryCandidates) {
        this.memoryCandidates = memoryCandidates;
    }

    public String getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(String snapshotTime) {
        this.snapshotTime = snapshotTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
