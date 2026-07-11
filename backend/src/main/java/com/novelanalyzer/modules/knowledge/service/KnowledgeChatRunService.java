package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeChatRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunService.class);
    private static final int MAX_LIST_LIMIT = 50;

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeChatService knowledgeChatService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;

    public KnowledgeChatRunService(JdbcTemplate jdbcTemplate,
                                   KnowledgeChatService knowledgeChatService,
                                   ObjectMapper objectMapper,
                                   @Qualifier("analysisStreamTaskExecutor") TaskExecutor executor) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeChatService = knowledgeChatService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public KnowledgeChatRunVO startRun(KnowledgeChatRequest request) {
        AuthUser user = requireUser();
        KnowledgeChatRequest normalized = normalizeRequest(request);
        String runId = "chatrun-" + UUID.randomUUID();
        String requestJson = writeJson(normalized);
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, request_json,
                    status, progress_phase, progress_message, cancel_requested, retry_count, max_retries, queued_at, deleted)
                values(?, ?, ?, ?, ?, ?, 'PENDING', 'queue', '已创建后台回答任务', false, 0, 3, current_timestamp, 0)
                """,
            runId,
            user.getUserId(),
            normalized.getProjectId(),
            normalized.getConversationId(),
            normalized.getQuestion(),
            requestJson
        );
        executor.execute(() -> executeRun(runId, user, normalized));
        return getRun(runId);
    }

    public KnowledgeChatRunVO getRun(String runId) {
        AuthUser user = requireUser();
        List<KnowledgeChatRunVO> runs = jdbcTemplate.query("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run
                where run_id = ? and user_id = ? and deleted = 0
                """,
            (rs, rowNum) -> mapRun(rs),
            trimToNull(runId),
            user.getUserId()
        );
        if (runs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat run not found");
        }
        return runs.get(0);
    }

    public List<KnowledgeChatRunVO> listConversationRuns(String conversationId, Integer limit) {
        AuthUser user = requireUser();
        String normalizedConversationId = trimToNull(conversationId);
        if (normalizedConversationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "conversationId is required");
        }
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return jdbcTemplate.query("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run
                where conversation_id = ? and user_id = ? and deleted = 0
                order by queued_at desc, run_id desc
                limit ?
                """,
            (rs, rowNum) -> mapRun(rs),
            normalizedConversationId,
            user.getUserId(),
            safeLimit
        );
    }

    public List<KnowledgeChatRunVO> listRecentRuns(Long projectId, Integer limit) {
        AuthUser user = requireUser();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        if (projectId == null) {
            return jdbcTemplate.query("""
                    select run_id, user_id, project_id, conversation_id, question, status,
                           progress_phase, progress_message, answer, result_json, trace_id, source_count,
                           error_message, cancel_requested, retry_count, max_retries,
                           queued_at, started_at, finished_at, update_time
                    from ai_chat_run r
                    where user_id = ? and project_id is null and deleted = 0
                      and not exists (
                          select 1 from ai_chat_run newer
                          where newer.user_id = r.user_id
                            and newer.conversation_id = r.conversation_id
                            and newer.project_id is null
                            and newer.deleted = 0
                            and (newer.queued_at > r.queued_at or (newer.queued_at = r.queued_at and newer.run_id > r.run_id))
                      )
                    order by update_time desc, queued_at desc, run_id desc
                    limit ?
                    """,
                (rs, rowNum) -> mapRun(rs),
                user.getUserId(),
                safeLimit
            );
        }
        return jdbcTemplate.query("""
                select run_id, user_id, project_id, conversation_id, question, status,
                       progress_phase, progress_message, answer, result_json, trace_id, source_count,
                       error_message, cancel_requested, retry_count, max_retries,
                       queued_at, started_at, finished_at, update_time
                from ai_chat_run r
                where user_id = ? and project_id = ? and deleted = 0
                  and not exists (
                      select 1 from ai_chat_run newer
                      where newer.user_id = r.user_id
                        and newer.conversation_id = r.conversation_id
                        and newer.project_id = r.project_id
                        and newer.deleted = 0
                        and (newer.queued_at > r.queued_at or (newer.queued_at = r.queued_at and newer.run_id > r.run_id))
                  )
                order by update_time desc, queued_at desc, run_id desc
                limit ?
                """,
            (rs, rowNum) -> mapRun(rs),
            user.getUserId(),
            projectId,
            safeLimit
        );
    }

    public KnowledgeChatRunVO cancelRun(String runId) {
        AuthUser user = requireUser();
        KnowledgeChatRunVO current = getRun(runId);
        if (!isTerminal(current.getStatus())) {
            jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'CANCELLED',
                        cancel_requested = true,
                        progress_phase = 'cancelled',
                        progress_message = '已请求取消后台回答',
                        finished_at = current_timestamp,
                        update_time = current_timestamp
                    where run_id = ? and user_id = ? and deleted = 0
                    """,
                runId,
                user.getUserId()
            );
        }
        return getRun(runId);
    }

    private void executeRun(String runId, AuthUser user, KnowledgeChatRequest request) {
        AuthUser previousUser = AuthUserHolder.get();
        String previousTraceId = TraceIdHolder.get();
        try {
            AuthUserHolder.set(user);
            TraceIdHolder.set(runId);
            if (isCancelRequested(runId)) {
                markCancelled(runId);
                return;
            }
            jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'RUNNING',
                        progress_phase = 'answer',
                        progress_message = '正在执行后台 Agent 回答',
                        started_at = current_timestamp,
                        update_time = current_timestamp
                    where run_id = ? and deleted = 0
                    """,
                runId
            );
            StringBuilder partialAnswer = new StringBuilder();
            KnowledgeChatResponseVO response = knowledgeChatService.chatWithProgress(
                request,
                new KnowledgeChatService.ChatProgressListener() {
                    @Override
                    public void onProgress(String phase, String message) {
                        updateProgress(runId, phase, message);
                    }

                    @Override
                    public void onDelta(String delta) {
                        if (delta == null || delta.isBlank()) {
                            return;
                        }
                        partialAnswer.append(delta);
                        updatePartialAnswer(runId, partialAnswer.toString());
                    }
                },
                () -> isCancelRequested(runId)
            );
            if (isCancelRequested(runId)) {
                markCancelled(runId);
                return;
            }
            if (response == null) {
                markCancelled(runId);
                return;
            }
            String resultJson = writeJson(response.getResultJson());
            jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'ANSWERED',
                        progress_phase = 'done',
                        progress_message = '后台回答已完成',
                        answer = ?,
                        result_json = ?,
                        trace_id = ?,
                        source_count = ?,
                        error_message = null,
                        finished_at = current_timestamp,
                        update_time = current_timestamp
                    where run_id = ? and deleted = 0 and cancel_requested = false
                    """,
                response.getAnswer(),
                resultJson,
                resolveTraceId(response),
                response.getSources() == null ? 0 : response.getSources().size(),
                runId
            );
        } catch (Exception ex) {
            LOGGER.warn("knowledge chat run failed: runId={}, reason={}", runId, ex.getMessage());
            jdbcTemplate.update("""
                    update ai_chat_run
                    set status = 'FAILED',
                        progress_phase = 'failed',
                        progress_message = '后台回答失败',
                        error_message = ?,
                        finished_at = current_timestamp,
                        update_time = current_timestamp
                    where run_id = ? and deleted = 0 and cancel_requested = false
                    """,
                truncate(ex.getMessage(), 1000),
                runId
            );
        } finally {
            if (previousUser == null) {
                AuthUserHolder.clear();
            } else {
                AuthUserHolder.set(previousUser);
            }
            if (previousTraceId == null || previousTraceId.isBlank()) {
                TraceIdHolder.clear();
            } else {
                TraceIdHolder.set(previousTraceId);
            }
        }
    }

    private void updateProgress(String runId, String phase, String message) {
        if (trimToNull(runId) == null) {
            return;
        }
        jdbcTemplate.update("""
                update ai_chat_run
                set progress_phase = ?,
                    progress_message = ?,
                    update_time = current_timestamp
                where run_id = ? and deleted = 0 and status = 'RUNNING' and cancel_requested = false
                """,
            truncate(trimToNull(phase), 40),
            truncate(trimToNull(message), 500),
            runId
        );
    }

    private void updatePartialAnswer(String runId, String partialAnswer) {
        if (trimToNull(runId) == null || trimToNull(partialAnswer) == null) {
            return;
        }
        jdbcTemplate.update("""
                update ai_chat_run
                set answer = ?,
                    update_time = current_timestamp
                where run_id = ? and deleted = 0 and status = 'RUNNING' and cancel_requested = false
                """,
            partialAnswer,
            runId
        );
    }

    private KnowledgeChatRequest normalizeRequest(KnowledgeChatRequest request) {
        if (request == null || trimToNull(request.getQuestion()) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "question is required");
        }
        request.setQuestion(request.getQuestion().trim());
        if (trimToNull(request.getConversationId()) == null) {
            request.setConversationId("conv-" + UUID.randomUUID());
        } else {
            request.setConversationId(request.getConversationId().trim());
        }
        return request;
    }

    private boolean isCancelRequested(String runId) {
        Boolean cancelled = jdbcTemplate.queryForObject(
            "select cancel_requested from ai_chat_run where run_id = ? and deleted = 0",
            Boolean.class,
            runId
        );
        return Boolean.TRUE.equals(cancelled);
    }

    private void markCancelled(String runId) {
        jdbcTemplate.update("""
                update ai_chat_run
                set status = 'CANCELLED',
                    progress_phase = 'cancelled',
                    progress_message = '后台回答已取消',
                    finished_at = current_timestamp,
                    update_time = current_timestamp
                where run_id = ? and deleted = 0
                """,
            runId
        );
    }

    private KnowledgeChatRunVO mapRun(ResultSet rs) throws SQLException {
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
        vo.setQueuedAt(timestampString(rs.getTimestamp("queued_at")));
        vo.setStartedAt(timestampString(rs.getTimestamp("started_at")));
        vo.setFinishedAt(timestampString(rs.getTimestamp("finished_at")));
        vo.setUpdatedAt(timestampString(rs.getTimestamp("update_time")));
        return vo;
    }

    private String resolveTraceId(KnowledgeChatResponseVO response) {
        if (response == null || response.getResultJson() == null) {
            return null;
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
            return trimToNull(nestedSnake == null ? null : String.valueOf(nestedSnake));
        }
        return null;
    }

    private boolean isTerminal(String status) {
        return "ANSWERED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private String writeJson(Object value) {
        try {
            Object safeValue = value == null ? Map.of() : value;
            return objectMapper.writeValueAsString(safeValue);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chat run json serialization failed");
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String timestampString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }
}
