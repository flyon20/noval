package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class AgentExpertProfileVO {

    private String expertName;
    private String displayName;
    private Boolean enabled;
    private String defaultMode;
    private String costClass;
    private Integer maxTokens;
    private Integer maxToolCalls;
    private List<String> capabilityIds = new ArrayList<>();
    private List<String> defaultSkillIds = new ArrayList<>();
    private List<String> requestedToolCapabilities = new ArrayList<>();
    private String outputContract;
    private String executionKind;
    private List<String> triggerIntents = new ArrayList<>();
    private List<String> triggerTasks = new ArrayList<>();
    private Integer priority;
    private String promptVersion;
    private String evalSuiteId;
    private Boolean guardrail;
    private String category;
    private Double expectedQualityGain;
    private Boolean qualityGainVerified;
    private String qualityGainSource;
    private Long qualityGainEvalRunId;
    private Double latencyCost;
    private Double tokenCost;
    private Double resourceCost;

    public String getExpertName() {
        return expertName;
    }

    public void setExpertName(String expertName) {
        this.expertName = expertName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    public String getCostClass() {
        return costClass;
    }

    public void setCostClass(String costClass) {
        this.costClass = costClass;
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

    public List<String> getCapabilityIds() {
        return capabilityIds;
    }

    public void setCapabilityIds(List<String> capabilityIds) {
        this.capabilityIds = capabilityIds == null ? new ArrayList<>() : new ArrayList<>(capabilityIds);
    }

    public List<String> getDefaultSkillIds() {
        return defaultSkillIds;
    }

    public void setDefaultSkillIds(List<String> defaultSkillIds) {
        this.defaultSkillIds = defaultSkillIds == null ? new ArrayList<>() : new ArrayList<>(defaultSkillIds);
    }

    public List<String> getRequestedToolCapabilities() {
        return requestedToolCapabilities;
    }

    public void setRequestedToolCapabilities(List<String> requestedToolCapabilities) {
        this.requestedToolCapabilities = requestedToolCapabilities == null ? new ArrayList<>() : new ArrayList<>(requestedToolCapabilities);
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

    public List<String> getTriggerIntents() {
        return triggerIntents;
    }

    public void setTriggerIntents(List<String> triggerIntents) {
        this.triggerIntents = triggerIntents == null ? new ArrayList<>() : new ArrayList<>(triggerIntents);
    }

    public List<String> getTriggerTasks() {
        return triggerTasks;
    }

    public void setTriggerTasks(List<String> triggerTasks) {
        this.triggerTasks = triggerTasks == null ? new ArrayList<>() : new ArrayList<>(triggerTasks);
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
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

    public Boolean getGuardrail() {
        return guardrail;
    }

    public void setGuardrail(Boolean guardrail) {
        this.guardrail = guardrail;
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

    public Boolean getQualityGainVerified() {
        return qualityGainVerified;
    }

    public void setQualityGainVerified(Boolean qualityGainVerified) {
        this.qualityGainVerified = qualityGainVerified;
    }

    public String getQualityGainSource() {
        return qualityGainSource;
    }

    public void setQualityGainSource(String qualityGainSource) {
        this.qualityGainSource = qualityGainSource;
    }

    public Long getQualityGainEvalRunId() {
        return qualityGainEvalRunId;
    }

    public void setQualityGainEvalRunId(Long qualityGainEvalRunId) {
        this.qualityGainEvalRunId = qualityGainEvalRunId;
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
