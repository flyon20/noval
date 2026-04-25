package com.novelanalyzer.modules.asyncjob.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class AsyncJobLockService {

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
        } catch (Exception ignored) {
            // Redis unavailable: degrade to best-effort "acquired" so the main path still works.
            return true;
        }
    }

    public void release(String lockKey, String lockValue) {
        try {
            String currentValue = stringRedisTemplate.opsForValue().get(lockKey);
            if (currentValue != null && currentValue.equals(lockValue)) {
                stringRedisTemplate.delete(lockKey);
            }
        } catch (Exception ignored) {
            // Redis unavailable: skip unlock, there is no remote state to clean up safely.
        }
    }
}
