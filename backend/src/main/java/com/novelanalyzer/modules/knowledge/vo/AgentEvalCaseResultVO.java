package com.novelanalyzer.modules.knowledge.vo;

public class AgentEvalCaseResultVO {

    private Long id;
    private Long runId;
    private String caseKey;
    private String status;
    private String intent;
    private String answerMode;
    private String retrievalMetrics;
    private String faithfulnessJson;
    private String failures;
    private String traceId;
    private Integer durationMs;
    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getCaseKey() {
        return caseKey;
    }

    public void setCaseKey(String caseKey) {
        this.caseKey = caseKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getAnswerMode() {
        return answerMode;
    }

    public void setAnswerMode(String answerMode) {
        this.answerMode = answerMode;
    }

    public String getRetrievalMetrics() {
        return retrievalMetrics;
    }

    public void setRetrievalMetrics(String retrievalMetrics) {
        this.retrievalMetrics = retrievalMetrics;
    }

    public String getFaithfulnessJson() {
        return faithfulnessJson;
    }

    public void setFaithfulnessJson(String faithfulnessJson) {
        this.faithfulnessJson = faithfulnessJson;
    }

    public String getFailures() {
        return failures;
    }

    public void setFailures(String failures) {
        this.failures = failures;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
