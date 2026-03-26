package com.novelanalyzer.modules.config.vo;

public class PromptConfigVO {

    private Long id;
    private String promptType;
    private String promptName;
    private String promptContent;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private String inputJsonSchema;
    private String inputExampleJson;
    private String outputJsonSchema;
    private String outputExampleJson;
    private String postProcessType;
    private String parseConfigJson;

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
}
