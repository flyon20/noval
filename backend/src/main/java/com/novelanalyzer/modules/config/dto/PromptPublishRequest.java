package com.novelanalyzer.modules.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

public class PromptPublishRequest {

    private String publishNote;
    @Valid
    @NotEmpty(message = "selections are required")
    private List<PromptPublishSelectionItem> selections = new ArrayList<>();

    public String getPublishNote() {
        return publishNote;
    }

    public void setPublishNote(String publishNote) {
        this.publishNote = publishNote;
    }

    public List<PromptPublishSelectionItem> getSelections() {
        return selections;
    }

    public void setSelections(List<PromptPublishSelectionItem> selections) {
        this.selections = selections;
    }

    public static class PromptPublishSelectionItem {
        @NotBlank(message = "promptType is required")
        private String promptType;
        @NotBlank(message = "promptName is required")
        private String promptName;
        @NotNull(message = "promptConfigId is required")
        private Long promptConfigId;

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

        public Long getPromptConfigId() {
            return promptConfigId;
        }

        public void setPromptConfigId(Long promptConfigId) {
            this.promptConfigId = promptConfigId;
        }
    }
}
