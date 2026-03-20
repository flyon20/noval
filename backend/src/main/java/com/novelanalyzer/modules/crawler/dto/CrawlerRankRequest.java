package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.NotBlank;

public class CrawlerRankRequest {

    @NotBlank(message = "platform is required")
    private String platform;

    @NotBlank(message = "category is required")
    private String category;

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
}

