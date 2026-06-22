package com.novelanalyzer.modules.knowledge.vo;

public class BookProfileVO {

    private Long bookId;
    private String platform;
    private String platformBookId;
    private String bookName;
    private String author;
    private String intro;
    private String category;
    private String bookUrl;
    private Integer latestRankNo;
    private String latestRankLabel;

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

    public String getPlatformBookId() {
        return platformBookId;
    }

    public void setPlatformBookId(String platformBookId) {
        this.platformBookId = platformBookId;
    }

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBookUrl() {
        return bookUrl;
    }

    public void setBookUrl(String bookUrl) {
        this.bookUrl = bookUrl;
    }

    public Integer getLatestRankNo() {
        return latestRankNo;
    }

    public void setLatestRankNo(Integer latestRankNo) {
        this.latestRankNo = latestRankNo;
    }

    public String getLatestRankLabel() {
        return latestRankLabel;
    }

    public void setLatestRankLabel(String latestRankLabel) {
        this.latestRankLabel = latestRankLabel;
    }
}
