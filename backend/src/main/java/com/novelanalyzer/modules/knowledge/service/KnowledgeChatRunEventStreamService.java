package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeChatRunSchedulingConfig;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunEventVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class KnowledgeChatRunEventStreamService {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final long STREAM_TIMEOUT_MILLIS = 30_000L;

    private final KnowledgeChatRunEventService eventService;
    private final JdbcTemplate jdbcTemplate;
    private final TaskScheduler taskScheduler;

    public KnowledgeChatRunEventStreamService(KnowledgeChatRunEventService eventService,
                                              JdbcTemplate jdbcTemplate,
                                              @Qualifier(KnowledgeChatRunSchedulingConfig.CHAT_RUN_SSE_TASK_SCHEDULER)
                                              TaskScheduler taskScheduler) {
        this.eventService = eventService;
        this.jdbcTemplate = jdbcTemplate;
        this.taskScheduler = taskScheduler;
    }

    public SseEmitter stream(String runId, Long afterSequence, String lastEventId) {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        long cursor = Math.max(
            afterSequence == null ? 0L : Math.max(afterSequence, 0L),
            parseSequence(lastEventId)
        );
        eventService.listEvents(runId, cursor, 1);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        StreamState state = new StreamState(runId, user, emitter, cursor);
        Runnable close = () -> close(state, null);
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close(state, error));
        ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
            () -> poll(state),
            Instant.now().plusMillis(10),
            POLL_INTERVAL
        );
        state.future().set(future);
        if (state.closed().get()) {
            future.cancel(false);
        }
        return emitter;
    }

    private void poll(StreamState state) {
        if (state.closed().get()) {
            return;
        }
        AuthUser previous = AuthUserHolder.get();
        try {
            AuthUserHolder.set(state.user());
            List<KnowledgeChatRunEventVO> events = eventService.listEvents(
                state.runId(), state.cursor().get(), 200
            );
            RunSnapshot snapshot = loadSnapshot(state.runId(), state.user().getUserId());
            if (shouldSendSnapshot(state.cursor().get(), events, snapshot)) {
                state.emitter().send(SseEmitter.event()
                    .id(String.valueOf(snapshot.sequenceNo()))
                    .name("snapshot")
                    .data(Map.of(
                        "runId", state.runId(),
                        "answer", snapshot.answer() == null ? "" : snapshot.answer(),
                        "snapshotSequenceNo", snapshot.sequenceNo()
                    )));
                state.cursor().set(snapshot.sequenceNo());
                events = eventService.listEvents(state.runId(), state.cursor().get(), 200);
            }
            events = contiguousPrefix(state.cursor().get(), events);
            for (KnowledgeChatRunEventVO event : events) {
                state.emitter().send(SseEmitter.event()
                    .id(String.valueOf(event.getSequenceNo()))
                    .name(event.getEventType().toLowerCase(Locale.ROOT))
                    .data(event));
                state.cursor().set(event.getSequenceNo());
            }
            if (events.isEmpty() && isTerminal(state.runId(), state.user().getUserId())) {
                close(state, null);
                state.emitter().complete();
                return;
            }
            if (state.pollCount().incrementAndGet() % 5 == 0) {
                state.emitter().send(SseEmitter.event().comment("heartbeat"));
            }
        } catch (IOException | RuntimeException ex) {
            close(state, ex);
            state.emitter().completeWithError(ex);
        } finally {
            if (previous == null) {
                AuthUserHolder.clear();
            } else {
                AuthUserHolder.set(previous);
            }
        }
    }

    private void close(StreamState state, Throwable error) {
        if (!state.closed().compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> future = state.future().get();
        if (future != null) {
            future.cancel(false);
        }
    }

    private boolean isTerminal(String runId, Long userId) {
        List<String> statuses = jdbcTemplate.query(
            "select status from ai_chat_run where run_id = ? and user_id = ? and deleted = 0",
            (rs, rowNum) -> rs.getString("status"),
            runId,
            userId
        );
        if (statuses.isEmpty()) {
            return true;
        }
        String status = statuses.get(0);
        return "ANSWERED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private RunSnapshot loadSnapshot(String runId, Long userId) {
        List<RunSnapshot> snapshots = jdbcTemplate.query("""
                select answer, snapshot_sequence_no
                from ai_chat_run
                where run_id = ? and user_id = ? and deleted = 0
                """,
            (rs, rowNum) -> new RunSnapshot(
                rs.getString("answer"),
                rs.getLong("snapshot_sequence_no")
            ),
            runId,
            userId
        );
        return snapshots.isEmpty() ? new RunSnapshot(null, 0L) : snapshots.get(0);
    }

    private boolean shouldSendSnapshot(long cursor,
                                       List<KnowledgeChatRunEventVO> events,
                                       RunSnapshot snapshot) {
        if (snapshot == null || snapshot.sequenceNo() <= cursor) {
            return false;
        }
        if (events.isEmpty()) {
            return true;
        }
        long expected = cursor + 1;
        for (KnowledgeChatRunEventVO event : events) {
            if (event.getSequenceNo() != expected) {
                return true;
            }
            expected++;
        }
        return false;
    }

    private List<KnowledgeChatRunEventVO> contiguousPrefix(
        long cursor,
        List<KnowledgeChatRunEventVO> events
    ) {
        int contiguousCount = 0;
        long expected = cursor + 1;
        for (KnowledgeChatRunEventVO event : events) {
            if (event.getSequenceNo() != expected) {
                break;
            }
            contiguousCount++;
            expected++;
        }
        return contiguousCount == events.size()
            ? events
            : events.subList(0, contiguousCount);
    }

    private long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private record StreamState(String runId,
                               AuthUser user,
                               SseEmitter emitter,
                               AtomicLong cursor,
                               AtomicInteger pollCount,
                               AtomicBoolean closed,
                               AtomicReference<ScheduledFuture<?>> future) {
        private StreamState(String runId, AuthUser user, SseEmitter emitter, long cursor) {
            this(
                runId,
                user,
                emitter,
                new AtomicLong(cursor),
                new AtomicInteger(),
                new AtomicBoolean(false),
                new AtomicReference<>()
            );
        }
    }

    private record RunSnapshot(String answer, long sequenceNo) {
    }
}
