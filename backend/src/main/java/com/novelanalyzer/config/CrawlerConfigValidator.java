package com.novelanalyzer.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class CrawlerConfigValidator {

    private static final int MIN_INTERNAL_API_KEY_LENGTH = 32;

    private final CrawlerProperties crawlerProperties;

    public CrawlerConfigValidator(CrawlerProperties crawlerProperties) {
        this.crawlerProperties = crawlerProperties;
    }

    @PostConstruct
    public void validate() {
        String internalApiKey = crawlerProperties.getInternalApiKey();
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalStateException("Crawler internal API key must be configured");
        }
        if (internalApiKey.length() < MIN_INTERNAL_API_KEY_LENGTH) {
            throw new IllegalStateException("Crawler internal API key must be at least 32 characters");
        }
    }
}
