package com.novelanalyzer.modules.config.vo;

public class UserPromptBindingVO {

    private String promptType;
    private String bindingMode;
    private Long boundPromptConfigId;
    private Long lastSelectedPromptConfigId;
    private Long effectivePromptConfigId;
    private String effectiveSource;
    private String fallbackWarning;

    public String getPromptType() {
        return promptType;
    }

    public void setPromptType(String promptType) {
        this.promptType = promptType;
    }

    public String getBindingMode() {
        return bindingMode;
    }

    public void setBindingMode(String bindingMode) {
        this.bindingMode = bindingMode;
    }

    public Long getBoundPromptConfigId() {
        return boundPromptConfigId;
    }

    public void setBoundPromptConfigId(Long boundPromptConfigId) {
        this.boundPromptConfigId = boundPromptConfigId;
    }

    public Long getLastSelectedPromptConfigId() {
        return lastSelectedPromptConfigId;
    }

    public void setLastSelectedPromptConfigId(Long lastSelectedPromptConfigId) {
        this.lastSelectedPromptConfigId = lastSelectedPromptConfigId;
    }

    public Long getEffectivePromptConfigId() {
        return effectivePromptConfigId;
    }

    public void setEffectivePromptConfigId(Long effectivePromptConfigId) {
        this.effectivePromptConfigId = effectivePromptConfigId;
    }

    public String getEffectiveSource() {
        return effectiveSource;
    }

    public void setEffectiveSource(String effectiveSource) {
        this.effectiveSource = effectiveSource;
    }

    public String getFallbackWarning() {
        return fallbackWarning;
    }

    public void setFallbackWarning(String fallbackWarning) {
        this.fallbackWarning = fallbackWarning;
    }
}
