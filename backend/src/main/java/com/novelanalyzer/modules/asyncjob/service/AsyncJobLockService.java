package com.novelanalyzer.modules.asyncjob.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
public class AsyncJobLockService {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public AsyncJobLockService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean tryAcquire(String lockKey, String lockValue, long ttlSeconds) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                ttlSeconds,
                TimeUnit.SECONDS
            );
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            // A missing lock store must never grant an execution lease.
            throw new IllegalStateException("async job lock service unavailable", ex);
        }
    }

    public boolean tryAcquireStrict(String lockKey, String lockValue, long ttlSeconds) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                ttlSeconds,
                TimeUnit.SECONDS
            );
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            throw new IllegalStateException("strict Redis lock acquisition failed", ex);
        }
    }

    public void release(String lockKey, String lockValue) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception ignored) {
            // Redis unavailable: skip unlock, there is no remote state to clean up safely.
        }
    }

    public void renew(String lockKey, String lockValue, long ttlSeconds) {
        try {
            String currentValue = stringRedisTemplate.opsForValue().get(lockKey);
            if (currentValue != null && currentValue.equals(lockValue)) {
                stringRedisTemplate.expire(lockKey, ttlSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // Redis unavailable: skip renewal. The job remains protected by MySQL idempotency.
        }
    }

    public boolean renewStrict(String lockKey, String lockValue, long ttlSeconds) {
        try {
            Long renewed = stringRedisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(lockKey),
                lockValue,
                String.valueOf(ttlSeconds)
            );
            return Long.valueOf(1L).equals(renewed);
        } catch (Exception ex) {
            throw new IllegalStateException("strict Redis lock renewal failed", ex);
        }
    }
}
