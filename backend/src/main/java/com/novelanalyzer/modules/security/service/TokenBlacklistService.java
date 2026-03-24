package com.novelanalyzer.modules.security.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class TokenBlacklistService {

    private static final String TOKEN_BLACKLIST_KEY_PREFIX = "auth:blacklist:token:";

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<String, Long> localBlacklist = new ConcurrentHashMap<>();

    public TokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void blacklist(String token, long expireSeconds) {
        if (token == null || token.isBlank()) {
            return;
        }
        long ttl = Math.max(expireSeconds, 1L);
        try {
            stringRedisTemplate.opsForValue().set(TOKEN_BLACKLIST_KEY_PREFIX + token, "1", ttl, TimeUnit.SECONDS);
        } catch (Exception ex) {
            localBlacklist.put(token, Instant.now().getEpochSecond() + ttl);
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Boolean exists = stringRedisTemplate.hasKey(TOKEN_BLACKLIST_KEY_PREFIX + token);
            return Boolean.TRUE.equals(exists);
        } catch (Exception ex) {
            Long expireAt = localBlacklist.get(token);
            if (expireAt == null) {
                return false;
            }
            if (expireAt <= Instant.now().getEpochSecond()) {
                localBlacklist.remove(token);
                return false;
            }
            return true;
        }
    }
}

