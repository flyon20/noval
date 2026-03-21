package com.novelanalyzer.modules.config.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("prompt_config")
public class PromptConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("prompt_type")
    private String promptType;
    @TableField("prompt_name")
    private String promptName;
    @TableField("prompt_content")
    private String promptContent;
    @TableField("model_name")
    private String modelName;
    private Double temperature;
    @TableField("max_tokens")
    private Integer maxTokens;
    private Integer status;
    @TableField("is_default")
    private Integer isDefault;
    @TableField("dify_workflow_id")
    private String difyWorkflowId;
    @TableField("dify_api_key_ref")
    private String difyApiKeyRef;
    @TableField("output_json_schema")
    private String outputJsonSchema;
    @TableField("output_example_json")
    private String outputExampleJson;
    @TableField("post_process_type")
    private String postProcessType;
    @TableField("parse_config_json")
    private String parseConfigJson;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
