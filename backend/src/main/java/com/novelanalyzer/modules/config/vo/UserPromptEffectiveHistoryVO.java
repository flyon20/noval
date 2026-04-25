package com.novelanalyzer.modules.config.vo;

import java.time.LocalDateTime;

public class UserPromptEffectiveHistoryVO {

    private Long id;
    private String promptType;
    private Long effectivePromptConfigId;
    private String effectiveSource;
    private Long publishVersionId;
    private Boolean fallback;
    private LocalDateTime createTime;

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

    public Boolean getFallback() {
        return fallback;
    }

    public void setFallback(Boolean fallback) {
        this.fallback = fallback;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
