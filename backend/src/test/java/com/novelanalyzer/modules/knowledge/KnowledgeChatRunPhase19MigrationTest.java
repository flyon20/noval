package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChatRunPhase19MigrationTest {

    private static final String H2_BACKFILL_START = "-- phase19:h2-backfill-start";
    private static final String H2_BACKFILL_END = "-- phase19:h2-backfill-end";

    @Test
    void shouldDeclareIdempotentPendingRunBackfillAndEventDirectedIndexes() throws IOException {
        String migration = readMigration();

        assertThat(migration).contains(
            H2_BACKFILL_START,
            H2_BACKFILL_END,
            "status = 'PENDING'",
            "event_type = 'EXECUTE'",
            "CONCAT('run:', r.run_id, ':execute')",
            "WHERE NOT EXISTS",
            "CREATE TEMPORARY TABLE noval_phase19_pending_execute_backfill AS",
            "SELECT run_id, CAST(0 AS SIGNED) AS sequence_no",
            "CREATE UNIQUE INDEX uk_noval_phase19_pending_execute_run",
            "ON noval_phase19_pending_execute_backfill(run_id)",
            "START TRANSACTION",
            "COMMIT",
            "idx_ai_chat_run_outbox_dispatch_pending",
            "(`event_type`, `status`, `available_at`, `outbox_id`)",
            "idx_ai_chat_run_outbox_dispatch_reclaim",
            "(`event_type`, `status`, `updated_at`, `outbox_id`)",
            "idx_ai_chat_run_outbox_dispatch_pending_attempt",
            "(`event_type`, `status`, `attempt_count`, `available_at`, `outbox_id`)",
            "idx_ai_chat_run_outbox_dispatch_reclaim_attempt",
            "(`event_type`, `status`, `attempt_count`, `updated_at`, `outbox_id`)",
            "idx_ai_chat_run_pending_execution_recovery",
            "(`status`, `deleted`, `update_time`, `run_id`)",
            "idx_ai_chat_run_outbox_execute_recovery",
            "(`event_type`, `status`, `updated_at`, `run_id`, `attempt_count`, `outbox_id`)",
            "dead_retry_count",
            "idx_ai_chat_run_outbox_terminal_dead_recovery",
            "(`event_type`, `status`, `dead_retry_count`, `updated_at`, `outbox_id`)"
        );
    }

    @Test
    void shouldDeclareIdempotentAttemptAwareIndexesForTheH2Schema() throws IOException {
        String schema = readH2Schema();

        assertThat(schema).contains(
            "CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_dispatch_pending_attempt",
            "ON ai_chat_run_outbox(event_type, status, attempt_count, available_at, outbox_id)",
            "CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_dispatch_reclaim_attempt",
            "ON ai_chat_run_outbox(event_type, status, attempt_count, updated_at, outbox_id)",
            "CREATE INDEX IF NOT EXISTS idx_ai_chat_run_pending_execution_recovery",
            "ON ai_chat_run(status, deleted, update_time, run_id)",
            "CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_execute_recovery",
            "ON ai_chat_run_outbox(event_type, status, updated_at, run_id, attempt_count, outbox_id)",
            "ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS dead_retry_count",
            "CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_terminal_dead_recovery",
            "ON ai_chat_run_outbox(event_type, status, dead_retry_count, updated_at, outbox_id)"
        );
    }

    @Test
    void shouldBackfillPendingRunsExactlyOnceOnRepeatedExecution() throws IOException {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        createSchema(jdbcTemplate);
        jdbcTemplate.update("insert into ai_chat_run(run_id, status, deleted, next_sequence_no) values('pending-new', 'PENDING', 0, 0)");
        jdbcTemplate.update("insert into ai_chat_run(run_id, status, deleted, next_sequence_no) values('pending-progress', 'PENDING', 0, 1)");
        jdbcTemplate.update("insert into ai_chat_run(run_id, status, deleted, next_sequence_no) values('pending-event', 'PENDING', 0, 1)");
        jdbcTemplate.update("insert into ai_chat_run(run_id, status, deleted, next_sequence_no) values('running', 'RUNNING', 0, 0)");
        jdbcTemplate.update("insert into ai_chat_run(run_id, status, deleted, next_sequence_no) values('deleted-pending', 'PENDING', 1, 0)");
        jdbcTemplate.update("""
            insert into ai_chat_run_event(run_id, sequence_no, event_type, event_idempotency_key)
            values('pending-progress', 1, 'PROGRESS', 'run:pending-progress:progress:1')
            """);
        jdbcTemplate.update("""
            insert into ai_chat_run_event(run_id, sequence_no, event_type, event_idempotency_key)
            values('pending-event', 1, 'EXECUTE', 'run:pending-event:execute')
            """);

        executeH2Backfill(jdbcTemplate, readMigration());
        executeH2Backfill(jdbcTemplate, readMigration());

        assertBackfilledOnce(jdbcTemplate, "pending-new", 1L);
        assertBackfilledOnce(jdbcTemplate, "pending-progress", 2L);
        assertBackfilledOnce(jdbcTemplate, "pending-event", 1L);
        assertThat(executeEventCount(jdbcTemplate, "running")).isZero();
        assertThat(executeEventCount(jdbcTemplate, "deleted-pending")).isZero();
    }

    private void assertBackfilledOnce(JdbcTemplate jdbcTemplate, String runId, long sequenceNo) {
        assertThat(executeEventCount(jdbcTemplate, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox where run_id = ? and event_type = 'EXECUTE'",
            Integer.class,
            runId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select sequence_no from ai_chat_run_event where run_id = ? and event_type = 'EXECUTE'",
            Long.class,
            runId
        )).isEqualTo(sequenceNo);
        assertThat(jdbcTemplate.queryForObject(
            "select next_sequence_no from ai_chat_run where run_id = ?",
            Long.class,
            runId
        )).isEqualTo(sequenceNo);
    }

    private int executeEventCount(JdbcTemplate jdbcTemplate, String runId) {
        return jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_event where run_id = ? and event_type = 'EXECUTE'",
            Integer.class,
            runId
        );
    }

    private void executeH2Backfill(JdbcTemplate jdbcTemplate, String migration) {
        int start = migration.indexOf(H2_BACKFILL_START);
        int end = migration.indexOf(H2_BACKFILL_END);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String block = migration.substring(start + H2_BACKFILL_START.length(), end);
        for (String statement : block.split(";")) {
            String sql = statement.trim();
            if (sql.isEmpty() || sql.equalsIgnoreCase("START TRANSACTION") || sql.equalsIgnoreCase("COMMIT")) {
                continue;
            }
            jdbcTemplate.execute(sql);
        }
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            create table ai_chat_run (
                run_id varchar(64) primary key,
                status varchar(20) not null,
                deleted tinyint not null default 0,
                execution_mode varchar(40),
                next_sequence_no bigint not null default 0
            )
            """);
        jdbcTemplate.execute("""
            create table ai_chat_run_event (
                event_id bigint auto_increment primary key,
                run_id varchar(64) not null,
                sequence_no bigint not null,
                event_type varchar(20) not null,
                event_idempotency_key varchar(200) not null,
                payload clob,
                created_at timestamp not null default current_timestamp,
                constraint uk_phase19_event_sequence unique(run_id, sequence_no),
                constraint uk_phase19_event_idempotency unique(run_id, event_idempotency_key)
            )
            """);
        jdbcTemplate.execute("""
            create table ai_chat_run_outbox (
                outbox_id bigint auto_increment primary key,
                event_id bigint not null,
                run_id varchar(64) not null,
                sequence_no bigint not null,
                event_type varchar(20) not null,
                event_idempotency_key varchar(200) not null,
                payload clob,
                status varchar(20) not null default 'PENDING',
                attempt_count int not null default 0,
                dead_retry_count int not null default 0,
                available_at timestamp not null default current_timestamp,
                published_at timestamp,
                last_error varchar(1000),
                created_at timestamp not null default current_timestamp,
                updated_at timestamp not null default current_timestamp,
                constraint uk_phase19_outbox_idempotency unique(run_id, event_idempotency_key)
            )
            """);
    }

    private String readMigration() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("sql/mysql/phase19-durable-chat-run-execution.sql");
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            candidate = current.resolve("backend/sql/mysql/phase19-durable-chat-run-execution.sql");
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate phase19-durable-chat-run-execution.sql");
    }

    private String readH2Schema() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("src/test/resources/sql/phase18-agent-harness-conversation-rag-h2.sql");
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            candidate = current.resolve(
                "backend/src/test/resources/sql/phase18-agent-harness-conversation-rag-h2.sql"
            );
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate phase18-agent-harness-conversation-rag-h2.sql");
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:phase19-" + System.nanoTime() +
                ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }
}
