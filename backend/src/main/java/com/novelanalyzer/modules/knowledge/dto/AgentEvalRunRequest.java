package com.novelanalyzer.modules.knowledge.dto;

public class AgentEvalRunRequest {

    private Long runId;
    private String suiteName;
    private String runKey;
    private String runnerName;
    private String evaluatorName;
    private String modelName;
    private Integer caseLimit;
    private Boolean synchronous;
    private String cancelKey;
    private String progressKey;

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getSuiteName() {
        return suiteName;
    }

    public void setSuiteName(String suiteName) {
        this.suiteName = suiteName;
    }

    public String getRunKey() {
        return runKey;
    }

    public void setRunKey(String runKey) {
        this.runKey = runKey;
    }

    public String getRunnerName() {
        return runnerName;
    }

    public void setRunnerName(String runnerName) {
        this.runnerName = runnerName;
    }

    public String getEvaluatorName() {
        return evaluatorName;
    }

    public void setEvaluatorName(String evaluatorName) {
        this.evaluatorName = evaluatorName;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getCaseLimit() {
        return caseLimit;
    }

    public void setCaseLimit(Integer caseLimit) {
        this.caseLimit = caseLimit;
    }

    public Boolean getSynchronous() {
        return synchronous;
    }

    public void setSynchronous(Boolean synchronous) {
        this.synchronous = synchronous;
    }

    public String getCancelKey() {
        return cancelKey;
    }

    public void setCancelKey(String cancelKey) {
        this.cancelKey = cancelKey;
    }

    public String getProgressKey() {
        return progressKey;
    }

    public void setProgressKey(String progressKey) {
        this.progressKey = progressKey;
    }
}
