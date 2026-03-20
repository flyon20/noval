package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;

public class PromptConfigUpdateRequest {

    @NotBlank(message = "promptType is required")
    private String promptType;

    @NotBlank(message = "promptName is required")
    private String promptName;

    @NotBlank(message = "promptContent is required")
    private String promptContent;

    @NotBlank(message = "modelName is required")
    private String modelName;

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
}

