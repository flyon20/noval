package com.novelanalyzer.modules.data.vo;

public class HotBookVO {

    private String theme;
    private String bookName;
    private String author;
    private Integer rankNo;
    private String rankLabel;
    private String reason;

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
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

    public Integer getRankNo() {
        return rankNo;
    }

    public void setRankNo(Integer rankNo) {
        this.rankNo = rankNo;
    }

    public String getRankLabel() {
        return rankLabel;
    }

    public void setRankLabel(String rankLabel) {
        this.rankLabel = rankLabel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
