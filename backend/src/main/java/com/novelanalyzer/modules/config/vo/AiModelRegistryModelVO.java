package com.novelanalyzer.modules.config.vo;

import java.util.Map;

public class AiModelRegistryModelVO {

    private String modelKey;
    private String displayName;
    private String providerType;
    private String modelName;
    private String baseUrl;
    private String apiKey;
    private Boolean apiKeyConfigured;
    private String apiKeyMasked;
    private Boolean enabled;
    private Boolean isDefault;
    private Double defaultTemperature;
    private Integer maxTokens;
    private String temperatureSpecJson;
    private Map<String, String> promptBindings;

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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Boolean getApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public void setApiKeyConfigured(Boolean apiKeyConfigured) {
        this.apiKeyConfigured = apiKeyConfigured;
    }

    public String getApiKeyMasked() {
        return apiKeyMasked;
    }

    public void setApiKeyMasked(String apiKeyMasked) {
        this.apiKeyMasked = apiKeyMasked;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
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

    public Map<String, String> getPromptBindings() {
        return promptBindings;
    }

    public void setPromptBindings(Map<String, String> promptBindings) {
        this.promptBindings = promptBindings;
    }
}
