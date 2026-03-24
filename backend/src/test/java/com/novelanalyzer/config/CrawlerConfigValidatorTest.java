package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlerConfigValidatorTest {

    @Test
    void shouldRejectBlankInternalApiKey() {
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("");

        CrawlerConfigValidator validator = new CrawlerConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("internal API key");
    }

    @Test
    void shouldRejectTooShortInternalApiKey() {
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("short-key");

        CrawlerConfigValidator validator = new CrawlerConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("internal API key");
    }

    @Test
    void shouldAcceptSecureInternalApiKey() {
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("crawler-internal-api-key-with-enough-length-1234567890");

        CrawlerConfigValidator validator = new CrawlerConfigValidator(properties);

        assertThatNoException().isThrownBy(validator::validate);
    }
}
