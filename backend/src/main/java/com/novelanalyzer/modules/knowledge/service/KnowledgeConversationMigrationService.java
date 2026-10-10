package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeConversationMigrationService {

    private static final String STATE_KEY = "phase18-conversation-backfill";
    private static final String EMPTY_LEGACY_ID = "__EMPTY__";
    private static final int MAX_BATCH_SIZE = 1000;
    private static final String OPERATION_LOCK = "phase18-conversation-migration";
    private static final Duration OPERATION_LEASE = Duration.ofHours(2);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean backfillRunning = new AtomicBoolean();
    private final AtomicBoolean verificationRunning = new AtomicBoolean();

    public KnowledgeConversationMigrationService(JdbcTemplate jdbcTemplate,
                                                 PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public BackfillResult backfill(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "batchSize must be between 1 and 1000");
        }
        if (!backfillRunning.compareAndSet(false, true)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "conversation backfill is already running");
        }
        String lockOwner = null;
        try {
            lockOwner = acquireOperationLock();
            return transactionTemplate.execute(status -> backfillBatch(batchSize));
        } finally {
            releaseOperationLock(lockOwner);
            backfillRunning.set(false);
        }
    }

    public VerificationResult verifyBackfill() {
        if (!verificationRunning.compareAndSet(false, true)) {
            throw new BusinessException(
                ResultCode.TOO_MANY_REQUESTS,
                "conversation migration verification is already running"
            );
        }
        String lockOwner = null;
        try {
            lockOwner = acquireOperationLock();
            return verifyBackfillInternal();
        } finally {
            releaseOperationLock(lockOwner);
            verificationRunning.set(false);
        }
    }

    private String acquireOperationLock() {
        jdbcTemplate.update(
            "insert into ai_conversation_migration_lock(lock_name) values(?) " +
                "on duplicate key update lock_name = values(lock_name)",
            OPERATION_LOCK
        );
        String owner = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp leaseUntil = Timestamp.from(Instant.now().plus(OPERATION_LEASE));
        int updated = jdbcTemplate.update(
            "update ai_conversation_migration_lock set lock_owner = ?, lease_until = ?, " +
                "updated_at = current_timestamp where lock_name = ? and " +
                "(lock_owner is null or lease_until is null or lease_until < ?)",
            owner,
            leaseUntil,
            OPERATION_LOCK,
            now
        );
        if (updated != 1) {
            throw new BusinessException(
                ResultCode.TOO_MANY_REQUESTS,
                "conversation migration operation is already running"
            );
        }
        return owner;
    }

    private void releaseOperationLock(String owner) {
        if (owner == null) {
            return;
        }
        jdbcTemplate.update(
            "update ai_conversation_migration_lock set lock_owner = null, lease_until = null, " +
                "updated_at = current_timestamp where lock_name = ? and lock_owner = ?",
            OPERATION_LOCK,
            owner
        );
    }

    private VerificationResult verifyBackfillInternal() {
        Long ownershipMismatch = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_chat_message m
                left join ai_conversation c on c.conversation_id = m.conversation_id
                where c.conversation_id is null
                   or c.user_id <> m.user_id
                   or coalesce(c.project_id, -1) <> coalesce(m.project_id, -1)
                """, Long.class);
        Long missingUser = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_chat_run r
                where r.deleted = 0
                  and not exists (
                      select 1 from ai_chat_message m
                      where m.run_id = r.run_id and m.role = 'USER' and m.deleted = 0
                  )
                """, Long.class);
        long expectedAssistant = countExpectedAssistantMessages();
        Long missingAssistant = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_chat_run r
                where r.deleted = 0 and r.answer is not null and trim(r.answer) <> '' and (
                    r.status = 'ANSWERED' or (
                        r.status in ('FAILED', 'CANCELLED')
                        and r.progress_phase in ('answer', 'compose', 'done')
                        and r.result_json is not null
                        and replace(trim(r.result_json), ' ', '') not in ('', '{}', 'null')
                    )
                ) and not exists (
                    select 1 from ai_chat_message m
                    where m.run_id = r.run_id and m.role = 'ASSISTANT' and m.deleted = 0
                )
                """, Long.class);
        Long actualUser = jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where role = 'USER' and deleted = 0",
            Long.class
        );
        Long actualAssistant = jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where role = 'ASSISTANT' and deleted = 0",
            Long.class
        );
        Long legacyRuns = jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run where deleted = 0",
            Long.class
        );
        Long conversations = jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation where status <> 'ARCHIVED'",
            Long.class
        );
        Long mappedConversations = jdbcTemplate.queryForObject(
            "select count(distinct canonical_conversation_id) from ai_conversation_legacy_map",
            Long.class
        );
        Long latestMessageMismatch = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_conversation c
                where c.last_message_id is null and exists (
                    select 1 from ai_chat_message present
                    where present.conversation_id = c.conversation_id and present.deleted = 0
                ) or c.last_message_id is not null and c.last_message_id <> coalesce((
                    select latest.message_id from ai_chat_message latest
                    where latest.conversation_id = c.conversation_id and latest.deleted = 0
                    order by latest.created_at desc, latest.message_id desc limit 1
                ), -1)
                """, Long.class);
        Long runScopeMismatch = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_chat_run r
                join ai_conversation c on c.conversation_id = r.conversation_id
                where r.deleted = 0
                  and (r.user_id <> c.user_id or coalesce(r.project_id, -1) <> coalesce(c.project_id, -1))
                """, Long.class);
        Long expectedConversations = jdbcTemplate.queryForObject("""
                select count(1) from (
                    select r.user_id, coalesce(r.project_id, -1) as project_scope_id,
                        case when trim(coalesce(r.legacy_conversation_id, r.conversation_id, '')) = ''
                            then concat('__EMPTY__:', r.run_id)
                            else coalesce(r.legacy_conversation_id, r.conversation_id) end as legacy_key
                    from ai_chat_run r
                    where r.deleted = 0 and (
                        r.legacy_conversation_id is not null
                        or r.trigger_message_id is null
                        or r.request_id like 'legacy-%'
                    )
                    group by r.user_id, coalesce(r.project_id, -1),
                        case when trim(coalesce(r.legacy_conversation_id, r.conversation_id, '')) = ''
                            then concat('__EMPTY__:', r.run_id)
                            else coalesce(r.legacy_conversation_id, r.conversation_id) end
                ) expected
                """, Long.class);
        Long mappingMismatch = jdbcTemplate.queryForObject("""
                select count(1) from (
                    select expected.user_id, expected.project_scope_id, expected.legacy_key,
                        count(m.map_id) as mapping_count
                    from (
                        select r.user_id, coalesce(r.project_id, -1) as project_scope_id,
                            case when trim(coalesce(r.legacy_conversation_id, r.conversation_id, '')) = ''
                                then concat('__EMPTY__:', r.run_id)
                                else coalesce(r.legacy_conversation_id, r.conversation_id) end as legacy_key
                        from ai_chat_run r
                        where r.deleted = 0 and (
                            r.legacy_conversation_id is not null
                            or r.trigger_message_id is null
                            or r.request_id like 'legacy-%'
                        )
                        group by r.user_id, coalesce(r.project_id, -1),
                            case when trim(coalesce(r.legacy_conversation_id, r.conversation_id, '')) = ''
                                then concat('__EMPTY__:', r.run_id)
                                else coalesce(r.legacy_conversation_id, r.conversation_id) end
                    ) expected
                    left join ai_conversation_legacy_map m on m.user_id = expected.user_id
                        and m.project_scope_id = expected.project_scope_id
                        and m.legacy_conversation_id = expected.legacy_key
                    group by expected.user_id, expected.project_scope_id, expected.legacy_key
                    having count(m.map_id) <> 1
                ) mismatched
                """, Long.class);
        Long unexpectedMappings = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_conversation_legacy_map m
                where not exists (
                    select 1 from ai_chat_run r
                    where r.deleted = 0 and r.user_id = m.user_id
                      and (r.legacy_conversation_id is not null
                           or r.trigger_message_id is null
                           or r.request_id like 'legacy-%')
                      and coalesce(r.project_id, -1) = m.project_scope_id
                      and m.legacy_conversation_id = case
                          when trim(coalesce(r.legacy_conversation_id, r.conversation_id, '')) = ''
                              then concat('__EMPTY__:', r.run_id)
                          else coalesce(r.legacy_conversation_id, r.conversation_id) end
                )
                """, Long.class);
        Long mappingScopeMismatch = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_conversation_legacy_map m
                join ai_conversation c on c.conversation_id = m.canonical_conversation_id
                where m.user_id <> c.user_id
                   or coalesce(m.project_id, -1) <> coalesce(c.project_id, -1)
                """, Long.class);
        Long unexpectedUser = jdbcTemplate.queryForObject("""
                select count(1) from ai_chat_message m
                where m.role = 'USER' and m.deleted = 0 and not exists (
                    select 1 from ai_chat_run r where r.run_id = m.run_id and r.deleted = 0
                )
                """, Long.class);
        Long unexpectedAssistant = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_chat_message m
                join ai_chat_run r on r.run_id = m.run_id and r.deleted = 0
                where m.role = 'ASSISTANT' and m.deleted = 0 and not (
                    r.answer is not null and trim(r.answer) <> '' and (
                        r.status = 'ANSWERED' or (
                            r.status in ('FAILED', 'CANCELLED')
                            and r.progress_phase in ('answer', 'compose', 'done')
                            and r.result_json is not null
                            and replace(trim(r.result_json), ' ', '') not in ('', '{}', 'null')
                        )
                    )
                )
                """, Long.class);
        long hashMismatch = countContentHashMismatches();
        return new VerificationResult(
            valueOrZero(ownershipMismatch),
            valueOrZero(missingUser),
            valueOrZero(missingAssistant),
            hashMismatch,
            valueOrZero(legacyRuns),
            valueOrZero(conversations),
            valueOrZero(mappedConversations),
            valueOrZero(actualUser),
            expectedAssistant,
            valueOrZero(actualAssistant),
            valueOrZero(latestMessageMismatch),
            valueOrZero(runScopeMismatch),
            valueOrZero(expectedConversations),
            Math.abs(valueOrZero(expectedConversations) - valueOrZero(mappedConversations)),
            valueOrZero(mappingMismatch) + valueOrZero(unexpectedMappings),
            valueOrZero(mappingScopeMismatch),
            valueOrZero(unexpectedUser),
            valueOrZero(unexpectedAssistant)
        );
    }

    private BackfillResult backfillBatch(int batchSize) {
        ensureStateRow();
        MigrationState state = lockState();
        int mainBudget = state.lastQueuedAt() == null ? batchSize : Math.max(1, batchSize * 3 / 4);
        List<LegacyRun> runs = loadBatch(state, mainBudget);
        for (LegacyRun run : runs) {
            if (needsRepair(run)) {
                migrateRun(run);
            }
        }
        Timestamp effectiveWatermark = state.lastQueuedAt();
        String effectiveRunId = state.lastRunId();
        if (!runs.isEmpty()) {
            LegacyRun last = runs.get(runs.size() - 1);
            effectiveWatermark = last.orderTime();
            effectiveRunId = last.runId();
            jdbcTemplate.update(
                "update ai_conversation_migration_state set last_queued_at = ?, last_run_id = ?, " +
                    "processed_run_count = processed_run_count + ?, updated_at = current_timestamp " +
                    "where state_key = ?",
                last.orderTime(),
                last.runId(),
                runs.size(),
                STATE_KEY
            );
        }
        int repairBudget = state.lastQueuedAt() == null || effectiveWatermark == null
            ? 0
            : Math.max(1, batchSize - mainBudget);
        List<LegacyRun> repairScan = repairBudget <= 0 || effectiveWatermark == null
            ? List.of()
            : loadRepairBatch(effectiveWatermark, effectiveRunId, repairBudget);
        for (LegacyRun repair : repairScan) {
            migrateRun(repair);
        }
        return new BackfillResult(
            runs.size() + repairScan.size(),
            effectiveWatermark,
            effectiveRunId
        );
    }

    private void migrateRun(LegacyRun run) {
        String canonicalId = findConversationIdForRun(run.runId());
        if (canonicalId == null) {
            canonicalId = resolveCanonicalConversationId(run);
        }
        Long triggerMessageId = findMessageId(run.runId(), "USER");
        if (triggerMessageId == null) {
            triggerMessageId = insertMessage(
                canonicalId,
                run,
                "USER",
                run.question(),
                null
            );
        }
        Long responseMessageId = findMessageId(run.runId(), "ASSISTANT");
        if (responseMessageId == null && isUsableAnswer(run)) {
            String contentJson = "ANSWERED".equals(run.status())
                ? run.resultJson()
                : "{\"migrationStatus\":\"PARTIAL\",\"legacyStatus\":\"" + safeJson(run.status()) + "\"}";
            responseMessageId = insertMessage(
                canonicalId,
                run,
                "ASSISTANT",
                run.answer(),
                contentJson
            );
        }
        Long lastMessageId = responseMessageId == null ? triggerMessageId : responseMessageId;
        jdbcTemplate.update(
            "update ai_conversation set " +
                "last_message_id = case when updated_at is null or updated_at < ? " +
                    "or (updated_at = ? and coalesce(last_run_id, '') <= ?) then ? else last_message_id end, " +
                "last_run_id = case when updated_at is null or updated_at < ? " +
                    "or (updated_at = ? and coalesce(last_run_id, '') <= ?) then ? else last_run_id end, " +
                "updated_at = case when updated_at is null or updated_at < ? then ? else updated_at end " +
                "where conversation_id = ? and user_id = ?",
            run.orderTime(),
            run.orderTime(),
            run.runId(),
            lastMessageId,
            run.orderTime(),
            run.orderTime(),
            run.runId(),
            run.runId(),
            run.orderTime(),
            run.orderTime(),
            canonicalId,
            run.userId()
        );
        jdbcTemplate.update(
            "update ai_chat_run set legacy_conversation_id = coalesce(legacy_conversation_id, conversation_id), " +
                "conversation_id = ?, request_id = coalesce(request_id, ?), " +
                "idempotency_key = coalesce(idempotency_key, ?), trigger_message_id = ?, response_message_id = ?, " +
                "update_time = update_time " +
                "where run_id = ? and user_id = ? and deleted = 0",
            canonicalId,
            "legacy-" + run.runId(),
            "legacy-" + run.runId(),
            triggerMessageId,
            responseMessageId,
            run.runId(),
            run.userId()
        );
    }

    private String resolveCanonicalConversationId(LegacyRun run) {
        String legacyId = trimToNull(run.conversationId());
        String mappingKey = legacyId == null ? EMPTY_LEGACY_ID + ":" + run.runId() : legacyId;
        String mapped = findMappedConversation(run.userId(), run.projectId(), mappingKey);
        if (mapped != null) {
            ensureConversation(mapped, run);
            return mapped;
        }
        String candidate = legacyId;
        if (candidate == null || isConversationOwnedByAnotherScope(candidate, run.userId(), run.projectId())) {
            candidate = migratedConversationId(run.userId(), run.projectId(), mappingKey);
        }
        ensureConversation(candidate, run);
        insertMapping(run.userId(), run.projectId(), mappingKey, candidate);
        return candidate;
    }

    private void ensureConversation(String conversationId, LegacyRun run) {
        List<Long> owners = jdbcTemplate.query(
            "select user_id from ai_conversation where conversation_id = ?",
            (rs, rowNum) -> rs.getLong(1),
            conversationId
        );
        if (!owners.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
            "insert into ai_conversation(conversation_id, user_id, project_id, title, status, created_at, updated_at) " +
                "values(?, ?, ?, ?, 'ACTIVE', ?, ?)",
            conversationId,
            run.userId(),
            run.projectId(),
            title(run.question()),
            run.orderTime(),
            run.orderTime()
        );
    }

    private boolean isConversationOwnedByAnotherScope(String conversationId, Long userId, Long projectId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation where conversation_id = ? " +
                "and (user_id <> ? or coalesce(project_id, -1) <> coalesce(?, -1))",
            Integer.class,
            conversationId,
            userId,
            projectId
        );
        return count != null && count > 0;
    }

    private String findMappedConversation(Long userId, Long projectId, String legacyId) {
        List<String> ids = jdbcTemplate.query(
            "select canonical_conversation_id from ai_conversation_legacy_map " +
                "where user_id = ? and project_scope_id = coalesce(?, -1) and legacy_conversation_id = ?",
            (rs, rowNum) -> rs.getString(1),
            userId,
            projectId,
            legacyId
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void insertMapping(Long userId, Long projectId, String legacyId, String canonicalId) {
        jdbcTemplate.update(
            "insert into ai_conversation_legacy_map(" +
                "user_id, project_id, legacy_conversation_id, canonical_conversation_id" +
                ") values(?, ?, ?, ?)",
            userId,
            projectId,
            legacyId,
            canonicalId
        );
    }

    private Long insertMessage(String conversationId,
                               LegacyRun run,
                               String role,
                               String content,
                               String contentJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_chat_message(" +
                    "conversation_id, user_id, project_id, run_id, role, content, content_json, created_at" +
                    ") values(?, ?, ?, ?, ?, ?, ?, ?)",
                new String[]{"message_id"}
            );
            statement.setString(1, conversationId);
            statement.setLong(2, run.userId());
            if (run.projectId() == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, run.projectId());
            }
            statement.setString(4, run.runId());
            statement.setString(5, role);
            statement.setString(6, content);
            statement.setString(7, trimToNull(contentJson));
            statement.setTimestamp(8, run.orderTime());
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private Long findMessageId(String runId, String role) {
        List<Long> ids = jdbcTemplate.query(
            "select message_id from ai_chat_message where run_id = ? and role = ? and deleted = 0",
            (rs, rowNum) -> rs.getLong(1),
            runId,
            role
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findConversationIdForRun(String runId) {
        List<String> ids = jdbcTemplate.query(
            "select conversation_id from ai_chat_message where run_id = ? and deleted = 0 " +
                "order by message_id limit 1",
            (rs, rowNum) -> rs.getString(1),
            runId
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<LegacyRun> loadBatch(MigrationState state, int batchSize) {
        String orderExpression = "migration_order_at";
        if (state.lastQueuedAt() == null) {
            return jdbcTemplate.query(
                runSelect() + " where deleted = 0 order by " + orderExpression + ", run_id limit ?",
                this::mapRun,
                batchSize
            );
        }
        return jdbcTemplate.query(
            runSelect() + " where deleted = 0 and (" + orderExpression + " > ? or (" +
                orderExpression + " = ? and run_id > ?)) order by " + orderExpression + ", run_id limit ?",
            this::mapRun,
            state.lastQueuedAt(),
            state.lastQueuedAt(),
            state.lastRunId(),
            batchSize
        );
    }

    private List<LegacyRun> loadRepairBatch(Timestamp watermark, String watermarkRunId, int batchSize) {
        List<LegacyRun> repairs = new java.util.ArrayList<>(jdbcTemplate.query(
            runSelect() + " where deleted = 0 and (migration_order_at < ? or " +
                "(migration_order_at = ? and run_id <= ?)) and trigger_message_id is null " +
                "order by migration_order_at, run_id limit ?",
            this::mapRun,
            watermark,
            watermark,
            watermarkRunId,
            batchSize
        ));
        int remaining = batchSize - repairs.size();
        if (remaining <= 0) {
            return repairs;
        }
        repairs.addAll(jdbcTemplate.query(
            runSelect() + " where deleted = 0 and (migration_order_at < ? or " +
                "(migration_order_at = ? and run_id <= ?)) and trigger_message_id is not null " +
                "and response_message_id is null and answer is not null and trim(answer) <> '' and (" +
                "status = 'ANSWERED' or (status in ('FAILED', 'CANCELLED') " +
                "and progress_phase in ('answer', 'compose', 'done') and result_json is not null " +
                "and replace(trim(result_json), ' ', '') not in ('', '{}', 'null'))) " +
                "order by migration_activity_at, run_id limit ?",
            this::mapRun,
            watermark,
            watermark,
            watermarkRunId,
            remaining
        ));
        return repairs;
    }

    private String runSelect() {
        return "select run_id, user_id, project_id, coalesce(legacy_conversation_id, conversation_id) " +
            "as conversation_id, question, answer, result_json, status, " +
            "progress_phase, trigger_message_id, response_message_id, migration_order_at as order_time, " +
            "migration_activity_at as activity_time from ai_chat_run";
    }

    private LegacyRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        long userId = rs.getLong("user_id");
        long projectId = rs.getLong("project_id");
        Long nullableProjectId = rs.wasNull() ? null : projectId;
        return new LegacyRun(
            rs.getString("run_id"),
            userId,
            nullableProjectId,
            rs.getString("conversation_id"),
            rs.getString("question"),
            rs.getString("answer"),
            rs.getString("result_json"),
            rs.getString("status"),
            rs.getString("progress_phase"),
            nullableLong(rs, "trigger_message_id"),
            nullableLong(rs, "response_message_id"),
            rs.getTimestamp("order_time"),
            rs.getTimestamp("activity_time")
        );
    }

    private void ensureStateRow() {
        jdbcTemplate.update(
            "insert into ai_conversation_migration_state(state_key, processed_run_count) values(?, 0) " +
                "on duplicate key update state_key = values(state_key)",
            STATE_KEY
        );
    }

    private MigrationState lockState() {
        return jdbcTemplate.queryForObject(
            "select last_queued_at, last_run_id from ai_conversation_migration_state " +
                "where state_key = ? for update",
            (rs, rowNum) -> new MigrationState(rs.getTimestamp(1), rs.getString(2)),
            STATE_KEY
        );
    }

    private long countContentHashMismatches() {
        long mismatches = 0L;
        String afterRunId = "";
        long afterMessageId = 0L;
        while (true) {
            List<HashPair> pairs = jdbcTemplate.query("""
                    select r.run_id, r.question, r.answer, m.message_id, m.role, m.content
                    from ai_chat_run r
                    join ai_chat_message m on m.run_id = r.run_id and m.deleted = 0
                    where r.deleted = 0 and m.role in ('USER', 'ASSISTANT')
                      and (r.run_id > ? or (r.run_id = ? and m.message_id > ?))
                    order by r.run_id, m.message_id
                    limit 500
                    """, (rs, rowNum) -> new HashPair(
                rs.getString("run_id"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getLong("message_id"),
                rs.getString("role"),
                rs.getString("content")
            ), afterRunId, afterRunId, afterMessageId);
            if (pairs.isEmpty()) {
                return mismatches;
            }
            mismatches += pairs.stream().filter(pair -> {
                String expected = "USER".equals(pair.role()) ? pair.question() : pair.answer();
                return !Objects.equals(hash(expected), hash(pair.content()));
            }).count();
            HashPair last = pairs.get(pairs.size() - 1);
            afterRunId = last.runId();
            afterMessageId = last.messageId();
            if (pairs.size() < 500) {
                return mismatches;
            }
        }
    }

    private long countExpectedAssistantMessages() {
        Long count = jdbcTemplate.queryForObject("""
                select count(1)
                from ai_chat_run
                where deleted = 0 and answer is not null and trim(answer) <> '' and (
                    status = 'ANSWERED' or (
                        status in ('FAILED', 'CANCELLED')
                        and progress_phase in ('answer', 'compose', 'done')
                        and result_json is not null
                        and replace(trim(result_json), ' ', '') not in ('', '{}', 'null')
                    )
                )
                """, Long.class);
        return valueOrZero(count);
    }

    private boolean isUsableAnswer(LegacyRun run) {
        if (trimToNull(run.answer()) == null) {
            return false;
        }
        if ("ANSWERED".equals(run.status())) {
            return true;
        }
        String normalizedResult = trimToNull(run.resultJson());
        return ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status()))
            && ("answer".equals(run.progressPhase()) || "compose".equals(run.progressPhase())
                || "done".equals(run.progressPhase()))
            && normalizedResult != null
            && !"{}".equals(normalizedResult.replace(" ", ""))
            && !"null".equalsIgnoreCase(normalizedResult);
    }

    private boolean needsRepair(LegacyRun run) {
        return run.triggerMessageId() == null || (run.responseMessageId() == null && isUsableAnswer(run));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String migratedConversationId(Long userId, Long projectId, String legacyId) {
        String scope = userId + "|" + (projectId == null ? -1 : projectId) + "|" + legacyId;
        return "conv-migrated-" + hash(scope).substring(0, 32);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String title(String question) {
        String normalized = trimToNull(question);
        if (normalized == null) {
            return "Migrated conversation";
        }
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String safeJson(String value) {
        return value == null ? "UNKNOWN" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    public record BackfillResult(long processedRuns, Timestamp lastQueuedAt, String lastRunId) {
    }

    public record VerificationResult(long ownershipMismatchCount,
                                     long missingUserMessageCount,
                                     long missingAssistantMessageCount,
                                     long contentHashMismatchCount,
                                     long legacyRunCount,
                                     long conversationCount,
                                     long mappedConversationCount,
                                     long actualUserMessageCount,
                                     long expectedAssistantMessageCount,
                                     long actualAssistantMessageCount,
                                     long latestMessageMismatchCount,
                                     long runScopeMismatchCount,
                                     long expectedConversationCount,
                                     long conversationCountMismatchCount,
                                     long mappingMismatchCount,
                                     long mappingScopeMismatchCount,
                                     long unexpectedUserMessageCount,
                                     long unexpectedAssistantMessageCount) {
    }

    private record MigrationState(Timestamp lastQueuedAt, String lastRunId) {
    }

    private record LegacyRun(String runId,
                             Long userId,
                             Long projectId,
                             String conversationId,
                             String question,
                             String answer,
                             String resultJson,
                             String status,
                             String progressPhase,
                             Long triggerMessageId,
                             Long responseMessageId,
                             Timestamp orderTime,
                             Timestamp activityTime) {
    }

    private record HashPair(String runId,
                            String question,
                            String answer,
                            long messageId,
                            String role,
                            String content) {
    }
}
