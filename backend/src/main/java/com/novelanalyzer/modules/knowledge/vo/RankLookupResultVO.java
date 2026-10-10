package com.novelanalyzer.modules.knowledge.vo;

import java.time.LocalDateTime;

public class RankLookupResultVO {

    private Long rankId;
    private Long snapshotId;
    private LocalDateTime snapshotTime;
    private String platform;
    private String channelCode;
    private String boardCode;
    private String channelName;
    private String boardName;
    private String category;
    private Integer rankNo;
    private Long bookId;
    private String bookName;
    private String author;
    private String intro;
    private String sourceLabel;
    private String freshness;
    private Long ageHours;
    private Boolean historicalReference;

    public Long getRankId() {
        return rankId;
    }

    public void setRankId(Long rankId) {
        this.rankId = rankId;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public LocalDateTime getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(LocalDateTime snapshotTime) {
        this.snapshotTime = snapshotTime;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getBoardCode() {
        return boardCode;
    }

    public void setBoardCode(String boardCode) {
        this.boardCode = boardCode;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getBoardName() {
        return boardName;
    }

    public void setBoardName(String boardName) {
        this.boardName = boardName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getRankNo() {
        return rankNo;
    }

    public void setRankNo(Integer rankNo) {
        this.rankNo = rankNo;
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

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }
    public String getFreshness() { return freshness; }
    public void setFreshness(String freshness) { this.freshness = freshness; }
    public Long getAgeHours() { return ageHours; }
    public void setAgeHours(Long ageHours) { this.ageHours = ageHours; }
    public Boolean getHistoricalReference() { return historicalReference; }
    public void setHistoricalReference(Boolean historicalReference) { this.historicalReference = historicalReference; }
}
