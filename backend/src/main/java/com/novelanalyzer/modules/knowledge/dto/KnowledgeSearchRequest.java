package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class KnowledgeSearchRequest {

    @NotNull(message = "user scope is required")
    @Positive(message = "user scope is invalid")
    private Long userId;
    @NotBlank(message = "query is required")
    private String query;
    private Long bookId;
    private String platform;
    private String sourceType;
    private Integer chapterNo;
    private String analysisType;
    @Min(0)
    @Max(1)
    private Double minScore;
    @Min(1)
    @Max(20)
    private Integer limit = 5;

    @AssertTrue(message = "query is required")
    public boolean isQueryValid() {
        return query != null && !query.trim().isEmpty();
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Integer getChapterNo() {
        return chapterNo;
    }

    public void setChapterNo(Integer chapterNo) {
        this.chapterNo = chapterNo;
    }

    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public Integer getLimit() {
        return limit;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
