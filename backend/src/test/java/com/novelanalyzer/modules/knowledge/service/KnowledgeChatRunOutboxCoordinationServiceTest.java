package com.novelanalyzer.modules.knowledge.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatRunOutboxCoordinationServiceTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldUseRedisWakeupAndDispatchLeaseWithoutStoringBusinessPayload() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(zSetOperations.add(eq(KnowledgeChatRunOutboxCoordinationService.WAKEUP_KEY),
            org.mockito.ArgumentMatchers.anyString(), anyDouble())).thenReturn(true);
        when(valueOperations.setIfAbsent(
            eq(KnowledgeChatRunOutboxCoordinationService.DISPATCH_LOCK_KEY),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(java.time.Duration.class)
        )).thenReturn(true);
        KnowledgeChatRunOutboxCoordinationService service =
            new KnowledgeChatRunOutboxCoordinationService(redisTemplate);

        service.signal();

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations).add(
            eq(KnowledgeChatRunOutboxCoordinationService.WAKEUP_KEY),
            tokenCaptor.capture(),
            anyDouble()
        );
        String wakeToken = tokenCaptor.getValue();
        assertThat(wakeToken).doesNotContain("run").doesNotContain("payload");
        when(zSetOperations.rangeByScore(
            eq(KnowledgeChatRunOutboxCoordinationService.WAKEUP_KEY),
            anyDouble(),
            anyDouble(),
            eq(0L),
            anyLong()
        )).thenReturn(Set.of(wakeToken));

        KnowledgeChatRunOutboxCoordinationService.WakeupSignal wakeup = service.currentWakeup();
        KnowledgeChatRunOutboxCoordinationService.DispatchLease lease = service.tryAcquireDispatchLease();

        assertThat(wakeup).isNotNull();
        assertThat(wakeup.tokens()).containsExactly(wakeToken);
        assertThat(lease).isNotNull();
        assertThat(lease.redisOwned()).isTrue();
        service.acknowledge(wakeup);
        service.releaseDispatchLease(lease);
        verify(zSetOperations).remove(
            KnowledgeChatRunOutboxCoordinationService.WAKEUP_KEY,
            wakeToken
        );
    }

    @Test
    void shouldFallBackToLocalWakeupAndLeaseWhenRedisIsUnavailable() {
        KnowledgeChatRunOutboxCoordinationService service =
            new KnowledgeChatRunOutboxCoordinationService((StringRedisTemplate) null);

        service.signal();
        KnowledgeChatRunOutboxCoordinationService.WakeupSignal wakeup = service.currentWakeup();
        KnowledgeChatRunOutboxCoordinationService.DispatchLease lease = service.tryAcquireDispatchLease();

        assertThat(wakeup).isNotNull();
        assertThat(lease).isNotNull();
        assertThat(lease.redisOwned()).isFalse();
        service.acknowledge(wakeup);
        service.releaseDispatchLease(lease);
        assertThat(service.currentWakeup()).isNull();
        assertThat(service.tryAcquireDispatchLease()).isNotNull();
    }

    @Test
    void shouldSignalOnlyAfterAnActiveTransactionCommits() {
        KnowledgeChatRunOutboxCoordinationService service =
            new KnowledgeChatRunOutboxCoordinationService((StringRedisTemplate) null);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.signalAfterCommit();

        assertThat(service.currentWakeup()).isNull();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronization synchronization =
            TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();
        assertThat(service.currentWakeup()).isNotNull();
    }
}
