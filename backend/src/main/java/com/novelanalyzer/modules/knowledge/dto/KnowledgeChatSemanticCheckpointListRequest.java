package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class KnowledgeChatSemanticCheckpointListRequest {

    @NotBlank
    @Size(max = 64)
    private String runId;

    @NotNull
    @Positive
    private Long userId;

    @Min(0)
    private Long afterSequence = 0L;

    @Min(1)
    @Max(500)
    private Integer limit = 500;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAfterSequence() {
        return afterSequence;
    }

    public void setAfterSequence(Long afterSequence) {
        this.afterSequence = afterSequence;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
