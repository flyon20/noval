package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.dto.AgentEvalRunRequest;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalRunVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "app.knowledge.eval", name = "queue-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeAgentEvalJobExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeAgentEvalJobExecutor.class);

    private final JdbcTemplate jdbcTemplate;
    private final LangGraphWorkerClient langGraphWorkerClient;
    private final KnowledgeAgentEvalQueueService queueService;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeAgentEvalJobExecutor(JdbcTemplate jdbcTemplate,
                                         LangGraphWorkerClient langGraphWorkerClient,
                                         KnowledgeAgentEvalQueueService queueService,
                                         KnowledgeProperties knowledgeProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.queueService = queueService;
        this.knowledgeProperties = knowledgeProperties;
    }

    public enum QueueConsumeAction {
        ACK,
        REQUEUE,
        DEAD_LETTER
    }

    public QueueConsumeAction handleQueuedJob(KnowledgeAgentEvalQueueService.EvalQueueItem item) {
        if (item == null || item.runId() == null) {
            LOGGER.warn("knowledge eval queued job discarded because payload is incomplete");
            return QueueConsumeAction.DEAD_LETTER;
        }
        Map<String, Object> run = findRunRow(item.runId());
        if (run.isEmpty()) {
            return QueueConsumeAction.DEAD_LETTER;
        }
        String status = stringValue(run.get("status"));
        if (isTerminal(status)) {
            return QueueConsumeAction.ACK;
        }
        if (booleanValue(run.get("cancel_requested"))) {
            markCancelled(item.runId(), "cancelled before worker execution");
            return QueueConsumeAction.ACK;
        }
        try {
            markRunning(item.runId());
            AgentEvalRunVO workerRun = langGraphWorkerClient.startKnowledgeEvalRun(workerRequest(item));
            mergeWorkerResult(item.runId(), workerRun);
            return QueueConsumeAction.ACK;
        } catch (Exception ex) {
            return handleFailure(item, ex);
        }
    }

    private QueueConsumeAction handleFailure(KnowledgeAgentEvalQueueService.EvalQueueItem item, Exception ex) {
        int nextAttempt = item.attempt() + 1;
        int maxRetries = Math.max(0, knowledgeProperties.getEval().getMaxRetries());
        LOGGER.warn("knowledge eval queued job failed: runId={}, attempt={}, message={}",
            item.runId(),
            nextAttempt,
            ex.getMessage());
        if (nextAttempt <= maxRetries) {
            long delaySeconds = queueService.retryBackoffSeconds(nextAttempt);
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);
            jdbcTemplate.update("""
                    update ai_eval_run
                    set status = 'QUEUED',
                        retry_count = ?,
                        next_retry_at = ?,
                        error_message = ?,
                        progress_message = 'retry scheduled',
                        update_time = current_timestamp
                    where id = ?
                    """,
                nextAttempt,
                Timestamp.valueOf(nextRetryAt),
                ex.getMessage(),
                item.runId()
            );
            if (queueService.retry(item, nextAttempt, delaySeconds)) {
                return QueueConsumeAction.ACK;
            }
            return QueueConsumeAction.REQUEUE;
        }
        jdbcTemplate.update("""
                update ai_eval_run
                set status = 'FAILED',
                    error_message = ?,
                    finished_at = current_timestamp,
                    progress_message = 'failed',
                    update_time = current_timestamp
                where id = ?
                """,
            ex.getMessage(),
            item.runId()
        );
        return QueueConsumeAction.DEAD_LETTER;
    }

    private AgentEvalRunRequest workerRequest(KnowledgeAgentEvalQueueService.EvalQueueItem item) {
        AgentEvalRunRequest request = new AgentEvalRunRequest();
        request.setRunId(item.runId());
        request.setRunKey(item.runKey());
        request.setSuiteName(item.suiteName());
        request.setRunnerName(item.runnerName());
        request.setEvaluatorName(item.evaluatorName());
        request.setModelName(item.modelName());
        request.setCaseLimit(item.caseLimit());
        request.setSynchronous(true);
        request.setCancelKey(item.cancelKey());
        request.setProgressKey(item.progressKey());
        return request;
    }

    private Map<String, Object> findRunRow(Long runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select id, status, cancel_requested from ai_eval_run where id = ? and deleted = 0",
            runId
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void markRunning(Long runId) {
        jdbcTemplate.update("""
                update ai_eval_run
                set status = 'RUNNING',
                    started_at = coalesce(started_at, current_timestamp),
                    last_heartbeat_at = current_timestamp,
                    progress_message = 'worker running',
                    update_time = current_timestamp
                where id = ?
                """,
            runId
        );
    }

    private void markCancelled(Long runId, String message) {
        jdbcTemplate.update("""
                update ai_eval_run
                set status = 'CANCELLED',
                    cancel_requested = true,
                    cancelled_at = coalesce(cancelled_at, current_timestamp),
                    finished_at = current_timestamp,
                    progress_message = ?,
                    update_time = current_timestamp
                where id = ?
                """,
            message,
            runId
        );
    }

    private void mergeWorkerResult(Long runId, AgentEvalRunVO workerRun) {
        if (workerRun == null) {
            jdbcTemplate.update("""
                    update ai_eval_run
                    set status = 'FAILED',
                        error_message = 'worker returned empty eval result',
                        finished_at = current_timestamp,
                        update_time = current_timestamp
                    where id = ?
                    """,
                runId
            );
            return;
        }
        String status = workerRun.getStatus() == null || workerRun.getStatus().isBlank()
            ? "PASSED"
            : workerRun.getStatus();
        jdbcTemplate.update("""
                update ai_eval_run
                set status = ?,
                    total_cases = ?,
                    passed_cases = ?,
                    failed_cases = ?,
                    progress_current = ?,
                    progress_total = ?,
                    progress_message = ?,
                    metrics_json = ?,
                    error_message = ?,
                    finished_at = case when ? in ('PASSED', 'FAILED', 'CANCELLED') then current_timestamp else finished_at end,
                    last_heartbeat_at = current_timestamp,
                    update_time = current_timestamp
                where id = ?
                """,
            status,
            intValue(workerRun.getTotalCases()),
            intValue(workerRun.getPassedCases()),
            intValue(workerRun.getFailedCases()),
            workerRun.getProgressCurrent() == null ? intValue(workerRun.getPassedCases()) + intValue(workerRun.getFailedCases()) : workerRun.getProgressCurrent(),
            workerRun.getProgressTotal() == null ? intValue(workerRun.getTotalCases()) : workerRun.getProgressTotal(),
            workerRun.getProgressMessage(),
            workerRun.getMetricsJson(),
            workerRun.getErrorMessage(),
            status,
            runId
        );
    }

    private boolean isTerminal(String status) {
        return "PASSED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
