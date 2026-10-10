package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.config.KnowledgeChatRunSchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgeChatRunRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunRecoveryService.class);
    private static final Duration CANCELLING_GRACE = Duration.ofSeconds(3);
    private static final Duration OUTBOX_DISPATCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ORPHANED_PENDING_RUN_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration OUTBOX_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration TERMINAL_DEAD_RETRY_DELAY = Duration.ofMinutes(15);
    private static final int MAX_OUTBOX_ATTEMPTS = 5;
    private static final int MAX_TERMINAL_DEAD_RECOVERIES = 3;
    private static final int MAX_LAST_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeChatPersistenceService persistenceService;
    private final KnowledgeChatRunQueueService queueService;
    private final TerminalEventPort terminalEventPort;
    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeChatRunOutboxCoordinationService outboxCoordinationService;

    @Autowired
    public KnowledgeChatRunRecoveryService(JdbcTemplate jdbcTemplate,
                                           KnowledgeChatPersistenceService persistenceService,
                                           ObjectProvider<KnowledgeChatRunQueueService> queueServiceProvider,
                                           ObjectProvider<KnowledgeChatRunPostProcessingService> terminalEventPortProvider,
                                           KnowledgeProperties knowledgeProperties,
                                           KnowledgeChatRunOutboxCoordinationService outboxCoordinationService) {
        this(
            jdbcTemplate,
            persistenceService,
            queueServiceProvider.getIfAvailable(),
            terminalEventPortProvider.getIfAvailable(),
            knowledgeProperties,
            outboxCoordinationService
        );
    }

    public KnowledgeChatRunRecoveryService(JdbcTemplate jdbcTemplate,
                                           KnowledgeChatPersistenceService persistenceService,
                                           KnowledgeChatRunQueueService queueService,
                                           KnowledgeProperties knowledgeProperties) {
        this(jdbcTemplate, persistenceService, queueService, null, knowledgeProperties);
    }

    public KnowledgeChatRunRecoveryService(JdbcTemplate jdbcTemplate,
                                           KnowledgeChatPersistenceService persistenceService,
                                           KnowledgeChatRunQueueService queueService,
                                           TerminalEventPort terminalEventPort,
                                           KnowledgeProperties knowledgeProperties) {
        this(jdbcTemplate, persistenceService, queueService, terminalEventPort, knowledgeProperties, null);
    }

    public KnowledgeChatRunRecoveryService(JdbcTemplate jdbcTemplate,
                                           KnowledgeChatPersistenceService persistenceService,
                                           KnowledgeChatRunQueueService queueService,
                                           TerminalEventPort terminalEventPort,
                                           KnowledgeProperties knowledgeProperties,
                                           KnowledgeChatRunOutboxCoordinationService outboxCoordinationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.persistenceService = persistenceService;
        this.queueService = queueService;
        this.terminalEventPort = terminalEventPort;
        this.knowledgeProperties = knowledgeProperties;
        this.outboxCoordinationService = outboxCoordinationService;
    }

    @Scheduled(
        fixedDelayString = "${app.knowledge.chat-run.outbox-dispatch-interval-millis:1000}",
        scheduler = KnowledgeChatRunSchedulingConfig.CHAT_RUN_MAINTENANCE_TASK_SCHEDULER
    )
    public void dispatchScheduledOutbox() {
        if (outboxCoordinationService != null) {
            KnowledgeChatRunOutboxCoordinationService.WakeupSignal wakeup = outboxCoordinationService.currentWakeup();
            if (wakeup == null) {
                return;
            }
            dispatchCoordinatedOutbox(20, wakeup);
            return;
        }
        try {
            dispatchPendingOutbox(20);
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run outbox dispatch skipped: {}", ex.getMessage());
        }
    }

    @Scheduled(
        fixedDelayString = "${app.knowledge.chat-run.outbox-recovery-interval-millis:30000}",
        initialDelayString = "${app.knowledge.chat-run.outbox-recovery-initial-delay-millis:30000}",
        scheduler = KnowledgeChatRunSchedulingConfig.CHAT_RUN_MAINTENANCE_TASK_SCHEDULER
    )
    public void recoverScheduledOutbox() {
        if (outboxCoordinationService != null) {
            dispatchCoordinatedOutbox(20, null);
            return;
        }
        try {
            dispatchPendingOutbox(20);
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run outbox recovery skipped: {}", ex.getMessage());
        }
    }

    @Scheduled(
        fixedDelayString = "${app.knowledge.chat-run.recovery-interval-millis:3000}",
        scheduler = KnowledgeChatRunSchedulingConfig.CHAT_RUN_MAINTENANCE_TASK_SCHEDULER
    )
    public void recoverScheduledRuns() {
        try {
            recoverExpiredRuns(20);
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run recovery scan skipped: {}", ex.getMessage());
        }
    }

    public int dispatchPendingOutbox(int limit) {
        return dispatchPendingOutboxBatch(limit).dispatched();
    }

    private DispatchBatchResult dispatchPendingOutboxBatch(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Instant now = Instant.now();
        Timestamp staleBefore = Timestamp.from(now.minus(OUTBOX_DISPATCH_TIMEOUT));
        failDeadPendingExecutions(safeLimit);
        recoverParkedTerminalOutbox(safeLimit, now);
        if (knowledgeProperties.getChatRun().isQueueEnabled()) {
            try {
                recoverOrphanedPendingExecutions(safeLimit, now);
            } catch (RuntimeException ex) {
                LOGGER.warn("orphaned pending chat run recovery failed: {}", ex.getMessage());
            }
        }
        List<OutboxItem> items = jdbcTemplate.query("""
                select outbox_id, run_id, event_type, status, attempt_count
                from ai_chat_run_outbox
                where event_type in ('EXECUTE', 'CANCEL_REQUESTED', 'ANSWERED', 'FAILED', 'CANCELLED') and (
                    (status = 'PENDING' and available_at <= current_timestamp)
                    or (status = 'DISPATCHING' and updated_at < ?)
                )
                order by outbox_id asc
                limit ?
                """,
            (rs, rowNum) -> new OutboxItem(
                rs.getLong("outbox_id"),
                rs.getString("run_id"),
                rs.getString("event_type"),
                rs.getString("status"),
                rs.getInt("attempt_count")
            ),
            staleBefore,
            safeLimit
        );
        int dispatched = 0;
        for (OutboxItem item : items) {
            try {
                dispatched += dispatchOutboxItem(item, staleBefore);
            } catch (RuntimeException ex) {
                LOGGER.warn(
                    "chat run outbox item dispatch failed: outboxId={}, runId={}, eventType={}, message={}",
                    item.outboxId(),
                    item.runId(),
                    item.eventType(),
                    ex.getMessage()
                );
            }
        }
        return new DispatchBatchResult(items.size(), dispatched, safeLimit);
    }

    private void dispatchCoordinatedOutbox(
        int limit,
        KnowledgeChatRunOutboxCoordinationService.WakeupSignal wakeup
    ) {
        KnowledgeChatRunOutboxCoordinationService.DispatchLease lease =
            outboxCoordinationService.tryAcquireDispatchLease();
        if (lease == null) {
            return;
        }
        boolean completed = false;
        boolean morePending = false;
        try {
            DispatchBatchResult result = dispatchPendingOutboxBatch(limit);
            morePending = result.selected() >= result.limit();
            completed = true;
        } catch (RuntimeException ex) {
            LOGGER.debug("chat run coordinated outbox dispatch skipped: {}", ex.getMessage());
        } finally {
            if (completed && wakeup != null) {
                outboxCoordinationService.acknowledge(wakeup);
            }
            outboxCoordinationService.releaseDispatchLease(lease);
        }
        if (morePending) {
            outboxCoordinationService.signal();
        }
    }

    public boolean dispatchTerminalOutboxForRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        Timestamp staleBefore = Timestamp.from(Instant.now().minus(OUTBOX_DISPATCH_TIMEOUT));
        List<OutboxItem> items = jdbcTemplate.query("""
                select outbox_id, run_id, event_type, status, attempt_count
                from ai_chat_run_outbox
                where run_id = ? and event_type in ('ANSWERED', 'FAILED', 'CANCELLED') and (
                    (status = 'PENDING' and available_at <= current_timestamp)
                    or (status = 'DISPATCHING' and updated_at < ?)
                )
                order by outbox_id asc
                limit 1
                """,
            (rs, rowNum) -> new OutboxItem(
                rs.getLong("outbox_id"),
                rs.getString("run_id"),
                rs.getString("event_type"),
                rs.getString("status"),
                rs.getInt("attempt_count")
            ),
            runId.trim(),
            staleBefore
        );
        if (!items.isEmpty()) {
            return dispatchOutboxItem(items.get(0), staleBefore) == 1;
        }
        Integer published = jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox where run_id = ? "
                + "and event_type in ('ANSWERED', 'FAILED', 'CANCELLED') and status = 'PUBLISHED'",
            Integer.class,
            runId.trim()
        );
        return published != null && published > 0;
    }

    private int recoverOrphanedPendingExecutions(int limit, Instant now) {
        Timestamp orphanedBefore = Timestamp.from(now.minus(ORPHANED_PENDING_RUN_TIMEOUT));
        List<OrphanedPendingExecution> candidates = jdbcTemplate.query("""
                select o.outbox_id, o.run_id, o.attempt_count
                from ai_chat_run r
                join ai_chat_run_outbox o on o.run_id = r.run_id
                where r.status = 'PENDING' and r.deleted = 0 and r.update_time < ?
                  and o.event_type = 'EXECUTE' and o.status = 'PUBLISHED' and o.updated_at < ?
                  and not exists (
                      select 1 from ai_chat_run_outbox active
                      where active.run_id = r.run_id and active.event_type = 'EXECUTE'
                        and active.status in ('PENDING', 'DISPATCHING')
                  )
                  and not exists (
                      select 1 from ai_chat_run_outbox newer
                      where newer.run_id = r.run_id and newer.event_type = 'EXECUTE'
                        and newer.outbox_id > o.outbox_id
                  )
                order by r.update_time asc, o.outbox_id asc
                limit ?
                """,
            (rs, rowNum) -> new OrphanedPendingExecution(
                rs.getLong("outbox_id"),
                rs.getString("run_id"),
                rs.getInt("attempt_count")
            ),
            orphanedBefore,
            orphanedBefore,
            limit
        );
        int recovered = 0;
        for (OrphanedPendingExecution candidate : candidates) {
            try {
                String nextStatus = candidate.attemptCount() >= MAX_OUTBOX_ATTEMPTS ? "DEAD" : "PENDING";
                Timestamp availableAt = Timestamp.from(
                    now.plus("DEAD".equals(nextStatus) ? Duration.ZERO : OUTBOX_RETRY_DELAY)
                );
                String lastError = "DEAD".equals(nextStatus)
                    ? "queue publish failed: maximum attempts reached while recovering orphaned PENDING run"
                    : "queue execution recovery: orphaned PENDING run after published EXECUTE";
                int updated = jdbcTemplate.update("""
                        update ai_chat_run_outbox
                        set status = ?, available_at = ?, published_at = null,
                            last_error = ?, updated_at = current_timestamp
                        where outbox_id = ? and status = 'PUBLISHED' and attempt_count = ?
                          and exists (
                              select 1 from ai_chat_run r
                              where r.run_id = ? and r.status = 'PENDING' and r.deleted = 0
                                and r.update_time < ?
                          )
                        """,
                    nextStatus,
                    availableAt,
                    lastError,
                    candidate.outboxId(),
                    candidate.attemptCount(),
                    candidate.runId(),
                    orphanedBefore
                );
                if (updated == 1) {
                    if ("DEAD".equals(nextStatus)) {
                        failPendingRunForDeadExecute(candidate.runId(), lastError);
                    }
                    recovered++;
                    if ("PENDING".equals(nextStatus) && outboxCoordinationService != null) {
                        outboxCoordinationService.signal(OUTBOX_RETRY_DELAY);
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.warn(
                    "orphaned pending chat run item recovery failed: outboxId={}, runId={}, message={}",
                    candidate.outboxId(),
                    candidate.runId(),
                    ex.getMessage()
                );
            }
        }
        return recovered;
    }

    private int dispatchOutboxItem(OutboxItem item, Timestamp staleBefore) {
        if (item.attemptCount() >= MAX_OUTBOX_ATTEMPTS) {
            deadLetterExhausted(item, staleBefore);
            return 0;
        }
        int claimedAttempt = item.attemptCount() + 1;
        if (jdbcTemplate.update(
            "update ai_chat_run_outbox set status = 'DISPATCHING', attempt_count = attempt_count + 1, " +
                "updated_at = current_timestamp where outbox_id = ? and attempt_count = ? " +
                "and attempt_count < ? and ((status = 'PENDING' and available_at <= current_timestamp) " +
                "or (status = 'DISPATCHING' and updated_at < ?))",
            item.outboxId(),
            item.attemptCount(),
            MAX_OUTBOX_ATTEMPTS,
            staleBefore
        ) != 1) {
            return 0;
        }

        boolean published;
        try {
            published = dispatch(item);
        } catch (RuntimeException ex) {
            recordDispatchFailure(item, claimedAttempt, exceptionFailureMessage(item, ex));
            return 0;
        }
        if (!published) {
            recordDispatchFailure(item, claimedAttempt, dispatchReturnedFalseMessage(item));
            return 0;
        }
        if (!isQueueEvent(item.eventType())) {
            return 1;
        }
        return jdbcTemplate.update(
            "update ai_chat_run_outbox set status = 'PUBLISHED', published_at = current_timestamp, " +
                "last_error = null, updated_at = current_timestamp " +
                "where outbox_id = ? and status = 'DISPATCHING' and attempt_count = ?",
            item.outboxId(),
            claimedAttempt
        ) == 1 ? 1 : 0;
    }

    private void recordDispatchFailure(OutboxItem item, int claimedAttempt, String lastError) {
        boolean exhausted = claimedAttempt >= MAX_OUTBOX_ATTEMPTS;
        int updated = jdbcTemplate.update(
            "update ai_chat_run_outbox set status = ?, available_at = ?, last_error = ?, " +
                "updated_at = current_timestamp where outbox_id = ? and status = 'DISPATCHING' " +
                "and attempt_count = ?",
            exhausted ? "DEAD" : "PENDING",
            Timestamp.from(Instant.now().plus(exhausted ? Duration.ZERO : OUTBOX_RETRY_DELAY)),
            lastError,
            item.outboxId(),
            claimedAttempt
        );
        if (updated == 1 && exhausted && "EXECUTE".equals(item.eventType())) {
            failPendingRunForDeadExecute(item.runId(), lastError);
        }
        if (updated == 1 && !exhausted && outboxCoordinationService != null) {
            outboxCoordinationService.signal(OUTBOX_RETRY_DELAY);
        }
    }

    private void deadLetterExhausted(OutboxItem item, Timestamp staleBefore) {
        String detail = "DISPATCHING".equals(item.status())
            ? "maximum attempts reached after dispatcher timeout"
            : "maximum attempts reached before dispatch";
        int updated = jdbcTemplate.update(
            "update ai_chat_run_outbox set status = 'DEAD', last_error = ?, updated_at = current_timestamp " +
                "where outbox_id = ? and attempt_count = ? and ((status = 'PENDING' " +
                "and available_at <= current_timestamp) or (status = 'DISPATCHING' and updated_at < ?))",
            failureMessage(item, detail),
            item.outboxId(),
            item.attemptCount(),
            staleBefore
        );
        if (updated == 1 && "EXECUTE".equals(item.eventType())) {
            failPendingRunForDeadExecute(item.runId(), failureMessage(item, detail));
        }
    }

    private void failDeadPendingExecutions(int limit) {
        List<DeadPendingExecution> deadRuns = jdbcTemplate.query("""
                select r.run_id, r.user_id, o.last_error
                from ai_chat_run r
                join ai_chat_run_outbox o on o.run_id = r.run_id
                where r.status = 'PENDING' and r.deleted = 0
                  and o.event_type = 'EXECUTE' and o.status = 'DEAD'
                order by o.updated_at asc, o.outbox_id asc
                limit ?
                """,
            (rs, rowNum) -> new DeadPendingExecution(
                rs.getString("run_id"),
                rs.getLong("user_id"),
                rs.getString("last_error")
            ),
            limit
        );
        for (DeadPendingExecution deadRun : deadRuns) {
            failPendingRun(
                deadRun.runId(),
                deadRun.userId(),
                deadRun.lastError()
            );
        }
    }

    private void recoverParkedTerminalOutbox(int limit, Instant now) {
        Timestamp retryBefore = Timestamp.from(now.minus(TERMINAL_DEAD_RETRY_DELAY));
        List<Long> parkedOutboxIds = jdbcTemplate.queryForList("""
                select outbox_id
                from ai_chat_run_outbox
                where event_type in ('ANSWERED', 'FAILED', 'CANCELLED')
                  and status = 'DEAD' and dead_retry_count < ? and updated_at < ?
                order by updated_at asc, outbox_id asc
                limit ?
                """,
            Long.class,
            MAX_TERMINAL_DEAD_RECOVERIES,
            retryBefore,
            limit
        );
        for (Long outboxId : parkedOutboxIds) {
            jdbcTemplate.update("""
                    update ai_chat_run_outbox
                    set status = 'PENDING', attempt_count = 0,
                        dead_retry_count = dead_retry_count + 1,
                        available_at = current_timestamp,
                        last_error = substring(
                            concat('parked terminal retry: ', coalesce(last_error, 'unknown failure')),
                            1,
                            1000
                        ),
                        updated_at = current_timestamp
                    where outbox_id = ?
                      and event_type in ('ANSWERED', 'FAILED', 'CANCELLED')
                      and status = 'DEAD' and dead_retry_count < ? and updated_at < ?
                    """,
                outboxId,
                MAX_TERMINAL_DEAD_RECOVERIES,
                retryBefore
            );
        }
    }

    private void failPendingRunForDeadExecute(String runId, String errorMessage) {
        List<Long> userIds = jdbcTemplate.query(
            "select user_id from ai_chat_run where run_id = ? and status = 'PENDING' and deleted = 0",
            (rs, rowNum) -> rs.getLong("user_id"),
            runId
        );
        if (!userIds.isEmpty()) {
            failPendingRun(runId, userIds.get(0), errorMessage);
        }
    }

    private void failPendingRun(String runId, Long userId, String errorMessage) {
        AuthUser previous = AuthUserHolder.get();
        try {
            AuthUserHolder.set(AuthUser.of(userId, "chat-run-outbox-recovery", Set.of("SYSTEM")));
            persistenceService.markExecutionFailed(
                runId,
                errorMessage == null ? "queue execution exhausted" : errorMessage,
                "PENDING"
            );
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "dead execute outbox run finalization failed: runId={}, message={}",
                runId,
                ex.getMessage()
            );
        } finally {
            if (previous == null) {
                AuthUserHolder.clear();
            } else {
                AuthUserHolder.set(previous);
            }
        }
    }

    private String dispatchReturnedFalseMessage(OutboxItem item) {
        return failureMessage(
            item,
            isQueueEvent(item.eventType()) ? "publisher returned false" : "processor returned false"
        );
    }

    private String exceptionFailureMessage(OutboxItem item, RuntimeException ex) {
        String detail = ex.getClass().getSimpleName();
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            detail += ": " + ex.getMessage();
        }
        return failureMessage(item, detail);
    }

    private String failureMessage(OutboxItem item, String detail) {
        String prefix = isQueueEvent(item.eventType())
            ? "queue publish failed"
            : "terminal post-processing failed";
        String message = prefix + ": " + detail;
        return message.length() <= MAX_LAST_ERROR_LENGTH
            ? message
            : message.substring(0, MAX_LAST_ERROR_LENGTH);
    }

    public int recoverExpiredRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Instant now = Instant.now();
        List<RecoverableRun> runs = jdbcTemplate.query("""
                select run_id, user_id
                from ai_chat_run
                where deleted = 0 and (
                    (status = 'RUNNING' and lease_owner is not null and lease_expires_at < ?)
                    or
                    (status = 'CANCELLING' and update_time < ?
                        and (lease_owner is null or lease_expires_at is null or lease_expires_at < ?))
                )
                order by update_time asc
                limit ?
                """,
            (rs, rowNum) -> new RecoverableRun(rs.getString("run_id"), rs.getLong("user_id")),
            Timestamp.from(now),
            Timestamp.from(now.minus(CANCELLING_GRACE)),
            Timestamp.from(now),
            safeLimit
        );
        int recovered = 0;
        for (RecoverableRun run : runs) {
            AuthUser previous = AuthUserHolder.get();
            try {
                AuthUserHolder.set(AuthUser.of(run.userId(), "chat-run-recovery", Set.of("SYSTEM")));
                KnowledgeChatPersistenceService.RecoveryResult result =
                    persistenceService.recoverExpiredRun(run.runId(), CANCELLING_GRACE);
                if (result != KnowledgeChatPersistenceService.RecoveryResult.NONE) {
                    recovered++;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("chat run recovery failed: runId={}, message={}", run.runId(), ex.getMessage());
            } finally {
                if (previous == null) {
                    AuthUserHolder.clear();
                } else {
                    AuthUserHolder.set(previous);
                }
            }
        }
        return recovered;
    }

    private boolean dispatch(OutboxItem item) {
        if ("EXECUTE".equals(item.eventType())) {
            if (!knowledgeProperties.getChatRun().isQueueEnabled()) {
                return localExecutionAlreadyHandled(item.runId());
            }
            return queueService != null && queueService.publishExecute(item.runId());
        }
        if ("CANCEL_REQUESTED".equals(item.eventType())) {
            if (!knowledgeProperties.getChatRun().isQueueEnabled()) {
                return true;
            }
            return queueService != null && queueService.publishCancel(item.runId());
        }
        return terminalEventPort != null
            && terminalEventPort.process(item.outboxId(), item.runId(), item.eventType());
    }

    private boolean isQueueEvent(String eventType) {
        return "EXECUTE".equals(eventType) || "CANCEL_REQUESTED".equals(eventType);
    }

    private boolean localExecutionAlreadyHandled(String runId) {
        List<String> statuses = jdbcTemplate.query(
            "select status from ai_chat_run where run_id = ? and deleted = 0",
            (rs, rowNum) -> rs.getString("status"),
            runId
        );
        return !statuses.isEmpty() && !"PENDING".equals(statuses.get(0));
    }

    private record OutboxItem(long outboxId,
                              String runId,
                              String eventType,
                              String status,
                              int attemptCount) {
    }

    private record DispatchBatchResult(int selected, int dispatched, int limit) {
    }

    private record OrphanedPendingExecution(long outboxId, String runId, int attemptCount) {
    }

    private record DeadPendingExecution(String runId, Long userId, String lastError) {
    }

    private record RecoverableRun(String runId, Long userId) {
    }

    public interface TerminalEventPort {
        boolean process(long outboxId, String runId, String eventType);
    }
}
