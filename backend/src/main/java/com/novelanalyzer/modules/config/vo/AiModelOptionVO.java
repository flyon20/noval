package com.novelanalyzer.modules.config.vo;

public class AiModelOptionVO {

    private String modelKey;
    private String displayName;
    private String providerType;
    private Boolean isDefault;
    private Double defaultTemperature;
    private Integer maxTokens;
    private String temperatureSpecJson;
    private Boolean supportsReasoning;
    private String providerFamily;
    private java.util.List<String> reasoningTiers;

    public String getProviderFamily() {
        return providerFamily;
    }

    public void setProviderFamily(String providerFamily) {
        this.providerFamily = providerFamily;
    }

    public Boolean getSupportsReasoning() {
        return supportsReasoning;
    }

    public void setSupportsReasoning(Boolean supportsReasoning) {
        this.supportsReasoning = supportsReasoning;
    }

    public java.util.List<String> getReasoningTiers() {
        return reasoningTiers;
    }

    public void setReasoningTiers(java.util.List<String> reasoningTiers) {
        this.reasoningTiers = reasoningTiers;
    }

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Double getDefaultTemperature() {
        return defaultTemperature;
    }

    public void setDefaultTemperature(Double defaultTemperature) {
        this.defaultTemperature = defaultTemperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getTemperatureSpecJson() {
        return temperatureSpecJson;
    }

    public void setTemperatureSpecJson(String temperatureSpecJson) {
        this.temperatureSpecJson = temperatureSpecJson;
    }
}
