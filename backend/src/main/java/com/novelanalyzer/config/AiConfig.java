package com.novelanalyzer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    public RestTemplate aiRestTemplate(RestTemplateBuilder builder, AiProperties properties) {
        return builder
            .setConnectTimeout(Duration.ofMillis(properties.getTimeoutMillis()))
            .setReadTimeout(Duration.ofMillis(properties.getTimeoutMillis()))
            .build();
    }
}

