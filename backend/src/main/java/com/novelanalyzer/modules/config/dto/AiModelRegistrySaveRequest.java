package com.novelanalyzer.modules.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.List;

public class AiModelRegistrySaveRequest {

    private String defaultModelKey;
    @Valid
    @NotEmpty(message = "models must not be empty")
    private List<AiModelRegistryModelRequest> models = new ArrayList<>();

    public String getDefaultModelKey() {
        return defaultModelKey;
    }

    public void setDefaultModelKey(String defaultModelKey) {
        this.defaultModelKey = defaultModelKey;
    }

    public List<AiModelRegistryModelRequest> getModels() {
        return models;
    }

    public void setModels(List<AiModelRegistryModelRequest> models) {
        this.models = models;
    }
}
