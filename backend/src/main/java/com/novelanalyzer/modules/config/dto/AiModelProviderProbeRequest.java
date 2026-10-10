package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;

public class AiModelProviderProbeRequest {

    @NotBlank(message = "modelKey is required")
    private String modelKey;

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }
}
