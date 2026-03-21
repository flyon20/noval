package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.NotBlank;

public class CrawlerRankRequest {

    public static final String REFRESH_MODE_AUTO = "AUTO";
    public static final String REFRESH_MODE_FORCE = "FORCE";

    @NotBlank(message = "platform is required")
    private String platform;

    @NotBlank(message = "category is required")
    private String category;

    private String refreshMode;

    private String forceReason;

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
