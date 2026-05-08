package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.SmsAuthProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class SmsRiskControlService {

    private static final String SMS_INTERVAL_PREFIX = "sms:interval:";
    private static final String SMS_PHONE_WINDOW_PREFIX = "sms:window:phone:";
    private static final String SMS_IP_WINDOW_PREFIX = "sms:window:ip:";
    private static final String SMS_BIZ_WINDOW_PREFIX = "sms:window:biz:";

    private final StringRedisTemplate stringRedisTemplate;
    private final SmsAuthProperties smsAuthProperties;
    private final Map<String, Long> localExpiryMap = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> localCounterMap = new ConcurrentHashMap<>();

    public SmsRiskControlService(StringRedisTemplate stringRedisTemplate,
                                 SmsAuthProperties smsAuthProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.smsAuthProperties = smsAuthProperties;
    }

    public void assertCanSend(String phone, String bizType, String sendIp, long intervalSeconds) {
        String normalizedPhone = normalize(phone);
        String normalizedBizType = normalize(bizType);
        String normalizedIp = normalize(sendIp);

        try {
            assertInterval(normalizedPhone, intervalSeconds);
            assertWindow(SMS_PHONE_WINDOW_PREFIX + normalizedPhone, smsAuthProperties.getPhoneWindowSeconds(), smsAuthProperties.getPhoneMaxAttempts());
            if (!normalizedIp.isEmpty()) {
                assertWindow(SMS_IP_WINDOW_PREFIX + normalizedIp, smsAuthProperties.getIpWindowSeconds(), smsAuthProperties.getIpMaxAttempts());
            }
            if (!normalizedBizType.isEmpty()) {
                assertWindow(
                    SMS_BIZ_WINDOW_PREFIX + normalizedBizType + ":" + normalizedPhone,
                    smsAuthProperties.getBizWindowSeconds(),
                    smsAuthProperties.getBizMaxAttempts()
                );
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ignored) {
            assertIntervalLocal(normalizedPhone, intervalSeconds);
            assertWindowLocal(SMS_PHONE_WINDOW_PREFIX + normalizedPhone, smsAuthProperties.getPhoneWindowSeconds(), smsAuthProperties.getPhoneMaxAttempts());
            if (!normalizedIp.isEmpty()) {
                assertWindowLocal(SMS_IP_WINDOW_PREFIX + normalizedIp, smsAuthProperties.getIpWindowSeconds(), smsAuthProperties.getIpMaxAttempts());
            }
            if (!normalizedBizType.isEmpty()) {
                assertWindowLocal(
                    SMS_BIZ_WINDOW_PREFIX + normalizedBizType + ":" + normalizedPhone,
                    smsAuthProperties.getBizWindowSeconds(),
                    smsAuthProperties.getBizMaxAttempts()
                );
            }
        }
    }

    private void assertInterval(String phone, long intervalSeconds) {
        String key = SMS_INTERVAL_PREFIX + phone;
        Boolean exists = stringRedisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            throw tooManyRequests();
        }
        stringRedisTemplate.opsForValue().set(key, "1", intervalSeconds, TimeUnit.SECONDS);
    }

    private void assertWindow(String key, long windowSeconds, int maxAttempts) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        if (count != null && count > maxAttempts) {
            throw tooManyRequests();
        }
    }

    private void assertIntervalLocal(String phone, long intervalSeconds) {
        String key = SMS_INTERVAL_PREFIX + phone;
        long now = Instant.now().toEpochMilli();
        Long expireAt = localExpiryMap.get(key);
        if (expireAt != null && expireAt > now) {
            throw tooManyRequests();
        }
        localExpiryMap.put(key, now + TimeUnit.SECONDS.toMillis(intervalSeconds));
    }

    private void assertWindowLocal(String key, long windowSeconds, int maxAttempts) {
        long now = Instant.now().toEpochMilli();
        WindowCounter counter = localCounterMap.compute(key, (ignoredKey, existing) -> {
            if (existing == null || existing.expiresAt <= now) {
                return new WindowCounter(1, now + TimeUnit.SECONDS.toMillis(windowSeconds));
            }
            existing.count += 1;
            return existing;
        });
        if (counter.count > maxAttempts) {
            throw tooManyRequests();
        }
    }

    private BusinessException tooManyRequests() {
        return new BusinessException(ResultCode.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static class WindowCounter {
        private int count;
        private final long expiresAt;

        private WindowCounter(int count, long expiresAt) {
            this.count = count;
            this.expiresAt = expiresAt;
        }
    }
}
