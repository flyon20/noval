package com.novelanalyzer.modules.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeChatRunOutboxCoordinationService {

    static final String WAKEUP_KEY = "noval:knowledge:chat-run:outbox:wakeup";
    static final String DISPATCH_LOCK_KEY = "noval:knowledge:chat-run:outbox:dispatch-lock";
    private static final Duration WAKEUP_TTL = Duration.ofMinutes(5);
    private static final Duration DISPATCH_LOCK_TTL = Duration.ofMinutes(2);
    private static final int MAX_WAKEUP_BATCH = 100;
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunOutboxCoordinationService.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final ConcurrentHashMap<String, Long> localWakeups = new ConcurrentHashMap<>();
    private final AtomicBoolean localDispatching = new AtomicBoolean(false);

    @Autowired
    public KnowledgeChatRunOutboxCoordinationService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable(), Clock.systemUTC());
    }

    public KnowledgeChatRunOutboxCoordinationService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    KnowledgeChatRunOutboxCoordinationService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void signalAfterCommit() {
        signalAfterCommit(Duration.ZERO);
    }

    public void signalAfterCommit(Duration delay) {
        Runnable signal = () -> signal(delay);
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal.run();
                }
            });
            return;
        }
        signal.run();
    }

    public void signal() {
        signal(Duration.ZERO);
    }

    public void signal(Duration delay) {
        Duration safeDelay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        long dueAtMillis = Math.addExact(clock.millis(), safeDelay.toMillis());
        String token = UUID.randomUUID().toString();
        localWakeups.put(token, dueAtMillis);
        if (redisTemplate == null) {
            return;
        }
        try {
            Boolean stored = redisTemplate.opsForZSet().add(WAKEUP_KEY, token, dueAtMillis);
            redisTemplate.expire(WAKEUP_KEY, WAKEUP_TTL);
            if (Boolean.TRUE.equals(stored)) {
                localWakeups.remove(token, dueAtMillis);
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run outbox Redis wakeup unavailable: {}", ex.getMessage());
        }
    }

    public WakeupSignal currentWakeup() {
        long nowMillis = clock.millis();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (var entry : localWakeups.entrySet()) {
            if (entry.getValue() <= nowMillis) {
                tokens.add(entry.getKey());
                if (tokens.size() >= MAX_WAKEUP_BATCH) {
                    break;
                }
            }
        }
        if (redisTemplate != null && tokens.size() < MAX_WAKEUP_BATCH) {
            try {
                Set<String> redisTokens = redisTemplate.opsForZSet().rangeByScore(
                    WAKEUP_KEY,
                    0,
                    nowMillis,
                    0,
                    MAX_WAKEUP_BATCH - tokens.size()
                );
                if (redisTokens != null) {
                    tokens.addAll(redisTokens);
                }
            } catch (RuntimeException ex) {
                LOGGER.debug("chat run outbox Redis wakeup read unavailable: {}", ex.getMessage());
            }
        }
        return tokens.isEmpty() ? null : new WakeupSignal(Set.copyOf(tokens));
    }

    public DispatchLease tryAcquireDispatchLease() {
        if (!localDispatching.compareAndSet(false, true)) {
            return null;
        }
        if (redisTemplate == null) {
            return new DispatchLease(null, false);
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                DISPATCH_LOCK_KEY,
                token,
                DISPATCH_LOCK_TTL
            );
            if (Boolean.TRUE.equals(acquired)) {
                return new DispatchLease(token, true);
            }
            localDispatching.set(false);
            return null;
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run outbox Redis dispatch lock unavailable: {}", ex.getMessage());
            return new DispatchLease(null, false);
        }
    }

    public void acknowledge(WakeupSignal signal) {
        if (signal == null || signal.tokens().isEmpty()) {
            return;
        }
        for (String token : signal.tokens()) {
            localWakeups.remove(token);
        }
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(WAKEUP_KEY, signal.tokens().toArray());
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run outbox Redis wakeup acknowledgement unavailable: {}", ex.getMessage());
        }
    }

    public void releaseDispatchLease(DispatchLease lease) {
        try {
            if (lease != null && lease.redisOwned() && redisTemplate != null) {
                redisTemplate.execute(
                    COMPARE_AND_DELETE_SCRIPT,
                    Collections.singletonList(DISPATCH_LOCK_KEY),
                    lease.redisToken()
                );
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run outbox Redis dispatch lock release unavailable: {}", ex.getMessage());
        } finally {
            localDispatching.set(false);
        }
    }

    public record WakeupSignal(Set<String> tokens) {
    }

    public record DispatchLease(String redisToken, boolean redisOwned) {
    }
}
