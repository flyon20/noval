package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.AgentToolGovernanceSchemaCompatibilityRunner;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolGovernanceSchemaCompatibilityRunnerTest {

    private static final String LEGACY_CONVERSATION_ROLLOUT_DESCRIPTION =
        "Conversation/Message \u65b0\u8bfb\u8def\u5f84\u7070\u5ea6\u6bd4\u4f8b\uff1a0\u300110\u300150 \u6216 100\u3002";

    @Test
    void shouldUseMySqlJoinDeleteWithoutTargetTableSelfReference() throws Exception {
        Method sqlFactory = AgentToolGovernanceSchemaCompatibilityRunner.class
            .getDeclaredMethod("archivedDuplicateDeleteSql", boolean.class);
        sqlFactory.setAccessible(true);
        String mysqlSql = (String) sqlFactory.invoke(null, true);
        String portableSql = (String) sqlFactory.invoke(null, false);

        assertThat(mysqlSql)
            .contains("delete source_job")
            .contains("from async_job source_job")
            .contains("archived.source_async_job_id = source_job.id")
            .doesNotContain("where id in");
        assertThat(portableSql)
            .contains("delete from async_job")
            .contains("where id in");
    }

    @Test
    void shouldAlignArchiveTableToTheSourceMySqlCollation() throws Exception {
        Method sqlFactory = AgentToolGovernanceSchemaCompatibilityRunner.class
            .getDeclaredMethod("asyncJobArchiveCollationSql", String.class, String.class);
        sqlFactory.setAccessible(true);

        assertThat(sqlFactory.invoke(null, "utf8mb4", "utf8mb4_0900_ai_ci"))
            .isEqualTo(
                "alter table async_job_dedup_archive convert to character set "
                    + "utf8mb4 collate utf8mb4_0900_ai_ci"
            );
        assertThatThrownBy(() -> sqlFactory.invoke(
            null,
            "utf8mb4; drop table async_job",
            "utf8mb4_0900_ai_ci"
        ))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage("Unsafe MySQL character set or collation metadata");
    }

    @Test
    void shouldRemainRepeatableWhenOptionalDependencyTablesDoNotExistYet() throws Exception {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        AgentToolGovernanceSchemaCompatibilityRunner runner =
            new AgentToolGovernanceSchemaCompatibilityRunner(dataSource, jdbcTemplate);

        runner.run(null);
        runner.run(null);

        assertThat(tableExists(dataSource, "crawler_rank_refresh_commit")).isTrue();
        assertThat(tableExists(dataSource, "crawler_rank_refresh_fence")).isTrue();
        assertThat(tableExists(dataSource, "crawl_rank")).isFalse();
        assertThat(tableExists(dataSource, "system_config")).isFalse();
        assertThat(tableExists(dataSource, "ai_eval_case_result")).isFalse();
        assertThat(tableExists(dataSource, "ai_eval_trace_event")).isFalse();
    }

    @Test
    void shouldUpgradeExistingVolumeSchemaAndThreeDayFreshness() throws Exception {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_eval_case_result(case_key varchar(128) not null)");
        jdbcTemplate.execute("create table ai_eval_trace_event(case_key varchar(128) not null)");
        jdbcTemplate.execute("""
            create table crawl_rank(
                id bigint primary key,
                snapshot_id bigint,
                deleted tinyint,
                platform varchar(20),
                rank_no int
            )
            """);
        jdbcTemplate.execute("""
            create table system_config(
                config_key varchar(100) primary key,
                config_value varchar(100),
                config_type varchar(50),
                description varchar(200),
                is_editable tinyint,
                create_time timestamp,
                update_time timestamp,
                deleted tinyint
            )
            """);
        jdbcTemplate.update(
            "insert into system_config(config_key, config_value, update_time) values(?, ?, current_timestamp)",
            "crawler.rank.refresh-days",
            "5"
        );
        LocalDateTime untouchedAt = LocalDateTime.of(2026, 7, 1, 0, 0);
        jdbcTemplate.update("""
            insert into system_config(
                config_key, config_value, config_type, description, is_editable,
                create_time, update_time, deleted
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "ai.conversation.read-rollout-percent",
            "0",
            "ai",
            LEGACY_CONVERSATION_ROLLOUT_DESCRIPTION,
            1,
            untouchedAt,
            untouchedAt,
            0
        );

        AgentToolGovernanceSchemaCompatibilityRunner runner =
            new AgentToolGovernanceSchemaCompatibilityRunner(dataSource, jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(columnSize(dataSource, "ai_eval_case_result", "case_key")).isEqualTo(255);
        assertThat(columnSize(dataSource, "ai_eval_trace_event", "case_key")).isEqualTo(255);
        assertThat(jdbcTemplate.queryForObject(
            "select config_value from system_config where config_key = 'crawler.rank.refresh-days'",
            String.class
        )).isEqualTo("3");
        assertThat(jdbcTemplate.queryForObject(
            "select config_value from system_config where config_key = 'ai.conversation.read-rollout-percent'",
            String.class
        )).isEqualTo("100");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from crawler_rank_refresh_commit",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from crawler_rank_refresh_fence",
            Integer.class
        )).isZero();
        assertThat(indexExists(dataSource, "crawl_rank", "idx_crawl_rank_snapshot_lookup")).isTrue();
    }

    @Test
    void shouldArchiveCompleteLosersAndSelectSurvivorsByBusinessPriority() throws Exception {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createLegacyAsyncJobTable(jdbcTemplate);

        LocalDateTime base = LocalDateTime.of(2026, 7, 2, 10, 0);
        insertAsyncJob(jdbcTemplate, 10L, "priority-key", "FAILED", base, base.plusHours(4));
        insertAsyncJob(jdbcTemplate, 11L, "priority-key", "SUCCESS", base, base.plusHours(3));
        insertAsyncJob(jdbcTemplate, 12L, "priority-key", "PENDING", base, base.plusHours(2));
        insertAsyncJob(jdbcTemplate, 13L, "priority-key", "RUNNING", base, base.plusHours(1));

        insertAsyncJob(jdbcTemplate, 20L, "id-tie-key", "SUCCESS", base, base.plusHours(1));
        insertAsyncJob(jdbcTemplate, 21L, "id-tie-key", "SUCCESS", base, base.plusHours(1));

        insertAsyncJob(jdbcTemplate, 30L, "update-time-key", "FAILED", base, base.plusHours(2));
        insertAsyncJob(jdbcTemplate, 31L, "update-time-key", "FAILED", base, base.plusHours(1));

        insertAsyncJob(jdbcTemplate, 40L, "create-time-key", "CANCELLED", base.plusMinutes(1), base.plusHours(3));
        insertAsyncJob(jdbcTemplate, 41L, "create-time-key", "CANCELLED", base, base.plusHours(3));

        AgentToolGovernanceSchemaCompatibilityRunner runner =
            new AgentToolGovernanceSchemaCompatibilityRunner(dataSource, jdbcTemplate);
        runner.run(null);

        assertThat(jdbcTemplate.queryForList(
            "select id from async_job order by id",
            Long.class
        )).containsExactly(13L, 21L, 30L, 40L);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from async_job_dedup_archive",
            Integer.class
        )).isEqualTo(6);
        assertThat(jdbcTemplate.queryForList("""
            select source_async_job_id
            from async_job_dedup_archive
            where survivor_async_job_id = 13
            order by source_async_job_id
            """, Long.class)).containsExactly(10L, 11L, 12L);

        ArchivedJobSnapshot archived = jdbcTemplate.queryForObject("""
            select source_async_job_id, survivor_async_job_id, job_type, job_key,
                   resource_key, request_json, status, trigger_user_id,
                   result_ref_type, result_ref_id, result_summary, error_message,
                   retry_count, started_at, finished_at, queue_published_at,
                   queue_published_attempt, create_time, update_time, deleted,
                   survivor_status, selection_policy, archive_reason
            from async_job_dedup_archive
            where source_async_job_id = 10
            """, (resultSet, rowNum) -> new ArchivedJobSnapshot(
                resultSet.getLong("source_async_job_id"),
                resultSet.getLong("survivor_async_job_id"),
                resultSet.getString("job_type"),
                resultSet.getString("job_key"),
                resultSet.getString("resource_key"),
                resultSet.getString("request_json"),
                resultSet.getString("status"),
                resultSet.getLong("trigger_user_id"),
                resultSet.getString("result_ref_type"),
                resultSet.getLong("result_ref_id"),
                resultSet.getString("result_summary"),
                resultSet.getString("error_message"),
                resultSet.getInt("retry_count"),
                resultSet.getObject("started_at", LocalDateTime.class),
                resultSet.getObject("finished_at", LocalDateTime.class),
                resultSet.getObject("queue_published_at", LocalDateTime.class),
                resultSet.getInt("queue_published_attempt"),
                resultSet.getObject("create_time", LocalDateTime.class),
                resultSet.getObject("update_time", LocalDateTime.class),
                resultSet.getInt("deleted"),
                resultSet.getString("survivor_status"),
                resultSet.getString("selection_policy"),
                resultSet.getString("archive_reason")
            ));
        assertThat(archived).isEqualTo(new ArchivedJobSnapshot(
            10L,
            13L,
            "KNOWLEDGE_INDEX_BOOK",
            "priority-key",
            "resource-10",
            "{\"job\":10}",
            "FAILED",
            110L,
            "BOOK",
            1010L,
            "summary-10",
            "error-10",
            10,
            base.plusMinutes(1),
            base.plusHours(4),
            base.plusMinutes(2),
            10,
            base,
            base.plusHours(4),
            0,
            "RUNNING",
            "status-priority-update-time-create-time-id-v1",
            "duplicate logical async job"
        ));
        assertThat(jdbcTemplate.queryForObject("""
            select archived_at is not null
            from async_job_dedup_archive
            where source_async_job_id = 10
            """, Boolean.class)).isTrue();
        assertThat(indexExists(dataSource, "async_job", "uk_async_job_type_key_active")).isTrue();

        runner.run(null);

        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from async_job_dedup_archive",
            Integer.class
        )).isEqualTo(6);
        assertThat(jdbcTemplate.queryForList(
            "select id from async_job order by id",
            Long.class
        )).containsExactly(13L, 21L, 30L, 40L);
    }

    @Test
    void shouldResumeAfterRowsWereArchivedButNotDeleted() throws Exception {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createLegacyAsyncJobTable(jdbcTemplate);
        LocalDateTime base = LocalDateTime.of(2026, 7, 3, 10, 0);
        insertAsyncJob(jdbcTemplate, 100L, "partial-key", "RUNNING", base, base.plusHours(1));

        AgentToolGovernanceSchemaCompatibilityRunner runner =
            new AgentToolGovernanceSchemaCompatibilityRunner(dataSource, jdbcTemplate);
        runner.run(null);
        jdbcTemplate.execute("drop index uk_async_job_type_key_active");
        insertAsyncJob(jdbcTemplate, 101L, "partial-key", "FAILED", base, base.plusHours(2));
        jdbcTemplate.update("""
            insert into async_job_dedup_archive(
                migration_key, source_async_job_id, survivor_async_job_id,
                job_type, job_key, status, deleted, survivor_status,
                selection_policy, archive_reason
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "phase22-async-job-dedupe-v1",
            101L,
            100L,
            "KNOWLEDGE_INDEX_BOOK",
            "partial-key",
            "FAILED",
            0,
            "RUNNING",
            "status-priority-update-time-create-time-id-v1",
            "duplicate logical async job"
        );

        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForList("select id from async_job", Long.class))
            .containsExactly(100L);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from async_job_dedup_archive",
            Integer.class
        )).isEqualTo(1);
        assertThat(indexExists(dataSource, "async_job", "uk_async_job_type_key_active")).isTrue();
    }

    @Test
    void shouldPreserveExplicitConversationRolloutZero() throws Exception {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createSystemConfigTable(jdbcTemplate);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
        jdbcTemplate.update("""
            insert into system_config(
                config_key, config_value, config_type, description, is_editable,
                create_time, update_time, deleted
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "ai.conversation.read-rollout-percent",
            "0",
            "ai",
            LEGACY_CONVERSATION_ROLLOUT_DESCRIPTION,
            1,
            createdAt,
            createdAt.plusMinutes(1),
            0
        );

        AgentToolGovernanceSchemaCompatibilityRunner runner =
            new AgentToolGovernanceSchemaCompatibilityRunner(dataSource, jdbcTemplate);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("""
            select config_value
            from system_config
            where config_key = 'ai.conversation.read-rollout-percent'
            """, String.class)).isEqualTo("0");
    }

    @Test
    void shouldPropagateMigrationFailureForAnExistingBrokenTargetTable() {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table system_config(config_key varchar(100), config_value varchar(100))");
        jdbcTemplate.update(
            "insert into system_config(config_key, config_value) values(?, ?)",
            "crawler.rank.refresh-days",
            "5"
        );
        AgentToolGovernanceSchemaCompatibilityRunner runner =
            new AgentToolGovernanceSchemaCompatibilityRunner(dataSource, jdbcTemplate);

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("update_time");
    }

    private void createSystemConfigTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            create table system_config(
                config_key varchar(100) primary key,
                config_value varchar(100),
                config_type varchar(50),
                description varchar(200),
                is_editable tinyint,
                create_time timestamp,
                update_time timestamp,
                deleted tinyint
            )
            """);
    }

    private void createLegacyAsyncJobTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            create table async_job(
                id bigint primary key,
                job_type varchar(50) not null,
                job_key varchar(255) not null,
                resource_key varchar(255),
                request_json clob,
                status varchar(20) not null,
                trigger_user_id bigint,
                result_ref_type varchar(50),
                result_ref_id bigint,
                result_summary varchar(255),
                error_message varchar(500),
                retry_count int,
                started_at timestamp,
                finished_at timestamp,
                queue_published_at timestamp,
                queue_published_attempt int,
                create_time timestamp,
                update_time timestamp,
                deleted tinyint
            )
            """);
    }

    private void insertAsyncJob(JdbcTemplate jdbcTemplate,
                                long id,
                                String jobKey,
                                String status,
                                LocalDateTime createTime,
                                LocalDateTime updateTime) {
        jdbcTemplate.update("""
            insert into async_job(
                id, job_type, job_key, resource_key, request_json, status,
                trigger_user_id, result_ref_type, result_ref_id, result_summary,
                error_message, retry_count, started_at, finished_at,
                queue_published_at, queue_published_attempt, create_time,
                update_time, deleted
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            "KNOWLEDGE_INDEX_BOOK",
            jobKey,
            "resource-" + id,
            "{\"job\":" + id + "}",
            status,
            id + 100,
            "BOOK",
            id + 1000,
            "summary-" + id,
            "error-" + id,
            (int) id,
            createTime.plusMinutes(1),
            updateTime,
            createTime.plusMinutes(2),
            (int) id,
            createTime,
            updateTime,
            0
        );
    }

    private DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:agent-tool-governance-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }

    private boolean tableExists(DriverManagerDataSource dataSource, String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : new String[]{tableName, tableName.toUpperCase(), tableName.toLowerCase()}) {
                try (ResultSet resultSet = connection.getMetaData().getTables(
                    connection.getCatalog(), connection.getSchema(), table, null
                )) {
                    if (resultSet.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int columnSize(DriverManagerDataSource dataSource,
                           String tableName,
                           String columnName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(
                 connection.getCatalog(), connection.getSchema(), tableName, columnName
             )) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt("COLUMN_SIZE");
        }
    }

    private boolean indexExists(DriverManagerDataSource dataSource,
                                String tableName,
                                String indexName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : new String[]{tableName, tableName.toUpperCase(), tableName.toLowerCase()}) {
                try (ResultSet resultSet = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), connection.getSchema(), table, false, false
                )) {
                    while (resultSet.next()) {
                        String found = resultSet.getString("INDEX_NAME");
                        if (found != null && found.equalsIgnoreCase(indexName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private record ArchivedJobSnapshot(
        long sourceId,
        long survivorId,
        String jobType,
        String jobKey,
        String resourceKey,
        String requestJson,
        String status,
        long triggerUserId,
        String resultRefType,
        long resultRefId,
        String resultSummary,
        String errorMessage,
        int retryCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime queuePublishedAt,
        int queuePublishedAttempt,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        int deleted,
        String survivorStatus,
        String selectionPolicy,
        String archiveReason
    ) {
    }
}
