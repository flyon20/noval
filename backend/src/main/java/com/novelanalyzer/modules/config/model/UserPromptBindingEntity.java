package com.novelanalyzer.modules.config.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_prompt_binding")
public class UserPromptBindingEntity {

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
    @TableField("last_selected_prompt_config_id")
    private Long lastSelectedPromptConfigId;
    @TableField("effective_prompt_config_id")
    private Long effectivePromptConfigId;
    @TableField("publish_version_id")
    private Long publishVersionId;
    @TableField("fallback_warning")
    private String fallbackWarning;
    private Integer status;
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

    public Long getLastSelectedPromptConfigId() {
        return lastSelectedPromptConfigId;
    }

    public void setLastSelectedPromptConfigId(Long lastSelectedPromptConfigId) {
        this.lastSelectedPromptConfigId = lastSelectedPromptConfigId;
    }

    public Long getEffectivePromptConfigId() {
        return effectivePromptConfigId;
    }

    public void setEffectivePromptConfigId(Long effectivePromptConfigId) {
        this.effectivePromptConfigId = effectivePromptConfigId;
    }

    public Long getPublishVersionId() {
        return publishVersionId;
    }

    public void setPublishVersionId(Long publishVersionId) {
        this.publishVersionId = publishVersionId;
    }

    public String getFallbackWarning() {
        return fallbackWarning;
    }

    public void setFallbackWarning(String fallbackWarning) {
        this.fallbackWarning = fallbackWarning;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
