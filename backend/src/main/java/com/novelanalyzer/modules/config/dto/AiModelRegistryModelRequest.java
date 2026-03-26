package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class AiModelRegistryModelRequest {

    @NotBlank(message = "modelKey is required")
    private String modelKey;
    private String displayName;
    private String providerType;
    private String modelName;
    private String baseUrl;
    private String apiKey;
    private Boolean enabled;
    private Boolean isDefault;
    @DecimalMin(value = "0.0", message = "defaultTemperature must be greater than or equal to 0")
    @DecimalMax(value = "2.0", message = "defaultTemperature must be less than or equal to 2")
    private Double defaultTemperature;
    @Min(value = 1, message = "maxTokens must be greater than 0")
    private Integer maxTokens;
    private String temperatureSpecJson;

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
}
