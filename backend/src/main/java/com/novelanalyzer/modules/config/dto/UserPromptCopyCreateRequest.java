package com.novelanalyzer.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class UserPromptCopyCreateRequest {

    @NotBlank(message = "promptType is required")
    private String promptType;
    @NotNull(message = "sourcePromptConfigId is required")
    private Long sourcePromptConfigId;
    private String copyName;

    public String getPromptType() {
        return promptType;
    }

    public void setPromptType(String promptType) {
        this.promptType = promptType;
    }

    public Long getSourcePromptConfigId() {
        return sourcePromptConfigId;
    }

    public void setSourcePromptConfigId(Long sourcePromptConfigId) {
        this.sourcePromptConfigId = sourcePromptConfigId;
    }

    public String getCopyName() {
        return copyName;
    }

    public void setCopyName(String copyName) {
        this.copyName = copyName;
    }
}
