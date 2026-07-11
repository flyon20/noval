package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeChatRunServiceTest {

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateRunAndPersistAnsweredResultForCurrentUser() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgress(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenReturn(answeredResponse());
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
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
        assertThat(runs).extracting(KnowledgeChatRunVO::getRunId).containsExactly(started.getRunId());
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
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, conversation_id, question, request_json,
                    status, progress_phase, progress_message, cancel_requested, deleted)
                values('run-cancel', 7, 'conv-1', 'question', '{}',
                    'RUNNING', 'answer', '正在生成回答', false, 0)
                """);
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            mock(KnowledgeChatService.class),
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        KnowledgeChatRunVO cancelled = service.cancelRun("run-cancel");

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getCancelRequested()).isTrue();
        assertThat(cancelled.getProgressMessage()).contains("已请求取消");
    }

    @Test
    void shouldPersistStreamingProgressAndPartialAnswerDuringDurableRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgress(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            KnowledgeChatService.ChatProgressListener listener = invocation.getArgument(1);
            listener.onProgress("intent", "progress intent");
            listener.onDelta("partial answer");
            String runId = jdbcTemplate.queryForObject(
                "select run_id from ai_chat_run where conversation_id = 'conv-stream'",
                String.class
            );
            KnowledgeChatRunVO running = queryRun(jdbcTemplate, runId);
            assertThat(running.getStatus()).isEqualTo("RUNNING");
            assertThat(running.getProgressPhase()).isEqualTo("intent");
            assertThat(running.getProgressMessage()).isEqualTo("progress intent");
            assertThat(running.getAnswer()).contains("partial answer");
            return answeredResponse();
        });
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("deep outline");
        request.setConversationId("conv-stream");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getStatus()).isEqualTo("ANSWERED");
        assertThat(completed.getAnswer()).contains("完整大纲");
    }

    @Test
    void shouldResolveTraceIdFromWorkerSnakeCaseTracePayload() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createChatRunTable(jdbcTemplate);
        KnowledgeChatService chatService = mock(KnowledgeChatService.class);
        when(chatService.chatWithProgress(
            any(KnowledgeChatRequest.class),
            any(KnowledgeChatService.ChatProgressListener.class),
            any(BooleanSupplier.class)
        )).thenReturn(answeredSnakeCaseTraceResponse());
        KnowledgeChatRunService service = new KnowledgeChatRunService(
            jdbcTemplate,
            chatService,
            new ObjectMapper(),
            new SyncTaskExecutor()
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeChatRequest request = new KnowledgeChatRequest();
        request.setQuestion("deep trace question");
        request.setConversationId("conv-snake-trace");

        KnowledgeChatRunVO completed = service.startRun(request);

        assertThat(completed.getTraceId()).isEqualTo("trace-snake-1");
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
}
