package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;

public class UserConfigUpdateRequest {

    @NotBlank
    private String configKey;
    private String configValue;

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }

    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
}
