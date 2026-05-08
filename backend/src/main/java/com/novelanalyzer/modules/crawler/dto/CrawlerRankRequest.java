package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public class CrawlerRankRequest {

    public static final String REFRESH_MODE_AUTO = "AUTO";
    public static final String REFRESH_MODE_FORCE = "FORCE";

    @NotBlank(message = "platform is required")
    private String platform;

    private String category;

    private String channelCode;

    private String boardCode;

    private String refreshMode;

    private String forceReason;

    private Integer rankFetchCount;

    @AssertTrue(message = "category or channelCode/boardCode is required")
    public boolean isScopeValid() {
        return hasLegacyCategory() || hasBoardSelection();
    }

    public boolean hasLegacyCategory() {
        return category != null && !category.isBlank();
    }

    public boolean hasBoardSelection() {
        return channelCode != null && !channelCode.isBlank()
            && boardCode != null && !boardCode.isBlank();
    }

    @AssertTrue(message = "rankFetchCount must be between 10 and 100, in steps of 10")
    public boolean isRankFetchCountValid() {
        return rankFetchCount == null
            || (rankFetchCount >= 10 && rankFetchCount <= 100 && rankFetchCount % 10 == 0);
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public String getRefreshMode() {
        return refreshMode;
    }

    public void setRefreshMode(String refreshMode) {
        this.refreshMode = refreshMode;
    }

    public String getForceReason() {
        return forceReason;
    }

    public void setForceReason(String forceReason) {
        this.forceReason = forceReason;
    }

    public Integer getRankFetchCount() {
        return rankFetchCount;
    }

    public void setRankFetchCount(Integer rankFetchCount) {
        this.rankFetchCount = rankFetchCount;
    }
}
