package com.novelanalyzer.modules.config.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_prompt_effective_history")
public class UserPromptEffectiveHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("prompt_type")
    private String promptType;
    @TableField("binding_mode")
    private String bindingMode;
    @TableField("bound_prompt_config_id")
    private Long boundPromptConfigId;
    @TableField("effective_prompt_config_id")
    private Long effectivePromptConfigId;
    @TableField("effective_source")
    private String effectiveSource;
    @TableField("publish_version_id")
    private Long publishVersionId;
    @TableField("previous_effective_prompt_config_id")
    private Long previousEffectivePromptConfigId;
    @TableField("selected_model_key")
    private String selectedModelKey;
    @TableField("fallback")
    private Integer fallback;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public Long getEffectivePromptConfigId() {
        return effectivePromptConfigId;
    }

    public void setEffectivePromptConfigId(Long effectivePromptConfigId) {
        this.effectivePromptConfigId = effectivePromptConfigId;
    }

    public String getEffectiveSource() {
        return effectiveSource;
    }

    public void setEffectiveSource(String effectiveSource) {
        this.effectiveSource = effectiveSource;
    }

    public Long getPublishVersionId() {
        return publishVersionId;
    }

    public void setPublishVersionId(Long publishVersionId) {
        this.publishVersionId = publishVersionId;
    }

    public Long getPreviousEffectivePromptConfigId() {
        return previousEffectivePromptConfigId;
    }

    public void setPreviousEffectivePromptConfigId(Long previousEffectivePromptConfigId) {
        this.previousEffectivePromptConfigId = previousEffectivePromptConfigId;
    }

    public String getSelectedModelKey() {
        return selectedModelKey;
    }

    public void setSelectedModelKey(String selectedModelKey) {
        this.selectedModelKey = selectedModelKey;
    }

    public Integer getFallback() {
        return fallback;
    }

    public void setFallback(Integer fallback) {
        this.fallback = fallback;
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
