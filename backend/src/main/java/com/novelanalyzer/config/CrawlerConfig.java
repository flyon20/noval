package com.novelanalyzer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(CrawlerProperties.class)
public class CrawlerConfig {

    @Bean
    public RestTemplate crawlerRestTemplate(RestTemplateBuilder builder, CrawlerProperties properties) {
        return builder
            .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
            .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
            .build();
    }
}

