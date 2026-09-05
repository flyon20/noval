package com.novelanalyzer.modules.knowledge.dto;

import java.util.List;

public class AgentExpertProfileUpdateRequest {

    private Boolean enabled;
    private Integer priority;
    private Integer maxTokens;
    private Integer maxToolCalls;
    private List<String> triggerIntents;
    private List<String> triggerTasks;
    private List<String> capabilityIds;
    private List<String> defaultSkillIds;
    private List<String> requestedToolCapabilities;
    private String outputContract;
    private String executionKind;
    private String promptVersion;
    private String evalSuiteId;
    private String category;
    private Double expectedQualityGain;
    private Double latencyCost;
    private Double tokenCost;
    private Double resourceCost;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(Integer maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public List<String> getTriggerIntents() {
        return triggerIntents;
    }

    public void setTriggerIntents(List<String> triggerIntents) {
        this.triggerIntents = triggerIntents;
    }

    public List<String> getTriggerTasks() {
        return triggerTasks;
    }

    public void setTriggerTasks(List<String> triggerTasks) {
        this.triggerTasks = triggerTasks;
    }

    public List<String> getCapabilityIds() {
        return capabilityIds;
    }

    public void setCapabilityIds(List<String> capabilityIds) {
        this.capabilityIds = capabilityIds;
    }

    public List<String> getDefaultSkillIds() {
        return defaultSkillIds;
    }

    public void setDefaultSkillIds(List<String> defaultSkillIds) {
        this.defaultSkillIds = defaultSkillIds;
    }

    public List<String> getRequestedToolCapabilities() {
        return requestedToolCapabilities;
    }

    public void setRequestedToolCapabilities(List<String> requestedToolCapabilities) {
        this.requestedToolCapabilities = requestedToolCapabilities;
    }

    public String getOutputContract() {
        return outputContract;
    }

    public void setOutputContract(String outputContract) {
        this.outputContract = outputContract;
    }

    public String getExecutionKind() {
        return executionKind;
    }

    public void setExecutionKind(String executionKind) {
        this.executionKind = executionKind;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getEvalSuiteId() {
        return evalSuiteId;
    }

    public void setEvalSuiteId(String evalSuiteId) {
        this.evalSuiteId = evalSuiteId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Double getExpectedQualityGain() {
        return expectedQualityGain;
    }

    public void setExpectedQualityGain(Double expectedQualityGain) {
        this.expectedQualityGain = expectedQualityGain;
    }

    public Double getLatencyCost() {
        return latencyCost;
    }

    public void setLatencyCost(Double latencyCost) {
        this.latencyCost = latencyCost;
    }

    public Double getTokenCost() {
        return tokenCost;
    }

    public void setTokenCost(Double tokenCost) {
        this.tokenCost = tokenCost;
    }

    public Double getResourceCost() {
        return resourceCost;
    }

    public void setResourceCost(Double resourceCost) {
        this.resourceCost = resourceCost;
    }
}
