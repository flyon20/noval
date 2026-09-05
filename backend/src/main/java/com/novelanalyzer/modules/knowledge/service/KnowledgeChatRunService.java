package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.config.KnowledgeChatRunSchedulingConfig;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledFuture;

@Service
public class KnowledgeChatRunService implements KnowledgeChatRunRabbitConsumer.ExecutionPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunService.class);
    private static final int MAX_LIST_LIMIT = 50;
    private static final Set<String> CONTEXT_PROGRESS_EVENTS = Set.of(
        "context_compacting",
        "context_compacted"
    );
    private static final List<String> CONTEXT_PROGRESS_NUMERIC_FIELDS = List.of(
        "contextWindowTokens",
        "thresholdTokens",
        "beforeInputTokens",
        "afterInputTokens",
        "retainedTurnCount",
        "summarizedMessageCount",
        "generation"
    );

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeChatService knowledgeChatService;
    private final KnowledgeChatPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
    private KnowledgeChatRunQueueService queueService;
    private TaskScheduler heartbeatTaskScheduler;
    private TaskScheduler deltaTaskScheduler;
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationSignals = new ConcurrentHashMap<>();

    @Autowired
    public KnowledgeChatRunService(JdbcTemplate jdbcTemplate,
                                   KnowledgeChatService knowledgeChatService,
                                   KnowledgeChatPersistenceService persistenceService,
                                   ObjectMapper objectMapper,
                                   @Qualifier("analysisStreamTaskExecutor") TaskExecutor executor) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeChatService = knowledgeChatService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Autowired
    void configureDurableExecution(KnowledgeProperties properties,
                                   ObjectProvider<KnowledgeChatRunQueueService> queueServiceProvider,
                                   @Qualifier(KnowledgeChatRunSchedulingConfig.CHAT_RUN_HEARTBEAT_TASK_SCHEDULER)
                                   ObjectProvider<TaskScheduler> heartbeatTaskSchedulerProvider,
                                   @Qualifier(KnowledgeChatRunSchedulingConfig.CHAT_RUN_DELTA_TASK_SCHEDULER)
                                   ObjectProvider<TaskScheduler> deltaTaskSchedulerProvider) {
        this.knowledgeProperties = properties == null ? new KnowledgeProperties() : properties;
        this.queueService = queueServiceProvider.getIfAvailable();
        this.heartbeatTaskScheduler = heartbeatTaskSchedulerProvider.getIfAvailable();
        this.deltaTaskScheduler = deltaTaskSchedulerProvider.getIfAvailable();
    }

    public KnowledgeChatRunVO startRun(KnowledgeChatRequest request) {
        AuthUser user = requireUser();
        KnowledgeChatRequest normalized = normalizeRequest(request);
        String proposedRunId = "chatrun-" + UUID.randomUUID();
        String requestJson = writeJson(normalized);
        KnowledgeChatPersistenceService.QueuedRunStart start = persistenceService.createQueuedRun(
            proposedRunId,
            user,
            normalized,
            requestJson
        );
        String runId = start.runId();
        if (!start.created()) {
            return getRun(runId);
        }
        if (knowledgeProperties.getChatRun().isQueueEnabled() && queueService != null) {
            return getRun(runId);
        }
        try {
            executor.execute(() -> executeRun(runId, user, normalized, 1));
        } catch (RuntimeException ex) {
            persistenceService.markSubmissionFailed(runId, ex.getMessage());
            return getRun(runId);
        }
        return getRun(runId);
    }

    public KnowledgeChatRunVO getRun(String runId) {
        AuthUser user = requireUser();
        List<KnowledgeChatRunVO> runs = jdbcTemplate.query("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       snapshot_sequence_no,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run
                where run_id = ? and user_id = ? and deleted = 0
                """,
            (rs, rowNum) -> mapRun(rs),
            trimToNull(runId),
            user.getUserId()
        );
        if (runs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat run not found");
        }
        return runs.get(0);
    }

    public List<KnowledgeChatRunVO> listConversationRuns(String conversationId, Integer limit) {
        AuthUser user = requireUser();
        String normalizedConversationId = trimToNull(conversationId);
        if (normalizedConversationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "conversationId is required");
        }
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return jdbcTemplate.query("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       snapshot_sequence_no,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run
                where conversation_id = ? and user_id = ? and deleted = 0
                order by queued_at desc, run_id desc
                limit ?
                """,
            (rs, rowNum) -> mapRun(rs),
            normalizedConversationId,
            user.getUserId(),
            safeLimit
        );
    }

    public List<KnowledgeChatRunVO> listRecentRuns(Long projectId, Integer limit) {
        AuthUser user = requireUser();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        if (projectId == null) {
            return jdbcTemplate.query("""
                    select run_id, user_id, project_id, conversation_id, question, status,
                           progress_phase, progress_message, answer, result_json, trace_id, source_count,
                           snapshot_sequence_no,
                           error_message, cancel_requested, retry_count, max_retries,
                           queued_at, started_at, finished_at, update_time
                    from ai_chat_run r
                    where user_id = ? and project_id is null and deleted = 0
                      and not exists (
                          select 1 from ai_chat_run newer
                          where newer.user_id = r.user_id
                            and newer.conversation_id = r.conversation_id
                            and newer.project_id is null
                            and newer.deleted = 0
                            and (newer.queued_at > r.queued_at or (newer.queued_at = r.queued_at and newer.run_id > r.run_id))
                      )
                    order by update_time desc, queued_at desc, run_id desc
                    limit ?
                    """,
                (rs, rowNum) -> mapRun(rs),
                user.getUserId(),
                safeLimit
            );
        }
        return jdbcTemplate.query("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       snapshot_sequence_no,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run r
                where user_id = ? and project_id = ? and deleted = 0
                  and not exists (
                      select 1 from ai_chat_run newer
                      where newer.user_id = r.user_id
                        and newer.conversation_id = r.conversation_id
                        and newer.project_id = r.project_id
                        and newer.deleted = 0
                        and (newer.queued_at > r.queued_at or (newer.queued_at = r.queued_at and newer.run_id > r.run_id))
                  )
                order by update_time desc, queued_at desc, run_id desc
                limit ?
                """,
            (rs, rowNum) -> mapRun(rs),
            user.getUserId(),
            projectId,
            safeLimit
        );
    }

    public KnowledgeChatRunVO cancelRun(String runId) {
        AuthUser user = requireUser();
        KnowledgeChatRunVO current = getRun(runId);
        if (!isTerminal(current.getStatus())) {
            persistenceService.requestCancellation(runId, user.getUserId());
            cancellationSignals.computeIfAbsent(runId, key -> new AtomicBoolean()).set(true);
            if (knowledgeProperties.getChatRun().isQueueEnabled() && queueService != null) {
                queueService.publishCancel(runId);
            }
        }
        return getRun(runId);
    }

    @Override
    public void execute(String runId) {
        RunExecutionContext context = loadExecutionContext(runId);
        if (context != null) {
            executeRun(runId, context.user(), context.request(), context.attemptNo());
        }
    }

    @Override
    public void cancel(String runId) {
        cancellationSignals.computeIfAbsent(runId, key -> new AtomicBoolean()).set(true);
    }

    private void executeRun(String runId,
                            AuthUser user,
                            KnowledgeChatRequest request,
                            int attemptNo) {
        AuthUser previousUser = AuthUserHolder.get();
        String previousTraceId = TraceIdHolder.get();
        String checkpointThreadId = resolveCheckpointThreadId(runId);
        String leaseOwner = "chat-worker-" + UUID.randomUUID();
        KnowledgeChatPersistenceService.RunLease lease = null;
        ScheduledFuture<?> heartbeatTask = null;
        ScheduledFuture<?> deltaFlushTask = null;
        AtomicBoolean cancelSignal = cancellationSignals.computeIfAbsent(
            runId, key -> new AtomicBoolean(false)
        );
        try {
            AuthUserHolder.set(user);
            TraceIdHolder.set(checkpointThreadId);
            lease = persistenceService.claimRun(runId, leaseOwner, leaseDuration());
            if (lease == null) {
                persistenceService.deferPendingExecution(runId, Duration.ofSeconds(2));
                return;
            }
            long fencingToken = lease.fencingToken();
            StringBuilder partialAnswer = new StringBuilder();
            StringBuilder pendingDelta = new StringBuilder();
            Object deltaLock = new Object();
            AtomicLong progressCounter = new AtomicLong();
            AtomicLong deltaChunkCounter = new AtomicLong();
            AtomicLong lastSnapshotNanos = new AtomicLong(System.nanoTime());
            AtomicLong totalAnswerBytes = new AtomicLong();
            AtomicLong lastSnapshotBytes = new AtomicLong();
            AtomicLong pendingDeltaBytes = new AtomicLong();
            AtomicBoolean leaseLost = new AtomicBoolean(false);
            request.setResumeFromCheckpoint(attemptNo > 1);
            Runnable flushDelta = () -> {
                synchronized (deltaLock) {
                    if (pendingDelta.isEmpty()) {
                        return;
                    }
                    String deltaChunk = pendingDelta.toString();
                    long nextChunkNo = deltaChunkCounter.get() + 1L;
                    long answerBytes = totalAnswerBytes.get();
                    long now = System.nanoTime();
                    boolean snapshotDue = lastSnapshotBytes.get() == 0
                        || answerBytes - lastSnapshotBytes.get() >= 2048
                        || now - lastSnapshotNanos.get() >= 750_000_000L;
                    Long sequence;
                    try {
                        sequence = persistenceService.appendFencedEventAndSnapshot(
                            runId,
                            leaseOwner,
                            fencingToken,
                            "DELTA",
                            "node:compose_answer:attempt:" + attemptNo
                                + ":event:delta:chunk:" + nextChunkNo,
                            Map.of("delta", deltaChunk),
                            null,
                            null,
                            snapshotDue ? partialAnswer.toString() : null
                        );
                    } catch (RuntimeException ex) {
                        LOGGER.warn(
                            "knowledge chat delta snapshot failed: runId={}, reason={}",
                            runId,
                            ex.getMessage()
                        );
                        return;
                    }
                    if (sequence == null) {
                        leaseLost.set(true);
                    } else {
                        pendingDelta.setLength(0);
                        pendingDeltaBytes.set(0);
                        deltaChunkCounter.set(nextChunkNo);
                        if (snapshotDue) {
                            lastSnapshotBytes.set(answerBytes);
                            lastSnapshotNanos.set(now);
                        }
                    }
                }
            };
            if (heartbeatTaskScheduler != null) {
                heartbeatTask = heartbeatTaskScheduler.scheduleAtFixedRate(() -> {
                    try {
                        boolean renewed = persistenceService.heartbeatRun(
                            runId, leaseOwner, fencingToken, leaseDuration()
                        );
                        if (!renewed) {
                            leaseLost.set(true);
                        }
                    } catch (RuntimeException ex) {
                        leaseLost.set(true);
                        LOGGER.warn(
                            "knowledge chat run heartbeat failed: runId={}, reason={}",
                            runId,
                            ex.getMessage()
                        );
                    }
                }, heartbeatInterval());
            }
            if (deltaTaskScheduler != null) {
                deltaFlushTask = deltaTaskScheduler.scheduleAtFixedRate(
                    () -> runWithUser(user, flushDelta),
                    Duration.ofMillis(750)
                );
            }
            KnowledgeChatResponseVO response;
            try {
                response = knowledgeChatService.chatWithProgressForDurableRun(
                    request,
                    new KnowledgeChatService.ChatProgressListener() {
                    @Override
                    public void onProgress(String phase, String message) {
                        onProgress(phase, message, Map.of());
                    }

                    @Override
                    public void onProgress(String phase, String message, Map<String, Object> details) {
                        Map<String, Object> progressPayload = sanitizeProgressPayload(phase, message, details);
                        String eventType = durableProgressEventType(progressPayload);
                        Long sequence;
                        try {
                            sequence = persistenceService.appendFencedEventAndSnapshot(
                                runId,
                                leaseOwner,
                                fencingToken,
                                eventType,
                                "node:" + (phase == null ? "progress" : phase)
                                    + ":attempt:" + attemptNo
                                    + ":event:" + eventType.toLowerCase(java.util.Locale.ROOT)
                                    + ":chunk:" + progressCounter.incrementAndGet(),
                                progressPayload,
                                phase,
                                message,
                                null
                            );
                        } catch (RuntimeException ex) {
                            LOGGER.warn(
                                "knowledge chat progress snapshot failed: runId={}, eventType={}, reason={}",
                                runId, eventType, ex.getMessage()
                            );
                            return;
                        }
                        if (sequence == null) {
                            leaseLost.set(true);
                        }
                    }

                    @Override
                    public void onDelta(String delta) {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        int deltaBytes = delta.getBytes(StandardCharsets.UTF_8).length;
                        synchronized (deltaLock) {
                            partialAnswer.append(delta);
                            pendingDelta.append(delta);
                            totalAnswerBytes.addAndGet(deltaBytes);
                            pendingDeltaBytes.addAndGet(deltaBytes);
                        }
                        if (deltaChunkCounter.get() == 0
                            || pendingDeltaBytes.get() >= 2048
                            || System.nanoTime() - lastSnapshotNanos.get() >= 750_000_000L) {
                            flushDelta.run();
                        }
                    }
                    },
                    () -> leaseLost.get() || cancelSignal.get() || isCancelRequested(runId)
                );
            } finally {
                flushDelta.run();
            }
            if (isCancelRequested(runId)) {
                persistenceService.requestCancellation(runId, user.getUserId());
                persistenceService.completeCancelledRun(
                    runId, leaseOwner, fencingToken, "user requested cancellation"
                );
                return;
            }
            if (response == null) {
                persistenceService.completeFailedRun(
                    runId, leaseOwner, fencingToken, "worker returned no response"
                );
                return;
            }
            knowledgeChatService.prepareMemoryCandidatesForDurablePersistence(
                user.getUserId(), request, response
            );
            Map<String, Object> persistedResult = new LinkedHashMap<>(response.getResultJson());
            persistedResult.put("_runStatus", response.getStatus());
            persistedResult.put("_actions", response.getActions());
            persistedResult.put("_sources", response.getSources());
            persistedResult.put("_candidates", response.getCandidates());
            String resultJson = writeJson(persistedResult);
            boolean completed = persistenceService.completeAnsweredRun(
                runId,
                leaseOwner,
                fencingToken,
                response.getAnswer(),
                resultJson,
                resolveTraceId(response),
                response.getSources() == null ? 0 : response.getSources().size()
            );
            if (!completed && isCancelRequested(runId)) {
                persistenceService.requestCancellation(runId, user.getUserId());
                persistenceService.completeCancelledRun(
                    runId, leaseOwner, fencingToken, "cancelled during terminal commit"
                );
            } else if (!completed) {
                throw new IllegalStateException("chat run completion state changed");
            }
        } catch (Exception ex) {
            LOGGER.warn("knowledge chat run failed: runId={}, reason={}", runId, ex.getMessage());
            if (lease == null) {
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("chat run claim failed", ex);
            }
            if (lease != null) {
                if (isCancelRequested(runId)) {
                    persistenceService.requestCancellation(runId, user.getUserId());
                    persistenceService.completeCancelledRun(
                        runId, leaseOwner, lease.fencingToken(), ex.getMessage()
                    );
                } else {
                    persistenceService.completeFailedRun(
                        runId, leaseOwner, lease.fencingToken(), ex.getMessage()
                    );
                }
            }
        } finally {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
            }
            if (deltaFlushTask != null) {
                deltaFlushTask.cancel(false);
            }
            cancellationSignals.remove(runId, cancelSignal);
            if (previousUser == null) {
                AuthUserHolder.clear();
            } else {
                AuthUserHolder.set(previousUser);
            }
            if (previousTraceId == null || previousTraceId.isBlank()) {
                TraceIdHolder.clear();
            } else {
                TraceIdHolder.set(previousTraceId);
            }
        }
    }

    private static Map<String, Object> sanitizeProgressPayload(String phase,
                                                               String message,
                                                               Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase == null ? "" : phase);
        payload.put("message", message == null ? "" : message);
        if (details == null || details.isEmpty()) {
            return payload;
        }
        String progressEvent = String.valueOf(details.getOrDefault("progressEvent", "")).trim();
        if (!CONTEXT_PROGRESS_EVENTS.contains(progressEvent)) {
            return payload;
        }
        payload.put("progressEvent", progressEvent);
        for (String field : CONTEXT_PROGRESS_NUMERIC_FIELDS) {
            Long value = nonNegativeLong(details.get(field));
            if (value != null) {
                payload.put(field, value);
            }
        }
        return payload;
    }

    private static Long nonNegativeLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed >= 0L ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String durableProgressEventType(Map<String, Object> payload) {
        String progressEvent = String.valueOf(payload.getOrDefault("progressEvent", ""));
        return switch (progressEvent) {
            case "context_compacting" -> "CONTEXT_COMPACTING";
            case "context_compacted" -> "CONTEXT_COMPACTED";
            default -> "PROGRESS";
        };
    }

    private KnowledgeChatRequest normalizeRequest(KnowledgeChatRequest request) {
        if (request == null || trimToNull(request.getQuestion()) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "question is required");
        }
        request.setQuestion(request.getQuestion().trim());
        if (trimToNull(request.getConversationId()) == null) {
            request.setConversationId("conv-" + UUID.randomUUID());
        } else {
            request.setConversationId(request.getConversationId().trim());
        }
        if (trimToNull(request.getRequestId()) == null) {
            request.setRequestId("request-" + UUID.randomUUID());
        } else {
            request.setRequestId(request.getRequestId().trim());
        }
        if (request.getRequestId().length() > 80) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "requestId is too long");
        }
        return request;
    }

    private void runWithUser(AuthUser user, Runnable task) {
        AuthUser previous = AuthUserHolder.get();
        try {
            AuthUserHolder.set(user);
            task.run();
        } finally {
            if (previous == null) {
                AuthUserHolder.clear();
            } else {
                AuthUserHolder.set(previous);
            }
        }
    }

    private RunExecutionContext loadExecutionContext(String runId) {
        List<RunExecutionContext> contexts = jdbcTemplate.query("""
                select user_id, request_json, attempt_no
                from ai_chat_run
                where run_id = ? and deleted = 0
                """,
            (rs, rowNum) -> {
                try {
                    KnowledgeChatRequest request = objectMapper.readValue(
                        rs.getString("request_json"),
                        KnowledgeChatRequest.class
                    );
                    return new RunExecutionContext(
                        AuthUser.of(rs.getLong("user_id"), "chat-run-worker", Set.of("USER")),
                        request,
                        rs.getInt("attempt_no")
                    );
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("chat run request json is invalid", ex);
                }
            },
            trimToNull(runId)
        );
        return contexts.isEmpty() ? null : contexts.get(0);
    }

    private boolean isCancelRequested(String runId) {
        Boolean cancelled = jdbcTemplate.queryForObject(
            "select cancel_requested from ai_chat_run where run_id = ? and deleted = 0",
            Boolean.class,
            runId
        );
        return Boolean.TRUE.equals(cancelled);
    }

    private KnowledgeChatRunVO mapRun(ResultSet rs) throws SQLException {
        KnowledgeChatRunVO vo = new KnowledgeChatRunVO();
        vo.setRunId(rs.getString("run_id"));
        vo.setUserId(rs.getLong("user_id"));
        long projectId = rs.getLong("project_id");
        vo.setProjectId(rs.wasNull() ? null : projectId);
        vo.setConversationId(rs.getString("conversation_id"));
        vo.setQuestion(rs.getString("question"));
        vo.setStatus(rs.getString("status"));
        vo.setProgressPhase(rs.getString("progress_phase"));
        vo.setProgressMessage(rs.getString("progress_message"));
        vo.setAnswer(rs.getString("answer"));
        vo.setResultJson(rs.getString("result_json"));
        vo.setTraceId(rs.getString("trace_id"));
        vo.setSourceCount(rs.getInt("source_count"));
        vo.setSnapshotSequenceNo(rs.getLong("snapshot_sequence_no"));
        vo.setErrorMessage(rs.getString("error_message"));
        vo.setCancelRequested(rs.getBoolean("cancel_requested"));
        vo.setRetryCount(rs.getInt("retry_count"));
        vo.setMaxRetries(rs.getInt("max_retries"));
        vo.setQueuedAt(timestampString(rs.getTimestamp("queued_at")));
        vo.setStartedAt(timestampString(rs.getTimestamp("started_at")));
        vo.setFinishedAt(timestampString(rs.getTimestamp("finished_at")));
        vo.setUpdatedAt(timestampString(rs.getTimestamp("update_time")));
        return vo;
    }

    private String resolveTraceId(KnowledgeChatResponseVO response) {
        if (response == null || response.getResultJson() == null) {
            return null;
        }
        Object direct = response.getResultJson().get("traceId");
        String traceId = trimToNull(direct == null ? null : String.valueOf(direct));
        if (traceId != null) {
            return traceId;
        }
        Object directSnake = response.getResultJson().get("trace_id");
        traceId = trimToNull(directSnake == null ? null : String.valueOf(directSnake));
        if (traceId != null) {
            return traceId;
        }
        Object trace = response.getResultJson().get("trace");
        if (trace instanceof Map<?, ?> traceMap) {
            Object nested = traceMap.get("traceId");
            traceId = trimToNull(nested == null ? null : String.valueOf(nested));
            if (traceId != null) {
                return traceId;
            }
            Object nestedSnake = traceMap.get("trace_id");
            return trimToNull(nestedSnake == null ? null : String.valueOf(nestedSnake));
        }
        return null;
    }

    private boolean isTerminal(String status) {
        return "ANSWERED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private String writeJson(Object value) {
        try {
            Object safeValue = value == null ? Map.of() : value;
            return objectMapper.writeValueAsString(safeValue);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chat run json serialization failed");
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String timestampString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }

    private String resolveCheckpointThreadId(String runId) {
        String current = trimToNull(runId);
        for (int depth = 0; depth < 8 && current != null; depth++) {
            List<String> parents = jdbcTemplate.query(
                "select parent_run_id from ai_chat_run where run_id = ? and deleted = 0",
                (rs, rowNum) -> rs.getString("parent_run_id"),
                current
            );
            if (parents.isEmpty()) {
                return trimToNull(runId);
            }
            String parent = trimToNull(parents.get(0));
            if (parent == null) {
                return current;
            }
            current = parent;
        }
        return trimToNull(runId);
    }

    private Duration leaseDuration() {
        return Duration.ofSeconds(Math.max(2, knowledgeProperties.getChatRun().getLeaseSeconds()));
    }

    private Duration heartbeatInterval() {
        return Duration.ofSeconds(Math.max(1, knowledgeProperties.getChatRun().getHeartbeatSeconds()));
    }

    private record RunExecutionContext(AuthUser user, KnowledgeChatRequest request, int attemptNo) {
    }
}
