package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeChatRunPostProcessingService
    implements KnowledgeChatRunRecoveryService.TerminalEventPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeChatService chatService;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeChatRunPostProcessingService(JdbcTemplate jdbcTemplate,
                                                 ObjectMapper objectMapper,
                                                 KnowledgeChatService chatService,
                                                 PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean process(long outboxId, String runId, String eventType) {
        String previousTraceId = TraceIdHolder.get();
        try {
            TraceIdHolder.set(runId);
            PostProcessContext context = loadContext(outboxId, runId, eventType, false);
            if (context == null || !"DISPATCHING".equals(context.outboxStatus())
                || !matchesTerminal(eventType, context.runStatus())) {
                return false;
            }
            KnowledgeChatResponseVO enrichedResponse = context.response();
            if ("ANSWERED".equals(eventType)) {
                chatService.persistCompletedRunIndexArtifacts(
                    context.userId(),
                    context.request(),
                    enrichedResponse,
                    context.completedBookId(),
                    "chat-run:" + runId + ":outbox:" + outboxId
                );
            }
            Boolean committed = transactionTemplate.execute(status -> {
                PostProcessContext row = loadContext(outboxId, runId, eventType, true);
                if (row == null || !"DISPATCHING".equals(row.outboxStatus())
                    || !matchesTerminal(eventType, row.runStatus())) {
                    return false;
                }
                if ("ANSWERED".equals(eventType)) {
                    Map<String, Object> resultJson = new LinkedHashMap<>(enrichedResponse.getResultJson());
                    resultJson.put("postProcessingStatus", "completed");
                    enrichedResponse.setResultJson(resultJson);
                    chatService.persistCompletedRunDatabaseArtifactsStrict(
                        row.userId(), row.request(), row.conversationId(), enrichedResponse
                    );
                    String enrichedResultJson = writeJson(enrichedResponse);
                    int runUpdated = jdbcTemplate.update(
                        "update ai_chat_run set result_json = ? where run_id = ? "
                            + "and status = 'ANSWERED' and deleted = 0",
                        enrichedResultJson,
                        runId
                    );
                    if (runUpdated != 1) {
                        status.setRollbackOnly();
                        return false;
                    }
                    if (row.responseMessageId() != null) {
                        int messageUpdated = jdbcTemplate.update(
                            "update ai_chat_message set content_json = ? "
                                + "where message_id = ? and run_id = ? and role = 'ASSISTANT'",
                            enrichedResultJson,
                            row.responseMessageId(),
                            runId
                        );
                        if (messageUpdated != 1) {
                            status.setRollbackOnly();
                            return false;
                        }
                    }
                }
                int updated = jdbcTemplate.update("""
                        update ai_chat_run_outbox
                        set status = 'PUBLISHED', published_at = current_timestamp,
                            last_error = null, updated_at = current_timestamp
                        where outbox_id = ? and status = 'DISPATCHING'
                        """,
                    outboxId
                );
                if (updated != 1) {
                    status.setRollbackOnly();
                    return false;
                }
                return true;
            });
            return Boolean.TRUE.equals(committed);
        } finally {
            if (previousTraceId == null) {
                TraceIdHolder.clear();
            } else {
                TraceIdHolder.set(previousTraceId);
            }
        }
    }

    private PostProcessContext loadContext(long outboxId,
                                           String runId,
                                           String eventType,
                                           boolean forUpdate) {
        String lockingClause = forUpdate ? " for update" : "";
        List<PostProcessContext> rows = jdbcTemplate.query("""
                select o.status as outbox_status, r.user_id, r.conversation_id,
                       r.request_json, r.answer, r.result_json, r.response_message_id,
                       r.status as run_status
                from ai_chat_run_outbox o
                join ai_chat_run r on r.run_id = o.run_id
                where o.outbox_id = ? and o.run_id = ? and o.event_type = ?
                """ + lockingClause,
            (rs, rowNum) -> new PostProcessContext(
                rs.getString("outbox_status"),
                rs.getLong("user_id"),
                rs.getString("conversation_id"),
                readRequest(rs.getString("request_json")),
                readResponse(rs.getString("answer"), rs.getString("result_json"), eventType),
                completedBookId(rs.getString("result_json")),
                rs.getObject("response_message_id", Long.class),
                rs.getString("run_status")
            ),
            outboxId,
            runId,
            eventType
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private KnowledgeChatRequest readRequest(String requestJson) {
        try {
            return objectMapper.readValue(requestJson, KnowledgeChatRequest.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored chat request is invalid", ex);
        }
    }

    private KnowledgeChatResponseVO readResponse(String answer, String resultJson, String eventType) {
        Map<String, Object> stored = readResult(resultJson);
        if (stored.get("resultJson") instanceof Map<?, ?>) {
            KnowledgeChatResponseVO response = objectMapper.convertValue(
                stored,
                KnowledgeChatResponseVO.class
            );
            if (response.getAnswer() == null) {
                response.setAnswer(answer);
            }
            return response;
        }
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setAnswer(answer);
        response.setStatus(stringValue(stored.remove("_runStatus"), eventType));
        Object actions = stored.remove("_actions");
        if (actions instanceof List<?> list) {
            response.setActions(list.stream().map(String::valueOf).toList());
        }
        Object sources = stored.remove("_sources");
        if (sources != null) {
            response.setSources(objectMapper.convertValue(
                sources,
                new TypeReference<List<KnowledgeChatResponseVO.SourceVO>>() { }
            ));
        }
        Object candidates = stored.remove("_candidates");
        if (candidates != null) {
            response.setCandidates(objectMapper.convertValue(
                candidates,
                new TypeReference<List<KnowledgeChatResponseVO.CandidateVO>>() { }
            ));
        }
        response.setResultJson(stored);
        return response;
    }

    private Map<String, Object> readResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(resultJson, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored chat result is invalid", ex);
        }
    }

    private String writeJson(KnowledgeChatResponseVO response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("enriched chat response is invalid", ex);
        }
    }

    private Long completedBookId(String resultJson) {
        Map<String, Object> stored = readResult(resultJson);
        Object value = stored.get("localBookId");
        if (value == null && stored.get("resultJson") instanceof Map<?, ?> nested) {
            value = nested.get("localBookId");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean matchesTerminal(String eventType, String runStatus) {
        return switch (eventType) {
            case "ANSWERED" -> "ANSWERED".equals(runStatus);
            case "FAILED" -> "FAILED".equals(runStatus);
            case "CANCELLED" -> "CANCELLED".equals(runStatus);
            default -> false;
        };
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private record PostProcessContext(String outboxStatus,
                                      Long userId,
                                      String conversationId,
                                      KnowledgeChatRequest request,
                                      KnowledgeChatResponseVO response,
                                      Long completedBookId,
                                      Long responseMessageId,
                                      String runStatus) {
    }
}
