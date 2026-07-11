package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.dto.AgentEvalRunRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentEvalQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentEvalService;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalCaseResultVO;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalRunVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeAgentEvalServiceTest {

    @Test
    void shouldListEvalRunsAndCaseResultsForAdminEvalCenter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createEvalTables(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases, metrics_json)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "run-001",
            "agent-runtime",
            "worker-golden-runner",
            "rule-based",
            "deepseek-chat",
            "FAILED",
            2,
            1,
            1,
            "{\"faithfulness_pass_rate\":0.5,\"trace_completeness_rate\":1.0}"
        );
        jdbcTemplate.update("""
                insert into ai_eval_case_result(run_id, case_key, status, intent, answer_mode,
                    retrieval_metrics, faithfulness_json, failures, trace_id, duration_ms)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            "market-001",
            "PASSED",
            "market_scan",
            "trend",
            "{\"hit_rate_at_k\":1.0}",
            "{\"passed\":true}",
            "[]",
            "trace-market",
            120
        );
        jdbcTemplate.update("""
                insert into ai_eval_case_result(run_id, case_key, status, intent, answer_mode,
                    retrieval_metrics, faithfulness_json, failures, trace_id, duration_ms)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            "mixed-001",
            "FAILED",
            "mixed_creation_research",
            "mixed_creation",
            "{\"hit_rate_at_k\":0.0}",
            "{\"passed\":false}",
            "[\"trace:missing_tool:rank.lookup\"]",
            "trace-mixed",
            240
        );
        KnowledgeAgentEvalService service = new KnowledgeAgentEvalService(jdbcTemplate, mock(LangGraphWorkerClient.class));

        List<AgentEvalRunVO> runs = service.listRuns(20);
        List<AgentEvalCaseResultVO> cases = service.listCaseResults(1L, 20);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getRunKey()).isEqualTo("run-001");
        assertThat(runs.get(0).getSuiteName()).isEqualTo("agent-runtime");
        assertThat(runs.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(runs.get(0).getTotalCases()).isEqualTo(2);
        assertThat(runs.get(0).getMetricsJson()).contains("faithfulness_pass_rate");
        assertThat(cases).hasSize(2);
        assertThat(cases).extracting(AgentEvalCaseResultVO::getCaseKey)
            .containsExactly("mixed-001", "market-001");
        assertThat(cases.get(0).getFailures()).contains("missing_tool");
        assertThat(cases.get(0).getDurationMs()).isEqualTo(240);
    }

    @Test
    void shouldQueueEvalRunInsteadOfCallingWorkerInline() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createEvalTables(jdbcTemplate);
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        KnowledgeAgentEvalQueueService queueService = mock(KnowledgeAgentEvalQueueService.class);
        when(queueService.enqueue(any(KnowledgeAgentEvalQueueService.EvalQueueItem.class))).thenReturn(true);
        KnowledgeAgentEvalService service = new KnowledgeAgentEvalService(jdbcTemplate, workerClient, queueService);
        AgentEvalRunRequest request = new AgentEvalRunRequest();
        request.setSuiteName("agent-runtime");
        request.setRunKey("agent-runtime:manual-001");
        request.setCaseLimit(10);

        AgentEvalRunVO result = service.startRun(request);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getRunKey()).isEqualTo("agent-runtime:manual-001");
        assertThat(result.getStatus()).isEqualTo("QUEUED");
        assertThat(result.getTotalCases()).isEqualTo(0);
        assertThat(result.getProgressCurrent()).isEqualTo(0);
        assertThat(result.getProgressTotal()).isEqualTo(10);
        assertThat(result.getRetryCount()).isEqualTo(0);
        assertThat(result.getCancelRequested()).isFalse();
        assertThat(result.getSettingsJson()).contains("ai:agent:eval:cancel:" + result.getId());
        assertThat(result.getSettingsJson()).contains("ai:agent:eval:progress:" + result.getId());
        verifyNoInteractions(workerClient);
        verify(queueService).enqueue(argThat(item ->
            item.runId().equals(result.getId())
                && "agent-runtime".equals(item.suiteName())
                && "agent-runtime:manual-001".equals(item.runKey())
                && Integer.valueOf(10).equals(item.caseLimit())
                && item.attempt() == 0
        ));
    }

    @Test
    void shouldRecoverStaleEvalRunsUsingRetryBudget() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createEvalTables(jdbcTemplate);
        KnowledgeAgentEvalQueueService queueService = mock(KnowledgeAgentEvalQueueService.class);
        when(queueService.enqueue(any(KnowledgeAgentEvalQueueService.EvalQueueItem.class))).thenReturn(true);
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases, progress_current, progress_total,
                    progress_message, cancel_requested, retry_count, max_retries, last_heartbeat_at)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "agent-runtime:stale-retry",
            "agent-runtime",
            "admin-trigger",
            "rule-based",
            "deepseek-chat",
            "RUNNING",
            10,
            2,
            0,
            2,
            10,
            "worker running",
            false,
            1,
            3,
            java.sql.Timestamp.valueOf("2000-01-01 00:00:00")
        );
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases, progress_current, progress_total,
                    progress_message, cancel_requested, retry_count, max_retries, last_heartbeat_at)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "agent-runtime:stale-fail",
            "agent-runtime",
            "admin-trigger",
            "rule-based",
            "deepseek-chat",
            "CANCELLING",
            10,
            2,
            0,
            2,
            10,
            "cancel requested",
            true,
            3,
            3,
            java.sql.Timestamp.valueOf("2000-01-01 00:00:00")
        );
        KnowledgeAgentEvalService service = new KnowledgeAgentEvalService(
            jdbcTemplate,
            mock(LangGraphWorkerClient.class),
            queueService
        );

        int recovered = service.recoverStaleRuns(Duration.ofMinutes(30));

        assertThat(recovered).isEqualTo(2);
        assertThat(service.listRuns(10))
            .filteredOn(run -> "agent-runtime:stale-retry".equals(run.getRunKey()))
            .singleElement()
            .satisfies(run -> {
                assertThat(run.getStatus()).isEqualTo("QUEUED");
                assertThat(run.getRetryCount()).isEqualTo(2);
                assertThat(run.getProgressMessage()).contains("stale");
            });
        assertThat(service.listRuns(10))
            .filteredOn(run -> "agent-runtime:stale-fail".equals(run.getRunKey()))
            .singleElement()
            .satisfies(run -> {
                assertThat(run.getStatus()).isEqualTo("FAILED");
                assertThat(run.getErrorMessage()).contains("stale");
            });
        verify(queueService).enqueue(argThat(item ->
            item.runId().equals(1L)
                && "agent-runtime:stale-retry".equals(item.runKey())
                && item.attempt() == 2
        ));
    }

    @Test
    void shouldCancelAndRetryEvalRunsWithDurableFields() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createEvalTables(jdbcTemplate);
        KnowledgeAgentEvalQueueService queueService = mock(KnowledgeAgentEvalQueueService.class);
        when(queueService.enqueue(any(KnowledgeAgentEvalQueueService.EvalQueueItem.class))).thenReturn(true);
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases, progress_current, progress_total,
                    progress_message, cancel_requested, retry_count, max_retries)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "agent-runtime:retry-001",
            "agent-runtime",
            "admin-trigger",
            "rule-based",
            "deepseek-chat",
            "RUNNING",
            10,
            4,
            6,
            4,
            10,
            "failed on case 5",
            false,
            1,
            3
        );
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases, progress_current, progress_total,
                    progress_message, cancel_requested, retry_count, max_retries)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "agent-runtime:failed-001",
            "agent-runtime",
            "admin-trigger",
            "rule-based",
            "deepseek-chat",
            "FAILED",
            10,
            4,
            6,
            4,
            10,
            "failed on case 5",
            false,
            1,
            3
        );
        KnowledgeAgentEvalService service = new KnowledgeAgentEvalService(
            jdbcTemplate,
            mock(LangGraphWorkerClient.class),
            queueService
        );

        AgentEvalRunVO cancelling = service.cancelRun(1L);
        AgentEvalRunVO retried = service.retryRun(2L);

        assertThat(cancelling.getStatus()).isEqualTo("CANCELLING");
        assertThat(cancelling.getCancelRequested()).isTrue();
        assertThat(retried.getStatus()).isEqualTo("QUEUED");
        assertThat(retried.getRetryCount()).isEqualTo(2);
        assertThat(retried.getCancelRequested()).isFalse();
        assertThat(retried.getProgressCurrent()).isEqualTo(0);
        verify(queueService).enqueue(argThat(item ->
            item.runId().equals(2L)
                && "agent-runtime:failed-001".equals(item.runKey())
                && item.attempt() == 2
        ));
    }

    @Test
    void shouldRejectRetryForNonTerminalEvalRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createEvalTables(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name,
                    status, total_cases, passed_cases, failed_cases, progress_current, progress_total,
                    cancel_requested, retry_count, max_retries)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "agent-runtime:running-001",
            "agent-runtime",
            "admin-trigger",
            "rule-based",
            "RUNNING",
            10,
            3,
            0,
            3,
            10,
            false,
            0,
            3
        );
        KnowledgeAgentEvalService service = new KnowledgeAgentEvalService(
            jdbcTemplate,
            mock(LangGraphWorkerClient.class),
            mock(KnowledgeAgentEvalQueueService.class)
        );

        assertThatThrownBy(() -> service.retryRun(1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only failed or cancelled eval runs can be retried");
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent-eval-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        return new JdbcTemplate(dataSource);
    }

    private static void createEvalTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "run_key varchar(128)," +
            "suite_name varchar(100)," +
            "runner_name varchar(100)," +
            "evaluator_name varchar(100)," +
            "model_name varchar(100)," +
            "status varchar(20)," +
            "total_cases int," +
            "passed_cases int," +
            "failed_cases int," +
            "progress_current int default 0," +
            "progress_total int default 0," +
            "progress_message varchar(500)," +
            "cancel_requested boolean default false," +
            "cancelled_at timestamp," +
            "retry_count int default 0," +
            "max_retries int default 3," +
            "next_retry_at timestamp," +
            "last_heartbeat_at timestamp," +
            "error_message varchar(1000)," +
            "metrics_json clob," +
            "settings_json clob," +
            "queued_at timestamp default current_timestamp," +
            "started_at timestamp default current_timestamp," +
            "finished_at timestamp," +
            "update_time timestamp default current_timestamp," +
            "deleted tinyint default 0)");
        jdbcTemplate.execute("create table ai_eval_case_result (" +
            "id bigint auto_increment primary key," +
            "run_id bigint," +
            "case_key varchar(128)," +
            "status varchar(20)," +
            "intent varchar(80)," +
            "answer_mode varchar(80)," +
            "retrieval_metrics clob," +
            "faithfulness_json clob," +
            "failures clob," +
            "trace_id varchar(80)," +
            "duration_ms int," +
            "create_time timestamp default current_timestamp," +
            "deleted tinyint default 0)");
    }
}
