package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatApplicationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatPersistenceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunRecoveryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatApplicationServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldFailRunWhenStreamExecutorRejectsBeforeLifecycleStarts() {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-stream-rejected", true, null, "compat-worker", 1L
            ));
        when(chatService.streamChatForDurableCommit(any(), any(), any(BooleanSupplier.class)))
            .thenThrow(new TaskRejectedException("stream executor saturated"));
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("stream rejection");
        request.setConversationId("conv-stream-rejected");
        request.setRequestId("request-stream-rejected");

        assertThatThrownBy(() -> service.streamChat(request))
            .isInstanceOf(TaskRejectedException.class);

        verify(persistenceService).completeFailedRun(
            eq("run-stream-rejected"),
            eq("compat-worker"),
            eq(1L),
            eq("stream executor saturated")
        );
    }

    @Test
    void shouldCommitTerminalRunBeforeSynchronousPostProcessingAndReturnEnrichedResult() throws Exception {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        KnowledgeChatRunRecoveryService recoveryService = mock(KnowledgeChatRunRecoveryService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-blocking-postprocess", true, null, "compat-worker", 1L
            ));
        KnowledgeChatResponseVO raw = new KnowledgeChatResponseVO();
        raw.setStatus("answered");
        raw.setAnswer("answer");
        raw.setResultJson(Map.of("traceId", "trace-postprocess"));
        when(chatService.chatForDurableCommit(any(), any(BooleanSupplier.class))).thenReturn(raw);
        when(persistenceService.completeAnsweredRun(
            any(), any(), eq(1L), any(), any(), any(), anyInt()
        )).thenReturn(true);
        when(recoveryService.dispatchTerminalOutboxForRun("run-blocking-postprocess")).thenReturn(true);
        KnowledgeChatResponseVO enriched = new KnowledgeChatResponseVO();
        enriched.setStatus("answered");
        enriched.setAnswer("answer");
        enriched.setResultJson(Map.of(
            "traceId", "trace-postprocess",
            "postProcessingStatus", "completed",
            "localBookId", 99
        ));
        when(persistenceService.findCompletedResponseJson("run-blocking-postprocess", 7L))
            .thenReturn(new ObjectMapper().writeValueAsString(enriched));
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "recoveryService", recoveryService);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        KnowledgeChatResponseVO response = service.chat(request("blocking postprocess"));

        assertThat(response.getResultJson())
            .containsEntry("postProcessingStatus", "completed")
            .containsEntry("localBookId", 99);
        InOrder order = inOrder(persistenceService, recoveryService);
        order.verify(persistenceService).completeAnsweredRun(
            eq("run-blocking-postprocess"), eq("compat-worker"), eq(1L),
            eq("answer"), any(), eq("trace-postprocess"), eq(0)
        );
        order.verify(recoveryService).dispatchTerminalOutboxForRun("run-blocking-postprocess");
        verify(chatService, never()).persistCompletedRunArtifacts(any(), any(), any(), any(), any());
    }

    @Test
    void shouldPersistCompatibilityStreamDeltasAndBindTrace() {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-stream-durable", true, null, "compat-worker", 1L
            ));
        when(persistenceService.appendFencedEventAndSnapshot(
            any(), any(), eq(1L), any(), any(), any(), any(), any(), any()
        )).thenReturn(1L, 2L, 3L);
        when(persistenceService.completeAnsweredRun(
            any(), any(), eq(1L), any(), any(), any(), anyInt()
        )).thenReturn(true);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setAnswer(" \ntext");
        response.setResultJson(Map.of(
            "trace", Map.of("trace_id", "trace-compat-stream")
        ));
        when(chatService.streamChatForDurableCommit(
            any(), any(), any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.StreamLifecycleListener listener = invocation.getArgument(1);
            listener.onDelta(" ");
            listener.onDelta("\ntext");
            listener.onProgress("context", "上下文已自动压缩", Map.of(
                "progressEvent", "context_compacted",
                "beforeInputTokens", 910000L,
                "afterInputTokens", 620000L
            ));
            listener.onCompleted(response);
            return new SseEmitter(0L);
        });
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = request("stream durable");

        service.streamChat(request);

        verify(persistenceService, times(2)).appendFencedEventAndSnapshot(
            eq("run-stream-durable"),
            eq("compat-worker"),
            eq(1L),
            eq("DELTA"),
            any(),
            any(),
            eq("answer"),
            any(),
            any()
        );
        verify(persistenceService).appendFencedEventAndSnapshot(
            eq("run-stream-durable"),
            eq("compat-worker"),
            eq(1L),
            eq("CONTEXT_COMPACTED"),
            any(),
            eq(Map.of(
                "phase", "context",
                "message", "上下文已自动压缩",
                "progressEvent", "context_compacted",
                "beforeInputTokens", 910000L,
                "afterInputTokens", 620000L
            )),
            eq("context"),
            eq("上下文已自动压缩"),
            any()
        );
        verify(persistenceService).completeAnsweredRun(
            eq("run-stream-durable"),
            eq("compat-worker"),
            eq(1L),
            eq(" \ntext"),
            any(),
            eq("trace-compat-stream"),
            eq(0)
        );
    }

    @Test
    void shouldContinueCompatibilityStreamWhenProgressSnapshotTemporarilyFails() {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-progress-recoverable", true, null, "compat-worker", 1L
            ));
        when(persistenceService.appendFencedEventAndSnapshot(
            any(), any(), eq(1L), any(), any(), any(), any(), any(), any()
        )).thenReturn(1L)
            .thenThrow(new IllegalStateException("temporary database issue"))
            .thenReturn(2L);
        when(persistenceService.completeAnsweredRun(
            any(), any(), eq(1L), any(), any(), any(), anyInt()
        )).thenReturn(true);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setAnswer("完整回答");
        when(chatService.streamChatForDurableCommit(
            any(), any(), any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.StreamLifecycleListener listener = invocation.getArgument(1);
            listener.onDelta("完整");
            listener.onDelta("回答");
            listener.onProgress("context", "上下文已自动压缩", Map.of(
                "progressEvent", "context_compacted",
                "beforeInputTokens", 910000L,
                "afterInputTokens", 620000L
            ));
            listener.onCompleted(response);
            return new SseEmitter(0L);
        });
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        service.streamChat(request("progress snapshot retry"));

        verify(persistenceService).completeAnsweredRun(
            eq("run-progress-recoverable"),
            eq("compat-worker"),
            eq(1L),
            eq("完整回答"),
            any(),
            any(),
            eq(0)
        );
    }

    @Test
    void shouldStopCompatibilityBlockingCommitWhenHeartbeatLosesLease() {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-lease-lost", true, null, "compat-worker", 1L
            ));
        when(persistenceService.heartbeatRun(
            eq("run-lease-lost"), eq("compat-worker"), eq(1L), any(Duration.class)
        )).thenReturn(false);
        when(chatService.chatForDurableCommit(any(), any(BooleanSupplier.class)))
            .thenAnswer(invocation -> {
                BooleanSupplier cancelled = invocation.getArgument(1);
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!cancelled.getAsBoolean() && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
                response.setAnswer("must not commit");
                return response;
            });
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getChatRun().setHeartbeatSeconds(1);
        properties.getChatRun().setLeaseSeconds(2);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setDaemon(true);
        scheduler.initialize();
        ReflectionTestUtils.setField(service, "knowledgeProperties", properties);
        ReflectionTestUtils.setField(service, "heartbeatTaskScheduler", scheduler);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        try {
            assertThatThrownBy(() -> service.chat(request("lease lost")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease lost");
        } finally {
            scheduler.shutdown();
        }

        verify(persistenceService, never()).completeAnsweredRun(
            any(), any(), eq(1L), any(), any(), any(), anyInt()
        );
        verify(persistenceService, never()).completeFailedRun(
            any(), any(), eq(1L), any()
        );
    }

    @Test
    void shouldFlushCompatibilityDeltaWhileStreamIsStillRunning() {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-stream-timer", true, null, "compat-worker", 1L
            ));
        when(persistenceService.heartbeatRun(any(), any(), eq(1L), any(Duration.class)))
            .thenReturn(true);
        AtomicInteger persistedDeltas = new AtomicInteger();
        when(persistenceService.appendFencedEventAndSnapshot(
            any(), any(), eq(1L), any(), any(), any(), any(), any(), any()
        )).thenAnswer(invocation -> (long) persistedDeltas.incrementAndGet());
        when(persistenceService.completeAnsweredRun(
            any(), any(), eq(1L), any(), any(), any(), anyInt()
        )).thenReturn(true);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setAnswer("ab");
        when(chatService.streamChatForDurableCommit(
            any(), any(), any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.StreamLifecycleListener listener = invocation.getArgument(1);
            listener.onDelta("a");
            listener.onDelta("b");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (persistedDeltas.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(persistedDeltas.get()).isEqualTo(2);
            listener.onCompleted(response);
            return new SseEmitter(0L);
        });
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setDaemon(true);
        scheduler.initialize();
        ReflectionTestUtils.setField(service, "heartbeatTaskScheduler", scheduler);
        ReflectionTestUtils.setField(service, "deltaTaskScheduler", scheduler);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        try {
            service.streamChat(request("stream timer"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldRetryCompatibilityDeltaWithoutDroppingBufferedText() {
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatPersistenceService persistenceService = mock(KnowledgeChatPersistenceService.class);
        when(persistenceService.beginBlockingRun(any(), any(), any(), any()))
            .thenReturn(new KnowledgeChatPersistenceService.BlockingRunStart(
                "run-stream-retry", true, null, "compat-worker", 1L
            ));
        when(persistenceService.appendFencedEventAndSnapshot(
            any(), any(), eq(1L), any(), any(), any(), any(), any(), any()
        )).thenThrow(new IllegalStateException("temporary database failure"))
            .thenReturn(1L);
        when(persistenceService.completeAnsweredRun(
            any(), any(), eq(1L), any(), any(), any(), anyInt()
        )).thenReturn(true);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setAnswer("ab");
        when(chatService.streamChatForDurableCommit(
            any(), any(), any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.StreamLifecycleListener listener = invocation.getArgument(1);
            listener.onDelta("a");
            listener.onDelta("b");
            listener.onCompleted(response);
            return new SseEmitter(0L);
        });
        KnowledgeChatApplicationService service = new KnowledgeChatApplicationService(
            chatService,
            persistenceService,
            new ObjectMapper()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        service.streamChat(request("stream retry"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(persistenceService, times(2)).appendFencedEventAndSnapshot(
            eq("run-stream-retry"),
            eq("compat-worker"),
            eq(1L),
            eq("DELTA"),
            any(),
            payload.capture(),
            eq("answer"),
            any(),
            any()
        );
        assertThat(payload.getAllValues().get(0)).containsEntry("delta", "a");
        assertThat(payload.getAllValues().get(1)).containsEntry("delta", "ab");
    }

    private KnowledgeChatRequest request(String question) {
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion(question);
        request.setConversationId("conv-" + question.replace(' ', '-'));
        request.setRequestId("request-" + question.replace(' ', '-'));
        return request;
    }
}
