package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public class UserRankPreferenceRequest {

    @NotBlank(message = "platform is required")
    private String platform;

    @NotBlank(message = "channelCode is required")
    private String channelCode;

    @NotBlank(message = "boardCode is required")
    private String boardCode;

    private Integer rankFetchCount;

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

    @AssertTrue(message = "rankFetchCount must be between 10 and 100, in steps of 10")
    public boolean isRankFetchCountValid() {
        return rankFetchCount == null
            || (rankFetchCount >= 10 && rankFetchCount <= 100 && rankFetchCount % 10 == 0);
    }
}
