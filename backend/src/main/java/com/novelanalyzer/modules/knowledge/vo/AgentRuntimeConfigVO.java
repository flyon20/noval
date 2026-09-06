package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class AgentRuntimeConfigVO {

    private String reasoningModeDefault;
    private Integer maxParallelSpecialists;
    private Integer maxTotalInputTokens;
    private Integer contextCompactionThresholdPercent;
    private Integer runTokenBudgetPercent;
    private Integer maxFinalOutputTokensFast;
    private Integer maxFinalOutputTokensDeep;
    private Boolean enableIntentCache;
    private Boolean enableTaskGraphCache;
    private Boolean enableToolCache;
    private Boolean enableEvidenceCache;
    private Boolean enableSpecialistCache;
    private Boolean specialistMcpEnabled;
    private Boolean harnessEvidenceRepairEnabled = false;
    private Boolean harnessAnswerValidationEnabled = false;
    private Boolean harnessTaskCheckpointEnabled = false;
    private Boolean harnessStageSkillsEnabled = false;
    private Integer maxPromptCharsPerExpert;
    private Integer maxSkillPromptChars;
    private Integer maxEvidenceItems;
    private List<AgentProviderProfileVO> providerProfiles = new ArrayList<>();
    private AgentProviderRoutingPolicyVO providerRoutingPolicy;

    public Boolean getHarnessEvidenceRepairEnabled() {
        return harnessEvidenceRepairEnabled;
    }

    public void setHarnessEvidenceRepairEnabled(Boolean value) {
        harnessEvidenceRepairEnabled = value;
    }

    public Boolean getHarnessAnswerValidationEnabled() {
        return harnessAnswerValidationEnabled;
    }

    public void setHarnessAnswerValidationEnabled(Boolean value) {
        harnessAnswerValidationEnabled = value;
    }

    public Boolean getHarnessTaskCheckpointEnabled() {
        return harnessTaskCheckpointEnabled;
    }

    public void setHarnessTaskCheckpointEnabled(Boolean value) {
        harnessTaskCheckpointEnabled = value;
    }

    public Boolean getHarnessStageSkillsEnabled() {
        return harnessStageSkillsEnabled;
    }

    public void setHarnessStageSkillsEnabled(Boolean value) {
        harnessStageSkillsEnabled = value;
    }

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

    public Integer getContextCompactionThresholdPercent() {
        return contextCompactionThresholdPercent;
    }

    public void setContextCompactionThresholdPercent(Integer contextCompactionThresholdPercent) {
        this.contextCompactionThresholdPercent = contextCompactionThresholdPercent;
    }

    public Integer getRunTokenBudgetPercent() {
        return runTokenBudgetPercent;
    }

    public void setRunTokenBudgetPercent(Integer runTokenBudgetPercent) {
        this.runTokenBudgetPercent = runTokenBudgetPercent;
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

    public Boolean getSpecialistMcpEnabled() {
        return specialistMcpEnabled;
    }

    public void setSpecialistMcpEnabled(Boolean specialistMcpEnabled) {
        this.specialistMcpEnabled = specialistMcpEnabled;
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

    public List<AgentProviderProfileVO> getProviderProfiles() {
        return providerProfiles;
    }

    public void setProviderProfiles(List<AgentProviderProfileVO> providerProfiles) {
        this.providerProfiles = providerProfiles == null ? new ArrayList<>() : providerProfiles;
    }

    public AgentProviderRoutingPolicyVO getProviderRoutingPolicy() {
        return providerRoutingPolicy;
    }

    public void setProviderRoutingPolicy(AgentProviderRoutingPolicyVO providerRoutingPolicy) {
        this.providerRoutingPolicy = providerRoutingPolicy;
    }
}
