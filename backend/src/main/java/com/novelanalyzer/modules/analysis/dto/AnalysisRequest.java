package com.novelanalyzer.modules.analysis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AnalysisRequest {

    @NotBlank(message = "platform is required")
    private String platform;

    @NotNull(message = "bookId is required")
    private Long bookId;

    @NotNull(message = "chapterCount is required")
    @Min(value = 1, message = "chapterCount must be >= 1")
    @Max(value = 10, message = "chapterCount must be <= 10")
    private Integer chapterCount;

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

    public Integer getChapterCount() {
        return chapterCount;
    }

    public void setChapterCount(Integer chapterCount) {
        this.chapterCount = chapterCount;
    }
}

