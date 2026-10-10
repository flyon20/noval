package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeConversationServiceTest {

    private static final String PHASE18_SCHEMA = "sql/phase18-agent-harness-conversation-rag-h2.sql";

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldAddConversationMessageEventAndRunAttemptSchemaIdempotently() {
        DriverManagerDataSource dataSource = dataSource("schema");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_chat_run (run_id varchar(64) primary key)");

        executePhase18(dataSource);
        executePhase18(dataSource);

        assertThat(tableExists(jdbcTemplate, "ai_conversation")).isTrue();
        assertThat(tableExists(jdbcTemplate, "ai_chat_message")).isTrue();
        assertThat(tableExists(jdbcTemplate, "ai_chat_run_event")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_run", "request_id")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_run", "attempt_no")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_run", "next_sequence_no")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_run", "snapshot_sequence_no")).isTrue();

        jdbcTemplate.update(
            "insert into ai_conversation(conversation_id, user_id, project_id, title) values(?, ?, ?, ?)",
            "conv-project", 7L, 11L, "Project conversation"
        );
        jdbcTemplate.update(
            "insert into ai_conversation(conversation_id, user_id, project_id, title) values(?, ?, ?, ?)",
            "conv-standalone", 7L, null, "Standalone conversation"
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, role, content) values(?, ?, ?, ?, ?)",
            "conv-project", 8L, 11L, "USER", "wrong user"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, role, content) values(?, ?, ?, ?, ?)",
            "conv-project", 7L, 12L, "USER", "wrong project"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, role, content) values(?, ?, ?, ?, ?)",
            "conv-project", 7L, null, "USER", "missing project"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, role, content) values(?, ?, ?, ?, ?)",
            "conv-standalone", 7L, 11L, "USER", "unexpected project"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbcTemplate.update(
            "insert into ai_chat_run(run_id, request_id, attempt_no) values(?, ?, ?)",
            "run-1", "request-1", 1
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_run(run_id, request_id, attempt_no) values(?, ?, ?)",
            "run-2", "request-1", 1
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbcTemplate.update(
            "insert into ai_chat_run_event(run_id, sequence_no, event_type, event_idempotency_key, payload) " +
                "values(?, ?, ?, ?, ?)",
            "run-1", 1L, "PROGRESS", "node-1:1:progress:1", "{}"
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_run_event(run_id, sequence_no, event_type, event_idempotency_key, payload) " +
                "values(?, ?, ?, ?, ?)",
            "run-1", 1L, "DELTA", "node-1:1:delta:1", "{}"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into ai_chat_run_event(run_id, sequence_no, event_type, event_idempotency_key, payload) " +
                "values(?, ?, ?, ?, ?)",
            "run-1", 2L, "PROGRESS", "node-1:1:progress:1", "{}"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldRepairPartiallyCreatedConversationMessageAndEventTables() {
        DriverManagerDataSource dataSource = dataSource("partial-schema");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_chat_run (run_id varchar(64) primary key)");
        jdbcTemplate.execute("create table ai_conversation (conversation_id varchar(80) primary key)");
        jdbcTemplate.execute("create table ai_chat_message (message_id bigint auto_increment primary key)");
        jdbcTemplate.execute("create table ai_chat_run_event (event_id bigint auto_increment primary key)");

        executePhase18(dataSource);
        executePhase18(dataSource);

        assertThat(columnExists(jdbcTemplate, "ai_conversation", "project_scope_id")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_conversation", "updated_at")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_message", "conversation_id")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_message", "project_scope_id")).isTrue();
        assertThat(columnExists(jdbcTemplate, "ai_chat_run_event", "event_idempotency_key")).isTrue();
        assertThat(indexExists(jdbcTemplate, "ai_conversation", "idx_ai_conversation_user_updated")).isTrue();
        assertThat(indexExists(jdbcTemplate, "ai_chat_message", "idx_ai_chat_message_conversation")).isTrue();
        assertThat(constraintExists(
            jdbcTemplate,
            "ai_chat_message",
            "fk_ai_chat_message_conversation_scope"
        )).isTrue();
    }

    @Test
    void shouldGuardPopulatedPartialTablesBeforeApplyingSyntheticDefaults() throws Exception {
        String mysqlMigration = Files.readString(
            Path.of("..", "sql", "mysql", "phase18-agent-harness-conversation-rag.sql"),
            StandardCharsets.UTF_8
        );

        int guardCall = mysqlMigration.indexOf(
            "CALL noval_phase18_assert_empty_if_column_missing('ai_chat_message', 'conversation_id')"
        );
        int syntheticDefault = mysqlMigration.indexOf(
            "'ai_chat_message', 'conversation_id', 'conversation_id VARCHAR(80) NOT NULL DEFAULT"
        );
        assertThat(guardCall).isGreaterThanOrEqualTo(0);
        assertThat(syntheticDefault).isGreaterThan(guardCall);
        assertThat(mysqlMigration).contains(
            "CALL noval_phase18_add_primary_key_if_missing(",
            "'ai_conversation', 'PRIMARY KEY (`conversation_id`)'",
            "'ai_chat_message', 'PRIMARY KEY (`message_id`)'",
            "'ai_chat_run_event', 'PRIMARY KEY (`event_id`)'",
            "CALL noval_phase18_ensure_auto_increment('ai_chat_message', 'message_id')",
            "CALL noval_phase18_ensure_auto_increment('ai_chat_run_event', 'event_id')"
        );
    }

    @Test
    void shouldCreateListLoadOrderedMessagesAndArchiveWithDerivedOwnership() throws Exception {
        DriverManagerDataSource dataSource = dataSource("service");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_chat_run (run_id varchar(64) primary key)");
        jdbcTemplate.execute("create table ai_project (" +
            "project_id bigint primary key," +
            "user_id bigint not null," +
            "name varchar(120) not null," +
            "status varchar(20) not null)");
        executePhase18(dataSource);
        jdbcTemplate.update(
            "insert into ai_project(project_id, user_id, name, status) values(?, ?, ?, ?)",
            11L, 7L, "Owned Project", "ACTIVE"
        );
        jdbcTemplate.update(
            "insert into ai_project(project_id, user_id, name, status) values(?, ?, ?, ?)",
            12L, 8L, "Other Project", "ACTIVE"
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        Object service = conversationService(jdbcTemplate);
        Object created = ReflectionTestUtils.invokeMethod(service, "create", 11L, "Opening Ideas");
        BeanWrapperImpl createdBean = new BeanWrapperImpl(created);
        String conversationId = (String) createdBean.getPropertyValue("conversationId");

        assertThat(conversationId).startsWith("conv-");
        assertThat(createdBean.getPropertyValue("userId")).isEqualTo(7L);
        assertThat(createdBean.getPropertyValue("projectId")).isEqualTo(11L);

        Object initial = ReflectionTestUtils.invokeMethod(service, "createInitialForProject", 11L);
        assertThat(new BeanWrapperImpl(initial).getPropertyValue("conversationId")).isNotNull();

        jdbcTemplate.update(
            "insert into ai_chat_run(run_id, user_id, project_id, conversation_id, status, request_id) " +
                "values(?, ?, ?, ?, ?, ?)",
            "run-1", 7L, 11L, conversationId, "ANSWERED", "request-1"
        );
        ReflectionTestUtils.invokeMethod(
            service, "appendMessage", conversationId, "run-1", "USER", "First question", null, 3
        );
        ReflectionTestUtils.invokeMethod(
            service, "appendMessage", conversationId, "run-1", "ASSISTANT", "First answer", "{}", 4
        );
        jdbcTemplate.update(
            "insert into ai_chat_run(run_id, user_id, project_id, conversation_id, status, request_id) " +
                "values(?, ?, ?, ?, ?, ?)",
            "run-2", 7L, 11L, conversationId, "ANSWERED", "request-2"
        );
        ReflectionTestUtils.invokeMethod(
            service, "appendMessage", conversationId, "run-2", "USER", "Second question", null, 3
        );
        ReflectionTestUtils.invokeMethod(
            service, "appendMessage", conversationId, "run-2", "ASSISTANT", "Second answer", "{}", 4
        );

        List<?> conversations = ReflectionTestUtils.invokeMethod(service, "listMine", 11L);
        assertThat(conversations).hasSize(2);
        Object listed = conversations.stream()
            .filter(item -> conversationId.equals(new BeanWrapperImpl(item).getPropertyValue("conversationId")))
            .findFirst()
            .orElseThrow();
        assertThat(new BeanWrapperImpl(listed).getPropertyValue("lastRunId")).isEqualTo("run-2");
        assertThat(new BeanWrapperImpl(listed).getPropertyValue("lastRunStatus")).isEqualTo("ANSWERED");
        Object detail = ReflectionTestUtils.invokeMethod(service, "get", conversationId);
        assertThat(new BeanWrapperImpl(detail).getPropertyValue("messages")).asList().hasSize(4);

        List<?> messages = ReflectionTestUtils.invokeMethod(service, "listMessages", conversationId);
        assertThat(messages).extracting(message -> new BeanWrapperImpl(message).getPropertyValue("role"))
            .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(messages).extracting(message -> new BeanWrapperImpl(message).getPropertyValue("userId"))
            .containsOnly(7L);
        assertThat(messages).extracting(message -> new BeanWrapperImpl(message).getPropertyValue("projectId"))
            .containsOnly(11L);

        AuthUserHolder.set(AuthUser.of(8L, "other", Set.of("USER")));
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(service, "listMine", 11L)).isEmpty();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "get", conversationId))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);

        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ReflectionTestUtils.invokeMethod(service, "archive", conversationId);
        assertThat(ReflectionTestUtils.<List<?>>invokeMethod(service, "listMine", 11L)).hasSize(1);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "get", conversationId))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private Object conversationService(JdbcTemplate jdbcTemplate) throws Exception {
        Class<?> serviceType = Class.forName(
            "com.novelanalyzer.modules.knowledge.service.KnowledgeConversationService"
        );
        return serviceType.getConstructor(JdbcTemplate.class).newInstance(jdbcTemplate);
    }

    private void executePhase18(DriverManagerDataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(PHASE18_SCHEMA));
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from INFORMATION_SCHEMA.TABLES where lower(TABLE_NAME) = ?",
            Integer.class,
            tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from INFORMATION_SCHEMA.COLUMNS " +
                "where lower(TABLE_NAME) = ? and lower(COLUMN_NAME) = ?",
            Integer.class,
            tableName,
            columnName
        );
        return count != null && count > 0;
    }

    private boolean indexExists(JdbcTemplate jdbcTemplate, String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from INFORMATION_SCHEMA.INDEXES " +
                "where lower(TABLE_NAME) = ? and lower(INDEX_NAME) = ?",
            Integer.class,
            tableName,
            indexName
        );
        return count != null && count > 0;
    }

    private boolean constraintExists(JdbcTemplate jdbcTemplate, String tableName, String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                "where lower(TABLE_NAME) = ? and lower(CONSTRAINT_NAME) = ?",
            Integer.class,
            tableName,
            constraintName
        );
        return count != null && count > 0;
    }

    private DriverManagerDataSource dataSource(String suffix) {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:conversation-" + suffix + "-" + System.nanoTime() +
                ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }
}
