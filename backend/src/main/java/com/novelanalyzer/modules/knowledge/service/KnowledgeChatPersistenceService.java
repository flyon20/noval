package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatMessageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunEventVO;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class KnowledgeChatPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeConversationService conversationService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeChatRunEventService eventService;
    private final AgentResourcePressureService resourcePressureService;
    private final KnowledgeChatRunOutboxCoordinationService outboxCoordinationService;

    @Autowired
    public KnowledgeChatPersistenceService(JdbcTemplate jdbcTemplate,
                                           KnowledgeConversationService conversationService,
                                           PlatformTransactionManager transactionManager,
                                           ObjectMapper objectMapper,
                                           KnowledgeProperties knowledgeProperties,
                                           KnowledgeChatRunEventService eventService,
                                           AgentResourcePressureService resourcePressureService,
                                           KnowledgeChatRunOutboxCoordinationService outboxCoordinationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationService = conversationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.knowledgeProperties = knowledgeProperties;
        this.eventService = eventService;
        this.resourcePressureService = resourcePressureService;
        this.outboxCoordinationService = outboxCoordinationService;
    }

    public KnowledgeChatPersistenceService(JdbcTemplate jdbcTemplate,
                                           KnowledgeConversationService conversationService,
                                           PlatformTransactionManager transactionManager,
                                           ObjectMapper objectMapper,
                                           KnowledgeProperties knowledgeProperties,
                                           KnowledgeChatRunEventService eventService,
                                           AgentResourcePressureService resourcePressureService) {
        this(
            jdbcTemplate,
            conversationService,
            transactionManager,
            objectMapper,
            knowledgeProperties,
            eventService,
            resourcePressureService,
            null
        );
    }

    public KnowledgeChatPersistenceService(JdbcTemplate jdbcTemplate,
                                           KnowledgeConversationService conversationService,
                                           PlatformTransactionManager transactionManager,
                                           ObjectMapper objectMapper,
                                           KnowledgeProperties knowledgeProperties,
                                           KnowledgeChatRunEventService eventService) {
        this(
            jdbcTemplate,
            conversationService,
            transactionManager,
            objectMapper,
            knowledgeProperties,
            eventService,
            AgentResourcePressureService.permissive(knowledgeProperties)
        );
    }

    public KnowledgeChatPersistenceService(JdbcTemplate jdbcTemplate,
                                           KnowledgeConversationService conversationService,
                                           PlatformTransactionManager transactionManager,
                                           ObjectMapper objectMapper,
                                           KnowledgeProperties knowledgeProperties) {
        this(
            jdbcTemplate,
            conversationService,
            transactionManager,
            objectMapper,
            knowledgeProperties,
            new KnowledgeChatRunEventService(jdbcTemplate, transactionManager, objectMapper)
        );
    }

    public KnowledgeChatPersistenceService(JdbcTemplate jdbcTemplate,
                                           KnowledgeConversationService conversationService,
                                           PlatformTransactionManager transactionManager,
                                           ObjectMapper objectMapper) {
        this(jdbcTemplate, conversationService, transactionManager, objectMapper, new KnowledgeProperties());
    }

    public QueuedRunStart createQueuedRun(String proposedRunId,
                                          AuthUser user,
                                          KnowledgeChatRequest request,
                                          String requestJson) {
        return createRun(proposedRunId, user, request, requestJson, "PENDING", "queue", "已创建后台回答任务");
    }

    public BlockingRunStart beginBlockingRun(String proposedRunId,
                                             AuthUser user,
                                             KnowledgeChatRequest request,
                                             String requestJson) {
        BlockingRunStart result = transactionTemplate.execute(status -> {
            conversationService.ensureConversation(
                request.getConversationId(), request.getProjectId(), request.getQuestion()
            );
            lockConversation(request.getConversationId(), user.getUserId());
            ExistingRun prior = findOwnedByIdempotencyKey(user.getUserId(), request.getRequestId());
            if (prior != null) {
                validateSameRequest(prior, request, requestJson);
                if ("ANSWERED".equals(prior.status())) {
                    return new BlockingRunStart(prior.runId(), false, prior.resultJson(), null, 0L);
                }
                if ("FAILED".equals(prior.status())) {
                    throw new BusinessException(
                        ResultCode.BAD_REQUEST,
                        "request failed; retry with a new requestId"
                    );
                }
                throw new BusinessException(ResultCode.BAD_REQUEST, "request is already running");
            }
            String executionMode = normalizeExecutionMode(request.getReasoningMode());
            assertResourceAdmission(executionMode);
            jdbcTemplate.queryForObject(
                "select mode from ai_chat_run_admission_guard where mode = ? for update",
                String.class,
                executionMode
            );
            Integer active = jdbcTemplate.queryForObject("""
                    select count(1) from ai_chat_run
                    where execution_mode = ? and status in ('RUNNING', 'CANCELLING')
                      and deleted = 0
                    """,
                Integer.class,
                executionMode
            );
            if (active != null && active >= maxActiveRuns(executionMode)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "server is busy; retry later");
            }
            QueuedRunStart started = createRun(
                proposedRunId,
                user,
                request,
                requestJson,
                "RUNNING",
                "answer",
                "正在执行回答"
            );
            String leaseOwner = "compat-chat-" + UUID.randomUUID();
            int leased = jdbcTemplate.update("""
                    update ai_chat_run
                    set lease_owner = ?, lease_expires_at = ?, heartbeat_at = current_timestamp,
                        fencing_token = fencing_token + 1, update_time = current_timestamp
                    where run_id = ? and status = 'RUNNING' and deleted = 0
                    """,
                leaseOwner,
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))),
                started.runId()
            );
            if (leased != 1) {
                status.setRollbackOnly();
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "blocking chat lease failed");
            }
            return new BlockingRunStart(started.runId(), true, null, leaseOwner, 1L);
        });
        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "blocking chat admission failed");
        }
        return result;
    }

    public void markSubmissionFailed(String runId, String errorMessage) {
        markExecutionFailed(runId, errorMessage, "PENDING");
    }

    public void markExecutionFailed(String runId, String errorMessage, String... allowedStatuses) {
        String statusPredicate = String.join("','", allowedStatuses);
        transactionTemplate.executeWithoutResult(status -> {
            String safeError = truncate(errorMessage, 1000);
            int updated = jdbcTemplate.update(
                "update ai_chat_run set status = 'FAILED', progress_phase = 'failed', " +
                    "progress_message = '后台回答失败', error_message = ?, finished_at = current_timestamp, " +
                    "update_time = current_timestamp where run_id = ? and deleted = 0 and cancel_requested = false " +
                    "and status in ('" + statusPredicate + "')",
                safeError,
                runId
            );
            if (updated == 1) {
                eventService.appendEvent(
                    runId,
                    "FAILED",
                    "run:" + runId + ":terminal",
                    Map.of("status", "FAILED", "message", safeError == null ? "execution failed" : safeError)
                );
            }
        });
    }

    public RunLease claimRun(String runId, String leaseOwner, Duration leaseDuration) {
        String normalizedRunId = trimToNull(runId);
        String normalizedOwner = trimToNull(leaseOwner);
        if (normalizedRunId == null || normalizedOwner == null) {
            return null;
        }
        Duration safeDuration = normalizeLeaseDuration(leaseDuration);
        return transactionTemplate.execute(status -> {
            String executionMode = jdbcTemplate.query(
                "select execution_mode from ai_chat_run where run_id = ? and deleted = 0",
                rs -> rs.next() ? normalizeExecutionMode(rs.getString(1)) : null,
                normalizedRunId
            );
            if (executionMode == null) {
                return null;
            }
            if (resourcePressureBlocks(executionMode)) {
                return null;
            }
            jdbcTemplate.queryForObject(
                "select mode from ai_chat_run_admission_guard where mode = ? for update",
                String.class,
                executionMode
            );
            List<LeaseCandidate> candidates = jdbcTemplate.query("""
                    select status, lease_expires_at, fencing_token
                    from ai_chat_run
                    where run_id = ? and deleted = 0 and cancel_requested = false
                    for update
                    """,
                (rs, rowNum) -> new LeaseCandidate(
                    rs.getString("status"),
                    rs.getTimestamp("lease_expires_at"),
                    rs.getLong("fencing_token")
                ),
                normalizedRunId
            );
            if (candidates.isEmpty()) {
                return null;
            }
            LeaseCandidate candidate = candidates.get(0);
            Instant now = Instant.now();
            if (!"PENDING".equals(candidate.status())) {
                return null;
            }
            Integer active = jdbcTemplate.queryForObject("""
                    select count(1) from ai_chat_run
                    where execution_mode = ? and status in ('RUNNING', 'CANCELLING')
                      and deleted = 0 and run_id <> ?
                    """,
                Integer.class,
                executionMode,
                normalizedRunId
            );
            if (active != null && active >= maxActiveRuns(executionMode)) {
                return null;
            }
            long fencingToken = candidate.fencingToken() + 1;
            Timestamp leaseExpiresAt = Timestamp.from(now.plus(safeDuration));
            int updated = jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'RUNNING', progress_phase = 'answer',
                        progress_message = '正在执行后台 Agent 回答',
                        lease_owner = ?, lease_expires_at = ?, heartbeat_at = ?,
                        fencing_token = ?, started_at = coalesce(started_at, current_timestamp),
                        update_time = current_timestamp
                    where run_id = ? and deleted = 0 and cancel_requested = false
                      and status = 'PENDING'
                    """,
                normalizedOwner,
                leaseExpiresAt,
                Timestamp.from(now),
                fencingToken,
                normalizedRunId
            );
            return updated == 1
                ? new RunLease(normalizedRunId, normalizedOwner, fencingToken, executionMode, leaseExpiresAt)
                : null;
        });
    }

    public boolean heartbeatRun(String runId,
                                String leaseOwner,
                                long fencingToken,
                                Duration leaseDuration) {
        Instant now = Instant.now();
        Timestamp leaseExpiresAt = Timestamp.from(now.plus(normalizeLeaseDuration(leaseDuration)));
        return jdbcTemplate.update("""
                update ai_chat_run
                set heartbeat_at = ?, lease_expires_at = ?, update_time = current_timestamp
                where run_id = ? and status = 'RUNNING' and deleted = 0
                  and cancel_requested = false and lease_owner = ? and fencing_token = ?
                  and lease_expires_at >= current_timestamp
                """,
            Timestamp.from(now),
            leaseExpiresAt,
            trimToNull(runId),
            trimToNull(leaseOwner),
            fencingToken
        ) == 1;
    }

    public boolean deferPendingExecution(String runId, Duration delay) {
        Duration safeDelay = delay == null || delay.isNegative() ? Duration.ofSeconds(2) : delay;
        Boolean deferred = transactionTemplate.execute(status -> {
            Integer pending = jdbcTemplate.queryForObject(
                "select count(1) from ai_chat_run where run_id = ? and status = 'PENDING' and deleted = 0",
                Integer.class,
                trimToNull(runId)
            );
            if (pending == null || pending == 0) {
                return false;
            }
            List<Long> outboxIds = jdbcTemplate.query("""
                    select outbox_id from ai_chat_run_outbox
                    where run_id = ? and event_type = 'EXECUTE'
                    order by outbox_id desc limit 1
                    """,
                (rs, rowNum) -> rs.getLong("outbox_id"),
                trimToNull(runId)
            );
            if (outboxIds.isEmpty()) {
                return false;
            }
            return jdbcTemplate.update("""
                    update ai_chat_run_outbox
                    set status = 'PENDING', available_at = ?, published_at = null,
                        last_error = 'admission deferred', updated_at = current_timestamp
                    where outbox_id = ?
                    """,
                Timestamp.from(Instant.now().plus(safeDelay)),
                outboxIds.get(0)
            ) == 1;
        });
        if (Boolean.TRUE.equals(deferred) && outboxCoordinationService != null) {
            outboxCoordinationService.signalAfterCommit(safeDelay);
        }
        return Boolean.TRUE.equals(deferred);
    }

    public boolean updateRunSnapshot(String runId,
                                     String leaseOwner,
                                     long fencingToken,
                                     String progressPhase,
                                     String progressMessage,
                                     String partialAnswer,
                                     long snapshotSequenceNo) {
        long safeSequence = Math.max(snapshotSequenceNo, 0);
        return jdbcTemplate.update("""
                update ai_chat_run
                set progress_phase = ?, progress_message = ?,
                    answer = coalesce(?, answer),
                    snapshot_sequence_no = case
                        when snapshot_sequence_no < ? then ? else snapshot_sequence_no end,
                    heartbeat_at = current_timestamp,
                    update_time = current_timestamp
                where run_id = ? and status = 'RUNNING' and deleted = 0
                  and cancel_requested = false and lease_owner = ? and fencing_token = ?
                  and lease_expires_at >= current_timestamp
                """,
            truncate(progressPhase, 40),
            truncate(progressMessage, 500),
            partialAnswer,
            safeSequence,
            safeSequence,
            trimToNull(runId),
            trimToNull(leaseOwner),
            fencingToken
        ) == 1;
    }

    public boolean updateCompatibilityPartialAnswer(String runId,
                                                    String leaseOwner,
                                                    long fencingToken,
                                                    String partialAnswer) {
        if (partialAnswer == null || partialAnswer.isEmpty()) {
            return false;
        }
        return jdbcTemplate.update("""
                update ai_chat_run
                set answer = ?, heartbeat_at = current_timestamp, update_time = current_timestamp
                where run_id = ? and status = 'RUNNING' and deleted = 0
                  and cancel_requested = false and lease_owner = ? and fencing_token = ?
                  and lease_expires_at >= current_timestamp
                """,
            partialAnswer,
            trimToNull(runId),
            trimToNull(leaseOwner),
            fencingToken
        ) == 1;
    }

    public Long appendFencedEventAndSnapshot(String runId,
                                             String leaseOwner,
                                             long fencingToken,
                                             String eventType,
                                             String eventIdempotencyKey,
                                             Object payload,
                                             String progressPhase,
                                             String progressMessage,
                                             String partialAnswer) {
        return transactionTemplate.execute(status -> {
            ExistingRun run = lockFencedRun(runId, leaseOwner, fencingToken, "RUNNING", false);
            if (run == null) {
                return null;
            }
            KnowledgeChatRunEventVO event = eventService.appendEvent(
                run.runId(),
                eventType,
                eventIdempotencyKey,
                payload
            );
            String safePhase = truncate(progressPhase, 40);
            String safeMessage = truncate(progressMessage, 500);
            String safeAnswer = partialAnswer;
            if (safePhase == null && safeMessage == null && safeAnswer == null) {
                return event.getSequenceNo();
            }
            int updated = jdbcTemplate.update("""
                    update ai_chat_run
                    set progress_phase = coalesce(?, progress_phase),
                        progress_message = coalesce(?, progress_message),
                        answer = coalesce(?, answer),
                        snapshot_sequence_no = case
                            when ? is not null and snapshot_sequence_no < ? then ?
                            else snapshot_sequence_no end,
                        heartbeat_at = current_timestamp, update_time = current_timestamp
                    where run_id = ? and status = 'RUNNING' and deleted = 0
                      and cancel_requested = false and lease_owner = ? and fencing_token = ?
                      and lease_expires_at >= current_timestamp
                    """,
                safePhase,
                safeMessage,
                safeAnswer,
                safeAnswer,
                event.getSequenceNo(),
                event.getSequenceNo(),
                run.runId(),
                trimToNull(leaseOwner),
                fencingToken
            );
            if (updated != 1) {
                throw new IllegalStateException("chat run fencing token rejected");
            }
            return event.getSequenceNo();
        });
    }

    public boolean claimPendingRun(String runId) {
        return jdbcTemplate.update(
            "update ai_chat_run set status = 'RUNNING', progress_phase = 'answer', " +
                "progress_message = '正在执行后台 Agent 回答', started_at = current_timestamp, " +
                "update_time = current_timestamp where run_id = ? and status = 'PENDING' " +
                "and cancel_requested = false and deleted = 0",
            runId
        ) == 1;
    }

    public boolean completeAnsweredRun(String runId,
                                       String leaseOwner,
                                       long fencingToken,
                                       String answer,
                                       String resultJson,
                                       String traceId,
                                       int sourceCount) {
        Boolean completed = transactionTemplate.execute(status -> {
            ExistingRun run = lockFencedRun(runId, leaseOwner, fencingToken, "RUNNING", false);
            if (run == null) {
                return false;
            }
            Long responseMessageId = null;
            if (trimToNull(answer) != null) {
                KnowledgeChatMessageVO message = conversationService.appendMessage(
                    run.conversationId(),
                    run.runId(),
                    "ASSISTANT",
                    answer,
                    resultJson,
                    null
                );
                responseMessageId = message.getMessageId();
            }
            KnowledgeChatRunEventVO terminalEvent = eventService.appendEvent(
                run.runId(),
                "ANSWERED",
                "run:" + run.runId() + ":terminal",
                Map.of(
                    "status", "ANSWERED",
                    "traceId", traceId == null ? "" : traceId,
                    "answer", answer == null ? "" : answer
                )
            );
            int updated = jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'ANSWERED', progress_phase = 'done',
                        progress_message = '后台回答已完成', answer = ?, result_json = ?, trace_id = ?,
                        source_count = ?, response_message_id = ?, error_message = null,
                        snapshot_sequence_no = case
                            when snapshot_sequence_no < ? then ? else snapshot_sequence_no end,
                        finished_at = current_timestamp, lease_owner = null, lease_expires_at = null,
                        heartbeat_at = current_timestamp, update_time = current_timestamp
                    where run_id = ? and status = 'RUNNING' and deleted = 0
                      and cancel_requested = false and lease_owner = ? and fencing_token = ?
                      and lease_expires_at >= current_timestamp
                    """,
                answer,
                resultJson,
                traceId,
                Math.max(sourceCount, 0),
                responseMessageId,
                Math.max(terminalEvent.getSequenceNo() - 1L, 0L),
                Math.max(terminalEvent.getSequenceNo() - 1L, 0L),
                run.runId(),
                trimToNull(leaseOwner),
                fencingToken
            );
            if (updated != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    public String findCompletedResponseJson(String runId, Long userId) {
        List<String> rows = jdbcTemplate.query(
            "select result_json from ai_chat_run where run_id = ? and user_id = ? "
                + "and status = 'ANSWERED' and deleted = 0",
            (rs, rowNum) -> rs.getString("result_json"),
            runId,
            userId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean requestCancellation(String runId, Long userId) {
        Boolean requested = transactionTemplate.execute(status -> {
            List<String> statuses = jdbcTemplate.query("""
                    select status from ai_chat_run
                    where run_id = ? and user_id = ? and deleted = 0
                    for update
                    """,
                (rs, rowNum) -> rs.getString("status"),
                trimToNull(runId),
                userId
            );
            if (statuses.isEmpty()) {
                return false;
            }
            String currentStatus = statuses.get(0);
            if ("ANSWERED".equals(currentStatus) || "FAILED".equals(currentStatus)
                || "CANCELLED".equals(currentStatus)) {
                return false;
            }
            if (!"CANCELLING".equals(currentStatus)) {
                jdbcTemplate.update("""
                        update ai_chat_run
                        set status = 'CANCELLING', cancel_requested = true,
                            progress_phase = 'cancelling', progress_message = '正在取消后台回答',
                            update_time = current_timestamp
                        where run_id = ? and user_id = ? and deleted = 0
                          and status in ('PENDING', 'RUNNING')
                        """,
                    trimToNull(runId),
                    userId
                );
            }
            eventService.appendEvent(
                trimToNull(runId),
                "CANCEL_REQUESTED",
                "run:" + trimToNull(runId) + ":cancel-requested",
                Map.of("status", "CANCELLING")
            );
            return true;
        });
        return Boolean.TRUE.equals(requested);
    }

    public boolean completeCancelledRun(String runId,
                                        String leaseOwner,
                                        long fencingToken,
                                        String reason) {
        Boolean completed = transactionTemplate.execute(status -> {
            ExistingRun run = lockFencedRun(runId, leaseOwner, fencingToken, "CANCELLING", true);
            if (run == null) {
                return false;
            }
            Long responseMessageId = appendPartialMessageIfPresent(run, "CANCELLED");
            eventService.appendEvent(
                run.runId(),
                "CANCELLED",
                "run:" + run.runId() + ":terminal",
                Map.of("status", "CANCELLED", "reason", trimToNull(reason) == null ? "cancelled" : reason)
            );
            int updated = jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'CANCELLED', progress_phase = 'cancelled',
                        progress_message = '后台回答已取消', error_message = ?,
                        response_message_id = coalesce(response_message_id, ?),
                        finished_at = current_timestamp, lease_owner = null, lease_expires_at = null,
                        heartbeat_at = current_timestamp, update_time = current_timestamp
                    where run_id = ? and status = 'CANCELLING' and deleted = 0
                      and cancel_requested = true and lease_owner = ? and fencing_token = ?
                      and lease_expires_at >= current_timestamp
                    """,
                truncate(reason, 1000),
                responseMessageId,
                run.runId(),
                trimToNull(leaseOwner),
                fencingToken
            );
            if (updated != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    public boolean completeFailedRun(String runId,
                                     String leaseOwner,
                                     long fencingToken,
                                     String errorMessage) {
        Boolean completed = transactionTemplate.execute(status -> {
            ExistingRun run = lockFencedRun(runId, leaseOwner, fencingToken, "RUNNING", false);
            if (run == null) {
                return false;
            }
            String safeError = truncate(errorMessage, 1000);
            Long responseMessageId = appendPartialMessageIfPresent(run, "FAILED");
            eventService.appendEvent(
                run.runId(),
                "FAILED",
                "run:" + run.runId() + ":terminal",
                Map.of("status", "FAILED", "message", safeError == null ? "execution failed" : safeError)
            );
            int updated = jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'FAILED', progress_phase = 'failed',
                        progress_message = '后台回答失败', error_message = ?,
                        response_message_id = coalesce(response_message_id, ?),
                        finished_at = current_timestamp, lease_owner = null, lease_expires_at = null,
                        heartbeat_at = current_timestamp, update_time = current_timestamp
                    where run_id = ? and status = 'RUNNING' and deleted = 0
                      and cancel_requested = false and lease_owner = ? and fencing_token = ?
                      and lease_expires_at >= current_timestamp
                    """,
                safeError,
                responseMessageId,
                run.runId(),
                trimToNull(leaseOwner),
                fencingToken
            );
            if (updated != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    public RecoveryResult recoverExpiredRun(String runId, Duration cancellingGrace) {
        return transactionTemplate.execute(status -> {
            List<RecoverableRun> runs = jdbcTemplate.query("""
                    select run_id, user_id, project_id, conversation_id, question, request_json,
                           request_id, idempotency_key, trigger_message_id, execution_mode,
                           resource_budget_json, status, retry_count, max_retries, attempt_no,
                           answer, lease_owner, lease_expires_at, update_time
                    from ai_chat_run
                    where run_id = ? and deleted = 0
                    for update
                    """,
                (rs, rowNum) -> new RecoverableRun(
                    rs.getString("run_id"),
                    rs.getLong("user_id"),
                    nullableLong(rs, "project_id"),
                    rs.getString("conversation_id"),
                    rs.getString("question"),
                    rs.getString("request_json"),
                    rs.getString("request_id"),
                    rs.getString("idempotency_key"),
                    nullableLong(rs, "trigger_message_id"),
                    normalizeExecutionMode(rs.getString("execution_mode")),
                    rs.getString("resource_budget_json"),
                    rs.getString("status"),
                    rs.getInt("retry_count"),
                    rs.getInt("max_retries"),
                    rs.getInt("attempt_no"),
                    rs.getString("answer"),
                    rs.getString("lease_owner"),
                    rs.getTimestamp("lease_expires_at"),
                    rs.getTimestamp("update_time")
                ),
                trimToNull(runId)
            );
            if (runs.isEmpty()) {
                return RecoveryResult.NONE;
            }
            RecoverableRun run = runs.get(0);
            Instant now = Instant.now();
            boolean durableLeaseExpired = trimToNull(run.leaseOwner()) != null
                && run.leaseExpiresAt() != null
                && run.leaseExpiresAt().toInstant().isBefore(now);
            boolean cancellationLeaseInactive = trimToNull(run.leaseOwner()) == null
                || run.leaseExpiresAt() == null
                || run.leaseExpiresAt().toInstant().isBefore(now);
            if ("CANCELLING".equals(run.status()) && cancellationLeaseInactive
                && run.updateTime() != null
                && run.updateTime().toInstant().isBefore(now.minus(normalizeCancellingGrace(cancellingGrace)))) {
                Long responseMessageId = appendPartialMessageIfPresent(
                    run.runId(), run.conversationId(), run.answer(), "CANCELLED"
                );
                eventService.appendEvent(
                    run.runId(),
                    "CANCELLED",
                    "run:" + run.runId() + ":terminal",
                    Map.of("status", "CANCELLED", "reason", "cancellation recovery")
                );
                jdbcTemplate.update("""
                        update ai_chat_run
                        set status = 'CANCELLED', progress_phase = 'cancelled',
                            progress_message = '后台回答已取消', finished_at = current_timestamp,
                            response_message_id = coalesce(response_message_id, ?),
                            lease_owner = null, lease_expires_at = null,
                            heartbeat_at = current_timestamp, update_time = current_timestamp
                        where run_id = ? and status = 'CANCELLING' and deleted = 0
                        """,
                    responseMessageId,
                    run.runId()
                );
                return RecoveryResult.CANCELLED;
            }
            if (!"RUNNING".equals(run.status()) || !durableLeaseExpired) {
                return RecoveryResult.NONE;
            }
            if (run.retryCount() < run.maxRetries()) {
                int nextAttempt = run.attemptNo() + 1;
                String childRunId = "chatrun-" + UUID.randomUUID();
                String requestId = trimToNull(run.requestId()) == null ? run.runId() : run.requestId();
                String childIdempotencyKey = requestId + ":attempt:" + nextAttempt;
                Long responseMessageId = appendPartialMessageIfPresent(
                    run.runId(), run.conversationId(), run.answer(), "FAILED"
                );
                eventService.appendEvent(
                    run.runId(),
                    "FAILED",
                    "run:" + run.runId() + ":terminal",
                    Map.of(
                        "status", "FAILED",
                        "reason", "lease expired",
                        "childRunId", childRunId
                    )
                );
                jdbcTemplate.update("""
                        update ai_chat_run
                        set status = 'FAILED', progress_phase = 'failed',
                            progress_message = '执行租约过期，已创建重试任务',
                            error_message = 'lease expired', finished_at = current_timestamp,
                            response_message_id = coalesce(response_message_id, ?),
                            lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                            update_time = current_timestamp
                        where run_id = ? and status = 'RUNNING' and deleted = 0
                        """,
                    responseMessageId,
                    run.runId()
                );
                jdbcTemplate.update("""
                        insert into ai_chat_run(
                            run_id, user_id, project_id, conversation_id, question, request_json,
                            status, progress_phase, progress_message, cancel_requested,
                            retry_count, max_retries, queued_at, deleted, request_id, attempt_no,
                            parent_run_id, idempotency_key, trigger_message_id, execution_mode,
                            resource_budget_json
                        ) values(?, ?, ?, ?, ?, ?, 'PENDING', 'queue', '执行租约过期，正在重新排队',
                            false, ?, ?, current_timestamp, 0, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    childRunId,
                    run.userId(),
                    run.projectId(),
                    run.conversationId(),
                    run.question(),
                    run.requestJson(),
                    run.retryCount() + 1,
                    run.maxRetries(),
                    requestId,
                    nextAttempt,
                    run.runId(),
                    childIdempotencyKey,
                    run.triggerMessageId(),
                    run.executionMode(),
                    run.resourceBudgetJson()
                );
                eventService.appendEvent(
                    childRunId,
                    "EXECUTE",
                    "run:" + childRunId + ":execute",
                    Map.of(
                        "status", "PENDING",
                        "attempt", nextAttempt,
                        "parentRunId", run.runId(),
                        "reason", "lease expired"
                    )
                );
                return RecoveryResult.REQUEUED;
            }
            Long responseMessageId = appendPartialMessageIfPresent(
                run.runId(), run.conversationId(), run.answer(), "FAILED"
            );
            eventService.appendEvent(
                run.runId(),
                "FAILED",
                "run:" + run.runId() + ":terminal",
                Map.of("status", "FAILED", "reason", "lease retry exhausted")
            );
            jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'FAILED', progress_phase = 'failed',
                        progress_message = '后台回答恢复重试已耗尽',
                        error_message = 'lease retry exhausted', finished_at = current_timestamp,
                        response_message_id = coalesce(response_message_id, ?),
                        lease_owner = null, lease_expires_at = null,
                        heartbeat_at = current_timestamp, update_time = current_timestamp
                    where run_id = ? and status = 'RUNNING' and deleted = 0
                    """,
                responseMessageId,
                run.runId()
            );
            return RecoveryResult.FAILED;
        });
    }

    public boolean completeAnsweredRun(String runId,
                                       String answer,
                                       String resultJson,
                                       String traceId,
                                       int sourceCount) {
        Boolean completed = transactionTemplate.execute(status -> {
            ExistingRun run = lockRunningRun(runId);
            if (run == null) {
                return false;
            }
            Long responseMessageId = null;
            if (trimToNull(answer) != null) {
                KnowledgeChatMessageVO message = conversationService.appendMessage(
                    run.conversationId(),
                    run.runId(),
                    "ASSISTANT",
                    answer,
                    resultJson,
                    null
                );
                responseMessageId = message.getMessageId();
            }
            eventService.appendEvent(
                run.runId(),
                "ANSWERED",
                "run:" + run.runId() + ":terminal",
                Map.of("status", "ANSWERED", "traceId", traceId == null ? "" : traceId)
            );
            int updated = jdbcTemplate.update(
                "update ai_chat_run set status = 'ANSWERED', progress_phase = 'done', " +
                    "progress_message = '后台回答已完成', answer = ?, result_json = ?, trace_id = ?, " +
                    "source_count = ?, response_message_id = ?, error_message = null, " +
                    "finished_at = current_timestamp, update_time = current_timestamp " +
                    "where run_id = ? and status = 'RUNNING' and deleted = 0 and cancel_requested = false",
                answer,
                resultJson,
                traceId,
                Math.max(sourceCount, 0),
                responseMessageId,
                run.runId()
            );
            return updated == 1;
        });
        return Boolean.TRUE.equals(completed);
    }

    private QueuedRunStart createRun(String proposedRunId,
                                     AuthUser user,
                                     KnowledgeChatRequest request,
                                     String requestJson,
                                     String status,
                                     String progressPhase,
                                     String progressMessage) {
        try {
            return transactionTemplate.execute(transactionStatus -> {
                conversationService.ensureConversation(
                    request.getConversationId(),
                    request.getProjectId(),
                    request.getQuestion()
                );
                lockConversation(request.getConversationId(), user.getUserId());
                ExistingRun existing = findOwnedByIdempotencyKey(user.getUserId(), request.getRequestId());
                if (existing != null) {
                    validateSameRequest(existing, request, requestJson);
                    return new QueuedRunStart(existing.runId(), false);
                }
                assertResourceAdmission(normalizeExecutionMode(request.getReasoningMode()));
                Integer activeConversationRuns = jdbcTemplate.queryForObject("""
                        select count(1) from ai_chat_run
                        where user_id = ? and conversation_id = ? and deleted = 0
                          and status in ('PENDING', 'RUNNING', 'CANCELLING')
                        """,
                    Integer.class,
                    user.getUserId(),
                    request.getConversationId()
                );
                if (activeConversationRuns != null && activeConversationRuns > 0) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "conversation already has an active run");
                }
                KnowledgeChatMessageVO trigger = conversationService.appendMessage(
                    request.getConversationId(),
                    proposedRunId,
                    "USER",
                    request.getQuestion(),
                    null,
                    null
                );
                jdbcTemplate.update("""
                        insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, request_json,
                            status, progress_phase, progress_message, cancel_requested, retry_count, max_retries,
                            queued_at, started_at, deleted, request_id, attempt_no, idempotency_key, trigger_message_id,
                            execution_mode, resource_budget_json)
                        values(?, ?, ?, ?, ?, ?, ?, ?, ?, false, 0, 3, current_timestamp,
                            case when ? = 'RUNNING' then current_timestamp else null end,
                            0, ?, 1, ?, ?, ?, ?)
                        """,
                    proposedRunId,
                    user.getUserId(),
                    request.getProjectId(),
                    request.getConversationId(),
                    request.getQuestion(),
                    requestJson,
                    status,
                    progressPhase,
                    progressMessage,
                    status,
                    request.getRequestId(),
                    request.getRequestId(),
                    trigger.getMessageId(),
                    normalizeExecutionMode(request.getReasoningMode()),
                    writeJson(Map.of(
                        "reasoningMode", normalizeExecutionMode(request.getReasoningMode()),
                        "maxActiveDeepRuns", knowledgeProperties.getResourcePolicy().getMaxActiveDeepRuns(),
                        "maxActiveFastRuns", knowledgeProperties.getResourcePolicy().getMaxActiveFastRuns()
                    ))
                );
                String initialEventType = "PENDING".equals(status) ? "EXECUTE" : "RUNNING";
                eventService.appendEvent(
                    proposedRunId,
                    initialEventType,
                    "run:" + proposedRunId + ":" + initialEventType.toLowerCase(),
                    Map.of("status", status, "executionMode", normalizeExecutionMode(request.getReasoningMode()))
                );
                return new QueuedRunStart(proposedRunId, true);
            });
        } catch (DuplicateKeyException ex) {
            ExistingRun existing = findOwnedByIdempotencyKey(user.getUserId(), request.getRequestId());
            if (existing == null) {
                throw ex;
            }
            validateSameRequest(existing, request, requestJson);
            return new QueuedRunStart(existing.runId(), false);
        }
    }

    private void validateSameRequest(ExistingRun existing,
                                     KnowledgeChatRequest request,
                                     String requestJson) {
        if (!Objects.equals(existing.conversationId(), request.getConversationId())
            || !Objects.equals(existing.projectId(), request.getProjectId())
            || !equivalentJson(existing.requestJson(), requestJson)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "requestId already used by another request");
        }
    }

    private boolean equivalentJson(String left, String right) {
        try {
            return Objects.equals(objectMapper.readTree(left), objectMapper.readTree(right));
        } catch (JsonProcessingException ex) {
            return Objects.equals(left, right);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chat run json serialization failed");
        }
    }

    private void lockConversation(String conversationId, Long userId) {
        jdbcTemplate.queryForObject(
            "select conversation_id from ai_conversation where conversation_id = ? and user_id = ? " +
                "and status <> 'ARCHIVED' for update",
            String.class,
            conversationId,
            userId
        );
    }

    private ExistingRun lockRunningRun(String runId) {
        List<ExistingRun> runs = jdbcTemplate.query(
            runSelect() + " where run_id = ? and status = 'RUNNING' and cancel_requested = false " +
                "and deleted = 0 for update",
            this::mapExistingRun,
            runId
        );
        return runs.isEmpty() ? null : runs.get(0);
    }

    private ExistingRun lockFencedRun(String runId,
                                      String leaseOwner,
                                      long fencingToken,
                                      String expectedStatus,
                                      boolean cancelRequested) {
        List<ExistingRun> runs = jdbcTemplate.query(
            runSelect() + " where run_id = ? and status = ? and cancel_requested = ? " +
                "and lease_owner = ? and fencing_token = ? and lease_expires_at >= current_timestamp " +
                "and deleted = 0 for update",
            this::mapExistingRun,
            trimToNull(runId),
            expectedStatus,
            cancelRequested,
            trimToNull(leaseOwner),
            fencingToken
        );
        return runs.isEmpty() ? null : runs.get(0);
    }

    private Long appendPartialMessageIfPresent(ExistingRun run, String terminalStatus) {
        if (run == null) {
            return null;
        }
        return appendPartialMessageIfPresent(
            run.runId(), run.conversationId(), run.answer(), terminalStatus
        );
    }

    private Long appendPartialMessageIfPresent(String runId,
                                                String conversationId,
                                                String answer,
                                                String terminalStatus) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        KnowledgeChatMessageVO message = conversationService.appendMessage(
            conversationId,
            runId,
            "ASSISTANT",
            answer,
            writeJson(Map.of("status", "PARTIAL", "terminalStatus", terminalStatus)),
            null
        );
        return message.getMessageId();
    }

    private ExistingRun findOwnedByIdempotencyKey(Long userId, String requestId) {
        List<ExistingRun> runs = jdbcTemplate.query(
            runSelect() + " where user_id = ? and request_id = ? and deleted = 0 " +
                "order by attempt_no desc limit 1",
            this::mapExistingRun,
            userId,
            requestId
        );
        return runs.isEmpty() ? null : runs.get(0);
    }

    private ExistingRun findOwnedByRunId(String runId, Long userId) {
        List<ExistingRun> runs = jdbcTemplate.query(
            runSelect() + " where run_id = ? and user_id = ? and deleted = 0",
            this::mapExistingRun,
            runId,
            userId
        );
        if (runs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat run not found");
        }
        return runs.get(0);
    }

    private String runSelect() {
        return "select run_id, user_id, project_id, conversation_id, request_json, status, result_json, answer " +
            "from ai_chat_run";
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private ExistingRun mapExistingRun(ResultSet rs, int rowNum) throws SQLException {
        long userId = rs.getLong("user_id");
        long projectId = rs.getLong("project_id");
        Long nullableProjectId = rs.wasNull() ? null : projectId;
        return new ExistingRun(
            rs.getString("run_id"),
            userId,
            nullableProjectId,
            rs.getString("conversation_id"),
            rs.getString("request_json"),
            rs.getString("status"),
            rs.getString("result_json"),
            rs.getString("answer")
        );
    }

    private String truncate(String value, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Duration normalizeLeaseDuration(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            return Duration.ofMinutes(2);
        }
        return value.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10) : value;
    }

    private Duration normalizeCancellingGrace(Duration value) {
        if (value == null || value.isNegative()) {
            return Duration.ofSeconds(3);
        }
        return value.compareTo(Duration.ofMinutes(1)) > 0 ? Duration.ofMinutes(1) : value;
    }

    private String normalizeExecutionMode(String value) {
        return "DEEP".equalsIgnoreCase(trimToNull(value)) ? "DEEP" : "FAST";
    }

    private int maxActiveRuns(String executionMode) {
        KnowledgeProperties.ResourcePolicy policy = knowledgeProperties.getResourcePolicy();
        return "DEEP".equals(executionMode)
            ? policy.getMaxActiveDeepRuns()
            : policy.getMaxActiveFastRuns();
    }

    private void assertResourceAdmission(String executionMode) {
        if (resourcePressureBlocks(executionMode)) {
            throw new BusinessException(
                ResultCode.SERVICE_UNAVAILABLE,
                "\u7cfb\u7edf\u8d44\u6e90\u7d27\u5f20\uff0c\u5df2\u6682\u505c\u6df1\u5ea6\u56de\u7b54\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"
            );
        }
    }

    private boolean resourcePressureBlocks(String executionMode) {
        return "DEEP".equals(executionMode) && resourcePressureService.shouldRejectDeepRun();
    }

    public record QueuedRunStart(String runId, boolean created) {
    }

    public record BlockingRunStart(String runId,
                                   boolean execute,
                                   String existingResponseJson,
                                   String leaseOwner,
                                   long fencingToken) {
    }

    public record RunLease(String runId,
                           String leaseOwner,
                           long fencingToken,
                           String executionMode,
                           Timestamp leaseExpiresAt) {
    }

    private record LeaseCandidate(String status, Timestamp leaseExpiresAt, long fencingToken) {
    }

    public enum RecoveryResult {
        NONE,
        REQUEUED,
        CANCELLED,
        FAILED
    }

    private record RecoverableRun(String runId,
                                  Long userId,
                                  Long projectId,
                                  String conversationId,
                                  String question,
                                  String requestJson,
                                  String requestId,
                                  String idempotencyKey,
                                  Long triggerMessageId,
                                  String executionMode,
                                  String resourceBudgetJson,
                                  String status,
                                   int retryCount,
                                   int maxRetries,
                                   int attemptNo,
                                   String answer,
                                   String leaseOwner,
                                   Timestamp leaseExpiresAt,
                                  Timestamp updateTime) {
    }

    private record ExistingRun(String runId,
                               Long userId,
                               Long projectId,
                               String conversationId,
                               String requestJson,
                               String status,
                               String resultJson,
                               String answer) {
    }
}
