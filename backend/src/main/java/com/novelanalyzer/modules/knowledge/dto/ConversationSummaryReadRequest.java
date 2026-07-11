package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ConversationSummaryReadRequest {

    @NotNull(message = "userId is required")
    private Long userId;
    @NotBlank(message = "conversationId is required")
    private String conversationId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
