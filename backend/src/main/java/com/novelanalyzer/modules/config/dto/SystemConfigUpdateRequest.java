package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;

public class SystemConfigUpdateRequest {

    @NotBlank(message = "configKey is required")
    private String configKey;

    @NotBlank(message = "configValue is required")
    private String configValue;

    private String configType;
    private String description;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigType() {
        return configType;
    }

    public void setConfigType(String configType) {
        this.configType = configType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
