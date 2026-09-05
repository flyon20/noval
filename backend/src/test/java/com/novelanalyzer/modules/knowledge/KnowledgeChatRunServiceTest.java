package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatPersistenceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatRunServiceTest {

    @Test
    void shouldRequireConversationServiceInsteadOfConstructingItInternally() {
        assertThat(KnowledgeChatRunService.class.getConstructors()).hasSize(1);
        assertThat(List.of(KnowledgeChatRunService.class.getConstructors()[0].getParameterTypes()))
            .contains(KnowledgeChatPersistenceService.class);
    }

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateRunAndPersistAnsweredResultForCurrentUser() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        createProjectTable(jdbcTemplate);
        jdbcTemplate.update(
            "insert into ai_project(project_id, user_id, name, status) values(?, ?, ?, ?)",
            99L, 7L, "Run Project", "ACTIVE"
        );
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatResponseVO workerResponse = answeredResponse();
        workerResponse.setResultJson(new java.util.LinkedHashMap<>(Map.of(
            "traceId", "trace-run-1",
            "conversationId", "conv-1",
            "memoryCandidatePayloads", List.of(Map.of("content", "PRIVATE_MEMORY_BODY"))
        )));
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onProgress("context", "上下文已自动压缩", Map.of(
                "progressEvent", "context_compacted",
                "beforeInputTokens", 910000L,
                "afterInputTokens", 620000L
            ));
            return workerResponse;
        });
        doAnswer(invocation -> {
            KnowledgeChatResponseVO response = invocation.getArgument(2);
            response.getResultJson().remove("memoryCandidatePayloads");
            return null;
        }).when(chatService).prepareMemoryCandidatesForDurablePersistence(anyLong(), any(), any());
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
            persistenceService(jdbcTemplate, new KnowledgeConversationService(jdbcTemplate)),
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("给出完整的大纲设计");
        request.setConversationId("conv-1");
        request.setProjectId(99L);

        KnowledgeChatRunVO started = service.startRun(request);
        KnowledgeChatRunVO detail = service.getRun(started.getRunId());
        List<KnowledgeChatRunVO> runs = service.listConversationRuns("conv-1", 10);

        assertThat(started.getRunId()).isNotBlank();
        assertThat(detail.getStatus()).isEqualTo("ANSWERED");
        assertThat(detail.getConversationId()).isEqualTo("conv-1");
        assertThat(detail.getProjectId()).isEqualTo(99L);
        assertThat(detail.getAnswer()).contains("完整大纲");
        assertThat(detail.getTraceId()).isEqualTo("trace-run-1");
        assertThat(detail.getSourceCount()).isEqualTo(1);
        assertThat(detail.getResultJson()).contains("trace-run-1");
        assertThat(detail.getResultJson()).doesNotContain("PRIVATE_MEMORY_BODY", "memoryCandidatePayloads");
        assertThat(jdbcTemplate.queryForList(
            "select event_type, payload from ai_chat_run_event where run_id = ? order by sequence_no",
            started.getRunId()
        )).anySatisfy(event -> {
            assertThat(event.get("event_type")).isEqualTo("CONTEXT_COMPACTED");
            assertThat(String.valueOf(event.get("payload")))
                .contains("beforeInputTokens", "afterInputTokens")
                .doesNotContain("compactedSummary");
        });
        verify(chatService).prepareMemoryCandidatesForDurablePersistence(anyLong(), any(), any());
        assertThat(runs).extracting(KnowledgeChatRunVO::getRunId).containsExactly(started.getRunId());
        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
            "select message_id, user_id, project_id, run_id, role, content from ai_chat_message " +
                "where conversation_id = ? order by message_id",
            "conv-1"
        );
        assertThat(messages).extracting(row -> row.get("role")).containsExactly("USER", "ASSISTANT");
        assertThat(messages).extracting(row -> row.get("run_id")).containsOnly(started.getRunId());
        assertThat(messages).extracting(row -> ((Number) row.get("user_id")).longValue()).containsOnly(7L);
        assertThat(messages).extracting(row -> ((Number) row.get("project_id")).longValue()).containsOnly(99L);
        Map<String, Object> linkage = jdbcTemplate.queryForMap(
            "select request_id, idempotency_key, trigger_message_id, response_message_id " +
                "from ai_chat_run where run_id = ?",
            started.getRunId()
        );
        assertThat(linkage.get("request_id")).isEqualTo(request.getRequestId());
        assertThat(linkage.get("idempotency_key")).isEqualTo(request.getRequestId());
        assertThat(linkage.get("trigger_message_id")).isEqualTo(messages.get(0).get("message_id"));
        assertThat(linkage.get("response_message_id")).isEqualTo(messages.get(1).get("message_id"));
    }

    @Test
    void shouldPersistOnlyUserMessageWhenDurableRunFailsWithoutOutput() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenThrow(new RuntimeException("worker failed"));
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("failed question");
        request.setConversationId("conv-failed");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getStatus()).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForList(
            "select role, content from ai_chat_message where conversation_id = ? order by message_id",
            "conv-failed"
        )).containsExactly(Map.of("role", "USER", "content", "failed question"));
    }

    @Test
    void shouldRollbackConversationAndRunWhenInitialUserMessageCannotBePersisted() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate) {
            @Override
            public com.novelanalyzer.modules.knowledge.vo.KnowledgeChatMessageVO appendMessage(
                String conversationId,
                String runId,
                String role,
                String content,
                String contentJson,
                Integer tokenCount
            ) {
                throw new IllegalStateException("message persistence failed");
            }
        };
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            persistenceService(jdbcTemplate, conversationService),
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("atomic create");
        request.setConversationId("conv-atomic-create");

        assertThatThrownBy(() -> service.startRun(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("message persistence failed");

        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation where conversation_id = ?",
            Integer.class,
            "conv-atomic-create"
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run where conversation_id = ?",
            Integer.class,
            "conv-atomic-create"
        )).isZero();
    }

    @Test
    void shouldMarkRunFailedWhenExecutorRejectsSubmission() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            persistenceService(jdbcTemplate, conversationService),
            new ObjectMapper(),
            task -> {
                throw new TaskRejectedException("executor saturated");
            }
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("rejected submission");
        request.setConversationId("conv-rejected");

        request.setRequestId("request-rejected-1");
        KnowledgeChatRunVO first = service.startRun(request);
        KnowledgeChatRunVO retried = service.startRun(request);

        assertThat(first.getStatus()).isEqualTo("FAILED");
        assertThat(retried.getRunId()).isEqualTo(first.getRunId());
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run where conversation_id = ?",
            Integer.class,
            "conv-rejected"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where conversation_id = ? and role = 'USER'",
            Integer.class,
            "conv-rejected"
        )).isEqualTo(1);
    }

    @Test
    void shouldTreatNormalizedJsonFormattingAsTheSameIdempotentRequest() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        AuthUser user = AuthUser.of(7L, "writer", Set.of("USER"));
        AuthUserHolder.set(user);
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("json normalization");
        request.setConversationId("conv-json-normalization");
        request.setRequestId("request-json-normalization");
        request.setReasoningMode("deep");
        ObjectMapper objectMapper = new ObjectMapper();
        String compact = objectMapper.writeValueAsString(request);
        String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
        KnowledgeChatPersistenceService persistenceService = persistenceService(
            jdbcTemplate,
            new KnowledgeConversationService(jdbcTemplate)
        );

        KnowledgeChatPersistenceService.QueuedRunStart first = persistenceService.createQueuedRun(
            "run-json-1", user, request, compact
        );
        KnowledgeChatPersistenceService.QueuedRunStart duplicate = persistenceService.createQueuedRun(
            "run-json-2", user, request, pretty
        );

        assertThat(first.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.runId()).isEqualTo(first.runId());
    }

    @Test
    void shouldRollbackAnsweredStatusWhenAssistantMessagePersistenceFails() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeConversationService baseConversationService = new KnowledgeConversationService(jdbcTemplate);
        baseConversationService.ensureConversation("conv-terminal-atomic", null, "terminal atomic");
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, conversation_id, question, request_json,
                    status, cancel_requested, deleted)
                values('run-terminal-atomic', 7, 'conv-terminal-atomic', 'terminal atomic', '{}',
                    'RUNNING', false, 0)
                """);
        KnowledgeConversationService failingConversationService = new KnowledgeConversationService(jdbcTemplate) {
            @Override
            public com.novelanalyzer.modules.knowledge.vo.KnowledgeChatMessageVO appendMessage(
                String conversationId,
                String runId,
                String role,
                String content,
                String contentJson,
                Integer tokenCount
            ) {
                if ("ASSISTANT".equals(role)) {
                    throw new IllegalStateException("assistant persistence failed");
                }
                return super.appendMessage(conversationId, runId, role, content, contentJson, tokenCount);
            }
        };
        KnowledgeChatPersistenceService persistenceService = persistenceService(
            jdbcTemplate,
            failingConversationService
        );

        assertThatThrownBy(() -> persistenceService.completeAnsweredRun(
            "run-terminal-atomic",
            "answer",
            "{}",
            "trace-terminal",
            0
        )).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run where run_id = 'run-terminal-atomic'",
            String.class
        )).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where run_id = 'run-terminal-atomic' and role = 'ASSISTANT'",
            Integer.class
        )).isZero();
    }

    @Test
    void shouldNotRestartRunThatWasAlreadyCancelled() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, conversation_id, question, request_json,
                    status, cancel_requested, deleted)
                values('run-already-cancelled', 7, 'conv-cancelled-race', 'cancelled', '{}',
                    'CANCELLED', false, 0)
                """);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUser user = AuthUser.of(7L, "writer", Set.of("USER"));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("cancelled");
        request.setConversationId("conv-cancelled-race");

        ReflectionTestUtils.invokeMethod(service, "executeRun", "run-already-cancelled", user, request, 1);

        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run where run_id = 'run-already-cancelled'",
            String.class
        )).isEqualTo("CANCELLED");
        verify(chatService, never()).chatWithProgressForDurableRun(any(), any(), any());
    }

    @Test
    void shouldDeriveAssistantConversationFromPersistedRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        conversationService.ensureConversation("conv-run-owner", null, "owner");
        conversationService.ensureConversation("conv-wrong-target", null, "wrong");
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, conversation_id, question, request_json,
                    status, cancel_requested, deleted)
                values('run-owner-check', 7, 'conv-run-owner', 'owner', '{}', 'RUNNING', false, 0)
                """);
        KnowledgeChatPersistenceService persistenceService = persistenceService(jdbcTemplate, conversationService);

        boolean completed = persistenceService.completeAnsweredRun(
            "run-owner-check",
            "owner answer",
            "{}",
            "trace-owner",
            0
        );

        assertThat(completed).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where conversation_id = 'conv-run-owner' " +
                "and run_id = 'run-owner-check' and role = 'ASSISTANT'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where conversation_id = 'conv-wrong-target'",
            Integer.class
        )).isZero();
    }

    @Test
    void shouldReuseRootRunAsCheckpointThreadForRetryAttempts() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                deleted, request_id, attempt_no, idempotency_key
            ) values('run-root', 7, 'conv-checkpoint', 'q', '{}', 'FAILED', 0, 'request-root', 1, 'request-root')
            """);
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                deleted, request_id, attempt_no, parent_run_id, idempotency_key
            ) values('run-child', 7, 'conv-checkpoint', 'q', '{}', 'FAILED', 0, 'request-root', 2, 'run-root', 'request-root:attempt:2')
            """);
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                deleted, request_id, attempt_no, parent_run_id, idempotency_key
            ) values('run-grandchild', 7, 'conv-checkpoint', 'q', '{}', 'PENDING', 0, 'request-root', 3, 'run-child', 'request-root:attempt:3')
            """);
        KnowledgeChatRunService service = runService(
            jdbcTemplate, mock(KnowledgeChatService.class), new SyncTaskExecutor()
        );

        String checkpointThreadId = ReflectionTestUtils.invokeMethod(
            service, "resolveCheckpointThreadId", "run-grandchild"
        );

        assertThat(checkpointThreadId).isEqualTo("run-root");
    }

    @Test
    void shouldPersistCancellationOnlyAfterDeltaBeforeTerminalCommit() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onDelta("partial before cancellation");
            jdbcTemplate.update("update ai_chat_run set cancel_requested = true where conversation_id = ?", "conv-race");
            KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
            response.setAnswer("must not be persisted");
            response.setResultJson(Map.of("traceId", "trace-race"));
            return response;
        });
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("cancel race");
        request.setConversationId("conv-race");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForList(
            "select role, content from ai_chat_message where conversation_id = ? order by message_id",
            "conv-race"
        )).containsExactly(
            Map.of("role", "USER", "content", "cancel race"),
            Map.of("role", "ASSISTANT", "content", "partial before cancellation")
        );
        assertThat(jdbcTemplate.queryForList("""
            select event_type from ai_chat_run_event
            where run_id = ? and event_type in ('ANSWERED', 'CANCEL_REQUESTED', 'CANCELLED')
            order by sequence_no
            """, String.class, completed.getRunId()))
            .containsExactly("CANCEL_REQUESTED", "CANCELLED");
        assertThat(jdbcTemplate.queryForList("""
            select event_type from ai_chat_run_outbox
            where run_id = ? and event_type in ('ANSWERED', 'CANCEL_REQUESTED', 'CANCELLED')
            order by sequence_no
            """, String.class, completed.getRunId()))
            .containsExactly("CANCEL_REQUESTED", "CANCELLED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(1) from ai_chat_message
            where run_id = ? and role = 'ASSISTANT' and content = 'must not be persisted'
            """, Integer.class, completed.getRunId())).isZero();
    }

    @Test
    void shouldRejectReadingAnotherUsersRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, conversation_id, question, request_json, status, deleted)
                values('run-other', 8, 'conv-other', 'question', '{}', 'ANSWERED', 0)
                """);
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            persistenceService(jdbcTemplate, new KnowledgeConversationService(jdbcTemplate)),
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        assertThatThrownBy(() -> service.getRun("run-other"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("chat run not found");
    }

    @Test
    void shouldListLatestProjectConversationRunsOnly() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, request_json, status, queued_at, update_time, deleted)
                values('run-old', 7, 99, 'conv-project', 'old question', '{}', 'ANSWERED', timestamp '2026-07-06 01:00:00', timestamp '2026-07-06 01:00:00', 0)
                """);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, request_json, status, queued_at, update_time, deleted)
                values('run-new', 7, 99, 'conv-project', 'new question', '{}', 'ANSWERED', timestamp '2026-07-06 02:00:00', timestamp '2026-07-06 02:00:00', 0)
                """);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, request_json, status, queued_at, update_time, deleted)
                values('run-other-project', 7, 100, 'conv-other', 'other project', '{}', 'ANSWERED', timestamp '2026-07-06 03:00:00', timestamp '2026-07-06 03:00:00', 0)
                """);
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            persistenceService(jdbcTemplate, new KnowledgeConversationService(jdbcTemplate)),
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        List<KnowledgeChatRunVO> runs = service.listRecentRuns(99L, 20);

        assertThat(runs).extracting(KnowledgeChatRunVO::getRunId).containsExactly("run-new");
        assertThat(runs.get(0).getQuestion()).isEqualTo("new question");
    }

    @Test
    void shouldCancelOwnedRunningRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, conversation_id, question, request_json,
                    status, progress_phase, progress_message, cancel_requested, deleted)
                values('run-cancel', 7, 'conv-1', 'question', '{}',
                    'RUNNING', 'answer', '正在生成回答', false, 0)
                """);
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            persistenceService(jdbcTemplate, new KnowledgeConversationService(jdbcTemplate)),
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        KnowledgeChatRunVO cancelled = service.cancelRun("run-cancel");

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLING");
        assertThat(cancelled.getCancelRequested()).isTrue();
        assertThat(jdbcTemplate.queryForList(
            "select event_type from ai_chat_run_event where run_id = 'run-cancel'",
            String.class
        )).containsExactly("CANCEL_REQUESTED");
    }

    @Test
    void shouldPersistStreamingProgressAndPartialAnswerDuringDurableRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onProgress("context", "上下文已自动压缩", Map.of(
                "progressEvent", "context_compacted",
                "contextWindowTokens", 1000000,
                "thresholdTokens", 900000,
                "beforeInputTokens", 920000,
                "afterInputTokens", 240000,
                "prompt", "PRIVATE_PROMPT"
            ));
            listener.onDelta("partial answer");
            String runId = jdbcTemplate.queryForObject(
                "select run_id from ai_chat_run where conversation_id = 'conv-stream'",
                String.class
            );
            KnowledgeChatRunVO running = queryRun(jdbcTemplate, runId);
            assertThat(running.getStatus()).isEqualTo("RUNNING");
            assertThat(running.getProgressPhase()).isEqualTo("context");
            assertThat(running.getProgressMessage()).isEqualTo("上下文已自动压缩");
            assertThat(running.getAnswer()).contains("partial answer");
            return answeredResponse();
        });
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("deep outline");
        request.setConversationId("conv-stream");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getStatus()).isEqualTo("ANSWERED");
        assertThat(completed.getAnswer()).contains("完整大纲");
        String progressPayload = jdbcTemplate.queryForObject(
            "select payload from ai_chat_run_event where event_type = 'CONTEXT_COMPACTED'",
            String.class
        );
        assertThat(progressPayload)
            .contains("\"progressEvent\":\"context_compacted\"")
            .contains("\"contextWindowTokens\":1000000")
            .contains("\"beforeInputTokens\":920000")
            .contains("\"afterInputTokens\":240000")
            .doesNotContain("PRIVATE_PROMPT", "prompt");
    }

    @Test
    void shouldBatchTokenSizedDeltasBeforeWritingRunEvents() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            for (int index = 0; index < 100; index++) {
                listener.onDelta("x");
            }
            return answeredResponse();
        });
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("batched delta");
        request.setConversationId("conv-batched-delta");

        KnowledgeChatRunVO completed = service.startRun(request);

        Integer deltaEvents = jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_event where run_id = ? and event_type = 'DELTA'",
            Integer.class,
            completed.getRunId()
        );
        assertThat(deltaEvents).isBetween(1, 2);
    }

    @Test
    void shouldFlushSmallPendingDeltaAfterTimerDeadline() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onDelta("a");
            listener.onDelta("b");
            long deadline = System.nanoTime() + 2_000_000_000L;
            Integer deltaEvents = 0;
            while (System.nanoTime() < deadline) {
                deltaEvents = jdbcTemplate.queryForObject("""
                    select count(1) from ai_chat_run_event
                    where event_type = 'DELTA'
                    """, Integer.class);
                if (deltaEvents != null && deltaEvents >= 2) {
                    break;
                }
                Thread.sleep(25L);
            }
            assertThat(deltaEvents).isEqualTo(2);
            return answeredResponse();
        });
        KnowledgeChatRunService service = runService(
            jdbcTemplate, chatService, new SyncTaskExecutor()
        );
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setDaemon(true);
        scheduler.setThreadNamePrefix("test-chat-run-timer-");
        scheduler.initialize();
        ReflectionTestUtils.setField(service, "heartbeatTaskScheduler", scheduler);
        ReflectionTestUtils.setField(service, "deltaTaskScheduler", scheduler);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("timer delta");
        request.setConversationId("conv-timer-delta");

        try {
            assertThat(service.startRun(request).getStatus()).isEqualTo("ANSWERED");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldPropagateClaimFailureSoRabbitCanRequeue() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                cancel_requested, deleted, attempt_no
            ) values('run-claim-failure', 7, 'conv-claim-failure', 'question', '{}',
                'PENDING', false, 0, 1)
            """);
        KnowledgeChatPersistenceService persistenceService = org.mockito.Mockito.spy(
            persistenceService(jdbcTemplate, new KnowledgeConversationService(jdbcTemplate))
        );
        doThrow(new IllegalStateException("database unavailable"))
            .when(persistenceService)
            .claimRun(any(), any(), any());
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            persistenceService,
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUser user = AuthUser.of(7L, "writer", Set.of("USER"));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("question");
        request.setConversationId("conv-claim-failure");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            service, "executeRun", "run-claim-failure", user, request, 1
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("database unavailable");
    }

    @Test
    void shouldRetryDurableDeltaWithoutDroppingBufferedText() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        KnowledgeChatPersistenceService persistenceService = org.mockito.Mockito.spy(
            persistenceService(jdbcTemplate, conversationService)
        );
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary database failure");
            }
            return invocation.callRealMethod();
        }).when(persistenceService).appendFencedEventAndSnapshot(
            any(), any(), anyLong(), any(), any(), any(), any(), any(), any()
        );
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onDelta("a");
            listener.onDelta("b");
            return answeredResponse();
        });
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
            persistenceService,
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("retry delta");
        request.setConversationId("conv-retry-delta");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getStatus()).isEqualTo("ANSWERED");
        assertThat(jdbcTemplate.queryForObject("""
            select payload from ai_chat_run_event
            where run_id = ? and event_type = 'DELTA'
            """, String.class, completed.getRunId())).contains("ab");
    }

    @Test
    void shouldContinueDurableRunWhenProgressSnapshotTemporarilyFails() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        KnowledgeChatPersistenceService persistenceService = org.mockito.Mockito.spy(
            persistenceService(jdbcTemplate, conversationService)
        );
        doAnswer(invocation -> {
            if ("CONTEXT_COMPACTED".equals(invocation.getArgument(3))) {
                throw new IllegalStateException("temporary progress database failure");
            }
            return invocation.callRealMethod();
        }).when(persistenceService).appendFencedEventAndSnapshot(
            any(), any(), anyLong(), any(), any(), any(), any(), any(), any()
        );
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onProgress("context", "上下文已自动压缩", Map.of(
                "progressEvent", "context_compacted",
                "beforeInputTokens", 910000L,
                "afterInputTokens", 620000L
            ));
            listener.onDelta("answer after progress failure");
            return answeredResponse();
        });
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
            persistenceService,
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("retry progress");
        request.setConversationId("conv-retry-progress");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getStatus()).isEqualTo("ANSWERED");
        assertThat(completed.getAnswer()).contains("完整大纲");
    }

    @Test
    void shouldResolveTraceIdFromWorkerSnakeCaseTracePayload() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenReturn(answeredSnakeCaseTraceResponse());
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("deep trace question");
        request.setConversationId("conv-snake-trace");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getTraceId()).isEqualTo("trace-snake-1");
    }

    @Test
    void shouldPersistBookCandidatesSoDeepRunCanRenderThePicker() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        createConversationTables(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgressForDurableRun(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenReturn(candidatesRequiredResponse());
        KnowledgeChatRunService service = runService(jdbcTemplate, chatService, new SyncTaskExecutor());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("帮我找找有没有一本书");
        request.setConversationId("conv-candidates");

        KnowledgeChatRunVO completed = service.startRun(request);
        KnowledgeChatRunVO detail = service.getRun(completed.getRunId());

        assertThat(detail.getStatus()).isEqualTo("ANSWERED");
        assertThat(detail.getResultJson())
            .contains("\"_runStatus\":\"candidates_required\"")
            .contains("\"_actions\":[\"select_candidate\"]")
            .contains("\"_candidates\":[")
            .contains("\"bookName\":\"候选甲\"")
            .contains("\"bookName\":\"候选乙\"")
            .contains("\"readableNovel\":false")
            .contains("\"unavailableReason\":\"search_result_is_audiobook\"");
    }

    private static KnowledgeChatResponseVO candidatesRequiredResponse() {
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("candidates_required");
        response.setAnswer("找到了多个可能的书籍，请选择正确作品后继续。");
        response.setActions(List.of("select_candidate"));
        KnowledgeChatResponseVO.CandidateVO readable = new KnowledgeChatResponseVO.CandidateVO();
        readable.setBookName("候选甲");
        readable.setPlatform("fanqie");
        readable.setPlatformBookId("pa");
        readable.setBookUrl("https://example.test/a");
        readable.setLocal(false);
        readable.setContentType("novel");
        readable.setReadableNovel(Boolean.TRUE);
        KnowledgeChatResponseVO.CandidateVO audiobook = new KnowledgeChatResponseVO.CandidateVO();
        audiobook.setBookName("候选乙");
        audiobook.setPlatform("fanqie");
        audiobook.setPlatformBookId("pb");
        audiobook.setBookUrl("https://example.test/b");
        audiobook.setLocal(false);
        audiobook.setContentType("audiobook");
        audiobook.setReadableNovel(Boolean.FALSE);
        audiobook.setUnavailableReason("search_result_is_audiobook");
        response.setCandidates(List.of(readable, audiobook));
        response.setResultJson(new java.util.LinkedHashMap<>(Map.of(
            "traceId", "trace-candidates",
            "conversationId", "conv-candidates",
            "candidateCount", 2
        )));
        return response;
    }

    private static KnowledgeChatResponseVO answeredResponse() {
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("完整大纲回答");
        KnowledgeChatResponseVO.SourceVO source = new KnowledgeChatResponseVO.SourceVO();
        source.setBookName("榜单作品");
        response.setSources(List.of(source));
        response.setResultJson(Map.of(
            "traceId", "trace-run-1",
            "conversationId", "conv-1"
        ));
        return response;
    }

    private static KnowledgeChatResponseVO answeredSnakeCaseTraceResponse() {
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("完整大纲回答");
        response.setResultJson(Map.of(
            "trace", Map.of(
                "trace_id", "trace-snake-1",
                "executedRuntimeNodes", List.of("assemble_context", "compose_answer")
            ),
            "conversationId", "conv-snake-trace"
        ));
        return response;
    }

    private static KnowledgeChatRunVO queryRun(JdbcTemplate jdbcTemplate, String runId) {
        return jdbcTemplate.queryForObject("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run
                where run_id = ?
                """,
            (rs, rowNum) -> {
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
                vo.setErrorMessage(rs.getString("error_message"));
                vo.setCancelRequested(rs.getBoolean("cancel_requested"));
                vo.setRetryCount(rs.getInt("retry_count"));
                vo.setMaxRetries(rs.getInt("max_retries"));
                return vo;
            },
            runId
        );
    }

    private static KnowledgeChatRunService runService(JdbcTemplate jdbcTemplate,
                                                      KnowledgeChatService chatService,
                                                      org.springframework.core.task.TaskExecutor executor) {
        return new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
            persistenceService(jdbcTemplate, new KnowledgeConversationService(jdbcTemplate)),
            new ObjectMapper(),
            executor
        );
    }

    private static KnowledgeChatPersistenceService persistenceService(
        JdbcTemplate jdbcTemplate,
        KnowledgeConversationService conversationService
    ) {
        return new KnowledgeChatPersistenceService(
            jdbcTemplate,
            conversationService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
            new ObjectMapper()
        );
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:chat-run-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        return new JdbcTemplate(dataSource);
    }

    private static void createChatRunTable(JdbcTemplate jdbcTemplate) {
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
                    snapshot_sequence_no bigint default 0,
                    error_message varchar(1000),
                    cancel_requested boolean default false,
                    retry_count int default 0,
                    max_retries int default 3,
                    queued_at timestamp default current_timestamp,
                    started_at timestamp,
                    finished_at timestamp,
                    update_time timestamp default current_timestamp,
                    deleted tinyint default 0
                )
                """);
    }

    private static void createConversationTables(JdbcTemplate jdbcTemplate) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("sql/phase18-agent-harness-conversation-rag-h2.sql")
        );
        DatabasePopulatorUtils.execute(populator, jdbcTemplate.getDataSource());
    }

    private static void createProjectTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table ai_project (" +
            "project_id bigint primary key," +
            "user_id bigint not null," +
            "name varchar(120) not null," +
            "status varchar(20) not null)");
    }
}
