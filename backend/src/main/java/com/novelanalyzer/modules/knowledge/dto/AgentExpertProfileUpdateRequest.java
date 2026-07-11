package com.novelanalyzer.modules.knowledge.dto;

import java.util.List;

public class AgentExpertProfileUpdateRequest {

    private Boolean enabled;
    private Integer priority;
    private Integer maxTokens;
    private Integer maxToolCalls;
    private List<String> triggerIntents;
    private List<String> triggerTasks;
    private List<String> allowedTools;
    private String promptVersion;
    private String evalSuiteId;

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

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
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
}
