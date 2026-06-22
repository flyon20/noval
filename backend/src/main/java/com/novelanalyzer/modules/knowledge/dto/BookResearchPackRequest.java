package com.novelanalyzer.modules.knowledge.dto;

public class BookResearchPackRequest {

    private String platform;
    private Long bookId;
    private String bookName;
    private Integer chapterLimit;
    private Integer analysisLimit;

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
