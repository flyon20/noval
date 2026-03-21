package com.novelanalyzer.modules.analysis.dto;

import jakarta.validation.constraints.NotBlank;

public class TrendAnalysisRequest {

    @NotBlank(message = "platform is required")
    private String platform;

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
