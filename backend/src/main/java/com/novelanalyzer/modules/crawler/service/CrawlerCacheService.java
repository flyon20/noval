package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class CrawlerCacheService {

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
        String jsonValue;
        try {
            jsonValue = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, jsonValue, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            localCache.put(key, new LocalCacheEntry(jsonValue, Instant.now().getEpochSecond() + ttlSeconds));
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

    private record LocalCacheEntry(String json, long expireAt) {
    }
}
