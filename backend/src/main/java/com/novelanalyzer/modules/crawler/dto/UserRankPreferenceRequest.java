package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.NotBlank;

public class UserRankPreferenceRequest {

    @NotBlank(message = "platform is required")
    private String platform;

    @NotBlank(message = "channelCode is required")
    private String channelCode;

    @NotBlank(message = "boardCode is required")
    private String boardCode;

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
}
