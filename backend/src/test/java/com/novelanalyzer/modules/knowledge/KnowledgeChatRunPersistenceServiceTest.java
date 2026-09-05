package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatPersistenceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunEventService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunOutboxCoordinationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatRunPersistenceServiceTest {

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldEnforceDeepRunAdmissionWithDatabaseGuard() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertPendingRun(jdbcTemplate, "run-deep-1", "DEEP");
        insertPendingRun(jdbcTemplate, "run-deep-2", "DEEP");
        KnowledgeChatRunOutboxCoordinationService coordinator = mock(
            KnowledgeChatRunOutboxCoordinationService.class
        );
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate, coordinator);

        KnowledgeChatPersistenceService.RunLease first = service.claimRun(
            "run-deep-1", "worker-a", Duration.ofSeconds(30)
        );
        KnowledgeChatPersistenceService.RunLease second = service.claimRun(
            "run-deep-2", "worker-b", Duration.ofSeconds(30)
        );

        assertThat(first).isNotNull();
        assertThat(first.fencingToken()).isEqualTo(1L);
        assertThat(second).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run where run_id = 'run-deep-2'",
            String.class
        )).isEqualTo("PENDING");
        jdbcTemplate.update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(0, 'run-deep-2', 1, 'EXECUTE', 'run:deep-2:execute',
                'PUBLISHED', 1, current_timestamp, current_timestamp, current_timestamp)
            """);
        assertThat(service.deferPendingExecution("run-deep-2", Duration.ofSeconds(2))).isTrue();
        verify(coordinator).signalAfterCommit(Duration.ofSeconds(2));
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run_outbox where run_id = 'run-deep-2'",
            String.class
        )).isEqualTo("PENDING");
    }

    @Test
    void shouldApplyDeepAdmissionToCompatibilityBlockingRuns() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        insertPendingRun(jdbcTemplate, "run-active-deep", "DEEP");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        assertThat(service.claimRun(
            "run-active-deep", "worker-a", Duration.ofSeconds(30)
        )).isNotNull();
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("compatibility deep request");
        request.setConversationId("conv-compat-deep");
        request.setRequestId("request-compat-deep");
        request.setReasoningMode("deep");

        assertThatThrownBy(() -> service.beginBlockingRun(
            "run-compat-deep",
            AuthUserHolder.get(),
            request,
            new ObjectMapper().writeValueAsString(request)
        )).isInstanceOf(BusinessException.class)
            .hasMessageContaining("server is busy");
    }

    @Test
    void shouldRejectNewDeepRunsAndDeferPendingDeepRunsUnderMemoryPressure() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        AgentResourcePressureService pressureService = mock(AgentResourcePressureService.class);
        when(pressureService.shouldRejectDeepRun()).thenReturn(true);
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate, pressureService);
        insertPendingRun(jdbcTemplate, "run-pressure-deep", "DEEP");

        assertThat(service.claimRun(
            "run-pressure-deep", "worker-pressure", Duration.ofSeconds(30)
        )).isNull();

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("deep request under pressure");
        request.setConversationId("conv-pressure-deep");
        request.setRequestId("request-pressure-deep");
        request.setReasoningMode("deep");

        assertThatThrownBy(() -> service.createQueuedRun(
            "run-new-pressure-deep",
            AuthUserHolder.get(),
            request,
            new ObjectMapper().writeValueAsString(request)
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.getResultCode()).isEqualTo(com.novelanalyzer.common.result.ResultCode.SERVICE_UNAVAILABLE)
        );
    }

    @Test
    void shouldCountCancellingRunsAgainstDeepAdmission() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        insertPendingRun(jdbcTemplate, "run-cancelling-deep", "DEEP");
        jdbcTemplate.update("""
            update ai_chat_run
            set status = 'CANCELLING', cancel_requested = true
            where run_id = 'run-cancelling-deep'
            """);
        insertPendingRun(jdbcTemplate, "run-waiting-deep", "DEEP");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);

        assertThat(service.claimRun(
            "run-waiting-deep", "worker-b", Duration.ofSeconds(30)
        )).isNull();

        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("compatibility deep while cancelling");
        request.setConversationId("conv-compat-cancelling-deep");
        request.setRequestId("request-compat-cancelling-deep");
        request.setReasoningMode("deep");
        assertThatThrownBy(() -> service.beginBlockingRun(
            "run-compat-cancelling-deep",
            AuthUserHolder.get(),
            request,
            new ObjectMapper().writeValueAsString(request)
        )).isInstanceOf(BusinessException.class)
            .hasMessageContaining("server is busy");
    }

    @Test
    void shouldHeartbeatOnlyForCurrentLeaseOwnerAndFencingToken() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertPendingRun(jdbcTemplate, "run-heartbeat", "FAST");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-heartbeat", "worker-a", Duration.ofSeconds(5)
        );

        boolean staleOwner = service.heartbeatRun(
            "run-heartbeat", "worker-b", lease.fencingToken(), Duration.ofSeconds(30)
        );
        boolean staleToken = service.heartbeatRun(
            "run-heartbeat", "worker-a", lease.fencingToken() - 1, Duration.ofSeconds(30)
        );
        boolean renewed = service.heartbeatRun(
            "run-heartbeat", "worker-a", lease.fencingToken(), Duration.ofSeconds(30)
        );

        assertThat(staleOwner).isFalse();
        assertThat(staleToken).isFalse();
        assertThat(renewed).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select lease_expires_at from ai_chat_run where run_id = 'run-heartbeat'",
            Timestamp.class
        ).toInstant()).isAfter(Instant.now().plusSeconds(20));
    }

    @Test
    void shouldRejectHeartbeatAfterLeaseExpiry() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertPendingRun(jdbcTemplate, "run-expired-heartbeat", "FAST");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-expired-heartbeat", "worker-a", Duration.ofSeconds(30)
        );
        jdbcTemplate.update(
            "update ai_chat_run set lease_expires_at = ? where run_id = 'run-expired-heartbeat'",
            Timestamp.from(Instant.now().minusSeconds(1))
        );

        assertThat(service.heartbeatRun(
            "run-expired-heartbeat", "worker-a", lease.fencingToken(), Duration.ofSeconds(30)
        )).isFalse();
    }

    @Test
    void shouldRejectStaleWorkerWritesAfterExpiredLeaseTakeover() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertPendingRun(jdbcTemplate, "run-takeover", "FAST");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease original = service.claimRun(
            "run-takeover", "worker-a", Duration.ofSeconds(30)
        );
        jdbcTemplate.update(
            "update ai_chat_run set lease_expires_at = ? where run_id = 'run-takeover'",
            Timestamp.from(Instant.now().minusSeconds(1))
        );
        authenticate();
        assertThat(service.completeFailedRun(
            "run-takeover", "worker-a", original.fencingToken(), "stale failure"
        )).isFalse();

        assertThat(service.claimRun(
            "run-takeover", "worker-b", Duration.ofSeconds(30)
        )).isNull();
        assertThat(service.recoverExpiredRun("run-takeover", Duration.ofSeconds(3)))
            .isEqualTo(KnowledgeChatPersistenceService.RecoveryResult.REQUEUED);
        String replacementRunId = jdbcTemplate.queryForObject(
            "select run_id from ai_chat_run where parent_run_id = 'run-takeover'",
            String.class
        );
        KnowledgeChatPersistenceService.RunLease replacement = service.claimRun(
            replacementRunId, "worker-b", Duration.ofSeconds(30)
        );
        boolean staleWrite = service.updateRunSnapshot(
            "run-takeover", "worker-a", original.fencingToken(),
            "intent", "stale progress", null, 1L
        );
        boolean currentWrite = service.updateRunSnapshot(
            replacementRunId, "worker-b", replacement.fencingToken(),
            "tools", "current progress", "partial", 2L
        );

        assertThat(replacement.fencingToken()).isEqualTo(1L);
        assertThat(staleWrite).isFalse();
        assertThat(currentWrite).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select progress_message from ai_chat_run where run_id = ?",
            String.class,
            replacementRunId
        )).isEqualTo("current progress");
    }

    @Test
    void shouldCommitAnsweredMessageEventAndRunAsOneFencedTerminalState() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        new KnowledgeConversationService(jdbcTemplate)
            .ensureConversation("conv-terminal", null, "terminal");
        insertPendingRun(jdbcTemplate, "run-terminal", "DEEP", "conv-terminal");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-terminal", "worker-a", Duration.ofSeconds(30)
        );

        boolean answered = service.completeAnsweredRun(
            "run-terminal", "worker-a", lease.fencingToken(),
            "final answer", "{}", "trace-1", 0
        );
        boolean duplicateTerminal = service.completeCancelledRun(
            "run-terminal", "worker-a", lease.fencingToken(), "late cancel"
        );

        assertThat(answered).isTrue();
        assertThat(duplicateTerminal).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run where run_id = 'run-terminal'",
            String.class
        )).isEqualTo("ANSWERED");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where run_id = 'run-terminal' and role = 'ASSISTANT'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
            "select event_type from ai_chat_run_event where run_id = 'run-terminal' order by sequence_no",
            String.class
        )).containsExactly("ANSWERED");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox where run_id = 'run-terminal'",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void shouldKeepAnsweredTerminalWhenCancellationContendsWithAnswerCommit() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        new KnowledgeConversationService(jdbcTemplate)
            .ensureConversation("conv-answer-wins", null, "answer wins");
        insertPendingRun(jdbcTemplate, "run-answer-wins", "FAST", "conv-answer-wins");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            jdbcTemplate.getDataSource()
        );
        CountDownLatch answerEventWritten = new CountDownLatch(1);
        CountDownLatch cancelAttempting = new CountDownLatch(1);
        CountDownLatch allowAnswerCommit = new CountDownLatch(1);
        KnowledgeChatRunEventService eventService = spy(new KnowledgeChatRunEventService(
            jdbcTemplate, transactionManager, new ObjectMapper()
        ));
        doAnswer(invocation -> {
            Object event = invocation.callRealMethod();
            answerEventWritten.countDown();
            assertThat(allowAnswerCommit.await(5, TimeUnit.SECONDS)).isTrue();
            return event;
        }).when(eventService).appendEvent(
            eq("run-answer-wins"), eq("ANSWERED"), anyString(), any()
        );
        KnowledgeChatPersistenceService service = persistenceService(
            jdbcTemplate, transactionManager, eventService
        );
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-answer-wins", "worker-a", Duration.ofSeconds(30)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> answer = executor.submit(() -> authenticated(() ->
                service.completeAnsweredRun(
                    "run-answer-wins",
                    "worker-a",
                    lease.fencingToken(),
                    "winner answer",
                    "{}",
                    "trace-answer-wins",
                    0
                )
            ));
            assertThat(answerEventWritten.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> cancellation = executor.submit(() -> authenticated(() -> {
                cancelAttempting.countDown();
                return service.requestCancellation("run-answer-wins", 7L);
            }));
            assertThat(cancelAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            allowAnswerCommit.countDown();

            assertThat(answer.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            allowAnswerCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(service.completeCancelledRun(
            "run-answer-wins", "worker-a", lease.fencingToken(), "late cancel"
        )).isFalse();
        assertTerminalProjection(
            jdbcTemplate,
            "run-answer-wins",
            "ANSWERED",
            "winner answer",
            1,
            "ANSWERED"
        );
    }

    @Test
    void shouldKeepCancelledTerminalWhenAnswerContendsWithCancellationCommit() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        new KnowledgeConversationService(jdbcTemplate)
            .ensureConversation("conv-cancel-wins", null, "cancel wins");
        insertPendingRun(jdbcTemplate, "run-cancel-wins", "FAST", "conv-cancel-wins");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            jdbcTemplate.getDataSource()
        );
        CountDownLatch cancelEventWritten = new CountDownLatch(1);
        CountDownLatch answerAttempting = new CountDownLatch(1);
        CountDownLatch allowCancellationCommit = new CountDownLatch(1);
        KnowledgeChatRunEventService eventService = spy(new KnowledgeChatRunEventService(
            jdbcTemplate, transactionManager, new ObjectMapper()
        ));
        doAnswer(invocation -> {
            Object event = invocation.callRealMethod();
            cancelEventWritten.countDown();
            assertThat(allowCancellationCommit.await(5, TimeUnit.SECONDS)).isTrue();
            return event;
        }).when(eventService).appendEvent(
            eq("run-cancel-wins"), eq("CANCEL_REQUESTED"), anyString(), any()
        );
        KnowledgeChatPersistenceService service = persistenceService(
            jdbcTemplate, transactionManager, eventService
        );
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-cancel-wins", "worker-a", Duration.ofSeconds(30)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> cancellation = executor.submit(() -> authenticated(() ->
                service.requestCancellation("run-cancel-wins", 7L)
            ));
            assertThat(cancelEventWritten.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> answer = executor.submit(() -> authenticated(() -> {
                answerAttempting.countDown();
                return service.completeAnsweredRun(
                    "run-cancel-wins",
                    "worker-a",
                    lease.fencingToken(),
                    "loser answer",
                    "{}",
                    "trace-cancel-wins",
                    0
                );
            }));
            assertThat(answerAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            allowCancellationCommit.countDown();

            assertThat(cancellation.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(answer.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            allowCancellationCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(service.completeCancelledRun(
            "run-cancel-wins", "worker-a", lease.fencingToken(), "user requested"
        )).isTrue();
        assertThat(service.updateRunSnapshot(
            "run-cancel-wins",
            "worker-a",
            lease.fencingToken(),
            "answer",
            "late snapshot",
            "loser answer",
            1L
        )).isFalse();
        assertTerminalProjection(
            jdbcTemplate,
            "run-cancel-wins",
            "CANCELLED",
            null,
            0,
            "CANCEL_REQUESTED",
            "CANCELLED"
        );
    }

    @Test
    void shouldKeepAnsweredSnapshotBeforeTerminalEventSequence() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        new KnowledgeConversationService(jdbcTemplate)
            .ensureConversation("conv-terminal-snapshot", null, "terminal snapshot");
        insertPendingRun(
            jdbcTemplate, "run-terminal-snapshot", "FAST", "conv-terminal-snapshot"
        );
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-terminal-snapshot", "worker-a", Duration.ofSeconds(30)
        );
        Long deltaSequence = service.appendFencedEventAndSnapshot(
            "run-terminal-snapshot",
            "worker-a",
            lease.fencingToken(),
            "DELTA",
            "run:terminal-snapshot:delta:1",
            Map.of("delta", "partial"),
            "answer",
            "partial",
            "partial"
        );

        assertThat(service.completeAnsweredRun(
            "run-terminal-snapshot",
            "worker-a",
            lease.fencingToken(),
            "final answer",
            "{}",
            "trace-terminal-snapshot",
            0
        )).isTrue();

        Map<String, Object> run = jdbcTemplate.queryForMap("""
            select snapshot_sequence_no from ai_chat_run
            where run_id = 'run-terminal-snapshot'
            """);
        Long terminalSequence = jdbcTemplate.queryForObject("""
            select sequence_no from ai_chat_run_event
            where run_id = 'run-terminal-snapshot' and event_type = 'ANSWERED'
            """, Long.class);
        String terminalPayload = jdbcTemplate.queryForObject("""
            select payload from ai_chat_run_event
            where run_id = 'run-terminal-snapshot' and event_type = 'ANSWERED'
            """, String.class);
        assertThat(((Number) run.get("snapshot_sequence_no")).longValue())
            .isEqualTo(deltaSequence);
        assertThat(terminalSequence).isEqualTo(deltaSequence + 1L);
        assertThat(terminalPayload).contains("final answer");
    }

    @Test
    void shouldPersistPartialAssistantMessageWhenFencedRunFails() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        new KnowledgeConversationService(jdbcTemplate)
            .ensureConversation("conv-partial-failed", null, "partial failed");
        insertPendingRun(jdbcTemplate, "run-partial-failed", "FAST", "conv-partial-failed");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-partial-failed", "worker-a", Duration.ofSeconds(30)
        );
        assertThat(service.updateRunSnapshot(
            "run-partial-failed",
            "worker-a",
            lease.fencingToken(),
            "answer",
            "partial",
            " partial answer",
            2L
        )).isTrue();

        assertThat(service.completeFailedRun(
            "run-partial-failed", "worker-a", lease.fencingToken(), "boom"
        )).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
            select content, content_json from ai_chat_message
            where run_id = 'run-partial-failed' and role = 'ASSISTANT'
            """)).containsEntry("content", " partial answer");
        assertThat(jdbcTemplate.queryForObject("""
            select content_json from ai_chat_message
            where run_id = 'run-partial-failed' and role = 'ASSISTANT'
            """, String.class)).contains("PARTIAL");
    }

    @Test
    void shouldRollbackTerminalMessageEventAndOutboxWhenLeaseExpiresDuringCommit() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        conversationService.ensureConversation("conv-terminal-race", null, "terminal race");
        insertPendingRun(jdbcTemplate, "run-terminal-race", "DEEP", "conv-terminal-race");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            jdbcTemplate.getDataSource()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeChatRunEventService eventService = spy(new KnowledgeChatRunEventService(
            jdbcTemplate, transactionManager, objectMapper
        ));
        KnowledgeChatPersistenceService service = new KnowledgeChatPersistenceService(
            jdbcTemplate,
            conversationService,
            transactionManager,
            objectMapper,
            new KnowledgeProperties(),
            eventService
        );
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-terminal-race", "worker-a", Duration.ofSeconds(30)
        );
        doAnswer(invocation -> {
            Object event = invocation.callRealMethod();
            jdbcTemplate.update(
                "update ai_chat_run set lease_expires_at = ? where run_id = 'run-terminal-race'",
                Timestamp.from(Instant.now().minusSeconds(1))
            );
            return event;
        }).when(eventService).appendEvent(
            eq("run-terminal-race"), eq("ANSWERED"), anyString(), any()
        );

        assertThat(service.completeAnsweredRun(
            "run-terminal-race",
            "worker-a",
            lease.fencingToken(),
            "final answer",
            "{}",
            "trace-terminal-race",
            0
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
            select count(1) from ai_chat_message
            where run_id = 'run-terminal-race' and role = 'ASSISTANT'
            """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(1) from ai_chat_run_event
            where run_id = 'run-terminal-race' and event_type = 'ANSWERED'
            """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(1) from ai_chat_run_outbox
            where run_id = 'run-terminal-race' and event_type = 'ANSWERED'
            """, Integer.class)).isZero();
    }

    @Test
    void shouldTransitionCancellationThroughCancellingAndEmitUniqueEvents() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        authenticate();
        insertPendingRun(jdbcTemplate, "run-cancel-flow", "FAST");
        KnowledgeChatPersistenceService service = persistenceService(jdbcTemplate);
        KnowledgeChatPersistenceService.RunLease lease = service.claimRun(
            "run-cancel-flow", "worker-a", Duration.ofSeconds(30)
        );

        assertThat(service.requestCancellation("run-cancel-flow", 7L)).isTrue();
        assertThat(service.requestCancellation("run-cancel-flow", 7L)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run where run_id = 'run-cancel-flow'",
            String.class
        )).isEqualTo("CANCELLING");

        assertThat(service.completeCancelledRun(
            "run-cancel-flow", "worker-a", lease.fencingToken(), "user requested"
        )).isTrue();
        assertThat(jdbcTemplate.queryForList(
            "select event_type from ai_chat_run_event where run_id = 'run-cancel-flow' order by sequence_no",
            String.class
        )).containsExactly("CANCEL_REQUESTED", "CANCELLED");
    }

    private KnowledgeChatPersistenceService persistenceService(JdbcTemplate jdbcTemplate) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getResourcePolicy().setMaxActiveDeepRuns(1);
        properties.getResourcePolicy().setMaxActiveFastRuns(2);
        return new KnowledgeChatPersistenceService(
            jdbcTemplate,
            new KnowledgeConversationService(jdbcTemplate),
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
            new ObjectMapper(),
            properties
        );
    }

    private KnowledgeChatPersistenceService persistenceService(
        JdbcTemplate jdbcTemplate,
        DataSourceTransactionManager transactionManager,
        KnowledgeChatRunEventService eventService
    ) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getResourcePolicy().setMaxActiveDeepRuns(1);
        properties.getResourcePolicy().setMaxActiveFastRuns(2);
        return new KnowledgeChatPersistenceService(
            jdbcTemplate,
            new KnowledgeConversationService(jdbcTemplate),
            transactionManager,
            new ObjectMapper(),
            properties,
            eventService
        );
    }

    private KnowledgeChatPersistenceService persistenceService(
        JdbcTemplate jdbcTemplate,
        KnowledgeChatRunOutboxCoordinationService outboxCoordinationService
    ) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getResourcePolicy().setMaxActiveDeepRuns(1);
        properties.getResourcePolicy().setMaxActiveFastRuns(2);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            jdbcTemplate.getDataSource()
        );
        KnowledgeChatRunEventService eventService = new KnowledgeChatRunEventService(
            jdbcTemplate,
            transactionManager,
            new ObjectMapper(),
            outboxCoordinationService
        );
        return new KnowledgeChatPersistenceService(
            jdbcTemplate,
            new KnowledgeConversationService(jdbcTemplate),
            transactionManager,
            new ObjectMapper(),
            properties,
            eventService,
            AgentResourcePressureService.permissive(properties),
            outboxCoordinationService
        );
    }

    private KnowledgeChatPersistenceService persistenceService(
        JdbcTemplate jdbcTemplate,
        AgentResourcePressureService pressureService
    ) {
        KnowledgeProperties properties = new KnowledgeProperties();
        return new KnowledgeChatPersistenceService(
            jdbcTemplate,
            new KnowledgeConversationService(jdbcTemplate),
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
            new ObjectMapper(),
            properties,
            new KnowledgeChatRunEventService(
                jdbcTemplate,
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
                new ObjectMapper()
            ),
            pressureService
        );
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:chat-run-persistence-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        return new JdbcTemplate(dataSource);
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
                progress_phase varchar(40),
                progress_message varchar(500),
                answer clob,
                result_json clob,
                trace_id varchar(80),
                source_count int default 0,
                error_message varchar(1000),
                cancel_requested boolean default false,
                retry_count int default 0,
                max_retries int default 3,
                started_at timestamp,
                finished_at timestamp,
                deleted tinyint default 0
            )
            """);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("sql/phase18-agent-harness-conversation-rag-h2.sql")
        );
        DatabasePopulatorUtils.execute(populator, jdbcTemplate.getDataSource());
    }

    private void insertPendingRun(JdbcTemplate jdbcTemplate, String runId, String executionMode) {
        insertPendingRun(jdbcTemplate, runId, executionMode, "conv-" + runId);
    }

    private void insertPendingRun(JdbcTemplate jdbcTemplate,
                                  String runId,
                                  String executionMode,
                                  String conversationId) {
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                execution_mode, cancel_requested, deleted, queued_at, update_time
            ) values (?, 7, ?, 'question', '{}', 'PENDING', ?, false, 0, current_timestamp, current_timestamp)
            """, runId, conversationId, executionMode);
    }

    private void authenticate() {
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
    }

    private <T> T authenticated(Callable<T> action) throws Exception {
        authenticate();
        try {
            return action.call();
        } finally {
            AuthUserHolder.clear();
        }
    }

    private void assertTerminalProjection(JdbcTemplate jdbcTemplate,
                                          String runId,
                                          String expectedStatus,
                                          String expectedAnswer,
                                          int expectedAssistantMessages,
                                          String... expectedEventTypes) {
        Map<String, Object> run = jdbcTemplate.queryForMap("""
            select status, answer, cancel_requested from ai_chat_run where run_id = ?
            """, runId);
        assertThat(run.get("status")).isEqualTo(expectedStatus);
        assertThat(run.get("answer")).isEqualTo(expectedAnswer);
        assertThat(jdbcTemplate.queryForObject("""
            select count(1) from ai_chat_message where run_id = ? and role = 'ASSISTANT'
            """, Integer.class, runId)).isEqualTo(expectedAssistantMessages);
        assertThat(jdbcTemplate.queryForList("""
            select event_type from ai_chat_run_event where run_id = ? order by sequence_no
            """, String.class, runId)).containsExactly(expectedEventTypes);
        assertThat(jdbcTemplate.queryForList("""
            select event_type from ai_chat_run_outbox where run_id = ? order by sequence_no
            """, String.class, runId)).containsExactly(expectedEventTypes);
        assertThat(jdbcTemplate.queryForObject("""
            select count(1) from ai_chat_run_event
            where run_id = ? and event_idempotency_key = ?
            """, Integer.class, runId, "run:" + runId + ":terminal")).isEqualTo(1);
    }
}
