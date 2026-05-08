package com.novelanalyzer.modules.config.vo;

import java.time.LocalDateTime;

public class PromptConfigVO {

    private Long id;
    private String promptType;
    private String promptName;
    private String promptContent;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private Boolean isDefault;
    private String inputJsonSchema;
    private String inputExampleJson;
    private String outputJsonSchema;
    private String outputExampleJson;
    private String postProcessType;
    private String parseConfigJson;
    private String scopeType;
    private Long ownerUserId;
    private Long sourcePromptConfigId;
    private Boolean isPublished;
    private Long publishedVersionNo;
    private String editableScope;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getInputJsonSchema() {
        return inputJsonSchema;
    }

    public void setInputJsonSchema(String inputJsonSchema) {
        this.inputJsonSchema = inputJsonSchema;
    }

    public String getInputExampleJson() {
        return inputExampleJson;
    }

    public void setInputExampleJson(String inputExampleJson) {
        this.inputExampleJson = inputExampleJson;
    }

    public String getOutputJsonSchema() {
        return outputJsonSchema;
    }

    public void setOutputJsonSchema(String outputJsonSchema) {
        this.outputJsonSchema = outputJsonSchema;
    }

    public String getOutputExampleJson() {
        return outputExampleJson;
    }

    public void setOutputExampleJson(String outputExampleJson) {
        this.outputExampleJson = outputExampleJson;
    }

    public String getPostProcessType() {
        return postProcessType;
    }

    public void setPostProcessType(String postProcessType) {
        this.postProcessType = postProcessType;
    }

    public String getParseConfigJson() {
        return parseConfigJson;
    }

    public void setParseConfigJson(String parseConfigJson) {
        this.parseConfigJson = parseConfigJson;
    }

    public String getEditableScope() {
        return editableScope;
    }

    public void setEditableScope(String editableScope) {
        this.editableScope = editableScope;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getSourcePromptConfigId() {
        return sourcePromptConfigId;
    }

    public void setSourcePromptConfigId(Long sourcePromptConfigId) {
        this.sourcePromptConfigId = sourcePromptConfigId;
    }

    public Boolean getIsPublished() {
        return isPublished;
    }

    public void setIsPublished(Boolean isPublished) {
        this.isPublished = isPublished;
    }

    public Long getPublishedVersionNo() {
        return publishedVersionNo;
    }

    public void setPublishedVersionNo(Long publishedVersionNo) {
        this.publishedVersionNo = publishedVersionNo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
