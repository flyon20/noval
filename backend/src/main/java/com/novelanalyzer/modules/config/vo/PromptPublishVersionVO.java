package com.novelanalyzer.modules.config.vo;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

public class PromptPublishVersionVO {

    private Long id;
    private Long versionNo;
    private String publishNote;
    private Long publishedBy;
    private LocalDateTime createdAt;
    private List<PromptPublishItemVO> items = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Long versionNo) {
        this.versionNo = versionNo;
    }

    public String getPublishNote() {
        return publishNote;
    }

    public void setPublishNote(String publishNote) {
        this.publishNote = publishNote;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<PromptPublishItemVO> getItems() {
        return items;
    }

    public void setItems(List<PromptPublishItemVO> items) {
        this.items = items;
    }

    public static class PromptPublishItemVO {
        private String promptType;
        private Long promptConfigId;
        private String promptName;

        public String getPromptType() {
            return promptType;
        }

        public void setPromptType(String promptType) {
            this.promptType = promptType;
        }

        public Long getPromptConfigId() {
            return promptConfigId;
        }

        public void setPromptConfigId(Long promptConfigId) {
            this.promptConfigId = promptConfigId;
        }

        public String getPromptName() {
            return promptName;
        }

        public void setPromptName(String promptName) {
            this.promptName = promptName;
        }
    }
}
