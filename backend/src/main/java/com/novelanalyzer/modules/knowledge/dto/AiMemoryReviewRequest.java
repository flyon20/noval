package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotNull;

public class AiMemoryReviewRequest {

    @NotNull(message = "userId is required")
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
