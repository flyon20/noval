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
}
