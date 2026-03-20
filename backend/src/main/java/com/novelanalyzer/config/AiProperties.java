package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private String difyBaseUrl;
    private String difyApiKeyRef;
    private String fallbackModel;
    private int timeoutMillis;

    public String getDifyBaseUrl() {
        return difyBaseUrl;
    }

    public void setDifyBaseUrl(String difyBaseUrl) {
        this.difyBaseUrl = difyBaseUrl;
    }

    public String getDifyApiKeyRef() {
        return difyApiKeyRef;
    }

    public void setDifyApiKeyRef(String difyApiKeyRef) {
        this.difyApiKeyRef = difyApiKeyRef;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}

