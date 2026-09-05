package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class BookResearchPackRequest {

    @NotNull(message = "user scope is required")
    @Positive(message = "user scope is invalid")
    private Long userId;
    private String platform;
    private Long bookId;
    private String bookName;
    private Integer chapterLimit;
    private Integer analysisLimit;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public Integer getChapterLimit() {
        return chapterLimit;
    }

    public void setChapterLimit(Integer chapterLimit) {
        this.chapterLimit = chapterLimit;
    }

    public Integer getAnalysisLimit() {
        return analysisLimit;
    }

    public void setAnalysisLimit(Integer analysisLimit) {
        this.analysisLimit = analysisLimit;
    }
}
