package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatPersistenceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunEventService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunOutboxCoordinationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunRecoveryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatRunRecoveryServiceTest {

    @Test
    void shouldSkipFastMySqlDispatchWhenRedisHasNoWakeup() {
        KnowledgeChatRunOutboxCoordinationService coordinator = mock(
            KnowledgeChatRunOutboxCoordinationService.class
        );
        TestContext context = context(null, true, jdbcTemplate(), coordinator);
        insertRun(context.jdbcTemplate(), "run-no-wakeup", "PENDING", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-no-wakeup",
            "EXECUTE",
            "PENDING",
            0,
            Instant.now()
        );

        context.recoveryService().dispatchScheduledOutbox();

        verify(coordinator).currentWakeup();
        verify(coordinator, never()).tryAcquireDispatchLease();
        verify(context.queueService(), never()).publishExecute("run-no-wakeup");
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where run_id = 'run-no-wakeup'",
            String.class
        )).isEqualTo("PENDING");
    }

    @Test
    void shouldDispatchRedisSignaledOutboxThroughRabbitMq() {
        KnowledgeChatRunOutboxCoordinationService coordinator = mock(
            KnowledgeChatRunOutboxCoordinationService.class
        );
        KnowledgeChatRunOutboxCoordinationService.WakeupSignal wakeup =
            new KnowledgeChatRunOutboxCoordinationService.WakeupSignal(Set.of("wake-1"));
        KnowledgeChatRunOutboxCoordinationService.DispatchLease lease =
            new KnowledgeChatRunOutboxCoordinationService.DispatchLease("lease-1", true);
        when(coordinator.currentWakeup()).thenReturn(wakeup);
        when(coordinator.tryAcquireDispatchLease()).thenReturn(lease);
        TestContext context = context(null, true, jdbcTemplate(), coordinator);
        insertRun(context.jdbcTemplate(), "run-signaled", "PENDING", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-signaled",
            "EXECUTE",
            "PENDING",
            0,
            Instant.now()
        );
        when(context.queueService().publishExecute("run-signaled")).thenReturn(true);

        context.recoveryService().dispatchScheduledOutbox();

        verify(context.queueService()).publishExecute("run-signaled");
        verify(coordinator).acknowledge(wakeup);
        verify(coordinator).releaseDispatchLease(lease);
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where run_id = 'run-signaled'",
            String.class
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldUseLowFrequencyMySqlRecoveryWithoutRedisWakeup() {
        KnowledgeChatRunOutboxCoordinationService coordinator = mock(
            KnowledgeChatRunOutboxCoordinationService.class
        );
        KnowledgeChatRunOutboxCoordinationService.DispatchLease lease =
            new KnowledgeChatRunOutboxCoordinationService.DispatchLease("lease-2", true);
        when(coordinator.tryAcquireDispatchLease()).thenReturn(lease);
        TestContext context = context(null, true, jdbcTemplate(), coordinator);
        insertRun(context.jdbcTemplate(), "run-recovery", "PENDING", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-recovery",
            "EXECUTE",
            "PENDING",
            0,
            Instant.now()
        );
        when(context.queueService().publishExecute("run-recovery")).thenReturn(true);

        context.recoveryService().recoverScheduledOutbox();

        verify(coordinator, never()).currentWakeup();
        verify(context.queueService()).publishExecute("run-recovery");
        verify(coordinator).releaseDispatchLease(lease);
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where run_id = 'run-recovery'",
            String.class
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldDispatchPendingExecuteOutboxAndMarkItPublished() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-outbox", "PENDING", false, 0, 3);
        context.jdbcTemplate().update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(0, 'run-outbox', 0, 'EXECUTE', 'run:run-outbox:execute',
                'PENDING', 0, current_timestamp, current_timestamp, current_timestamp)
            """);
        when(context.queueService().publishExecute("run-outbox")).thenReturn(true);

        int dispatched = context.recoveryService().dispatchPendingOutbox(10);

        assertThat(dispatched).isEqualTo(1);
        verify(context.queueService()).publishExecute("run-outbox");
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where run_id = 'run-outbox'",
            String.class
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldReclaimStaleDispatchingOutboxAfterDispatcherCrash() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-stale-outbox", "PENDING", false, 0, 3);
        context.jdbcTemplate().update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(0, 'run-stale-outbox', 0, 'EXECUTE', 'run:stale:execute',
                'DISPATCHING', 1, current_timestamp, current_timestamp, ?)
            """,
            Timestamp.from(Instant.now().minusSeconds(60))
        );
        when(context.queueService().publishExecute("run-stale-outbox")).thenReturn(true);

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isEqualTo(1);
        assertThat(context.jdbcTemplate().queryForMap(
            "select status, attempt_count from ai_chat_run_outbox where run_id = 'run-stale-outbox'"
        )).containsEntry("status", "PUBLISHED")
            .containsEntry("attempt_count", 2);
    }

    @Test
    void shouldDispatchTerminalOutboxToPostProcessingPort() {
        KnowledgeChatRunRecoveryService.TerminalEventPort terminalPort = mock(
            KnowledgeChatRunRecoveryService.TerminalEventPort.class
        );
        TestContext context = context(terminalPort);
        insertRun(context.jdbcTemplate(), "run-terminal-outbox", "ANSWERED", false, 0, 3);
        context.jdbcTemplate().update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(0, 'run-terminal-outbox', 2, 'ANSWERED', 'run:terminal:answered',
                'PENDING', 0, current_timestamp, current_timestamp, current_timestamp)
            """);
        when(terminalPort.process(1L, "run-terminal-outbox", "ANSWERED")).thenAnswer(invocation -> {
            context.jdbcTemplate().update(
                "update ai_chat_run_outbox set status = 'PUBLISHED' where outbox_id = 1"
            );
            return true;
        });

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isEqualTo(1);
        verify(terminalPort).process(1L, "run-terminal-outbox", "ANSWERED");
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where outbox_id = 1",
            String.class
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldCloseLocalExecuteOutboxAfterRunLeavesPendingState() {
        TestContext context = context(null, false);
        insertRun(context.jdbcTemplate(), "run-local-outbox", "RUNNING", false, 0, 3);
        context.jdbcTemplate().update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(0, 'run-local-outbox', 1, 'EXECUTE', 'run:local:execute',
                'PENDING', 0, current_timestamp, current_timestamp, current_timestamp)
            """);

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isEqualTo(1);
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where run_id = 'run-local-outbox'",
            String.class
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldIsolateTerminalFailureAndContinueDispatchingTheBatch() {
        KnowledgeChatRunRecoveryService.TerminalEventPort terminalPort = mock(
            KnowledgeChatRunRecoveryService.TerminalEventPort.class
        );
        TestContext context = context(terminalPort);
        insertRun(context.jdbcTemplate(), "run-terminal-poison", "ANSWERED", false, 0, 3);
        insertRun(context.jdbcTemplate(), "run-after-poison", "PENDING", false, 0, 3);
        insertOutbox(context.jdbcTemplate(), "run-terminal-poison", "ANSWERED", "PENDING", 0, Instant.now());
        insertOutbox(context.jdbcTemplate(), "run-after-poison", "EXECUTE", "PENDING", 0, Instant.now());
        when(terminalPort.process(1L, "run-terminal-poison", "ANSWERED"))
            .thenThrow(new IllegalStateException("invalid result json"));
        when(context.queueService().publishExecute("run-after-poison")).thenReturn(true);
        Instant beforeDispatch = Instant.now();

        int dispatched = context.recoveryService().dispatchPendingOutbox(10);

        assertThat(dispatched).isEqualTo(1);
        Map<String, Object> failedOutbox = context.jdbcTemplate().queryForMap(
            "select status, attempt_count, last_error from ai_chat_run_outbox where outbox_id = 1"
        );
        assertThat(failedOutbox).containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 1)
            .hasEntrySatisfying("last_error", value -> assertThat(value).asString()
                .contains("terminal post-processing failed", "invalid result json"));
        assertThat(context.jdbcTemplate().queryForObject(
            "select available_at from ai_chat_run_outbox where outbox_id = 1",
            Timestamp.class
        ).toInstant()).isAfterOrEqualTo(beforeDispatch.plusSeconds(4));
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where outbox_id = 2",
            String.class
        )).isEqualTo("PUBLISHED");
        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();
        verify(terminalPort, times(1)).process(1L, "run-terminal-poison", "ANSWERED");
    }

    @Test
    void shouldMarkTerminalOutboxDeadAfterFifthFailedAttempt() {
        KnowledgeChatRunRecoveryService.TerminalEventPort terminalPort = mock(
            KnowledgeChatRunRecoveryService.TerminalEventPort.class
        );
        TestContext context = context(terminalPort);
        insertRun(context.jdbcTemplate(), "run-terminal-dead", "FAILED", false, 0, 3);
        insertOutbox(context.jdbcTemplate(), "run-terminal-dead", "FAILED", "PENDING", 4, Instant.now());
        when(terminalPort.process(1L, "run-terminal-dead", "FAILED"))
            .thenThrow(new IllegalArgumentException("corrupt request payload"));

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();
        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(context.jdbcTemplate().queryForMap(
            "select status, attempt_count, last_error from ai_chat_run_outbox where outbox_id = 1"
        )).containsEntry("status", "DEAD")
            .containsEntry("attempt_count", 5)
            .hasEntrySatisfying("last_error", value -> assertThat(value).asString()
                .contains("terminal post-processing failed", "corrupt request payload"));
        verify(terminalPort, times(1)).process(1L, "run-terminal-dead", "FAILED");
    }

    @Test
    void shouldRetryParkedTerminalOutboxAfterCooldown() {
        KnowledgeChatRunRecoveryService.TerminalEventPort terminalPort = mock(
            KnowledgeChatRunRecoveryService.TerminalEventPort.class
        );
        TestContext context = context(terminalPort);
        insertRun(context.jdbcTemplate(), "run-terminal-parked", "ANSWERED", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-terminal-parked",
            "ANSWERED",
            "DEAD",
            5,
            Instant.now().minusSeconds(16 * 60L)
        );
        context.jdbcTemplate().update(
            "update ai_chat_run_outbox set last_error = ? where outbox_id = 1",
            "x".repeat(1000)
        );
        when(terminalPort.process(1L, "run-terminal-parked", "ANSWERED"))
            .thenAnswer(invocation -> {
                context.jdbcTemplate().update(
                    "update ai_chat_run_outbox set status = 'PUBLISHED' where outbox_id = 1"
                );
                return true;
            });

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isEqualTo(1);

        assertThat(context.jdbcTemplate().queryForMap("""
            select status, attempt_count, dead_retry_count
            from ai_chat_run_outbox where outbox_id = 1
            """)).containsEntry("status", "PUBLISHED")
            .containsEntry("attempt_count", 1)
            .containsEntry("dead_retry_count", 1);
    }

    @Test
    void shouldSkipParkedTerminalUpdateWhenNoCandidateExists() {
        TrackingJdbcTemplate jdbcTemplate = new TrackingJdbcTemplate(dataSource());
        TestContext context = context(null, true, jdbcTemplate);

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(jdbcTemplate.parkedTerminalUpdateCount()).isZero();
    }

    @Test
    void shouldKeepTerminalOutboxDeadAfterMaximumParkedRecoveries() {
        KnowledgeChatRunRecoveryService.TerminalEventPort terminalPort = mock(
            KnowledgeChatRunRecoveryService.TerminalEventPort.class
        );
        TestContext context = context(terminalPort);
        insertRun(context.jdbcTemplate(), "run-terminal-permanent-dead", "ANSWERED", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-terminal-permanent-dead",
            "ANSWERED",
            "DEAD",
            5,
            Instant.now().minusSeconds(16 * 60L)
        );
        context.jdbcTemplate().update(
            "update ai_chat_run_outbox set dead_retry_count = 3 where outbox_id = 1"
        );

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(context.jdbcTemplate().queryForMap("""
            select status, attempt_count, dead_retry_count
            from ai_chat_run_outbox where outbox_id = 1
            """)).containsEntry("status", "DEAD")
            .containsEntry("attempt_count", 5)
            .containsEntry("dead_retry_count", 3);
        verify(terminalPort, times(0)).process(1L, "run-terminal-permanent-dead", "ANSWERED");
    }

    @Test
    void shouldMarkQueueOutboxDeadAfterFifthFailedAttemptWithQueueError() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-queue-dead", "PENDING", false, 0, 3);
        insertOutbox(context.jdbcTemplate(), "run-queue-dead", "EXECUTE", "PENDING", 4, Instant.now());
        when(context.queueService().publishExecute("run-queue-dead")).thenReturn(false);

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(context.jdbcTemplate().queryForMap(
            "select status, attempt_count, last_error from ai_chat_run_outbox where outbox_id = 1"
        )).containsEntry("status", "DEAD")
            .containsEntry("attempt_count", 5)
            .hasEntrySatisfying("last_error", value -> assertThat(value).asString()
                .contains("queue publish failed")
                .doesNotContain("terminal post-processing failed"));
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run where run_id = 'run-queue-dead'",
            String.class
        )).isEqualTo("FAILED");
        assertThat(context.jdbcTemplate().queryForList(
            "select event_type from ai_chat_run_event where run_id = 'run-queue-dead' order by sequence_no",
            String.class
        )).containsExactly("FAILED");
    }

    @Test
    void shouldDeadLetterExhaustedStaleDispatchingOutboxWithoutReclaimingIt() {
        KnowledgeChatRunRecoveryService.TerminalEventPort terminalPort = mock(
            KnowledgeChatRunRecoveryService.TerminalEventPort.class
        );
        TestContext context = context(terminalPort);
        insertRun(context.jdbcTemplate(), "run-stale-dead", "ANSWERED", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-stale-dead",
            "ANSWERED",
            "DISPATCHING",
            5,
            Instant.now().minusSeconds(60)
        );

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(context.jdbcTemplate().queryForMap(
            "select status, attempt_count, last_error from ai_chat_run_outbox where outbox_id = 1"
        )).containsEntry("status", "DEAD")
            .containsEntry("attempt_count", 5)
            .hasEntrySatisfying("last_error", value -> assertThat(value).asString()
                .contains("terminal post-processing failed", "maximum attempts reached"));
        verify(terminalPort, times(0)).process(1L, "run-stale-dead", "ANSWERED");
    }

    @Test
    void shouldRecoverPublishedExecuteOutboxForAnOrphanedPendingRunWithBackoff() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-orphaned-pending", "PENDING", false, 0, 3);
        context.jdbcTemplate().update(
            "update ai_chat_run set update_time = ? where run_id = 'run-orphaned-pending'",
            Timestamp.from(Instant.now().minusSeconds(300))
        );
        insertOutbox(
            context.jdbcTemplate(),
            "run-orphaned-pending",
            "EXECUTE",
            "PUBLISHED",
            1,
            Instant.now().minusSeconds(300)
        );
        context.jdbcTemplate().update(
            "update ai_chat_run_outbox set published_at = ? where outbox_id = 1",
            Timestamp.from(Instant.now().minusSeconds(300))
        );
        Instant beforeRecovery = Instant.now();

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        Map<String, Object> recovered = context.jdbcTemplate().queryForMap("""
            select status, attempt_count, available_at, published_at, last_error
            from ai_chat_run_outbox where outbox_id = 1
            """);
        assertThat(recovered).containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 1)
            .containsEntry("published_at", null)
            .hasEntrySatisfying("last_error", value -> assertThat(value).asString()
                .contains("orphaned PENDING run", "published EXECUTE"));
        assertThat(((Timestamp) recovered.get("available_at")).toInstant())
            .isAfterOrEqualTo(beforeRecovery.plusSeconds(4));
        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();
        verify(context.queueService(), times(0)).publishExecute("run-orphaned-pending");
    }

    @Test
    void shouldNotRecoverAdmissionDeferredPendingExecutionIntoAHotLoop() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-admission-deferred", "PENDING", false, 0, 3);
        context.jdbcTemplate().update(
            "update ai_chat_run set update_time = ? where run_id = 'run-admission-deferred'",
            Timestamp.from(Instant.now().minusSeconds(300))
        );
        insertOutbox(
            context.jdbcTemplate(),
            "run-admission-deferred",
            "EXECUTE",
            "PUBLISHED",
            1,
            Instant.now().minusSeconds(300)
        );
        insertOutbox(
            context.jdbcTemplate(),
            "run-admission-deferred",
            "EXECUTE",
            "PENDING",
            1,
            Instant.now()
        );
        Timestamp deferredUntil = Timestamp.from(Instant.now().plusSeconds(60));
        context.jdbcTemplate().update("""
            update ai_chat_run_outbox
            set available_at = ?, last_error = 'admission deferred'
            where outbox_id = 2
            """, deferredUntil);

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();
        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        Map<String, Object> deferred = context.jdbcTemplate().queryForMap("""
            select status, attempt_count, available_at, last_error
            from ai_chat_run_outbox where outbox_id = 2
            """);
        assertThat(deferred).containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 1)
            .containsEntry("last_error", "admission deferred");
        assertThat(java.time.Duration.between(
            deferredUntil.toInstant(),
            ((Timestamp) deferred.get("available_at")).toInstant()
        ).abs()).isLessThanOrEqualTo(java.time.Duration.ofMillis(1));
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run_outbox where outbox_id = 1",
            String.class
        )).isEqualTo("PUBLISHED");
        verify(context.queueService(), times(0)).publishExecute("run-admission-deferred");
    }

    @Test
    void shouldDeadLetterExhaustedPublishedExecuteOutboxForAnOrphanedPendingRun() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-orphaned-exhausted", "PENDING", false, 0, 3);
        context.jdbcTemplate().update(
            "update ai_chat_run set update_time = ? where run_id = 'run-orphaned-exhausted'",
            Timestamp.from(Instant.now().minusSeconds(300))
        );
        insertOutbox(
            context.jdbcTemplate(),
            "run-orphaned-exhausted",
            "EXECUTE",
            "PUBLISHED",
            5,
            Instant.now().minusSeconds(300)
        );

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();
        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(context.jdbcTemplate().queryForMap("""
            select status, attempt_count, last_error from ai_chat_run_outbox where outbox_id = 1
            """)).containsEntry("status", "DEAD")
            .containsEntry("attempt_count", 5)
            .hasEntrySatisfying("last_error", value -> assertThat(value).asString()
                .contains("queue publish failed", "maximum attempts reached"));
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run where run_id = 'run-orphaned-exhausted'",
            String.class
        )).isEqualTo("FAILED");
        verify(context.queueService(), times(0)).publishExecute("run-orphaned-exhausted");
    }

    @Test
    void shouldFinalizeHistoricalDeadExecuteOutboxRunOnNextScan() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-historical-dead", "PENDING", false, 0, 3);
        insertOutbox(
            context.jdbcTemplate(),
            "run-historical-dead",
            "EXECUTE",
            "DEAD",
            5,
            Instant.now().minusSeconds(60)
        );

        assertThat(context.recoveryService().dispatchPendingOutbox(10)).isZero();

        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run where run_id = 'run-historical-dead'",
            String.class
        )).isEqualTo("FAILED");
    }

    @Test
    void shouldRecoverExpiredRunningLeaseToNextAttemptAndExecuteOutbox() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-expired", "RUNNING", false, 0, 3);
        context.jdbcTemplate().update("""
            update ai_chat_run
            set lease_owner = 'dead-worker', fencing_token = 1,
                lease_expires_at = ?, heartbeat_at = ?, execution_mode = 'DEEP'
            where run_id = 'run-expired'
            """,
            Timestamp.from(Instant.now().minusSeconds(5)),
            Timestamp.from(Instant.now().minusSeconds(10))
        );

        int recovered = context.recoveryService().recoverExpiredRuns(10);

        assertThat(recovered).isEqualTo(1);
        assertThat(context.jdbcTemplate().queryForMap(
            "select status, attempt_no, lease_owner from ai_chat_run where run_id = 'run-expired'"
        )).containsEntry("status", "FAILED")
            .containsEntry("attempt_no", 1)
            .containsEntry("lease_owner", null);
        Map<String, Object> child = context.jdbcTemplate().queryForMap("""
            select run_id, status, retry_count, attempt_no, parent_run_id, trigger_message_id
            from ai_chat_run where parent_run_id = 'run-expired'
            """);
        assertThat(child).containsEntry("status", "PENDING")
            .containsEntry("retry_count", 1)
            .containsEntry("attempt_no", 2)
            .containsEntry("parent_run_id", "run-expired");
        assertThat(context.jdbcTemplate().queryForList(
            "select event_type from ai_chat_run_event where run_id = 'run-expired' order by sequence_no",
            String.class
        )).containsExactly("FAILED");
        assertThat(context.jdbcTemplate().queryForList(
            "select event_type from ai_chat_run_event where run_id = ? order by sequence_no",
            String.class,
            child.get("run_id")
        )).containsExactly("EXECUTE");
    }

    @Test
    void shouldFinalizeStaleCancellingRunWithoutLiveLease() {
        TestContext context = context();
        insertRun(context.jdbcTemplate(), "run-stale-cancel", "CANCELLING", true, 0, 3);
        context.jdbcTemplate().update(
            "update ai_chat_run set update_time = ?, lease_expires_at = null where run_id = 'run-stale-cancel'",
            Timestamp.from(Instant.now().minusSeconds(10))
        );

        int recovered = context.recoveryService().recoverExpiredRuns(10);

        assertThat(recovered).isEqualTo(1);
        assertThat(context.jdbcTemplate().queryForObject(
            "select status from ai_chat_run where run_id = 'run-stale-cancel'",
            String.class
        )).isEqualTo("CANCELLED");
        assertThat(context.jdbcTemplate().queryForList(
            "select event_type from ai_chat_run_event where run_id = 'run-stale-cancel' order by sequence_no",
            String.class
        )).containsExactly("CANCELLED");
    }

    private TestContext context() {
        return context(null, true);
    }

    private TestContext context(KnowledgeChatRunRecoveryService.TerminalEventPort terminalEventPort) {
        return context(terminalEventPort, true);
    }

    private TestContext context(KnowledgeChatRunRecoveryService.TerminalEventPort terminalEventPort,
                                boolean queueEnabled) {
        return context(terminalEventPort, queueEnabled, jdbcTemplate());
    }

    private TestContext context(KnowledgeChatRunRecoveryService.TerminalEventPort terminalEventPort,
                                 boolean queueEnabled,
                                 JdbcTemplate jdbcTemplate) {
        return context(terminalEventPort, queueEnabled, jdbcTemplate, null);
    }

    private TestContext context(KnowledgeChatRunRecoveryService.TerminalEventPort terminalEventPort,
                                boolean queueEnabled,
                                JdbcTemplate jdbcTemplate,
                                KnowledgeChatRunOutboxCoordinationService outboxCoordinationService) {
        createSchema(jdbcTemplate);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getChatRun().setQueueEnabled(queueEnabled);
        KnowledgeChatRunEventService eventService = new KnowledgeChatRunEventService(
            jdbcTemplate, transactionManager, objectMapper
        );
        KnowledgeChatPersistenceService persistenceService = new KnowledgeChatPersistenceService(
            jdbcTemplate,
            new KnowledgeConversationService(jdbcTemplate),
            transactionManager,
            objectMapper,
            properties,
            eventService
        );
        KnowledgeChatRunQueueService queueService = mock(KnowledgeChatRunQueueService.class);
        KnowledgeChatRunRecoveryService recoveryService = new KnowledgeChatRunRecoveryService(
            jdbcTemplate,
            persistenceService,
            queueService,
            terminalEventPort,
            properties,
            outboxCoordinationService
        );
        return new TestContext(jdbcTemplate, queueService, recoveryService);
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource());
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:chat-run-recovery-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            create table ai_chat_run (
                run_id varchar(64) primary key,
                user_id bigint not null,
                project_id bigint,
                conversation_id varchar(80) not null,
                question clob,
                request_json clob,
                status varchar(20) not null,
                progress_phase varchar(40), progress_message varchar(500), answer clob,
                result_json clob, trace_id varchar(80), source_count int default 0,
                error_message varchar(1000), cancel_requested boolean default false,
                retry_count int default 0, max_retries int default 3,
                started_at timestamp, finished_at timestamp, deleted tinyint default 0
            )
            """);
        DatabasePopulatorUtils.execute(
            new ResourceDatabasePopulator(
                new ClassPathResource("sql/phase18-agent-harness-conversation-rag-h2.sql")
            ),
            jdbcTemplate.getDataSource()
        );
    }

    private void insertRun(JdbcTemplate jdbcTemplate,
                           String runId,
                           String status,
                           boolean cancelRequested,
                           int retryCount,
                           int maxRetries) {
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                cancel_requested, retry_count, max_retries, attempt_no, execution_mode,
                deleted, queued_at, update_time
            ) values(?, 7, ?, 'question', '{}', ?, ?, ?, ?, 1, 'FAST', 0,
                current_timestamp, current_timestamp)
            """,
            runId,
            "conv-" + runId,
            status,
            cancelRequested,
            retryCount,
            maxRetries
        );
    }

    private void insertOutbox(JdbcTemplate jdbcTemplate,
                              String runId,
                              String eventType,
                              String status,
                              int attemptCount,
                              Instant updatedAt) {
        jdbcTemplate.update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(0, ?, 1, ?, ?, ?, ?, current_timestamp, current_timestamp, ?)
            """,
            runId,
            eventType,
            "run:" + runId + ":" + eventType.toLowerCase() + ":" + status.toLowerCase(),
            status,
            attemptCount,
            Timestamp.from(updatedAt)
        );
    }

    private record TestContext(JdbcTemplate jdbcTemplate,
                               KnowledgeChatRunQueueService queueService,
                               KnowledgeChatRunRecoveryService recoveryService) {
    }

    private static final class TrackingJdbcTemplate extends JdbcTemplate {
        private int parkedTerminalUpdateCount;

        private TrackingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("parked terminal retry")) {
                parkedTerminalUpdateCount++;
            }
            return super.update(sql, args);
        }

        private int parkedTerminalUpdateCount() {
            return parkedTerminalUpdateCount;
        }
    }
}
