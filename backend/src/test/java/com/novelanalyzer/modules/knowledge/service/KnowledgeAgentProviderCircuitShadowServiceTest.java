package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.vo.AgentProviderCircuitStateVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sliding-window breaker contract: three failures inside the cooldown window open it,
 * the age of the newest failure decides when a probe is due, and exactly one caller
 * wins that probe.
 */
class KnowledgeAgentProviderCircuitShadowServiceTest {

    private static final String PROFILE = "private-profile";
    private static final String VERSION = "a".repeat(64);
    private static final int COOLDOWN_SECONDS = 60;
    private static final long WINDOW_MILLIS = COOLDOWN_SECONDS * 1000L;
    private static final long NOW_MILLIS = 1_700_000_000_000L;
    private static final Clock CLOCK =
        Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC);

    private static KnowledgeAgentProviderCircuitShadowService serviceFor(FakeRedis redis) {
        return new KnowledgeAgentProviderCircuitShadowService(redis, CLOCK);
    }

    @Test
    void shouldStayClosedUntilTheThirdFailureInsideTheWindow() {
        FakeRedis redis = new FakeRedis();
        KnowledgeAgentProviderCircuitShadowService service = serviceFor(redis);

        redis.recordedFailureCount = 1L;
        assertThat(service.recordTransientFailure(PROFILE, VERSION, COOLDOWN_SECONDS).getState())
            .isEqualTo("CLOSED");
        redis.recordedFailureCount = 2L;
        assertThat(service.recordTransientFailure(PROFILE, VERSION, COOLDOWN_SECONDS).getState())
            .isEqualTo("CLOSED");

        redis.recordedFailureCount = 3L;
        AgentProviderCircuitStateVO opened =
            service.recordTransientFailure(PROFILE, VERSION, COOLDOWN_SECONDS);

        assertThat(opened.getState()).isEqualTo("OPEN");
        assertThat(opened.getFailureCount()).isEqualTo(3L);
    }

    @Test
    void shouldTrimTheWindowAndExpireItWithTheCooldown() {
        FakeRedis redis = new FakeRedis();
        redis.recordedFailureCount = 1L;

        serviceFor(redis).recordTransientFailure(PROFILE, VERSION, 90);

        List<Object> args = redis.recordInvocations.get(0);
        // Everything older than the window is dropped before this failure is scored at now.
        assertThat(args.get(0)).isEqualTo(String.valueOf(NOW_MILLIS - 90_000L));
        assertThat(args.get(1)).isEqualTo(String.valueOf(NOW_MILLIS));
        // A random suffix stops simultaneous failures collapsing into one member.
        assertThat(String.valueOf(args.get(2))).startsWith(NOW_MILLIS + "-");
        assertThat(args.get(3)).isEqualTo("90000");
        assertThat(redis.scriptKeys).singleElement().asString()
            .matches("noval:ai:provider-circuit:v1:[0-9a-f]{64}")
            .doesNotContain(PROFILE)
            .doesNotContain("aaaa");
    }

    @Test
    void shouldKeepProfileKeyAndVersionFromColliding() {
        assertThat(KnowledgeAgentProviderCircuitShadowService.circuitKey("a", "bc"))
            .isNotEqualTo(KnowledgeAgentProviderCircuitShadowService.circuitKey("ab", "c"));
    }

    @Test
    void shouldReadClosedWhileTheWindowHoldsFewerThanThreeFailures() {
        FakeRedis redis = new FakeRedis();
        redis.windowFailureCount = 2L;
        redis.newestFailureMillis = NOW_MILLIS;

        AgentProviderCircuitStateVO state = serviceFor(redis).readState(PROFILE, VERSION);

        assertThat(state.getState()).isEqualTo("CLOSED");
        assertThat(state.getFailureCount()).isEqualTo(2L);
    }

    @Test
    void shouldReadOpenWhileTheNewestFailureIsStillFresh() {
        FakeRedis redis = new FakeRedis();
        redis.windowFailureCount = 3L;
        redis.newestFailureMillis = NOW_MILLIS - (WINDOW_MILLIS / 2) + 1;

        AgentProviderCircuitStateVO state =
            serviceFor(redis).readState(PROFILE, VERSION, COOLDOWN_SECONDS);

        assertThat(state.getState()).isEqualTo("OPEN");
    }

    @Test
    void shouldReadHalfOpenOnceTheNewestFailureIsOlderThanHalfTheCooldown() {
        FakeRedis redis = new FakeRedis();
        redis.windowFailureCount = 3L;
        redis.newestFailureMillis = NOW_MILLIS - (WINDOW_MILLIS / 2);

        AgentProviderCircuitStateVO state =
            serviceFor(redis).readState(PROFILE, VERSION, COOLDOWN_SECONDS);

        assertThat(state.getState()).isEqualTo("HALF_OPEN");
    }

    @Test
    void shouldGrantDispatchWhileClosedAndRefuseItWhileOpen() {
        FakeRedis closed = new FakeRedis();
        closed.windowFailureCount = 2L;
        closed.newestFailureMillis = NOW_MILLIS;

        FakeRedis open = new FakeRedis();
        open.windowFailureCount = 3L;
        open.newestFailureMillis = NOW_MILLIS;

        assertThat(serviceFor(closed).tryAcquireDispatchPermit(PROFILE, VERSION, COOLDOWN_SECONDS))
            .isTrue();
        assertThat(serviceFor(open).tryAcquireDispatchPermit(PROFILE, VERSION, COOLDOWN_SECONDS))
            .isFalse();
        // A closed breaker must never burn a probe slot.
        verify(closed.values, never()).setIfAbsent(any(), any(), any());
    }

    @Test
    void shouldGrantExactlyOneHalfOpenProbePerCooldownHalfLife() {
        FakeRedis redis = new FakeRedis();
        redis.windowFailureCount = 3L;
        redis.newestFailureMillis = NOW_MILLIS - (WINDOW_MILLIS / 2);
        when(redis.values.setIfAbsent(any(), any(), any(Duration.class)))
            .thenReturn(Boolean.TRUE, Boolean.FALSE);
        KnowledgeAgentProviderCircuitShadowService service = serviceFor(redis);

        assertThat(service.tryAcquireDispatchPermit(PROFILE, VERSION, COOLDOWN_SECONDS)).isTrue();
        assertThat(service.tryAcquireDispatchPermit(PROFILE, VERSION, COOLDOWN_SECONDS)).isFalse();

        // The probe lease expires with the half-life, so the next window offers a new one.
        verify(redis.values, times(2)).setIfAbsent(
            eq(KnowledgeAgentProviderCircuitShadowService.probeKey(PROFILE, VERSION)),
            eq(String.valueOf(NOW_MILLIS)),
            eq(Duration.ofMillis(WINDOW_MILLIS / 2))
        );
    }

    @Test
    void shouldClearBothTheWindowAndTheProbeOnSuccess() {
        FakeRedis redis = new FakeRedis();

        AgentProviderCircuitStateVO state = serviceFor(redis).recordSuccess(PROFILE, VERSION);

        assertThat(state.getState()).isEqualTo("CLOSED");
        assertThat(state.getFailureCount()).isZero();
        // Leaving the probe key behind would silence the next half-open window entirely.
        assertThat(redis.deletedKeys).containsExactly(
            KnowledgeAgentProviderCircuitShadowService.circuitKey(PROFILE, VERSION),
            KnowledgeAgentProviderCircuitShadowService.probeKey(PROFILE, VERSION)
        );
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() {
        FakeRedis redis = new FakeRedis();
        redis.failure = new IllegalStateException("redis unavailable");
        KnowledgeAgentProviderCircuitShadowService service = serviceFor(redis);

        assertThat(service.recordTransientFailure(PROFILE, VERSION, COOLDOWN_SECONDS).getState())
            .isEqualTo("CLOSED");
        assertThat(service.readState(PROFILE, VERSION, COOLDOWN_SECONDS).getState())
            .isEqualTo("CLOSED");
        // A broken breaker must not become an outage of its own.
        assertThat(service.tryAcquireDispatchPermit(PROFILE, VERSION, COOLDOWN_SECONDS)).isTrue();
    }

    @Test
    void shouldFailOpenWithoutARedisTemplate() {
        KnowledgeAgentProviderCircuitShadowService service =
            new KnowledgeAgentProviderCircuitShadowService(null, CLOCK);

        assertThat(service.readState(PROFILE, VERSION).getState()).isEqualTo("CLOSED");
        assertThat(service.recordTransientFailure(PROFILE, VERSION, COOLDOWN_SECONDS).getState())
            .isEqualTo("CLOSED");
        assertThat(service.tryAcquireDispatchPermit(PROFILE, VERSION, COOLDOWN_SECONDS)).isTrue();
    }

    /**
     * Deterministic stand-in for Redis. The two Lua scripts are told apart by their ARGV
     * count — the recording script passes four, the read-only one passes a single window
     * floor — so each can be answered without leaning on Mockito varargs matching.
     */
    private static final class FakeRedis extends StringRedisTemplate {

        private final List<String> scriptKeys = new ArrayList<>();
        private final List<List<Object>> recordInvocations = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private final ValueOperations<String, String> values = mock(ValueOperations.class);

        private long recordedFailureCount;
        private long windowFailureCount;
        private long newestFailureMillis;
        private RuntimeException failure;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            scriptKeys.addAll(keys);
            if (failure != null) {
                throw failure;
            }
            if (args.length == 1) {
                return (T) List.of(
                    String.valueOf(windowFailureCount),
                    String.valueOf(newestFailureMillis)
                );
            }
            recordInvocations.add(List.of(args));
            return (T) Long.valueOf(recordedFailureCount);
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return values;
        }

        @Override
        public Long delete(Collection<String> keys) {
            deletedKeys.addAll(keys);
            return (long) keys.size();
        }
    }
}
