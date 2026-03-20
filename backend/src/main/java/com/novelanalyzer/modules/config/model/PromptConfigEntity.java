package com.novelanalyzer.modules.config.model;

public class PromptConfigEntity {

    private Long id;
    private String promptType;
    private String promptName;
    private String promptContent;
    private String modelName;
    private String difyWorkflowId;
    private String difyApiKeyRef;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getDifyWorkflowId() {
        return difyWorkflowId;
    }

    public void setDifyWorkflowId(String difyWorkflowId) {
        this.difyWorkflowId = difyWorkflowId;
    }

    public String getDifyApiKeyRef() {
        return difyApiKeyRef;
    }

    public void setDifyApiKeyRef(String difyApiKeyRef) {
        this.difyApiKeyRef = difyApiKeyRef;
    }
}

