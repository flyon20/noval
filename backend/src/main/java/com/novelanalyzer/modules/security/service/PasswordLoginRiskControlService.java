package com.novelanalyzer.modules.security.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.SecurityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class PasswordLoginRiskControlService {

    private static final String PHONE_FAIL_PREFIX = "auth:pwd:fail:phone:";
    private static final String IP_FAIL_PREFIX = "auth:pwd:fail:ip:";
    private static final String PHONE_IP_FAIL_PREFIX = "auth:pwd:fail:phoneip:";
    private static final String PHONE_COOLDOWN_PREFIX = "auth:pwd:cooldown:phone:";
    private static final String IP_COOLDOWN_PREFIX = "auth:pwd:cooldown:ip:";
    private static final String PHONE_IP_COOLDOWN_PREFIX = "auth:pwd:cooldown:phoneip:";

    private final StringRedisTemplate stringRedisTemplate;
    private final SecurityProperties securityProperties;
    private final Map<String, CounterWindow> localCounterMap = new ConcurrentHashMap<>();

    public PasswordLoginRiskControlService(StringRedisTemplate stringRedisTemplate,
                                           SecurityProperties securityProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.securityProperties = securityProperties;
    }

    public void assertAllowed(String phone, String ipAddress) {
        String normalizedPhone = normalize(phone);
        String normalizedIp = normalize(ipAddress);
        try {
            if (hasCooldown(PHONE_COOLDOWN_PREFIX + normalizedPhone)
                || hasCooldown(IP_COOLDOWN_PREFIX + normalizedIp)
                || hasCooldown(PHONE_IP_COOLDOWN_PREFIX + normalizedPhone + ":" + normalizedIp)) {
                throw blocked();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ignored) {
            if (hasLocalCooldown(PHONE_COOLDOWN_PREFIX + normalizedPhone)
                || hasLocalCooldown(IP_COOLDOWN_PREFIX + normalizedIp)
                || hasLocalCooldown(PHONE_IP_COOLDOWN_PREFIX + normalizedPhone + ":" + normalizedIp)) {
                throw blocked();
            }
        }
    }

    public void recordFailure(String phone, String ipAddress) {
        String normalizedPhone = normalize(phone);
        String normalizedIp = normalize(ipAddress);
        try {
            long phoneFailures = increment(PHONE_FAIL_PREFIX + normalizedPhone, securityProperties.getPasswordLoginPhoneWindowSeconds());
            long ipFailures = increment(IP_FAIL_PREFIX + normalizedIp, securityProperties.getPasswordLoginIpWindowSeconds());
            long phoneIpFailures = increment(PHONE_IP_FAIL_PREFIX + normalizedPhone + ":" + normalizedIp,
                securityProperties.getPasswordLoginPhoneIpWindowSeconds());

            if (phoneFailures >= securityProperties.getPasswordLoginPhoneMaxFailures()) {
                setCooldown(PHONE_COOLDOWN_PREFIX + normalizedPhone);
            }
            if (ipFailures >= securityProperties.getPasswordLoginIpMaxFailures()) {
                setCooldown(IP_COOLDOWN_PREFIX + normalizedIp);
            }
            if (phoneIpFailures >= securityProperties.getPasswordLoginPhoneIpMaxFailures()) {
                setCooldown(PHONE_IP_COOLDOWN_PREFIX + normalizedPhone + ":" + normalizedIp);
            }
        } catch (Exception ignored) {
            long phoneFailures = incrementLocal(PHONE_FAIL_PREFIX + normalizedPhone,
                TimeUnit.SECONDS.toMillis(securityProperties.getPasswordLoginPhoneWindowSeconds()));
            long ipFailures = incrementLocal(IP_FAIL_PREFIX + normalizedIp,
                TimeUnit.SECONDS.toMillis(securityProperties.getPasswordLoginIpWindowSeconds()));
            long phoneIpFailures = incrementLocal(PHONE_IP_FAIL_PREFIX + normalizedPhone + ":" + normalizedIp,
                TimeUnit.SECONDS.toMillis(securityProperties.getPasswordLoginPhoneIpWindowSeconds()));

            if (phoneFailures >= securityProperties.getPasswordLoginPhoneMaxFailures()) {
                setLocalCooldown(PHONE_COOLDOWN_PREFIX + normalizedPhone);
            }
            if (ipFailures >= securityProperties.getPasswordLoginIpMaxFailures()) {
                setLocalCooldown(IP_COOLDOWN_PREFIX + normalizedIp);
            }
            if (phoneIpFailures >= securityProperties.getPasswordLoginPhoneIpMaxFailures()) {
                setLocalCooldown(PHONE_IP_COOLDOWN_PREFIX + normalizedPhone + ":" + normalizedIp);
            }
        }
    }

    public void recordSuccess(String phone, String ipAddress) {
        String normalizedPhone = normalize(phone);
        String normalizedIp = normalize(ipAddress);
        try {
            stringRedisTemplate.delete(PHONE_FAIL_PREFIX + normalizedPhone);
            stringRedisTemplate.delete(PHONE_IP_FAIL_PREFIX + normalizedPhone + ":" + normalizedIp);
            stringRedisTemplate.delete(PHONE_COOLDOWN_PREFIX + normalizedPhone);
            stringRedisTemplate.delete(PHONE_IP_COOLDOWN_PREFIX + normalizedPhone + ":" + normalizedIp);
        } catch (Exception ignored) {
            localCounterMap.remove(PHONE_FAIL_PREFIX + normalizedPhone);
            localCounterMap.remove(PHONE_IP_FAIL_PREFIX + normalizedPhone + ":" + normalizedIp);
            localCounterMap.remove(PHONE_COOLDOWN_PREFIX + normalizedPhone);
            localCounterMap.remove(PHONE_IP_COOLDOWN_PREFIX + normalizedPhone + ":" + normalizedIp);
        }
    }

    private boolean hasCooldown(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    private void setCooldown(String key) {
        stringRedisTemplate.opsForValue().set(key, "1", securityProperties.getPasswordLoginCooldownSeconds(), TimeUnit.SECONDS);
    }

    private long increment(String key, long ttlSeconds) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
        return count == null ? 1L : count;
    }

    private boolean hasLocalCooldown(String key) {
        CounterWindow counter = localCounterMap.get(key);
        return counter != null && counter.expireAtMillis > System.currentTimeMillis();
    }

    private void setLocalCooldown(String key) {
        localCounterMap.put(key, new CounterWindow(1L,
            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(securityProperties.getPasswordLoginCooldownSeconds())));
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

    private BusinessException blocked() {
        return new BusinessException(ResultCode.TOO_MANY_REQUESTS, "密码登录过于频繁，请稍后再试");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
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
