package com.novelanalyzer.modules.asyncjob;

import com.novelanalyzer.modules.asyncjob.service.AsyncJobLockService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncJobLockServiceTest {

    @Test
    void doesNotGrantAnExecutionLeaseWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        AsyncJobLockService service = new AsyncJobLockService(redis);

        assertThatThrownBy(() -> service.tryAcquire("lock:test", "value", 30))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("async job lock service unavailable");
    }
}
