package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunEventVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeChatRunEventService {

    private static final int DEFAULT_LIST_LIMIT = 200;
    private static final int MAX_LIST_LIMIT = 500;
    private static final Set<String> OUTBOX_EVENT_TYPES = Set.of(
        "EXECUTE", "CANCEL_REQUESTED", "ANSWERED", "FAILED", "CANCELLED"
    );
    private static final Set<String> SEMANTIC_CHECKPOINT_EVENT_TYPES = Set.of(
        "MODEL_PREPARED", "MODEL_COMMITTED", "MODEL_UNKNOWN",
        "TOOL_PREPARED", "TOOL_COMMITTED", "TOOL_UNKNOWN", "TOOL_INVALIDATED", "TOOL_PROGRESS", "HARNESS_REPAIR"
    );

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeChatRunOutboxCoordinationService outboxCoordinationService;
    private final KnowledgeAiCacheContinuityService aiCacheContinuityService;
    private final TaskExecutor aiCacheProjectionTaskExecutor;

    @Autowired
    public KnowledgeChatRunEventService(JdbcTemplate jdbcTemplate,
                                        PlatformTransactionManager transactionManager,
                                        ObjectMapper objectMapper,
                                        KnowledgeChatRunOutboxCoordinationService outboxCoordinationService,
                                        KnowledgeAiCacheContinuityService aiCacheContinuityService,
                                        @Qualifier("aiCacheProjectionTaskExecutor")
                                        TaskExecutor aiCacheProjectionTaskExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.outboxCoordinationService = outboxCoordinationService;
        this.aiCacheContinuityService = aiCacheContinuityService;
        this.aiCacheProjectionTaskExecutor = aiCacheProjectionTaskExecutor;
    }

    public KnowledgeChatRunEventService(JdbcTemplate jdbcTemplate,
                                        PlatformTransactionManager transactionManager,
                                        ObjectMapper objectMapper,
                                        KnowledgeChatRunOutboxCoordinationService outboxCoordinationService,
                                        KnowledgeAiCacheContinuityService aiCacheContinuityService) {
        this(
            jdbcTemplate,
            transactionManager,
            objectMapper,
            outboxCoordinationService,
            aiCacheContinuityService,
            Runnable::run
        );
    }

    public KnowledgeChatRunEventService(JdbcTemplate jdbcTemplate,
                                        PlatformTransactionManager transactionManager,
                                        ObjectMapper objectMapper,
                                        KnowledgeChatRunOutboxCoordinationService outboxCoordinationService) {
        this(jdbcTemplate, transactionManager, objectMapper, outboxCoordinationService, null, null);
    }

    public KnowledgeChatRunEventService(JdbcTemplate jdbcTemplate,
                                        PlatformTransactionManager transactionManager,
                                        ObjectMapper objectMapper) {
        this(jdbcTemplate, transactionManager, objectMapper, null, null, null);
    }

    public KnowledgeChatRunEventVO appendEvent(String runId,
                                               String eventType,
                                               String eventIdempotencyKey,
                                               Object payload) {
        AuthUser user = requireUser();
        String normalizedRunId = requireText(runId, "runId", 64);
        String normalizedEventType = requireText(eventType, "eventType", 20);
        String normalizedIdempotencyKey = requireText(
            eventIdempotencyKey,
            "eventIdempotencyKey",
            200
        );
        return appendOwnedTransactional(
            user.getUserId(),
            normalizedRunId,
            normalizedEventType,
            normalizedIdempotencyKey,
            writeJson(payload)
        );
    }

    public KnowledgeChatRunEventVO append(String runId,
                                          String eventType,
                                          String eventIdempotencyKey,
                                          Object payload) {
        return appendEvent(runId, eventType, eventIdempotencyKey, payload);
    }

    public List<KnowledgeChatRunEventVO> listEvents(String runId, Long afterSequence) {
        return listEvents(runId, afterSequence, DEFAULT_LIST_LIMIT);
    }

    public List<KnowledgeChatRunEventVO> listEvents(String runId,
                                                    Long afterSequence,
                                                    Integer limit) {
        AuthUser user = requireUser();
        String normalizedRunId = requireText(runId, "runId", 64);
        ensureOwnedRun(normalizedRunId, user.getUserId());
        long safeAfterSequence = afterSequence == null ? 0L : Math.max(0L, afterSequence);
        int safeLimit = limit == null
            ? DEFAULT_LIST_LIMIT
            : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return jdbcTemplate.query("""
                select event_id, run_id, sequence_no, event_type, event_idempotency_key,
                       case when event_type in (
                           'MODEL_PREPARED', 'MODEL_COMMITTED', 'MODEL_UNKNOWN',
                           'TOOL_PREPARED', 'TOOL_COMMITTED', 'TOOL_UNKNOWN', 'TOOL_INVALIDATED', 'TOOL_PROGRESS', 'HARNESS_REPAIR'
                       ) then '{"internal":true}' else payload end as payload,
                       created_at
                from ai_chat_run_event
                where run_id = ? and sequence_no > ?
                order by sequence_no asc
                limit ?
                """,
            this::mapEvent,
            normalizedRunId,
            safeAfterSequence,
            safeLimit
        );
    }

    public List<KnowledgeChatRunEventVO> listRunEvents(String runId,
                                                       Long afterSequence,
                                                       Integer limit) {
        return listEvents(runId, afterSequence, limit);
    }

    public KnowledgeChatRunEventVO appendSemanticCheckpoint(Long userId,
                                                             String runId,
                                                             String eventType,
                                                             String eventIdempotencyKey,
                                                             Object payload) {
        Long normalizedUserId = requireUserId(userId);
        String normalizedRunId = requireText(runId, "runId", 64);
        String normalizedEventType = requireText(eventType, "eventType", 20);
        if (!SEMANTIC_CHECKPOINT_EVENT_TYPES.contains(normalizedEventType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported semantic checkpoint event type");
        }
        validateHarnessCheckpoint(normalizedUserId, normalizedRunId, normalizedEventType, payload);
        KnowledgeChatRunEventVO event = appendOwnedTransactional(
            normalizedUserId,
            normalizedRunId,
            normalizedEventType,
            requireText(eventIdempotencyKey, "eventIdempotencyKey", 200),
            writeJson(payload),
            true
        );
        if ("MODEL_COMMITTED".equals(normalizedEventType)
            && aiCacheContinuityService != null
            && aiCacheContinuityService.isEnabled()
            && aiCacheProjectionTaskExecutor != null) {
            try {
                aiCacheProjectionTaskExecutor.execute(() -> aiCacheContinuityService.project(
                    normalizedUserId,
                    normalizedRunId,
                    event.getEventId(),
                    payload
                ));
            } catch (RuntimeException ignored) {
                // Shadow telemetry may be dropped under saturation; the durable checkpoint remains authoritative.
            }
        }
        return event;
    }

    public List<KnowledgeChatRunEventVO> listSemanticCheckpoints(Long userId,
                                                                 String runId,
                                                                 Long afterSequence,
                                                                 Integer limit) {
        Long normalizedUserId = requireUserId(userId);
        String normalizedRunId = requireText(runId, "runId", 64);
        ensureOwnedRun(normalizedRunId, normalizedUserId);
        long safeAfterSequence = afterSequence == null ? 0L : Math.max(0L, afterSequence);
        int safeLimit = limit == null
            ? MAX_LIST_LIMIT
            : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return jdbcTemplate.query("""
                select event_id, run_id, sequence_no, event_type, event_idempotency_key,
                       payload, created_at
                from ai_chat_run_event
                where run_id = ? and sequence_no > ?
                  and event_type in (
                      'MODEL_PREPARED', 'MODEL_COMMITTED', 'MODEL_UNKNOWN',
                      'TOOL_PREPARED', 'TOOL_COMMITTED', 'TOOL_UNKNOWN', 'TOOL_INVALIDATED', 'TOOL_PROGRESS', 'HARNESS_REPAIR'
                  )
                order by sequence_no asc
                limit ?
                """,
            this::mapEvent,
            normalizedRunId,
            safeAfterSequence,
            safeLimit
        );
    }

    private void validateHarnessCheckpoint(Long userId, String runId, String eventType, Object payload) {
        if (!Set.of("TOOL_PROGRESS", "HARNESS_REPAIR").contains(eventType)) return;
        if (!(payload instanceof Map<?, ?> values)
            || !runId.equals(String.valueOf(values.get("runId")))
            || !userId.toString().equals(String.valueOf(values.get("userId")))
            || !(values.get("semanticKey") instanceof String semanticKey)
            || semanticKey.isBlank() || semanticKey.length() > 128) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "invalid harness checkpoint identity");
        }
        if ("HARNESS_REPAIR".equals(eventType)) {
            if (!"harness-repair-slot-v1".equals(values.get("schemaVersion")) || !Boolean.TRUE.equals(values.get("used"))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "invalid harness repair checkpoint");
            }
            return;
        }
        if (!(values.get("progress") instanceof Map<?, ?> progress)
            || !"tool-progress-v1".equals(progress.get("schemaVersion"))
            || !String.valueOf(progress.get("requestKey")).matches("progress_request_[0-9a-f]{24}")
            || !String.valueOf(progress.get("attemptId")).matches("[0-9a-f]{24}")
            || !(progress.get("ordinal") instanceof Integer ordinal) || ordinal < 1 || ordinal > 3) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "invalid tool progress checkpoint");
        }
    }

    private KnowledgeChatRunEventVO appendOwnedTransactional(Long userId,
                                                              String runId,
                                                              String eventType,
                                                              String eventIdempotencyKey,
                                                              String payloadJson) {
        return appendOwnedTransactional(
            userId,
            runId,
            eventType,
            eventIdempotencyKey,
            payloadJson,
            false
        );
    }

    private KnowledgeChatRunEventVO appendOwnedTransactional(Long userId,
                                                              String runId,
                                                              String eventType,
                                                              String eventIdempotencyKey,
                                                              String payloadJson,
                                                              boolean semanticEnvelope) {
        KnowledgeChatRunEventVO event = transactionTemplate.execute(status -> appendOwnedEvent(
            userId,
            runId,
            eventType,
            eventIdempotencyKey,
            payloadJson,
            semanticEnvelope
        ));
        if (event == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chat run event append failed");
        }
        if (OUTBOX_EVENT_TYPES.contains(eventType) && outboxCoordinationService != null) {
            outboxCoordinationService.signalAfterCommit();
        }
        return event;
    }

    private KnowledgeChatRunEventVO appendOwnedEvent(Long userId,
                                                     String runId,
                                                     String eventType,
                                                     String eventIdempotencyKey,
                                                     String payloadJson,
                                                     boolean semanticEnvelope) {
        long currentSequence = lockOwnedRunSequence(runId, userId);
        KnowledgeChatRunEventVO existing = findByIdempotencyKey(runId, eventIdempotencyKey);
        if (existing != null) {
            return existing;
        }

        long sequenceNo = Math.addExact(currentSequence, 1L);
        int sequenceUpdated = jdbcTemplate.update(
            "update ai_chat_run set next_sequence_no = ? " +
                "where run_id = ? and user_id = ? and deleted = 0",
            sequenceNo,
            runId,
            userId
        );
        if (sequenceUpdated != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat run not found");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into ai_chat_run_event(
                        run_id, sequence_no, event_type, event_idempotency_key, payload, created_at
                    ) values(?, ?, ?, ?, ?, current_timestamp)
                    """, new String[]{"event_id"});
            statement.setString(1, runId);
            statement.setLong(2, sequenceNo);
            statement.setString(3, eventType);
            statement.setString(4, eventIdempotencyKey);
            statement.setString(5, payloadJson);
            return statement;
        }, keyHolder);
        long eventId = generatedId(keyHolder);

        if (semanticEnvelope) {
            String enrichedPayload = semanticEnvelopePayload(
                payloadJson,
                eventId,
                runId,
                sequenceNo,
                eventType,
                eventIdempotencyKey
            );
            if (!java.util.Objects.equals(payloadJson, enrichedPayload)) {
                jdbcTemplate.update(
                    "update ai_chat_run_event set payload = ? where event_id = ?",
                    enrichedPayload,
                    eventId
                );
            }
        }

        if (OUTBOX_EVENT_TYPES.contains(eventType)) {
            jdbcTemplate.update("""
                    insert into ai_chat_run_outbox(
                        event_id, run_id, sequence_no, event_type, event_idempotency_key, payload,
                        status, attempt_count, available_at, created_at, updated_at
                    ) values(?, ?, ?, ?, ?, ?, 'PENDING', 0, current_timestamp, current_timestamp, current_timestamp)
                    """,
                eventId,
                runId,
                sequenceNo,
                eventType,
                eventIdempotencyKey,
                payloadJson
            );
        }
        return findById(eventId);
    }

    private String semanticEnvelopePayload(String payloadJson,
                                           long eventId,
                                           String runId,
                                           long sequenceNo,
                                           String eventType,
                                           String eventIdempotencyKey) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return payloadJson;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                payloadJson,
                new TypeReference<>() {
                }
            );
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("schemaVersion", 1);
            event.put("eventId", eventId);
            event.put("runId", runId);
            event.put("sequence", sequenceNo);
            event.put("eventType", eventType);
            event.put("visibility", "internal");
            event.put("eventIdempotencyKey", eventIdempotencyKey);
            payload.put("_event", event);
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "semantic checkpoint payload must be a JSON object");
        }
    }

    private long lockOwnedRunSequence(String runId, Long userId) {
        List<Long> sequences = jdbcTemplate.query(
            "select next_sequence_no from ai_chat_run " +
                "where run_id = ? and user_id = ? and deleted = 0 for update",
            (rs, rowNum) -> rs.getLong("next_sequence_no"),
            runId,
            userId
        );
        if (sequences.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat run not found");
        }
        return sequences.get(0);
    }

    private void ensureOwnedRun(String runId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run where run_id = ? and user_id = ? and deleted = 0",
            Integer.class,
            runId,
            userId
        );
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat run not found");
        }
    }

    private KnowledgeChatRunEventVO findByIdempotencyKey(String runId, String eventIdempotencyKey) {
        List<KnowledgeChatRunEventVO> events = jdbcTemplate.query("""
                select event_id, run_id, sequence_no, event_type, event_idempotency_key,
                       payload, created_at
                from ai_chat_run_event
                where run_id = ? and event_idempotency_key = ?
                """,
            this::mapEvent,
            runId,
            eventIdempotencyKey
        );
        return events.isEmpty() ? null : events.get(0);
    }

    private KnowledgeChatRunEventVO findById(long eventId) {
        return jdbcTemplate.queryForObject("""
                select event_id, run_id, sequence_no, event_type, event_idempotency_key,
                       payload, created_at
                from ai_chat_run_event
                where event_id = ?
                """,
            this::mapEvent,
            eventId
        );
    }

    private KnowledgeChatRunEventVO mapEvent(ResultSet resultSet, int rowNum) throws SQLException {
        KnowledgeChatRunEventVO event = new KnowledgeChatRunEventVO();
        event.setEventId(resultSet.getLong("event_id"));
        event.setRunId(resultSet.getString("run_id"));
        event.setSequenceNo(resultSet.getLong("sequence_no"));
        event.setEventType(resultSet.getString("event_type"));
        event.setEventIdempotencyKey(resultSet.getString("event_idempotency_key"));
        event.setPayload(resultSet.getString("payload"));
        if (resultSet.getTimestamp("created_at") != null) {
            event.setCreatedAt(resultSet.getTimestamp("created_at").toLocalDateTime());
        }
        return event;
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "userId is required");
        }
        return userId;
    }

    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, fieldName + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ResultCode.BAD_REQUEST, fieldName + " is too long");
        }
        return normalized;
    }

    private String writeJson(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "event payload is not serializable");
        }
    }

    private long generatedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chat run event id was not generated");
        }
        return key.longValue();
    }
}
