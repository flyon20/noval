package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.vo.AgentProviderCircuitStateVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tracks provider transient failures in a sliding window so a single blip cannot
 * trip the breaker, and hands out one half-open probe per cooldown half-life.
 *
 * <p>State is derived, never stored: the sorted set of failure timestamps is the
 * only source of truth. Three failures inside the window mean OPEN; once the most
 * recent failure is older than half the cooldown the profile becomes HALF_OPEN and
 * exactly one caller may probe it.
 */
@Service
public class KnowledgeAgentProviderCircuitShadowService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        KnowledgeAgentProviderCircuitShadowService.class
    );
    private static final String KEY_PREFIX = "noval:ai:provider-circuit:v1:";
    private static final String PROBE_SUFFIX = ":probe";
    /** US (0x1f) keeps profileKey and profileVersion from bleeding into each other. */
    private static final String IDENTITY_SEPARATOR = "";
    static final int FAILURE_THRESHOLD = 3;

    /**
     * Drops failures that fell out of the window, records this one, and reports how
     * many remain. Members carry a random suffix so simultaneous failures cannot
     * collapse into one sorted-set entry.
     */
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>(
        "redis.call('zremrangebyscore', KEYS[1], '-inf', ARGV[1]); "
            + "redis.call('zadd', KEYS[1], ARGV[2], ARGV[3]); "
            + "redis.call('pexpire', KEYS[1], ARGV[4]); "
            + "return redis.call('zcard', KEYS[1])",
        Long.class
    );

    /** Window failure count plus the newest failure timestamp, without mutating anything. */
    private static final DefaultRedisScript<List> READ_WINDOW_SCRIPT = new DefaultRedisScript<>(
        "local count = redis.call('zcount', KEYS[1], ARGV[1], '+inf'); "
            + "local newest = redis.call('zrange', KEYS[1], -1, -1, 'WITHSCORES'); "
            + "return {tostring(count), tostring(newest[2] or 0)}",
        List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Autowired
    public KnowledgeAgentProviderCircuitShadowService(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this(redisTemplateProvider.getIfAvailable(), Clock.systemUTC());
    }

    KnowledgeAgentProviderCircuitShadowService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    KnowledgeAgentProviderCircuitShadowService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public AgentProviderCircuitStateVO recordTransientFailure(String profileKey,
                                                               String profileVersion,
                                                               int cooldownSeconds) {
        if (redisTemplate == null) {
            return closed(profileKey, profileVersion);
        }
        long windowMillis = windowMillis(cooldownSeconds);
        long now = clock.millis();
        try {
            Long failureCount = redisTemplate.execute(
                RECORD_FAILURE_SCRIPT,
                Collections.singletonList(circuitKey(profileKey, profileVersion)),
                String.valueOf(now - windowMillis),
                String.valueOf(now),
                now + "-" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE),
                String.valueOf(windowMillis)
            );
            long observed = failureCount == null ? 0L : Math.max(0L, failureCount);
            return observed >= FAILURE_THRESHOLD
                ? state(profileKey, profileVersion, "OPEN", observed)
                : state(profileKey, profileVersion, "CLOSED", observed);
        } catch (RuntimeException ex) {
            LOGGER.debug("Provider circuit failure projection skipped: reason={}", ex.getMessage());
            return closed(profileKey, profileVersion);
        }
    }

    public AgentProviderCircuitStateVO recordSuccess(String profileKey, String profileVersion) {
        if (redisTemplate == null) {
            return closed(profileKey, profileVersion);
        }
        try {
            redisTemplate.delete(List.of(
                circuitKey(profileKey, profileVersion),
                probeKey(profileKey, profileVersion)
            ));
        } catch (RuntimeException ex) {
            LOGGER.debug("Provider circuit success projection skipped: reason={}", ex.getMessage());
        }
        return closed(profileKey, profileVersion);
    }

    public AgentProviderCircuitStateVO readState(String profileKey, String profileVersion) {
        return readState(profileKey, profileVersion, AiProviderCooldown.DEFAULT_SECONDS);
    }

    /**
     * Derives the breaker state. Never mutates, so it is safe to call from the
     * routing policy projection that the worker consumes.
     */
    public AgentProviderCircuitStateVO readState(String profileKey,
                                                 String profileVersion,
                                                 int cooldownSeconds) {
        if (redisTemplate == null) {
            return closed(profileKey, profileVersion);
        }
        long windowMillis = windowMillis(cooldownSeconds);
        long now = clock.millis();
        try {
            List<?> raw = redisTemplate.execute(
                READ_WINDOW_SCRIPT,
                Collections.singletonList(circuitKey(profileKey, profileVersion)),
                String.valueOf(now - windowMillis)
            );
            long failureCount = parseNonNegativeLong(elementAt(raw, 0));
            long newestFailureMillis = parseNonNegativeLong(elementAt(raw, 1));
            if (failureCount < FAILURE_THRESHOLD) {
                return state(profileKey, profileVersion, "CLOSED", failureCount);
            }
            boolean probeDue = newestFailureMillis > 0
                && now - newestFailureMillis >= windowMillis / 2;
            return state(profileKey, profileVersion, probeDue ? "HALF_OPEN" : "OPEN", failureCount);
        } catch (RuntimeException ex) {
            LOGGER.debug("Provider circuit state read skipped: reason={}", ex.getMessage());
            return closed(profileKey, profileVersion);
        }
    }

    /**
     * Claims the single half-open probe for this cooldown half-life.
     *
     * @return true when the caller may dispatch to the profile: the breaker is not
     *         OPEN, or it is HALF_OPEN and this caller won the probe.
     */
    public boolean tryAcquireDispatchPermit(String profileKey,
                                            String profileVersion,
                                            int cooldownSeconds) {
        if (redisTemplate == null) {
            return true;
        }
        AgentProviderCircuitStateVO observed = readState(profileKey, profileVersion, cooldownSeconds);
        if ("CLOSED".equals(observed.getState())) {
            return true;
        }
        if (!"HALF_OPEN".equals(observed.getState())) {
            return false;
        }
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                probeKey(profileKey, profileVersion),
                String.valueOf(clock.millis()),
                Duration.ofMillis(Math.max(1L, windowMillis(cooldownSeconds) / 2))
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            LOGGER.debug("Provider circuit probe claim skipped: reason={}", ex.getMessage());
            return true;
        }
    }

    private static long windowMillis(int cooldownSeconds) {
        int normalized = cooldownSeconds > 0 ? cooldownSeconds : AiProviderCooldown.DEFAULT_SECONDS;
        return normalized * 1000L;
    }

    private static String elementAt(List<?> raw, int index) {
        if (raw == null || raw.size() <= index) {
            return null;
        }
        Object value = raw.get(index);
        return value == null ? null : String.valueOf(value);
    }

    private static AgentProviderCircuitStateVO closed(String profileKey, String profileVersion) {
        return state(profileKey, profileVersion, "CLOSED", 0L);
    }

    private static AgentProviderCircuitStateVO state(String profileKey,
                                                     String profileVersion,
                                                     String state,
                                                     long failureCount) {
        AgentProviderCircuitStateVO vo = new AgentProviderCircuitStateVO();
        vo.setProfileKey(profileKey);
        vo.setProfileVersion(profileVersion);
        vo.setState(state);
        vo.setFailureCount(Math.max(0L, failureCount));
        return vo;
    }

    static String circuitKey(String profileKey, String profileVersion) {
        String identity = String.valueOf(profileKey) + IDENTITY_SEPARATOR + String.valueOf(profileVersion);
        return KEY_PREFIX + sha256(identity);
    }

    static String probeKey(String profileKey, String profileVersion) {
        return circuitKey(profileKey, profileVersion) + PROBE_SUFFIX;
    }

    private static long parseNonNegativeLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, (long) Double.parseDouble(value.trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Keeps the cooldown default in one place without depending on the config module. */
    static final class AiProviderCooldown {
        static final int DEFAULT_SECONDS = 60;

        private AiProviderCooldown() {
        }
    }
}
