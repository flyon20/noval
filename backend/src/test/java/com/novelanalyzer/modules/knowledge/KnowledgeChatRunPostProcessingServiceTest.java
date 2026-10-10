package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunPostProcessingService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class KnowledgeChatRunPostProcessingServiceTest {

    @Test
    void shouldProcessAnsweredOutboxOnceAfterTerminalRunCommit() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                answer, result_json, cancel_requested, retry_count, max_retries,
                queued_at, finished_at, deleted, request_id, attempt_no,
                idempotency_key, execution_mode
            ) values(
                'run-postprocess', 7, 'conv-postprocess', 'question',
                '{"question":"question","conversationId":"conv-postprocess","requestId":"request-postprocess"}',
                'ANSWERED', 'answer',
                '{"traceId":"trace-postprocess","_runStatus":"answered","_actions":[],"_sources":[]}',
                false, 0, 3, current_timestamp, current_timestamp, 0,
                'request-postprocess', 1, 'request-postprocess', 'FAST'
            )
            """);
        jdbcTemplate.update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(
                1, 'run-postprocess', 2, 'ANSWERED', 'run:postprocess:terminal',
                'DISPATCHING', 1, current_timestamp, current_timestamp, current_timestamp
            )
            """);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatRunPostProcessingService service = new KnowledgeChatRunPostProcessingService(
            jdbcTemplate,
            new ObjectMapper(),
            chatService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        assertThat(service.process(1L, "run-postprocess", "ANSWERED")).isTrue();
        assertThat(service.process(1L, "run-postprocess", "ANSWERED")).isFalse();
        verify(chatService, times(1)).persistCompletedRunDatabaseArtifactsStrict(
            eq(7L), any(), eq("conv-postprocess"), any(KnowledgeChatResponseVO.class)
        );
        verify(chatService, times(1)).persistCompletedRunIndexArtifacts(
            eq(7L), any(), any(KnowledgeChatResponseVO.class), isNull(),
            eq("chat-run:run-postprocess:outbox:1")
        );
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run_outbox where outbox_id = 1",
            String.class
        )).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
            "select result_json from ai_chat_run where run_id = 'run-postprocess'",
            String.class
        )).contains("\"postProcessingStatus\":\"completed\"");
    }

    @Test
    void shouldKeepTerminalOutboxRetryableWhenIndexSubmissionFails() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertAnsweredRunAndOutbox(jdbcTemplate, "run-index-failure");
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        doThrow(new IllegalStateException("index unavailable"))
            .when(chatService)
            .persistCompletedRunIndexArtifacts(
                eq(7L), any(), any(), isNull(), eq("chat-run:run-index-failure:outbox:1")
            );
        KnowledgeChatRunPostProcessingService service = new KnowledgeChatRunPostProcessingService(
            jdbcTemplate,
            new ObjectMapper(),
            chatService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        assertThatThrownBy(() -> service.process(1L, "run-index-failure", "ANSWERED"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("index unavailable");
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run_outbox where outbox_id = 1",
            String.class
        )).isEqualTo("DISPATCHING");
        verify(chatService, never()).persistCompletedRunDatabaseArtifactsStrict(
            any(), any(), any(), any()
        );
    }

    @Test
    void shouldPersistIndexMetadataAddedBeforeTransactionalCommit() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertAnsweredRunAndOutbox(jdbcTemplate, "run-index-metadata");
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        doAnswer(invocation -> {
            KnowledgeChatResponseVO response = invocation.getArgument(2);
            Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
            resultJson.put("indexJob", Map.of("jobId", 188L));
            response.setResultJson(resultJson);
            return null;
        }).when(chatService).persistCompletedRunIndexArtifacts(
            eq(7L), any(), any(), isNull(), eq("chat-run:run-index-metadata:outbox:1")
        );
        KnowledgeChatRunPostProcessingService service = new KnowledgeChatRunPostProcessingService(
            jdbcTemplate,
            new ObjectMapper(),
            chatService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        assertThat(service.process(1L, "run-index-metadata", "ANSWERED")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select result_json from ai_chat_run where run_id = 'run-index-metadata'",
            String.class
        )).contains("\"indexJob\":{\"jobId\":188}");
    }

    @Test
    void shouldReuseSameDurableIndexActionAfterCrashBeforeOutboxCommit() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertAnsweredRunAndOutbox(jdbcTemplate, "run-index-replay");
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        AtomicInteger durableSubmissions = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        java.util.Set<String> submittedActions = new java.util.HashSet<>();
        doAnswer(invocation -> {
            String actionKey = invocation.getArgument(4);
            if (submittedActions.add(actionKey)) {
                durableSubmissions.incrementAndGet();
            }
            KnowledgeChatResponseVO response = invocation.getArgument(2);
            Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
            resultJson.put("indexJob", Map.of("jobId", 199L, "status", "SUCCESS"));
            response.setResultJson(resultJson);
            if (invocations.getAndIncrement() == 0) {
                throw new IllegalStateException("process stopped after durable job submission");
            }
            return null;
        }).when(chatService).persistCompletedRunIndexArtifacts(
            eq(7L), any(), any(), isNull(), any(String.class)
        );
        KnowledgeChatRunPostProcessingService service = new KnowledgeChatRunPostProcessingService(
            jdbcTemplate,
            new ObjectMapper(),
            chatService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        assertThatThrownBy(() -> service.process(1L, "run-index-replay", "ANSWERED"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("durable job submission");
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_chat_run_outbox where outbox_id = 1",
            String.class
        )).isEqualTo("DISPATCHING");

        assertThat(service.process(1L, "run-index-replay", "ANSWERED")).isTrue();
        assertThat(durableSubmissions).hasValue(1);
        assertThat(submittedActions).containsExactly("chat-run:run-index-replay:outbox:1");
        assertThat(jdbcTemplate.queryForObject(
            "select result_json from ai_chat_run where run_id = 'run-index-replay'",
            String.class
        )).contains("\"jobId\":199");
    }

    @Test
    void shouldWriteEnrichedIndexMetadataToAssistantMessage() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        insertAnsweredRunAndOutbox(jdbcTemplate, "run-assistant-metadata");
        jdbcTemplate.update("""
            insert into ai_conversation(
                conversation_id, user_id, title, status, created_at, updated_at
            ) values(
                'conv-postprocess', 7, 'conversation', 'ACTIVE', current_timestamp, current_timestamp
            )
            """);
        jdbcTemplate.update("""
            insert into ai_chat_message(
                conversation_id, user_id, run_id, role, content, content_json, created_at, deleted
            ) values(
                'conv-postprocess', 7, 'run-assistant-metadata', 'ASSISTANT', 'answer', '{}',
                current_timestamp, 0
            )
            """);
        Long messageId = jdbcTemplate.queryForObject(
            "select message_id from ai_chat_message where run_id = 'run-assistant-metadata'",
            Long.class
        );
        jdbcTemplate.update(
            "update ai_chat_run set response_message_id = ? where run_id = 'run-assistant-metadata'",
            messageId
        );
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        doAnswer(invocation -> {
            KnowledgeChatResponseVO response = invocation.getArgument(2);
            Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
            resultJson.put("localBookId", 401L);
            resultJson.put("chapterIndexJob", Map.of("jobId", 200L, "status", "SUCCESS"));
            response.setResultJson(resultJson);
            return null;
        }).when(chatService).persistCompletedRunIndexArtifacts(
            eq(7L), any(), any(), isNull(), eq("chat-run:run-assistant-metadata:outbox:1")
        );
        KnowledgeChatRunPostProcessingService service = new KnowledgeChatRunPostProcessingService(
            jdbcTemplate,
            new ObjectMapper(),
            chatService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        assertThat(service.process(1L, "run-assistant-metadata", "ANSWERED")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select content_json from ai_chat_message where message_id = ?",
            String.class,
            messageId
        ))
            .contains("\"localBookId\":401")
            .contains("\"chapterIndexJob\":{")
            .contains("\"jobId\":200")
            .contains("\"status\":\"SUCCESS\"");
    }

    @Test
    void shouldRestoreBookCandidatesForCandidatesRequiredRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                answer, result_json, cancel_requested, retry_count, max_retries,
                queued_at, finished_at, deleted, request_id, attempt_no,
                idempotency_key, execution_mode
            ) values(
                'run-candidates', 7, 'conv-postprocess', 'question',
                '{"question":"question","conversationId":"conv-postprocess","requestId":"request-postprocess"}',
                'ANSWERED', 'answer',
                '{"candidateCount":2,"_runStatus":"candidates_required",
                  "_actions":["select_candidate"],"_sources":[],
                  "_candidates":[
                    {"bookName":"book-a","platform":"fanqie","platformBookId":"pa",
                     "bookUrl":"https://example.test/a","local":false,
                     "contentType":"novel","readableNovel":true},
                    {"bookName":"book-b","platform":"fanqie","platformBookId":"pb",
                     "bookUrl":"https://example.test/b","local":false,
                     "contentType":"audiobook","readableNovel":false,
                     "unavailableReason":"search_result_is_audiobook"}
                  ]}',
                false, 0, 3, current_timestamp, current_timestamp, 0,
                'request-postprocess', 1, 'request-postprocess', 'DEEP'
            )
            """);
        jdbcTemplate.update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(
                1, 'run-candidates', 2, 'ANSWERED', 'run:candidates:terminal',
                'DISPATCHING', 1, current_timestamp, current_timestamp, current_timestamp
            )
            """);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        KnowledgeChatRunPostProcessingService service = new KnowledgeChatRunPostProcessingService(
            jdbcTemplate,
            new ObjectMapper(),
            chatService,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        assertThat(service.process(1L, "run-candidates", "ANSWERED")).isTrue();
        String storedResult = jdbcTemplate.queryForObject(
            "select result_json from ai_chat_run where run_id = 'run-candidates'",
            String.class
        );
        assertThat(storedResult)
            .contains("\"status\":\"candidates_required\"")
            .contains("\"actions\":[\"select_candidate\"]")
            .contains("\"bookName\":\"book-a\"")
            .contains("\"bookName\":\"book-b\"")
            .contains("\"readableNovel\":false")
            .contains("\"unavailableReason\":\"search_result_is_audiobook\"")
            .doesNotContain("\"_candidates\"")
            .doesNotContain("\"candidates\":[]");
    }

    private void insertAnsweredRunAndOutbox(JdbcTemplate jdbcTemplate, String runId) {
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                answer, result_json, cancel_requested, retry_count, max_retries,
                queued_at, finished_at, deleted, request_id, attempt_no,
                idempotency_key, execution_mode
            ) values(
                ?, 7, 'conv-postprocess', 'question',
                '{"question":"question","conversationId":"conv-postprocess","requestId":"request-postprocess"}',
                'ANSWERED', 'answer',
                '{"traceId":"trace-postprocess","_runStatus":"answered","_actions":[],"_sources":[]}',
                false, 0, 3, current_timestamp, current_timestamp, 0,
                'request-postprocess', 1, 'request-postprocess', 'FAST'
            )
            """, runId);
        jdbcTemplate.update("""
            insert into ai_chat_run_outbox(
                event_id, run_id, sequence_no, event_type, event_idempotency_key,
                status, attempt_count, available_at, created_at, updated_at
            ) values(
                1, ?, 2, 'ANSWERED', 'run:postprocess:terminal',
                'DISPATCHING', 1, current_timestamp, current_timestamp, current_timestamp
            )
            """, runId);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:chat-run-postprocess-" + System.nanoTime()
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
}
