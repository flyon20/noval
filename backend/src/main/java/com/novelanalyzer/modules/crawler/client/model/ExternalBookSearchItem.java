package com.novelanalyzer.modules.crawler.client.model;

public class ExternalBookSearchItem {

    private String bookName;
    private String author;
    private String intro;
    private String bookUrl;
    private String platformBookId;
    private String contentType;
    private Boolean readableNovel;
    private String unavailableReason;

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

    public String getBookUrl() {
        return bookUrl;
    }

    public void setBookUrl(String bookUrl) {
        this.bookUrl = bookUrl;
    }

    public String getPlatformBookId() {
        return platformBookId;
    }

    public void setPlatformBookId(String platformBookId) {
        this.platformBookId = platformBookId;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Boolean getReadableNovel() {
        return readableNovel;
    }

    public void setReadableNovel(Boolean readableNovel) {
        this.readableNovel = readableNovel;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailableReason(String unavailableReason) {
        this.unavailableReason = unavailableReason;
    }
}
