package com.novelanalyzer.modules.knowledge.dto;

public class KnowledgeRebuildRequest {

    private String mode = "FAILED_ONLY";
    private Integer limit = 100;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
