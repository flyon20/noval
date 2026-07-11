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
    private List<String> allowedTools = new ArrayList<>();
    private List<String> triggerIntents = new ArrayList<>();
    private List<String> triggerTasks = new ArrayList<>();
    private Integer priority;
    private String promptVersion;
    private String evalSuiteId;
    private Boolean guardrail;

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

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools == null ? new ArrayList<>() : new ArrayList<>(allowedTools);
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
}
