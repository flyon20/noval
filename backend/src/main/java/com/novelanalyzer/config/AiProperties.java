package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private String providerType = "openai-compatible";
    private String difyBaseUrl;
    private String difyApiKeyRef;
    private String fallbackModel = "local-fallback";
    private int timeoutMillis = 15000;
    private OpenAiCompatible openAiCompatible = new OpenAiCompatible();

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

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

    public OpenAiCompatible getOpenAiCompatible() {
        return openAiCompatible;
    }

    public void setOpenAiCompatible(OpenAiCompatible openAiCompatible) {
        this.openAiCompatible = openAiCompatible;
    }

    public static class OpenAiCompatible {

        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKeyRef = "DEEPSEEK_API_KEY";
        private String defaultModel = "deepseek-chat";
        private boolean streamingEnabled;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKeyRef() {
            return apiKeyRef;
        }

        public void setApiKeyRef(String apiKeyRef) {
            this.apiKeyRef = apiKeyRef;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public boolean isStreamingEnabled() {
            return streamingEnabled;
        }

        public void setStreamingEnabled(boolean streamingEnabled) {
            this.streamingEnabled = streamingEnabled;
        }
    }
}
