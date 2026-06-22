package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTimeoutConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class);

    @Test
    void shouldAllowRedisTimeoutToBeConfiguredFromEnvironmentProperty() {
        contextRunner
            .withPropertyValues("spring.data.redis.timeout=15s")
            .run(context -> {
                RedisProperties properties = context.getBean(RedisProperties.class);
                assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(15));
            });
    }
}
