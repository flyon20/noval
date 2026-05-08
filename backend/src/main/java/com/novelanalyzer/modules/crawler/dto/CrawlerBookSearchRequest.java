package com.novelanalyzer.modules.crawler.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class CrawlerBookSearchRequest {

    @NotBlank(message = "platform is required")
    private String platform;

    @NotBlank(message = "keyword is required")
    private String keyword;

    @Min(1)
    @Max(20)
    private Integer limit = 10;

    @AssertTrue(message = "keyword is required")
    public boolean isKeywordValid() {
        return keyword != null && !keyword.trim().isEmpty();
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}