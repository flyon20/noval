package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeConversationMigrationTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldBackfillCanonicalConversationsMessagesAndCatchUpIdempotently() throws Exception {
        DriverManagerDataSource dataSource = dataSource("backfill");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createLegacyRunTable(jdbcTemplate);
        jdbcTemplate.execute("create table ai_project (" +
            "project_id bigint primary key, user_id bigint not null, name varchar(120), status varchar(20))");
        jdbcTemplate.update("insert into ai_project values(11, 7, 'P11', 'ACTIVE')");
        jdbcTemplate.update("insert into ai_project values(12, 7, 'P12', 'ACTIVE')");
        executePhase18(dataSource);
        insertLegacyRun(jdbcTemplate, "run-1", 7L, 11L, "legacy-shared", "question 1", "answer 1", "ANSWERED", 1);
        insertLegacyRun(jdbcTemplate, "run-2", 7L, 11L, "legacy-shared", "question 2", "partial 2", "FAILED", 2);
        insertLegacyRun(jdbcTemplate, "run-3", 8L, 12L, "legacy-shared", "question 3", "answer 3", "ANSWERED", 3);
        insertLegacyRun(jdbcTemplate, "run-4", 7L, null, "", "question 4", null, "FAILED", 4);
        insertLegacyRun(jdbcTemplate, "run-6", 7L, null, "", "question 6", null, "FAILED", 5);
        insertLegacyRun(jdbcTemplate, "run-7", 7L, 13L, "legacy-shared", "question 7", "answer 7", "ANSWERED", 6);

        Object migrationService = migrationService(jdbcTemplate, dataSource);
        assertThat(processed(ReflectionTestUtils.invokeMethod(migrationService, "backfill", 2))).isEqualTo(2);
        assertThat(drainBackfill(migrationService, 2)).isGreaterThanOrEqualTo(4L);

        assertThat(jdbcTemplate.queryForObject("select count(1) from ai_conversation", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("select count(1) from ai_chat_message", Integer.class)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
            "select count(distinct canonical_conversation_id) from ai_conversation_legacy_map " +
                "where legacy_conversation_id = 'legacy-shared'",
            Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_message where run_id = 'run-2' and role = 'ASSISTANT'",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation_legacy_map where legacy_conversation_id like '__EMPTY__:%'",
            Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "select trigger_message_id from ai_chat_run where run_id = 'run-1'",
            Long.class
        )).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "select response_message_id from ai_chat_run where run_id = 'run-1'",
            Long.class
        )).isNotNull();

        int messagesBeforeRerun = jdbcTemplate.queryForObject("select count(1) from ai_chat_message", Integer.class);
        assertThat(processed(ReflectionTestUtils.invokeMethod(migrationService, "backfill", 100))).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(1) from ai_chat_message", Integer.class))
            .isEqualTo(messagesBeforeRerun);

        jdbcTemplate.update(
            "update ai_chat_run set answer = ?, result_json = ?, progress_phase = 'answer', " +
                "update_time = current_timestamp where run_id = 'run-4'",
            "late partial 4",
            "{\"partial\":true}"
        );
        assertThat(processed(ReflectionTestUtils.invokeMethod(migrationService, "backfill", 100))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select content_json from ai_chat_message where run_id = 'run-4' and role = 'ASSISTANT'",
            String.class
        )).contains("PARTIAL");

        insertLegacyRun(jdbcTemplate, "run-5", 7L, 11L, "legacy-shared", "question 5", "answer 5", "ANSWERED", 7);
        assertThat(processed(ReflectionTestUtils.invokeMethod(migrationService, "backfill", 100))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(1) from ai_chat_message", Integer.class)).isEqualTo(12);

        jdbcTemplate.update(
            "update ai_chat_run set answer = ?, result_json = ?, progress_phase = 'answer', " +
                "update_time = current_timestamp where run_id = 'run-2'",
            "late partial 2",
            "{\"partial\":true}"
        );
        assertThat(processed(ReflectionTestUtils.invokeMethod(migrationService, "backfill", 100))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select last_run_id from ai_conversation where conversation_id = 'legacy-shared'",
            String.class
        )).isEqualTo("run-5");

        jdbcTemplate.update(
            "insert into ai_conversation(conversation_id, user_id, project_id, title, status) " +
                "values('conv-new', 7, 11, 'new conversation', 'ACTIVE')"
        );
        insertLegacyRun(
            jdbcTemplate, "run-new", 7L, 11L, "conv-new", "new question", "new answer", "ANSWERED", 8
        );
        jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, run_id, role, content) " +
                "values('conv-new', 7, 11, 'run-new', 'USER', 'new question')"
        );
        Long newUserMessageId = jdbcTemplate.queryForObject(
            "select message_id from ai_chat_message where run_id = 'run-new' and role = 'USER'",
            Long.class
        );
        jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, run_id, role, content) " +
                "values('conv-new', 7, 11, 'run-new', 'ASSISTANT', 'new answer')"
        );
        Long newAssistantMessageId = jdbcTemplate.queryForObject(
            "select message_id from ai_chat_message where run_id = 'run-new' and role = 'ASSISTANT'",
            Long.class
        );
        jdbcTemplate.update(
            "update ai_chat_run set trigger_message_id = ?, response_message_id = ?, request_id = 'request-new' " +
                "where run_id = 'run-new'",
            newUserMessageId,
            newAssistantMessageId
        );
        jdbcTemplate.update(
            "update ai_conversation set last_message_id = ?, last_run_id = 'run-new' " +
                "where conversation_id = 'conv-new'",
            newAssistantMessageId
        );
        drainBackfill(migrationService, 100);
        assertThat(jdbcTemplate.queryForObject(
            "select legacy_conversation_id from ai_chat_run where run_id = 'run-new'",
            String.class
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation_legacy_map where legacy_conversation_id = 'conv-new'",
            Integer.class
        )).isZero();

        Object verification = ReflectionTestUtils.invokeMethod(migrationService, "verifyBackfill");
        BeanWrapperImpl verified = new BeanWrapperImpl(verification);
        assertThat(verified.getPropertyValue("ownershipMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("missingUserMessageCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("missingAssistantMessageCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("contentHashMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("legacyRunCount")).isEqualTo(8L);
        assertThat(verified.getPropertyValue("actualUserMessageCount")).isEqualTo(8L);
        assertThat(verified.getPropertyValue("expectedAssistantMessageCount")).isEqualTo(7L);
        assertThat(verified.getPropertyValue("actualAssistantMessageCount")).isEqualTo(7L);
        assertThat(verified.getPropertyValue("latestMessageMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("runScopeMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("expectedConversationCount")).isEqualTo(5L);
        assertThat(verified.getPropertyValue("conversationCountMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("mappingMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("mappingScopeMismatchCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("unexpectedUserMessageCount")).isEqualTo(0L);
        assertThat(verified.getPropertyValue("unexpectedAssistantMessageCount")).isEqualTo(0L);

        Long staleUserMessage = jdbcTemplate.queryForObject(
            "select min(message_id) from ai_chat_message where conversation_id = 'legacy-shared'",
            Long.class
        );
        jdbcTemplate.update(
            "update ai_conversation set last_message_id = ? where conversation_id = 'legacy-shared'",
            staleUserMessage
        );
        Object staleVerificationResult = ReflectionTestUtils.invokeMethod(migrationService, "verifyBackfill");
        BeanWrapperImpl staleVerification = new BeanWrapperImpl(staleVerificationResult);
        assertThat(staleVerification.getPropertyValue("latestMessageMismatchCount")).isEqualTo(1L);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(migrationService, "backfill", 0))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class);
        jdbcTemplate.update(
            "insert into ai_conversation_migration_lock(lock_name, lock_owner, lease_until) " +
                "values('phase18-conversation-migration', 'other-node', dateadd('HOUR', 1, current_timestamp)) " +
                "on duplicate key update lock_owner = values(lock_owner), lease_until = values(lease_until)"
        );
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(migrationService, "backfill", 10))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class);
    }

    @Test
    void shouldSwitchBetweenLegacyFallbackAndConversationReadsByRollout() throws Exception {
        DriverManagerDataSource dataSource = dataSource("read-switch");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createLegacyRunTable(jdbcTemplate);
        jdbcTemplate.execute("create table ai_project (" +
            "project_id bigint primary key, user_id bigint not null, name varchar(120), status varchar(20))");
        jdbcTemplate.update("insert into ai_project values(11, 7, 'P11', 'ACTIVE')");
        jdbcTemplate.update("insert into ai_project values(12, 7, 'P12', 'ACTIVE')");
        executePhase18(dataSource);
        insertLegacyRun(jdbcTemplate, "run-legacy", 7L, 11L, "conv-legacy", "legacy question", "legacy answer", "ANSWERED", 1);
        insertLegacyRun(jdbcTemplate, "run-other-project", 7L, 12L, "conv-legacy", "other project question", "other project answer", "ANSWERED", 2);
        insertLegacyRun(jdbcTemplate, "run-empty", 7L, 11L, "", "empty question", "empty answer", "ANSWERED", 0);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        Object created = conversationService.create(11L, "new only conversation");
        String newOnlyConversationId = String.valueOf(
            new BeanWrapperImpl(created).getPropertyValue("conversationId")
        );

        Object legacyReadService = readService(jdbcTemplate, conversationService, () -> 0, () -> true);
        List<?> legacy = ReflectionTestUtils.invokeMethod(legacyReadService, "listMine", 11L);
        assertThat(legacy).hasSize(2);
        assertThat(new BeanWrapperImpl(legacy.get(0)).getPropertyValue("conversationId"))
            .isEqualTo("conv-legacy");
        assertThat(new BeanWrapperImpl(legacy.get(0)).getPropertyValue("lastRunStatus"))
            .isEqualTo("ANSWERED");
        assertThat(legacy).extracting(item -> new BeanWrapperImpl(item).getPropertyValue("conversationId"))
            .doesNotContain(newOnlyConversationId);
        Object emptyConversation = legacy.stream()
            .filter(item -> String.valueOf(new BeanWrapperImpl(item).getPropertyValue("conversationId"))
                .startsWith("conv-migrated-"))
            .findFirst()
            .orElseThrow();
        String emptyConversationId = String.valueOf(
            new BeanWrapperImpl(emptyConversation).getPropertyValue("conversationId")
        );
        Object emptyDetail = ReflectionTestUtils.invokeMethod(
            legacyReadService, "get", emptyConversationId, 11L
        );
        assertThat(new BeanWrapperImpl(emptyDetail).getPropertyValue("messages")).asList()
            .extracting(message -> new BeanWrapperImpl(message).getPropertyValue("content"))
            .containsExactly("empty question", "empty answer");
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(legacyReadService, "listMine", (Object) null)).hasSize(3);
        Object rollbackReadService = readService(jdbcTemplate, conversationService, () -> 0, () -> false);
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(rollbackReadService, "listMine", 11L)).hasSize(2);
        Object scopedLegacy = ReflectionTestUtils.invokeMethod(legacyReadService, "get", "conv-legacy", 11L);
        assertThat(new BeanWrapperImpl(scopedLegacy).getPropertyValue("messages")).asList()
            .extracting(message -> new BeanWrapperImpl(message).getPropertyValue("content"))
            .contains("legacy question", "legacy answer")
            .doesNotContain("other project question", "other project answer");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            legacyReadService, "get", "conv-legacy", null
        )).isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class);

        Object fallbackBeforeMigration = readService(jdbcTemplate, conversationService, () -> 100, () -> true);
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(fallbackBeforeMigration, "listMine", 12L)).hasSize(1);

        Object migrationService = migrationService(jdbcTemplate, dataSource);
        ReflectionTestUtils.invokeMethod(migrationService, "backfill", 1);
        Object partialReadService = readService(jdbcTemplate, conversationService, () -> 100, () -> true);
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(partialReadService, "listMine", 11L)).hasSize(3);
        drainBackfill(migrationService, 100);
        Object newReadService = readService(jdbcTemplate, conversationService, () -> 100, () -> true);
        List<?> migrated = ReflectionTestUtils.invokeMethod(newReadService, "listMine", 11L);
        assertThat(migrated).hasSize(3);
        Object detail = ReflectionTestUtils.invokeMethod(newReadService, "get", "conv-legacy", 11L);
        assertThat(new BeanWrapperImpl(detail).getPropertyValue("messages")).asList()
            .extracting(message -> new BeanWrapperImpl(message).getPropertyValue("role"))
            .containsExactly("USER", "ASSISTANT");

        conversationService.archive("conv-legacy");
        List<?> afterArchive = ReflectionTestUtils.invokeMethod(newReadService, "listMine", 11L);
        assertThat(afterArchive).extracting(item -> new BeanWrapperImpl(item).getPropertyValue("conversationId"))
            .doesNotContain("conv-legacy");
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(newReadService, "listMine", (Object) null))
            .extracting(item -> new BeanWrapperImpl(item).getPropertyValue("projectId"))
            .contains(12L);

        Object tenPercent = readService(jdbcTemplate, conversationService, () -> 10, () -> true);
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(tenPercent, "useConversationRead", 5L)).isTrue();
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(tenPercent, "useConversationRead", 15L)).isFalse();
        Object fiftyPercent = readService(jdbcTemplate, conversationService, () -> 50, () -> true);
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(fiftyPercent, "useConversationRead", 49L)).isTrue();
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(fiftyPercent, "useConversationRead", 50L)).isFalse();
    }

    @Test
    void shouldListDistinctLegacyConversationsBeyondTwoThousandRuns() throws Exception {
        DriverManagerDataSource dataSource = dataSource("legacy-list-volume");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createLegacyRunTable(jdbcTemplate);
        executePhase18(dataSource);
        Timestamp oldTime = Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0));
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, answer, result_json,
                    status, cancel_requested, queued_at, create_time, update_time, deleted)
                values('run-old', 7, 11, 'conv-old', 'old question', 'old answer', '{}',
                    'ANSWERED', false, ?, ?, ?, 0)
                """, oldTime, oldTime, oldTime);
        List<Object[]> rows = new java.util.ArrayList<>();
        Timestamp recentTime = Timestamp.valueOf(LocalDateTime.of(2026, 7, 12, 2, 0));
        for (int index = 0; index < 2001; index++) {
            rows.add(new Object[]{
                "run-volume-" + String.format("%04d", index),
                "question " + index,
                "answer " + index,
                recentTime,
                recentTime,
                recentTime
            });
        }
        jdbcTemplate.batchUpdate("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, answer, result_json,
                    status, cancel_requested, queued_at, create_time, update_time, deleted)
                values(?, 7, 11, 'conv-volume', ?, ?, '{}', 'ANSWERED', false, ?, ?, ?, 0)
                """, rows);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeConversationService conversationService = new KnowledgeConversationService(jdbcTemplate);
        Object legacyReadService = readService(jdbcTemplate, conversationService, () -> 0, () -> true);
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(legacyReadService, "listMine", 11L))
            .extracting(item -> new BeanWrapperImpl(item).getPropertyValue("conversationId"))
            .containsExactlyInAnyOrder("conv-old", "conv-volume");
    }

    private Object migrationService(JdbcTemplate jdbcTemplate, DriverManagerDataSource dataSource) throws Exception {
        Class<?> type = Class.forName(
            "com.novelanalyzer.modules.knowledge.service.KnowledgeConversationMigrationService"
        );
        Constructor<?> constructor = type.getConstructor(
            JdbcTemplate.class,
            org.springframework.transaction.PlatformTransactionManager.class
        );
        return constructor.newInstance(jdbcTemplate, new DataSourceTransactionManager(dataSource));
    }

    private Object readService(JdbcTemplate jdbcTemplate,
                               KnowledgeConversationService conversationService,
                               IntSupplier rollout,
                               BooleanSupplier fallback) throws Exception {
        Class<?> type = Class.forName(
            "com.novelanalyzer.modules.knowledge.service.KnowledgeConversationReadService"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
            JdbcTemplate.class,
            KnowledgeConversationService.class,
            IntSupplier.class,
            BooleanSupplier.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(jdbcTemplate, conversationService, rollout, fallback);
    }

    private long processed(Object result) {
        return ((Number) new BeanWrapperImpl(result).getPropertyValue("processedRuns")).longValue();
    }

    private long drainBackfill(Object migrationService, int batchSize) {
        long total = 0L;
        for (int attempt = 0; attempt < 100; attempt++) {
            long current = processed(ReflectionTestUtils.invokeMethod(migrationService, "backfill", batchSize));
            if (current == 0L) {
                return total;
            }
            total += current;
        }
        throw new AssertionError("conversation backfill did not drain");
    }

    private void insertLegacyRun(JdbcTemplate jdbcTemplate,
                                 String runId,
                                 Long userId,
                                 Long projectId,
                                 String conversationId,
                                 String question,
                                 String answer,
                                 String status,
                                 int minute) {
        Timestamp time = Timestamp.valueOf(LocalDateTime.of(2026, 7, 12, 1, minute));
        jdbcTemplate.update("""
                insert into ai_chat_run(run_id, user_id, project_id, conversation_id, question, answer, result_json,
                    status, cancel_requested, queued_at, create_time, update_time, deleted)
                values(?, ?, ?, ?, ?, ?, '{}', ?, false, ?, ?, ?, 0)
                """,
            runId, userId, projectId, conversationId, question, answer, status, time, time, time
        );
    }

    private void createLegacyRunTable(JdbcTemplate jdbcTemplate) {
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
                    queued_at timestamp,
                    started_at timestamp,
                    finished_at timestamp,
                    create_time timestamp,
                    update_time timestamp,
                    deleted tinyint default 0
                )
                """);
    }

    private void executePhase18(DriverManagerDataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("sql/phase18-agent-harness-conversation-rag-h2.sql")
        );
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private DriverManagerDataSource dataSource(String suffix) {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:conversation-migration-" + suffix + "-" + System.nanoTime() +
                ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }
}
