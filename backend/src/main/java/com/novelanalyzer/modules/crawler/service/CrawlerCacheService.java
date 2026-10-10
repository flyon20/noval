package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class CrawlerCacheService {

    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1 else return 0 end",
        Long.class
    );
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, LocalCacheEntry> localCache = new ConcurrentHashMap<>();

    public CrawlerCacheService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> T get(String key, Class<T> targetType) {
        String cachedJson = getJson(key);
        if (cachedJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(cachedJson, targetType);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public <T> T get(String key, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        String cachedJson = getJson(key);
        if (cachedJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(cachedJson, typeReference);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public void put(String key, Object value, long ttlSeconds) {
        String jsonValue = serialize(value);
        if (jsonValue == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, jsonValue, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            localCache.put(key, new LocalCacheEntry(jsonValue, Instant.now().getEpochSecond() + ttlSeconds));
        }
    }

    public <T> T getStrict(String key, Class<T> targetType) {
        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(key);
            return cachedJson == null ? null : objectMapper.readValue(cachedJson, targetType);
        } catch (Exception ex) {
            throw new IllegalStateException("strict Redis read failed", ex);
        }
    }

    public boolean putIfAbsent(String key, Object value, long ttlSeconds) {
        String jsonValue = serialize(value);
        if (jsonValue == null) {
            return false;
        }
        try {
            Boolean stored = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                jsonValue,
                ttlSeconds,
                TimeUnit.SECONDS
            );
            return Boolean.TRUE.equals(stored);
        } catch (Exception ex) {
            throw new IllegalStateException("strict Redis SET NX failed", ex);
        }
    }

    public boolean compareAndSet(String key, Object expectedValue, Object updatedValue, long ttlSeconds) {
        String expectedJson = serialize(expectedValue);
        String updatedJson = serialize(updatedValue);
        if (expectedJson == null || updatedJson == null) {
            return false;
        }
        try {
            Long updated = stringRedisTemplate.execute(
                COMPARE_AND_SET_SCRIPT,
                Collections.singletonList(key),
                expectedJson,
                updatedJson,
                String.valueOf(ttlSeconds)
            );
            return Long.valueOf(1L).equals(updated);
        } catch (Exception ex) {
            throw new IllegalStateException("strict Redis compare-and-set failed", ex);
        }
    }

    public boolean evictIfValue(String key, Object expectedValue) {
        String expectedJson = serialize(expectedValue);
        if (expectedJson == null) {
            return false;
        }
        try {
            Long removed = stringRedisTemplate.execute(
                COMPARE_AND_DELETE_SCRIPT,
                Collections.singletonList(key),
                expectedJson
            );
            return Long.valueOf(1L).equals(removed);
        } catch (Exception ex) {
            throw new IllegalStateException("strict Redis compare-and-delete failed", ex);
        }
    }

    public void evict(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ignored) {
            // Redis unavailable, fallback to local cache.
        }
        localCache.remove(key);
    }

    private String getJson(String key) {
        try {
            String redisValue = stringRedisTemplate.opsForValue().get(key);
            if (redisValue != null) {
                return redisValue;
            }
        } catch (Exception ignored) {
            // Redis unavailable, fallback to local cache.
        }
        LocalCacheEntry localEntry = localCache.get(key);
        if (localEntry == null) {
            return null;
        }
        if (localEntry.expireAt <= Instant.now().getEpochSecond()) {
            localCache.remove(key);
            return null;
        }
        return localEntry.json;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private record LocalCacheEntry(String json, long expireAt) {
    }
}
