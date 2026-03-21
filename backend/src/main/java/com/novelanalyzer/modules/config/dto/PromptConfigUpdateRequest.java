package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public class PromptConfigUpdateRequest {

    @NotBlank(message = "promptType is required")
    private String promptType;

    @NotBlank(message = "promptName is required")
    private String promptName;

    @NotBlank(message = "promptContent is required")
    private String promptContent;

    @NotBlank(message = "modelName is required")
    private String modelName;
    @DecimalMin(value = "0.0", message = "temperature must be greater than or equal to 0")
    @DecimalMax(value = "2.0", message = "temperature must be less than or equal to 2")
    private Double temperature;
    @Min(value = 1, message = "maxTokens must be greater than 0")
    private Integer maxTokens;

    public String getPromptType() {
        return promptType;
    }

    public void setPromptType(String promptType) {
        this.promptType = promptType;
    }

    public String getPromptName() {
        return promptName;
    }

    public void setPromptName(String promptName) {
        this.promptName = promptName;
    }

    public String getPromptContent() {
        return promptContent;
    }

    public void setPromptContent(String promptContent) {
        this.promptContent = promptContent;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }
}
