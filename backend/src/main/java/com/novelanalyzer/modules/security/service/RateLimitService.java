package com.novelanalyzer.modules.security.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.SecurityProperties;
import com.novelanalyzer.modules.security.repository.IpBlacklistRepository;
import com.novelanalyzer.modules.security.repository.OperationLogRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {

    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final StringRedisTemplate stringRedisTemplate;
    private final SecurityProperties securityProperties;
    private final OperationLogRepository operationLogRepository;
    private final IpBlacklistRepository ipBlacklistRepository;
    private final Map<String, CounterWindow> localCounterMap = new ConcurrentHashMap<>();

    public RateLimitService(StringRedisTemplate stringRedisTemplate,
                            SecurityProperties securityProperties,
                            OperationLogRepository operationLogRepository,
                            IpBlacklistRepository ipBlacklistRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.securityProperties = securityProperties;
        this.operationLogRepository = operationLogRepository;
        this.ipBlacklistRepository = ipBlacklistRepository;
    }

    public void assertWithinLimit(String ipAddress, String path, AuthUser authUser) {
        long count = increment(ipAddress, path);
        if (count <= securityProperties.getRateLimitPerMinute()) {
            return;
        }

        String username = authUser == null ? "anonymous" : authUser.getUsername();
        Long userId = authUser == null ? null : authUser.getUserId();
        operationLogRepository.insertOperationLog(
            userId,
            username,
            "SECURITY",
            "RATE_LIMIT",
            "request blocked by rate limit",
            0,
            "too many requests",
            ipAddress
        );

        long violationCount = incrementViolation(ipAddress);
        if (violationCount >= securityProperties.getAutoBlacklistThreshold()) {
            ipBlacklistRepository.upsertBlockedIp(
                ipAddress,
                "rate limit exceeded",
                LocalDateTime.now().plusSeconds(securityProperties.getBlacklistSeconds())
            );
        }
        throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "too many requests");
    }

    private long increment(String ipAddress, String path) {
        String minuteBucket = LocalDateTime.now().format(MINUTE_FORMATTER);
        String key = "ratelimit:" + ipAddress + ":" + path + ":" + minuteBucket;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, 70, TimeUnit.SECONDS);
            }
            return count == null ? 1L : count;
        } catch (Exception ex) {
            return incrementLocal(key, 70_000L);
        }
    }

    private long incrementViolation(String ipAddress) {
        String key = "ratelimit:violation:" + ipAddress;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
            }
            return count == null ? 1L : count;
        } catch (Exception ex) {
            return incrementLocal(key, 86_400_000L);
        }
    }

    private long incrementLocal(String key, long ttlMillis) {
        long now = System.currentTimeMillis();
        CounterWindow previous = localCounterMap.get(key);
        if (previous == null || previous.expireAtMillis <= now) {
            CounterWindow fresh = new CounterWindow(1L, now + ttlMillis);
            localCounterMap.put(key, fresh);
            return fresh.count;
        }
        previous.count++;
        return previous.count;
    }

    private static class CounterWindow {
        private long count;
        private final long expireAtMillis;

        private CounterWindow(long count, long expireAtMillis) {
            this.count = count;
            this.expireAtMillis = expireAtMillis;
        }
    }
}

