package com.novelanalyzer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, SmsAuthProperties.class, CloudflareTurnstileProperties.class})
public class AuthConfig {
}

