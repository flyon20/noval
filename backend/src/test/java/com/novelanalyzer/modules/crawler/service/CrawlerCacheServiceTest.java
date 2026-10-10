package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.crawler.model.RankRefreshIdempotencyEntry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CrawlerCacheServiceTest {

    @Test
    void shouldFailClosedWhenStrictIdempotencyRedisIsUnavailable() {
        StringRedisTemplate unavailableRedis = mock(
            StringRedisTemplate.class,
            invocation -> {
                throw new RedisConnectionFailureException("redis unavailable");
            }
        );
        CrawlerCacheService cache = new CrawlerCacheService(unavailableRedis, new ObjectMapper());
        RankRefreshIdempotencyEntry pending = RankRefreshIdempotencyEntry.inProgress("fp", "owner-1", 100L);

        assertThatThrownBy(() -> cache.getStrict("rank-refresh:key", RankRefreshIdempotencyEntry.class))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.putIfAbsent("rank-refresh:key", pending, 60))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.compareAndSet("rank-refresh:key", pending, pending, 60))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.evictIfValue("rank-refresh:key", pending))
            .isInstanceOf(IllegalStateException.class);
    }
}
