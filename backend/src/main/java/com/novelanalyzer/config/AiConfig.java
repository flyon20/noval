package com.novelanalyzer.config;

import dev.langchain4j.model.TokenCountEstimator;
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

    /**
     * Token 数量估算器：区分中文字符（约 1.5 token）和 ASCII 字符（约 0.25 token），
     * 比 length/2 粗估更接近实际 token 消耗，让 chunk 切割更准确。
     */
    @Bean
    public TokenCountEstimator aiTokenCountEstimator() {
        return new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                if (text == null || text.isEmpty()) return 0;
                int count = 0;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c > '\u2E7F') {
                        count += 2; // CJK：约 1.5 token，×2 后除 3
                    } else {
                        count += 1; // ASCII/拉丁：约 4 字符/token，×1 后除 3
                    }
                }
                return Math.max(1, count / 3);
            }

            @Override
            public int estimateTokenCountInMessage(dev.langchain4j.data.message.ChatMessage message) {
                return 0;
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<dev.langchain4j.data.message.ChatMessage> messages) {
                return 0;
            }
        };
    }
}

