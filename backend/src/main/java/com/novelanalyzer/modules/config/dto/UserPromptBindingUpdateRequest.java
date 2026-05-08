package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;

public class UserPromptBindingUpdateRequest {

    @NotBlank(message = "promptType is required")
    private String promptType;
    @NotBlank(message = "bindingMode is required")
    private String bindingMode;
    private Long boundPromptConfigId;

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
}
