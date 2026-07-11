package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.dto.AgentEvalRunRequest;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalCaseResultVO;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalRunVO;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeAgentEvalService {

    private final JdbcTemplate jdbcTemplate;
    private final LangGraphWorkerClient langGraphWorkerClient;
    private final KnowledgeAgentEvalQueueService queueService;
    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public KnowledgeAgentEvalService(JdbcTemplate jdbcTemplate,
                                     LangGraphWorkerClient langGraphWorkerClient,
                                     ObjectProvider<KnowledgeAgentEvalQueueService> queueServiceProvider,
                                     ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this(
            jdbcTemplate,
            langGraphWorkerClient,
            queueServiceProvider == null ? null : queueServiceProvider.getIfAvailable(),
            stringRedisTemplateProvider == null ? null : stringRedisTemplateProvider.getIfAvailable()
        );
    }

    public KnowledgeAgentEvalService(JdbcTemplate jdbcTemplate,
                                     LangGraphWorkerClient langGraphWorkerClient) {
        this(
            jdbcTemplate,
            langGraphWorkerClient,
            (KnowledgeAgentEvalQueueService) null,
            (StringRedisTemplate) null
        );
    }

    public KnowledgeAgentEvalService(JdbcTemplate jdbcTemplate,
                                     LangGraphWorkerClient langGraphWorkerClient,
                                     KnowledgeAgentEvalQueueService queueService) {
        this(jdbcTemplate, langGraphWorkerClient, queueService, null);
    }

    private KnowledgeAgentEvalService(JdbcTemplate jdbcTemplate,
                                      LangGraphWorkerClient langGraphWorkerClient,
                                      KnowledgeAgentEvalQueueService queueService,
                                      StringRedisTemplate stringRedisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.queueService = queueService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public AgentEvalRunVO startRun(AgentEvalRunRequest request) {
        AgentEvalRunRequest normalized = normalizeRequest(request);
        if (queueService == null) {
            return langGraphWorkerClient.startKnowledgeEvalRun(normalized);
        }
        Long runId = createQueuedRun(normalized);
        KnowledgeAgentEvalQueueService.EvalQueueItem item = queueItem(runId, normalized, 0);
        if (!queueService.enqueue(item)) {
            markFailed(runId, "knowledge eval queue publish failed");
            throw new IllegalStateException("knowledge eval queue publish failed");
        }
        return findRun(runId);
    }

    public AgentEvalRunVO cancelRun(Long runId) {
        if (runId == null) {
            return null;
        }
        AgentEvalRunVO current = findRun(runId);
        if (current == null) {
            return null;
        }
        if (!isCancellable(current.getStatus())) {
            return current;
        }
        jdbcTemplate.update("""
                update ai_eval_run
                set status = 'CANCELLING',
                    cancel_requested = true,
                    cancelled_at = current_timestamp,
                    progress_message = 'cancel requested by admin',
                    update_time = current_timestamp
                where id = ? and deleted = 0
                """,
            runId
        );
        writeRedis(cancelKey(runId), "true", Duration.ofHours(12));
        return findRun(runId);
    }

    public AgentEvalRunVO retryRun(Long runId) {
        AgentEvalRunVO current = findRun(runId);
        if (current == null) {
            return null;
        }
        if (!isRetryable(current.getStatus())) {
            throw new IllegalStateException("only failed or cancelled eval runs can be retried");
        }
        int retryCount = current.getRetryCount() == null ? 0 : current.getRetryCount();
        int maxRetries = current.getMaxRetries() == null ? 3 : current.getMaxRetries();
        if (retryCount >= maxRetries) {
            throw new IllegalStateException("eval run retry limit exceeded");
        }
        int nextAttempt = retryCount + 1;
        jdbcTemplate.update("""
                update ai_eval_run
                set status = 'QUEUED',
                    progress_current = 0,
                    progress_message = 'retry queued',
                    cancel_requested = false,
                    cancelled_at = null,
                    retry_count = ?,
                    next_retry_at = null,
                    error_message = null,
                    queued_at = current_timestamp,
                    update_time = current_timestamp
                where id = ? and deleted = 0
                """,
            nextAttempt,
            runId
        );
        AgentEvalRunRequest request = requestFromRun(findRun(runId));
        if (queueService != null && !queueService.enqueue(queueItem(runId, request, nextAttempt))) {
            markFailed(runId, "knowledge eval retry queue publish failed");
            throw new IllegalStateException("knowledge eval retry queue publish failed");
        }
        writeRedis(cancelKey(runId), "false", Duration.ofHours(12));
        return findRun(runId);
    }

    public int recoverStaleRuns(Duration staleAfter) {
        Duration safeStaleAfter = staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()
            ? Duration.ofMinutes(30)
            : staleAfter;
        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minus(safeStaleAfter));
        List<AgentEvalRunVO> staleRuns = jdbcTemplate.query("""
                select id, run_key, suite_name, runner_name, evaluator_name, model_name,
                       status, total_cases, passed_cases, failed_cases,
                       progress_current, progress_total, progress_message, cancel_requested,
                       cancelled_at, retry_count, max_retries, next_retry_at,
                       last_heartbeat_at, error_message, metrics_json, settings_json,
                       queued_at, started_at, finished_at
                from ai_eval_run
                where deleted = 0
                  and status in ('RUNNING', 'CANCELLING')
                  and last_heartbeat_at is not null
                  and last_heartbeat_at < ?
                """,
            (rs, rowNum) -> mapRun(rs),
            cutoff
        );
        int recovered = 0;
        for (AgentEvalRunVO run : staleRuns) {
            int retryCount = run.getRetryCount() == null ? 0 : run.getRetryCount();
            int maxRetries = run.getMaxRetries() == null ? 3 : run.getMaxRetries();
            if (retryCount < maxRetries && queueService != null) {
                int nextAttempt = retryCount + 1;
                jdbcTemplate.update("""
                        update ai_eval_run
                        set status = 'QUEUED',
                            progress_current = 0,
                            progress_message = 'stale run recovered and requeued',
                            cancel_requested = false,
                            cancelled_at = null,
                            retry_count = ?,
                            next_retry_at = null,
                            error_message = null,
                            queued_at = current_timestamp,
                            update_time = current_timestamp
                        where id = ? and deleted = 0
                        """,
                    nextAttempt,
                    run.getId()
                );
                AgentEvalRunRequest request = requestFromRun(findRun(run.getId()));
                if (!queueService.enqueue(queueItem(run.getId(), request, nextAttempt))) {
                    markFailed(run.getId(), "stale eval run recovery queue publish failed");
                }
            } else {
                markFailed(run.getId(), "stale eval run recovered after heartbeat timeout");
                jdbcTemplate.update("""
                        update ai_eval_run
                        set progress_message = 'stale run recovered as failed',
                            update_time = current_timestamp
                        where id = ? and deleted = 0
                        """,
                    run.getId()
                );
            }
            recovered++;
        }
        return recovered;
    }

    public List<AgentEvalRunVO> listRuns(Integer limit) {
        int safeLimit = normalizeLimit(limit);
        try {
            return jdbcTemplate.query("""
                    select id, run_key, suite_name, runner_name, evaluator_name, model_name,
                           status, total_cases, passed_cases, failed_cases,
                           progress_current, progress_total, progress_message, cancel_requested,
                           cancelled_at, retry_count, max_retries, next_retry_at,
                           last_heartbeat_at, error_message, metrics_json, settings_json,
                           queued_at, started_at, finished_at
                    from ai_eval_run
                    where deleted = 0
                    order by id desc
                    limit ?
                    """,
                (rs, rowNum) -> mapRun(rs),
                safeLimit
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private AgentEvalRunVO findRun(Long runId) {
        if (runId == null) {
            return null;
        }
        List<AgentEvalRunVO> runs = jdbcTemplate.query("""
                select id, run_key, suite_name, runner_name, evaluator_name, model_name,
                       status, total_cases, passed_cases, failed_cases,
                       progress_current, progress_total, progress_message, cancel_requested,
                       cancelled_at, retry_count, max_retries, next_retry_at,
                       last_heartbeat_at, error_message, metrics_json, settings_json,
                       queued_at, started_at, finished_at
                from ai_eval_run
                where deleted = 0 and id = ?
                """,
            (rs, rowNum) -> mapRun(rs),
            runId
        );
        return runs.isEmpty() ? null : runs.get(0);
    }

    public List<AgentEvalCaseResultVO> listCaseResults(Long runId, Integer limit) {
        if (runId == null) {
            return List.of();
        }
        int safeLimit = normalizeLimit(limit);
        try {
            return jdbcTemplate.query("""
                    select id, run_id, case_key, status, intent, answer_mode,
                           retrieval_metrics, faithfulness_json, failures,
                           trace_id, duration_ms, create_time
                    from ai_eval_case_result
                    where deleted = 0 and run_id = ?
                    order by id desc
                    limit ?
                    """,
                (rs, rowNum) -> mapCaseResult(rs),
                runId,
                safeLimit
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private AgentEvalRunVO mapRun(ResultSet rs) throws SQLException {
        AgentEvalRunVO vo = new AgentEvalRunVO();
        vo.setId(rs.getLong("id"));
        vo.setRunKey(rs.getString("run_key"));
        vo.setSuiteName(rs.getString("suite_name"));
        vo.setRunnerName(rs.getString("runner_name"));
        vo.setEvaluatorName(rs.getString("evaluator_name"));
        vo.setModelName(rs.getString("model_name"));
        vo.setStatus(rs.getString("status"));
        vo.setTotalCases(rs.getInt("total_cases"));
        vo.setPassedCases(rs.getInt("passed_cases"));
        vo.setFailedCases(rs.getInt("failed_cases"));
        vo.setProgressCurrent(rs.getInt("progress_current"));
        vo.setProgressTotal(rs.getInt("progress_total"));
        vo.setProgressMessage(rs.getString("progress_message"));
        vo.setCancelRequested(rs.getBoolean("cancel_requested"));
        vo.setCancelledAt(timestampString(rs.getTimestamp("cancelled_at")));
        vo.setRetryCount(rs.getInt("retry_count"));
        vo.setMaxRetries(rs.getInt("max_retries"));
        vo.setNextRetryAt(timestampString(rs.getTimestamp("next_retry_at")));
        vo.setLastHeartbeatAt(timestampString(rs.getTimestamp("last_heartbeat_at")));
        vo.setErrorMessage(rs.getString("error_message"));
        vo.setMetricsJson(rs.getString("metrics_json"));
        vo.setSettingsJson(rs.getString("settings_json"));
        vo.setQueuedAt(timestampString(rs.getTimestamp("queued_at")));
        vo.setStartedAt(timestampString(rs.getTimestamp("started_at")));
        vo.setFinishedAt(timestampString(rs.getTimestamp("finished_at")));
        return vo;
    }

    private AgentEvalCaseResultVO mapCaseResult(ResultSet rs) throws SQLException {
        AgentEvalCaseResultVO vo = new AgentEvalCaseResultVO();
        vo.setId(rs.getLong("id"));
        vo.setRunId(rs.getLong("run_id"));
        vo.setCaseKey(rs.getString("case_key"));
        vo.setStatus(rs.getString("status"));
        vo.setIntent(rs.getString("intent"));
        vo.setAnswerMode(rs.getString("answer_mode"));
        vo.setRetrievalMetrics(rs.getString("retrieval_metrics"));
        vo.setFaithfulnessJson(rs.getString("faithfulness_json"));
        vo.setFailures(rs.getString("failures"));
        vo.setTraceId(rs.getString("trace_id"));
        vo.setDurationMs(rs.getInt("duration_ms"));
        vo.setCreatedAt(timestampString(rs.getTimestamp("create_time")));
        return vo;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.max(1, Math.min(limit, 100));
    }

    private AgentEvalRunRequest normalizeRequest(AgentEvalRunRequest request) {
        AgentEvalRunRequest normalized = request == null ? new AgentEvalRunRequest() : request;
        if (normalized.getSuiteName() == null || normalized.getSuiteName().isBlank()) {
            normalized.setSuiteName("agent-runtime");
        }
        if (normalized.getRunnerName() == null || normalized.getRunnerName().isBlank()) {
            normalized.setRunnerName("admin-trigger");
        }
        if (normalized.getEvaluatorName() == null || normalized.getEvaluatorName().isBlank()) {
            normalized.setEvaluatorName("rule-based");
        }
        Integer caseLimit = normalized.getCaseLimit();
        if (caseLimit == null) {
            normalized.setCaseLimit(100);
        } else {
            normalized.setCaseLimit(Math.max(1, Math.min(caseLimit, 500)));
        }
        return normalized;
    }

    private Long createQueuedRun(AgentEvalRunRequest request) {
        String runKey = trimToNull(request.getRunKey());
        if (runKey == null) {
            runKey = request.getSuiteName() + ":" + UUID.randomUUID();
            request.setRunKey(runKey);
        }
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases,
                    progress_current, progress_total, progress_message, cancel_requested,
                    retry_count, max_retries, metrics_json, settings_json,
                    queued_at, started_at, last_heartbeat_at, deleted)
                values(?, ?, ?, ?, ?, 'QUEUED', 0, 0, 0, 0, ?, 'queued', false,
                    0, 3, null, ?, current_timestamp, null, current_timestamp, 0)
                """,
            runKey,
            request.getSuiteName(),
            request.getRunnerName(),
            request.getEvaluatorName(),
            request.getModelName(),
            request.getCaseLimit(),
            settingsJson(request)
        );
        Long runId = jdbcTemplate.queryForObject(
            "select id from ai_eval_run where run_key = ?",
            Long.class,
            runKey
        );
        request.setRunId(runId);
        request.setCancelKey(cancelKey(runId));
        request.setProgressKey(progressKey(runId));
        jdbcTemplate.update(
            "update ai_eval_run set settings_json = ?, update_time = current_timestamp where id = ?",
            settingsJson(request),
            runId
        );
        writeRedis(request.getCancelKey(), "false", Duration.ofHours(12));
        writeRedis(request.getProgressKey(), "queued", Duration.ofHours(12));
        return runId;
    }

    private KnowledgeAgentEvalQueueService.EvalQueueItem queueItem(Long runId,
                                                                   AgentEvalRunRequest request,
                                                                   int attempt) {
        return new KnowledgeAgentEvalQueueService.EvalQueueItem(
            runId,
            request.getRunKey(),
            request.getSuiteName(),
            request.getRunnerName(),
            request.getEvaluatorName(),
            request.getModelName(),
            request.getCaseLimit(),
            cancelKey(runId),
            progressKey(runId),
            attempt,
            null
        );
    }

    private AgentEvalRunRequest requestFromRun(AgentEvalRunVO run) {
        AgentEvalRunRequest request = new AgentEvalRunRequest();
        request.setRunId(run.getId());
        request.setRunKey(run.getRunKey());
        request.setSuiteName(run.getSuiteName());
        request.setRunnerName(run.getRunnerName());
        request.setEvaluatorName(run.getEvaluatorName());
        request.setModelName(run.getModelName());
        request.setCaseLimit(run.getProgressTotal() == null || run.getProgressTotal() <= 0 ? 100 : run.getProgressTotal());
        request.setSynchronous(true);
        request.setCancelKey(cancelKey(run.getId()));
        request.setProgressKey(progressKey(run.getId()));
        return request;
    }

    private void markFailed(Long runId, String message) {
        jdbcTemplate.update("""
                update ai_eval_run
                set status = 'FAILED', error_message = ?, finished_at = current_timestamp, update_time = current_timestamp
                where id = ?
                """,
            message,
            runId
        );
    }

    private String settingsJson(AgentEvalRunRequest request) {
        return "{\"caseLimit\":" + request.getCaseLimit()
            + ",\"synchronous\":true"
            + ",\"cancelKey\":\"" + escapeJson(request.getCancelKey() == null ? cancelKey(null) : request.getCancelKey()) + "\""
            + ",\"progressKey\":\"" + escapeJson(request.getProgressKey() == null ? progressKey(null) : request.getProgressKey()) + "\""
            + "}";
    }

    private String cancelKey(Long runId) {
        return runId == null ? "ai:agent:eval:cancel:pending" : "ai:agent:eval:cancel:" + runId;
    }

    private String progressKey(Long runId) {
        return runId == null ? "ai:agent:eval:progress:pending" : "ai:agent:eval:progress:" + runId;
    }

    private boolean isCancellable(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status) || "CANCELLING".equals(status);
    }

    private boolean isRetryable(String status) {
        return "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private void writeRedis(String key, String value, Duration ttl) {
        if (stringRedisTemplate == null || key == null || value == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException ignored) {
        }
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
}
