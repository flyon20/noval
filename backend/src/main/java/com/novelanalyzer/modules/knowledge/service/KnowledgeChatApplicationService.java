package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeChatRunSchedulingConfig;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class KnowledgeChatApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatApplicationService.class);
    private static final Duration COMPATIBILITY_DELTA_FLUSH_INTERVAL = Duration.ofMillis(750);
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

    private final KnowledgeChatService chatService;
    private final KnowledgeChatPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
    private TaskScheduler heartbeatTaskScheduler;
    private TaskScheduler deltaTaskScheduler;
    private KnowledgeChatRunRecoveryService recoveryService;

    public KnowledgeChatApplicationService(KnowledgeChatService chatService,
                                           KnowledgeChatPersistenceService persistenceService,
                                           ObjectMapper objectMapper) {
        this(chatService, persistenceService, objectMapper, null);
    }

    @Autowired
    public KnowledgeChatApplicationService(KnowledgeChatService chatService,
                                           KnowledgeChatPersistenceService persistenceService,
                                           ObjectMapper objectMapper,
                                           KnowledgeChatRunRecoveryService recoveryService) {
        this.chatService = chatService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.recoveryService = recoveryService;
    }

    @Autowired
    void configureCompatibilityHeartbeat(
        KnowledgeProperties knowledgeProperties,
        @Qualifier(KnowledgeChatRunSchedulingConfig.CHAT_RUN_HEARTBEAT_TASK_SCHEDULER)
        ObjectProvider<TaskScheduler> heartbeatTaskSchedulerProvider,
        @Qualifier(KnowledgeChatRunSchedulingConfig.CHAT_RUN_DELTA_TASK_SCHEDULER)
        ObjectProvider<TaskScheduler> deltaTaskSchedulerProvider
    ) {
        this.knowledgeProperties = knowledgeProperties == null
            ? new KnowledgeProperties()
            : knowledgeProperties;
        this.heartbeatTaskScheduler = heartbeatTaskSchedulerProvider.getIfAvailable();
        this.deltaTaskScheduler = deltaTaskSchedulerProvider.getIfAvailable();
    }

    public KnowledgeChatResponseVO chat(KnowledgeChatRequest request) {
        normalize(request);
        AuthUser user = requireUser();
        String requestJson = writeJson(request, "chat request serialization failed");
        KnowledgeChatPersistenceService.BlockingRunStart start = persistenceService.beginBlockingRun(
            "chatrun-" + UUID.randomUUID(),
            user,
            request,
            requestJson
        );
        if (trimToNull(start.existingResponseJson()) != null) {
            return readResponse(start.existingResponseJson());
        }
        CompatibilityLeaseMonitor leaseMonitor = startCompatibilityHeartbeat(start);
        String previousTraceId = TraceIdHolder.get();
        TraceIdHolder.set(start.runId());
        try {
            KnowledgeChatResponseVO response = chatService.chatForDurableCommit(
                request, leaseMonitor.leaseLost()::get
            );
            if (leaseMonitor.leaseLost().get()) {
                throw new IllegalStateException("blocking chat lease lost");
            }
            return completeCompatibilityAnswer(start, user, request, response, "blocking");
        } catch (RuntimeException ex) {
            if (!leaseMonitor.leaseLost().get()) {
                persistenceService.completeFailedRun(
                    start.runId(), start.leaseOwner(), start.fencingToken(), ex.getMessage()
                );
            }
            throw ex;
        } finally {
            restoreTraceId(previousTraceId);
            cancelMonitor(leaseMonitor);
        }
    }

    public SseEmitter streamChat(KnowledgeChatRequest request) {
        normalize(request);
        AuthUser user = requireUser();
        String requestJson = writeJson(request, "chat request serialization failed");
        KnowledgeChatPersistenceService.BlockingRunStart start = persistenceService.beginBlockingRun(
            "chatrun-" + UUID.randomUUID(),
            user,
            request,
            requestJson
        );
        if (trimToNull(start.existingResponseJson()) != null) {
            return completedEmitter(readResponse(start.existingResponseJson()));
        }
        CompatibilityLeaseMonitor leaseMonitor = startCompatibilityHeartbeat(start);
        CompatibilityDeltaBuffer deltaBuffer = new CompatibilityDeltaBuffer(
            start, leaseMonitor.leaseLost(), user
        );
        ScheduledFuture<?> deltaFlush = startCompatibilityDeltaFlush(deltaBuffer);
        String previousTraceId = TraceIdHolder.get();
        // KnowledgeChatService captures this identity before scheduling its SSE worker.
        TraceIdHolder.set(start.runId());
        try {
            return chatService.streamChatForDurableCommit(
                request,
                new KnowledgeChatService.StreamLifecycleListener() {
                @Override
                public void onDelta(String delta) {
                    deltaBuffer.onDelta(delta);
                }

                @Override
                public void onProgress(String phase, String message, Map<String, Object> details) {
                    deltaBuffer.onProgress(phase, message, details);
                }

                @Override
                public void onCompleted(KnowledgeChatResponseVO response) {
                    deltaBuffer.flush();
                    cancelFuture(deltaFlush);
                    try {
                        if (leaseMonitor.leaseLost().get()) {
                            throw new IllegalStateException("stream chat lease lost");
                        }
                        applyResponse(
                            response,
                            completeCompatibilityAnswer(start, user, request, response, "stream")
                        );
                    } finally {
                        cancelMonitor(leaseMonitor);
                    }
                }

                @Override
                public void onFailed(Exception error) {
                    deltaBuffer.flush();
                    cancelFuture(deltaFlush);
                    try {
                        if (!leaseMonitor.leaseLost().get()) {
                            persistenceService.updateCompatibilityPartialAnswer(
                                start.runId(), start.leaseOwner(), start.fencingToken(),
                                deltaBuffer.partialAnswer()
                            );
                            persistenceService.completeFailedRun(
                                start.runId(), start.leaseOwner(), start.fencingToken(), error.getMessage()
                            );
                        }
                    } finally {
                        cancelMonitor(leaseMonitor);
                    }
                }
            }, leaseMonitor.leaseLost()::get);
        } catch (RuntimeException ex) {
            deltaBuffer.flush();
            cancelFuture(deltaFlush);
            try {
                if (!leaseMonitor.leaseLost().get()) {
                    persistenceService.completeFailedRun(
                        start.runId(), start.leaseOwner(), start.fencingToken(), ex.getMessage()
                    );
                }
            } finally {
                cancelMonitor(leaseMonitor);
            }
            throw ex;
        } finally {
            restoreTraceId(previousTraceId);
        }
    }

    private static void restoreTraceId(String previousTraceId) {
        if (previousTraceId == null) {
            TraceIdHolder.clear();
        } else {
            TraceIdHolder.set(previousTraceId);
        }
    }

    private KnowledgeChatResponseVO completeCompatibilityAnswer(
        KnowledgeChatPersistenceService.BlockingRunStart start,
        AuthUser user,
        KnowledgeChatRequest request,
        KnowledgeChatResponseVO response,
        String route
    ) {
        chatService.prepareMemoryCandidatesForDurablePersistence(user.getUserId(), request, response);
        markPostProcessingPending(response);
        String responseJson = writeJson(response, "chat response serialization failed");
        boolean completed = persistenceService.completeAnsweredRun(
            start.runId(),
            start.leaseOwner(),
            start.fencingToken(),
            response == null ? null : response.getAnswer(),
            responseJson,
            resolveTraceId(response),
            response == null || response.getSources() == null ? 0 : response.getSources().size()
        );
        if (!completed) {
            throw new IllegalStateException(route + " chat completion state changed");
        }
        return trySynchronousPostProcessing(start.runId(), user.getUserId(), response);
    }

    private KnowledgeChatResponseVO trySynchronousPostProcessing(
        String runId,
        Long userId,
        KnowledgeChatResponseVO fallback
    ) {
        if (recoveryService == null) {
            return fallback;
        }
        try {
            if (!recoveryService.dispatchTerminalOutboxForRun(runId)) {
                return fallback;
            }
            Map<String, Object> resultJson = new LinkedHashMap<>(fallback.getResultJson());
            resultJson.put("postProcessingStatus", "completed");
            fallback.setResultJson(resultJson);
            String stored = persistenceService.findCompletedResponseJson(runId, userId);
            if (trimToNull(stored) != null) {
                return readResponse(stored);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "compatibility chat post-processing deferred: runId={}, reason={}",
                runId,
                ex.getMessage()
            );
        }
        return fallback;
    }

    private void markPostProcessingPending(KnowledgeChatResponseVO response) {
        if (response == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("postProcessingStatus", "pending");
        response.setResultJson(resultJson);
    }

    private void applyResponse(KnowledgeChatResponseVO target, KnowledgeChatResponseVO source) {
        if (target == null || source == null || target == source) {
            return;
        }
        target.setStatus(source.getStatus());
        target.setAnswer(source.getAnswer());
        target.setCandidates(source.getCandidates());
        target.setSources(source.getSources());
        target.setActions(source.getActions());
        target.setResultJson(source.getResultJson());
    }

    private CompatibilityLeaseMonitor startCompatibilityHeartbeat(
        KnowledgeChatPersistenceService.BlockingRunStart start
    ) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        if (heartbeatTaskScheduler == null || start.leaseOwner() == null) {
            return new CompatibilityLeaseMonitor(leaseLost, null);
        }
        Duration interval = Duration.ofSeconds(
            Math.max(1, knowledgeProperties.getChatRun().getHeartbeatSeconds())
        );
        Duration lease = Duration.ofSeconds(
            Math.max(2, knowledgeProperties.getChatRun().getLeaseSeconds())
        );
        ScheduledFuture<?> future = heartbeatTaskScheduler.scheduleAtFixedRate(
            () -> {
                try {
                    if (!persistenceService.heartbeatRun(
                        start.runId(), start.leaseOwner(), start.fencingToken(), lease
                    )) {
                        leaseLost.set(true);
                    }
                } catch (RuntimeException ex) {
                    leaseLost.set(true);
                    LOGGER.warn(
                        "compatibility chat heartbeat failed: runId={}, reason={}",
                        start.runId(), ex.getMessage()
                    );
                }
            },
            interval
        );
        return new CompatibilityLeaseMonitor(leaseLost, future);
    }

    private ScheduledFuture<?> startCompatibilityDeltaFlush(CompatibilityDeltaBuffer buffer) {
        if (deltaTaskScheduler == null) {
            return null;
        }
        return deltaTaskScheduler.scheduleAtFixedRate(
            buffer::flush,
            COMPATIBILITY_DELTA_FLUSH_INTERVAL
        );
    }

    private void cancelMonitor(CompatibilityLeaseMonitor monitor) {
        if (monitor != null) {
            cancelFuture(monitor.heartbeat());
        }
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void normalize(KnowledgeChatRequest request) {
        if (request == null || trimToNull(request.getQuestion()) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "question is required");
        }
        request.setQuestion(request.getQuestion().trim());
        request.setConversationId(trimToNull(request.getConversationId()) == null
            ? "conv-" + UUID.randomUUID()
            : request.getConversationId().trim());
        request.setRequestId(trimToNull(request.getRequestId()) == null
            ? "chatreq-" + UUID.randomUUID()
            : request.getRequestId().trim());
        if (request.getConversationId().length() > 80) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "conversationId is too long");
        }
        if (request.getRequestId().length() > 80) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "requestId is too long");
        }
    }

    private String writeJson(Object value, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, errorMessage);
        }
    }

    private KnowledgeChatResponseVO readResponse(String responseJson) {
        try {
            return objectMapper.readValue(responseJson, KnowledgeChatResponseVO.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "stored chat response is invalid");
        }
    }

    private String resolveTraceId(KnowledgeChatResponseVO response) {
        if (response == null || response.getResultJson() == null) {
            return trimToNull(TraceIdHolder.get());
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
            traceId = trimToNull(nestedSnake == null ? null : String.valueOf(nestedSnake));
            if (traceId != null) {
                return traceId;
            }
        }
        return trimToNull(TraceIdHolder.get());
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

    private static String durableProgressEventType(Map<String, Object> payload) {
        String progressEvent = String.valueOf(payload.getOrDefault("progressEvent", ""));
        return switch (progressEvent) {
            case "context_compacting" -> "CONTEXT_COMPACTING";
            case "context_compacted" -> "CONTEXT_COMPACTED";
            default -> "PROGRESS";
        };
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

    private SseEmitter completedEmitter(KnowledgeChatResponseVO response) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("start").data(Map.of("event", "start")));
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("event", "done");
            done.put("data", response);
            emitter.send(SseEmitter.event().name("done").data(done));
            emitter.complete();
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private record CompatibilityLeaseMonitor(
        AtomicBoolean leaseLost,
        ScheduledFuture<?> heartbeat
    ) { }

    private final class CompatibilityDeltaBuffer {
        private final KnowledgeChatPersistenceService.BlockingRunStart start;
        private final AtomicBoolean leaseLost;
        private final AuthUser user;
        private final Object lock = new Object();
        private final StringBuilder partialAnswer = new StringBuilder();
        private final StringBuilder pendingDelta = new StringBuilder();
        private final AtomicLong chunkCounter = new AtomicLong();
        private final AtomicLong progressCounter = new AtomicLong();
        private long pendingBytes;

        private CompatibilityDeltaBuffer(
            KnowledgeChatPersistenceService.BlockingRunStart start,
            AtomicBoolean leaseLost,
            AuthUser user
        ) {
            this.start = start;
            this.leaseLost = leaseLost;
            this.user = user;
        }

        private void onDelta(String delta) {
            if (delta == null || delta.isEmpty() || leaseLost.get()) {
                return;
            }
            boolean flushNow;
            synchronized (lock) {
                partialAnswer.append(delta);
                pendingDelta.append(delta);
                pendingBytes += delta.getBytes(StandardCharsets.UTF_8).length;
                flushNow = chunkCounter.get() == 0 || pendingBytes >= 2048;
            }
            if (flushNow) {
                flush();
            }
        }

        private void onProgress(String phase, String message, Map<String, Object> details) {
            if (leaseLost.get()) {
                return;
            }
            Map<String, Object> payload = sanitizeProgressPayload(phase, message, details);
            String eventType = durableProgressEventType(payload);
            Long sequence;
            try {
                sequence = persistenceService.appendFencedEventAndSnapshot(
                    start.runId(),
                    start.leaseOwner(),
                    start.fencingToken(),
                    eventType,
                    "compat:run:" + start.runId()
                        + ":event:" + eventType.toLowerCase(java.util.Locale.ROOT)
                        + ":chunk:" + progressCounter.incrementAndGet(),
                    payload,
                    phase,
                    message,
                    partialAnswer()
                );
            } catch (RuntimeException ex) {
                LOGGER.warn(
                    "compatibility chat progress snapshot failed: runId={}, eventType={}, reason={}",
                    start.runId(), eventType, ex.getMessage()
                );
                return;
            }
            if (sequence == null) {
                leaseLost.set(true);
            }
        }

        private void flush() {
            AuthUser previous = AuthUserHolder.get();
            try {
                AuthUserHolder.set(user);
                synchronized (lock) {
                    if (pendingDelta.isEmpty() || leaseLost.get()) {
                        return;
                    }
                    String delta = pendingDelta.toString();
                    long nextChunkNo = chunkCounter.get() + 1L;
                    Long sequence;
                    try {
                        sequence = persistenceService.appendFencedEventAndSnapshot(
                        start.runId(),
                        start.leaseOwner(),
                        start.fencingToken(),
                        "DELTA",
                        "compat:run:" + start.runId() + ":delta:" + nextChunkNo,
                        Map.of("delta", delta),
                        "answer",
                        "正在生成回答",
                            partialAnswer.toString()
                        );
                    } catch (RuntimeException ex) {
                        LOGGER.warn(
                            "compatibility chat delta snapshot failed: runId={}, reason={}",
                            start.runId(), ex.getMessage()
                        );
                        return;
                    }
                    if (sequence == null) {
                        leaseLost.set(true);
                    } else {
                        pendingDelta.setLength(0);
                        pendingBytes = 0;
                        chunkCounter.set(nextChunkNo);
                    }
                }
            } finally {
                if (previous == null) {
                    AuthUserHolder.clear();
                } else {
                    AuthUserHolder.set(previous);
                }
            }
        }

        private String partialAnswer() {
            synchronized (lock) {
                return partialAnswer.toString();
            }
        }
    }
}
