package com.novelanalyzer.modules.knowledge.vo;

public class AgentRuntimeConfigVO {

    private String reasoningModeDefault;
    private Integer maxParallelSpecialists;
    private Integer maxTotalInputTokens;
    private Integer maxFinalOutputTokensFast;
    private Integer maxFinalOutputTokensDeep;
    private Boolean enableIntentCache;
    private Boolean enableTaskGraphCache;
    private Boolean enableToolCache;
    private Boolean enableEvidenceCache;
    private Boolean enableSpecialistCache;
    private Integer maxPromptCharsPerExpert;
    private Integer maxSkillPromptChars;
    private Integer maxEvidenceItems;

    public String getReasoningModeDefault() {
        return reasoningModeDefault;
    }

    public void setReasoningModeDefault(String reasoningModeDefault) {
        this.reasoningModeDefault = reasoningModeDefault;
    }

    public Integer getMaxParallelSpecialists() {
        return maxParallelSpecialists;
    }

    public void setMaxParallelSpecialists(Integer maxParallelSpecialists) {
        this.maxParallelSpecialists = maxParallelSpecialists;
    }

    public Integer getMaxTotalInputTokens() {
        return maxTotalInputTokens;
    }

    public void setMaxTotalInputTokens(Integer maxTotalInputTokens) {
        this.maxTotalInputTokens = maxTotalInputTokens;
    }

    public Integer getMaxFinalOutputTokensFast() {
        return maxFinalOutputTokensFast;
    }

    public void setMaxFinalOutputTokensFast(Integer maxFinalOutputTokensFast) {
        this.maxFinalOutputTokensFast = maxFinalOutputTokensFast;
    }

    public Integer getMaxFinalOutputTokensDeep() {
        return maxFinalOutputTokensDeep;
    }

    public void setMaxFinalOutputTokensDeep(Integer maxFinalOutputTokensDeep) {
        this.maxFinalOutputTokensDeep = maxFinalOutputTokensDeep;
    }

    public Boolean getEnableIntentCache() {
        return enableIntentCache;
    }

    public void setEnableIntentCache(Boolean enableIntentCache) {
        this.enableIntentCache = enableIntentCache;
    }

    public Boolean getEnableTaskGraphCache() {
        return enableTaskGraphCache;
    }

    public void setEnableTaskGraphCache(Boolean enableTaskGraphCache) {
        this.enableTaskGraphCache = enableTaskGraphCache;
    }

    public Boolean getEnableToolCache() {
        return enableToolCache;
    }

    public void setEnableToolCache(Boolean enableToolCache) {
        this.enableToolCache = enableToolCache;
    }

    public Boolean getEnableEvidenceCache() {
        return enableEvidenceCache;
    }

    public void setEnableEvidenceCache(Boolean enableEvidenceCache) {
        this.enableEvidenceCache = enableEvidenceCache;
    }

    public Boolean getEnableSpecialistCache() {
        return enableSpecialistCache;
    }

    public void setEnableSpecialistCache(Boolean enableSpecialistCache) {
        this.enableSpecialistCache = enableSpecialistCache;
    }

    public Integer getMaxPromptCharsPerExpert() {
        return maxPromptCharsPerExpert;
    }

    public void setMaxPromptCharsPerExpert(Integer maxPromptCharsPerExpert) {
        this.maxPromptCharsPerExpert = maxPromptCharsPerExpert;
    }

    public Integer getMaxSkillPromptChars() {
        return maxSkillPromptChars;
    }

    public void setMaxSkillPromptChars(Integer maxSkillPromptChars) {
        this.maxSkillPromptChars = maxSkillPromptChars;
    }

    public Integer getMaxEvidenceItems() {
        return maxEvidenceItems;
    }

    public void setMaxEvidenceItems(Integer maxEvidenceItems) {
        this.maxEvidenceItems = maxEvidenceItems;
    }
}
