package com.novelanalyzer.modules.crawler.vo;

public class UserRankPreferenceVO {

    private Long userId;
    private String platform;
    private String channelCode;
    private String boardCode;
    private Integer rankFetchCount;

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

    public Integer getRankFetchCount() {
        return rankFetchCount;
    }

    public void setRankFetchCount(Integer rankFetchCount) {
        this.rankFetchCount = rankFetchCount;
    }
}
