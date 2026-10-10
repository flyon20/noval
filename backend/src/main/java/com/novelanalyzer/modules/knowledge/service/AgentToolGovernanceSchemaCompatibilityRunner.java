package com.novelanalyzer.modules.knowledge.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** Replays Task 7 upgrades for existing volumes after the base schema is available. */
@Component
@Order(1)
public class AgentToolGovernanceSchemaCompatibilityRunner implements ApplicationRunner {

    private static final String RANK_LOOKUP_INDEX = "idx_crawl_rank_snapshot_lookup";
    private static final String ASYNC_JOB_UNIQUE_INDEX = "uk_async_job_type_key_active";
    private static final String ASYNC_JOB_RECOVERY_INDEX = "idx_async_job_queue_recovery";
    private static final String ASYNC_JOB_ARCHIVE_SURVIVOR_INDEX =
        "idx_async_job_dedup_archive_survivor";
    private static final String ASYNC_JOB_ARCHIVE_GROUP_INDEX =
        "idx_async_job_dedup_archive_group";
    private static final String ASYNC_JOB_DEDUPE_MIGRATION_KEY = "phase22-async-job-dedupe-v1";
    private static final String ASYNC_JOB_DEDUPE_SELECTION_POLICY =
        "status-priority-update-time-create-time-id-v1";
    private static final String CONVERSATION_ROLLOUT_CONFIG_KEY =
        "ai.conversation.read-rollout-percent";
    private static final String LEGACY_CONVERSATION_ROLLOUT_DESCRIPTION =
        "Conversation/Message \u65b0\u8bfb\u8def\u5f84\u7070\u5ea6\u6bd4\u4f8b\uff1a0\u300110\u300150 \u6216 100\u3002";
    private static final String[] RANK_LOOKUP_COLUMNS = {
        "snapshot_id", "deleted", "platform", "rank_no", "id"
    };

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AgentToolGovernanceSchemaCompatibilityRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureRankGovernanceTables();
        ensureRankLookupIndex();
        ensureAsyncJobQueueSchema();
        ensureCaseKeyWidth("ai_eval_case_result");
        ensureCaseKeyWidth("ai_eval_trace_event");
        migrateRankFreshnessToThreeDays();
        migrateLegacyConversationRolloutDefault();
    }

    private void ensureAsyncJobQueueSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "async_job")) {
                return;
            }
        }
        ensureColumn("async_job", "queue_published_at", "datetime null");
        ensureColumn("async_job", "queue_published_attempt", "int null");
        ensureAsyncJobDedupeArchiveTable();

        try (Connection connection = dataSource.getConnection()) {
            if (!indexExists(connection, "async_job", ASYNC_JOB_UNIQUE_INDEX)) {
                archiveAndDeleteDuplicateAsyncJobs();
                createIndexIfMissing(
                    "async_job",
                    ASYNC_JOB_UNIQUE_INDEX,
                    "create unique index " + ASYNC_JOB_UNIQUE_INDEX
                        + " on async_job(job_type, job_key, deleted)"
                );
            }
        }
        createIndexIfMissing(
            "async_job",
            ASYNC_JOB_RECOVERY_INDEX,
            "create index " + ASYNC_JOB_RECOVERY_INDEX
                + " on async_job(job_type, status, queue_published_at, started_at, update_time, id)"
        );
    }

    private void archiveAndDeleteDuplicateAsyncJobs() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.query(
                "select id from async_job order by id for update",
                resultSet -> {
                    // Consume the locking read without retaining every id in memory.
                }
            );
            archiveDuplicateAsyncJobs();
            deleteArchivedDuplicateAsyncJobs();
        });
    }

    private void ensureAsyncJobDedupeArchiveTable() throws SQLException {
        boolean mysql;
        try (Connection connection = dataSource.getConnection()) {
            mysql = isMySql(connection);
        }
        String requestJsonType = mysql ? "longtext" : "clob";
        String timestampType = mysql ? "datetime" : "timestamp";
        jdbcTemplate.execute("""
            create table if not exists async_job_dedup_archive (
                archive_id bigint auto_increment primary key,
                migration_key varchar(64) not null,
                source_async_job_id bigint not null,
                survivor_async_job_id bigint not null,
                job_type varchar(50) not null,
                job_key varchar(255) not null,
                resource_key varchar(255),
                request_json %s,
                status varchar(20) not null,
                trigger_user_id bigint,
                result_ref_type varchar(50),
                result_ref_id bigint,
                result_summary varchar(255),
                error_message varchar(500),
                retry_count int,
                started_at %s,
                finished_at %s,
                queue_published_at %s,
                queue_published_attempt int,
                create_time %s,
                update_time %s,
                deleted tinyint,
                survivor_status varchar(20) not null,
                survivor_create_time %s,
                survivor_update_time %s,
                selection_policy varchar(100) not null,
                archive_reason varchar(100) not null,
                archived_at %s not null default current_timestamp,
                constraint uk_async_job_dedup_archive_source
                    unique (migration_key, source_async_job_id)
            )
            """.formatted(
                requestJsonType,
                timestampType,
                timestampType,
                timestampType,
                timestampType,
                timestampType,
                timestampType,
                timestampType,
                timestampType
            ));
        if (mysql) {
            alignAsyncJobDedupeArchiveCollation();
        }
        createIndexIfMissing(
            "async_job_dedup_archive",
            ASYNC_JOB_ARCHIVE_SURVIVOR_INDEX,
            "create index " + ASYNC_JOB_ARCHIVE_SURVIVOR_INDEX
                + " on async_job_dedup_archive(survivor_async_job_id)"
        );
        createIndexIfMissing(
            "async_job_dedup_archive",
            ASYNC_JOB_ARCHIVE_GROUP_INDEX,
            "create index " + ASYNC_JOB_ARCHIVE_GROUP_INDEX
                + " on async_job_dedup_archive(job_type, job_key, deleted)"
        );
    }

    private void alignAsyncJobDedupeArchiveCollation() {
        MySqlTextCollation source = mySqlTextCollation("async_job", "job_key");
        MySqlTextCollation archive = mySqlTextCollation("async_job_dedup_archive", "job_key");
        if (source == null || archive == null || source.equals(archive)) {
            return;
        }
        jdbcTemplate.execute(asyncJobArchiveCollationSql(source.characterSet(), source.collation()));
    }

    private MySqlTextCollation mySqlTextCollation(String tableName, String columnName) {
        return jdbcTemplate.query(
            """
                select character_set_name, collation_name
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                  and column_name = ?
                """,
            statement -> {
                statement.setString(1, tableName);
                statement.setString(2, columnName);
            },
            resultSet -> resultSet.next()
                ? new MySqlTextCollation(resultSet.getString(1), resultSet.getString(2))
                : null
        );
    }

    private static String asyncJobArchiveCollationSql(String characterSet, String collation) {
        if (!isSafeMySqlIdentifier(characterSet) || !isSafeMySqlIdentifier(collation)) {
            throw new IllegalArgumentException("Unsafe MySQL character set or collation metadata");
        }
        return "alter table async_job_dedup_archive convert to character set "
            + characterSet + " collate " + collation;
    }

    private static boolean isSafeMySqlIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+");
    }

    private void archiveDuplicateAsyncJobs() {
        jdbcTemplate.update("""
            insert into async_job_dedup_archive (
                migration_key,
                source_async_job_id,
                survivor_async_job_id,
                job_type,
                job_key,
                resource_key,
                request_json,
                status,
                trigger_user_id,
                result_ref_type,
                result_ref_id,
                result_summary,
                error_message,
                retry_count,
                started_at,
                finished_at,
                queue_published_at,
                queue_published_attempt,
                create_time,
                update_time,
                deleted,
                survivor_status,
                survivor_create_time,
                survivor_update_time,
                selection_policy,
                archive_reason,
                archived_at
            )
            select
                ?,
                ranked.id,
                ranked.survivor_async_job_id,
                ranked.job_type,
                ranked.job_key,
                ranked.resource_key,
                ranked.request_json,
                ranked.status,
                ranked.trigger_user_id,
                ranked.result_ref_type,
                ranked.result_ref_id,
                ranked.result_summary,
                ranked.error_message,
                ranked.retry_count,
                ranked.started_at,
                ranked.finished_at,
                ranked.queue_published_at,
                ranked.queue_published_attempt,
                ranked.create_time,
                ranked.update_time,
                ranked.deleted,
                ranked.survivor_status,
                ranked.survivor_create_time,
                ranked.survivor_update_time,
                ?,
                'duplicate logical async job',
                current_timestamp
            from (
                select
                    scored.*,
                    first_value(id) over (
                        partition by job_type, job_key, deleted
                        order by status_priority asc,
                            coalesce(update_time, create_time, '1970-01-01 00:00:00') desc,
                            coalesce(create_time, '1970-01-01 00:00:00') desc,
                            id desc
                    ) as survivor_async_job_id,
                    first_value(status) over (
                        partition by job_type, job_key, deleted
                        order by status_priority asc,
                            coalesce(update_time, create_time, '1970-01-01 00:00:00') desc,
                            coalesce(create_time, '1970-01-01 00:00:00') desc,
                            id desc
                    ) as survivor_status,
                    first_value(create_time) over (
                        partition by job_type, job_key, deleted
                        order by status_priority asc,
                            coalesce(update_time, create_time, '1970-01-01 00:00:00') desc,
                            coalesce(create_time, '1970-01-01 00:00:00') desc,
                            id desc
                    ) as survivor_create_time,
                    first_value(update_time) over (
                        partition by job_type, job_key, deleted
                        order by status_priority asc,
                            coalesce(update_time, create_time, '1970-01-01 00:00:00') desc,
                            coalesce(create_time, '1970-01-01 00:00:00') desc,
                            id desc
                    ) as survivor_update_time,
                    row_number() over (
                        partition by job_type, job_key, deleted
                        order by status_priority asc,
                            coalesce(update_time, create_time, '1970-01-01 00:00:00') desc,
                            coalesce(create_time, '1970-01-01 00:00:00') desc,
                            id desc
                    ) as dedupe_rank
                from (
                    select
                        source_job.*,
                        case upper(trim(status))
                            when 'RUNNING' then 1
                            when 'PENDING' then 2
                            when 'SUCCESS' then 3
                            when 'FAILED' then 4
                            when 'CANCELLED' then 5
                            else 6
                        end as status_priority
                    from async_job source_job
                ) scored
            ) ranked
            where ranked.dedupe_rank > 1
              and not exists (
                  select 1
                  from async_job_dedup_archive archived
                  where archived.migration_key = ?
                    and archived.source_async_job_id = ranked.id
              )
            """,
            ASYNC_JOB_DEDUPE_MIGRATION_KEY,
            ASYNC_JOB_DEDUPE_SELECTION_POLICY,
            ASYNC_JOB_DEDUPE_MIGRATION_KEY
        );
    }

    private void deleteArchivedDuplicateAsyncJobs() {
        boolean mysql;
        try (Connection connection = dataSource.getConnection()) {
            mysql = isMySql(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to identify database for AsyncJob dedupe", ex);
        }
        jdbcTemplate.update(archivedDuplicateDeleteSql(mysql), ASYNC_JOB_DEDUPE_MIGRATION_KEY);
    }

    private static String archivedDuplicateDeleteSql(boolean mysql) {
        return mysql
            ? """
                delete source_job
                from async_job source_job
                join async_job_dedup_archive archived
                  on archived.migration_key = ?
                 and archived.source_async_job_id = source_job.id
                join async_job survivor
                  on survivor.id = archived.survivor_async_job_id
                 and survivor.job_type = archived.job_type
                 and survivor.job_key = archived.job_key
                 and (
                     survivor.deleted = archived.deleted
                     or (survivor.deleted is null and archived.deleted is null)
                 )
                """
            : """
                delete from async_job
                where id in (
                    select archived.source_async_job_id
                    from async_job_dedup_archive archived
                    join async_job survivor
                      on survivor.id = archived.survivor_async_job_id
                     and survivor.job_type = archived.job_type
                     and survivor.job_key = archived.job_key
                     and (
                         survivor.deleted = archived.deleted
                         or (survivor.deleted is null and archived.deleted is null)
                     )
                    where archived.migration_key = ?
                )
                """;
    }

    private void ensureColumn(String tableName, String columnName, String typeDefinition) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (columnSize(connection, tableName, columnName) != null) {
                return;
            }
        }
        jdbcTemplate.execute(
            "alter table " + tableName + " add column " + columnName + " " + typeDefinition
        );
    }

    private void createIndexIfMissing(String tableName,
                                      String indexName,
                                      String createSql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (indexExists(connection, tableName, indexName)) {
                return;
            }
        }
        try {
            jdbcTemplate.execute(createSql);
        } catch (DataAccessException ex) {
            try (Connection connection = dataSource.getConnection()) {
                if (indexExists(connection, tableName, indexName)) {
                    return;
                }
            }
            throw ex;
        }
    }

    private void ensureRankGovernanceTables() {
        jdbcTemplate.execute("""
            create table if not exists crawler_rank_refresh_commit (
                idempotency_hash varchar(64) primary key,
                request_fingerprint varchar(64) not null,
                channel_code varchar(50) not null,
                board_code varchar(50) not null,
                snapshot_id bigint not null,
                snapshot_time timestamp not null,
                total_count int not null default 0,
                reused tinyint not null default 0,
                refresh_limited tinyint not null default 0,
                create_time timestamp default current_timestamp,
                update_time timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists crawler_rank_refresh_fence (
                rank_board_id bigint primary key,
                fencing_token bigint not null default 0,
                update_time timestamp default current_timestamp
            )
            """);
    }

    private void ensureRankLookupIndex() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "crawl_rank")
                || !columnsExist(connection, "crawl_rank", RANK_LOOKUP_COLUMNS)
                || indexExists(connection, "crawl_rank", RANK_LOOKUP_INDEX)) {
                return;
            }
        }
        try {
            jdbcTemplate.execute(
                "create index " + RANK_LOOKUP_INDEX
                    + " on crawl_rank(snapshot_id, deleted, platform, rank_no, id)"
            );
        } catch (DataAccessException ex) {
            try (Connection connection = dataSource.getConnection()) {
                if (indexExists(connection, "crawl_rank", RANK_LOOKUP_INDEX)) {
                    return;
                }
            }
            throw ex;
        }
    }

    private void ensureCaseKeyWidth(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Integer width = columnSize(connection, tableName, "case_key");
            if (width == null || width >= 255) {
                return;
            }
            String product = connection.getMetaData().getDatabaseProductName();
            boolean mysql = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
            jdbcTemplate.execute(mysql
                ? "alter table " + tableName + " modify column case_key varchar(255) not null"
                : "alter table " + tableName + " alter column case_key varchar(255) not null");
        }
    }

    private void migrateRankFreshnessToThreeDays() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "system_config")) {
                return;
            }
        }
        jdbcTemplate.update(
            "update system_config set config_value = '3', update_time = current_timestamp "
                + "where config_key = 'crawler.rank.refresh-days' and trim(config_value) = '5'"
        );
    }

    private void migrateLegacyConversationRolloutDefault() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "system_config")
                || !columnsExist(
                    connection,
                    "system_config",
                    "config_key",
                    "config_value",
                    "config_type",
                    "description",
                    "is_editable",
                    "create_time",
                    "update_time",
                    "deleted"
                )) {
                return;
            }
        }
        jdbcTemplate.update("""
            update system_config
            set config_value = '100', update_time = current_timestamp
            where config_key = ?
              and trim(config_value) = '0'
              and lower(trim(config_type)) = 'ai'
              and trim(description) = ?
              and coalesce(is_editable, 1) = 1
              and coalesce(deleted, 0) = 0
              and create_time is not null
              and update_time is not null
              and create_time = update_time
            """,
            CONVERSATION_ROLLOUT_CONFIG_KEY,
            LEGACY_CONVERSATION_ROLLOUT_DESCRIPTION
        );
    }

    private Integer columnSize(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String table : variants(tableName)) {
            for (String column : variants(columnName)) {
                try (ResultSet resultSet = metaData.getColumns(
                    connection.getCatalog(), connection.getSchema(), table, column
                )) {
                    if (resultSet.next()) {
                        return resultSet.getInt("COLUMN_SIZE");
                    }
                }
            }
        }
        return null;
    }

    private boolean columnsExist(Connection connection,
                                 String tableName,
                                 String... columnNames) throws SQLException {
        for (String columnName : columnNames) {
            if (columnSize(connection, tableName, columnName) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String table : variants(tableName)) {
            try (ResultSet resultSet = metaData.getTables(
                connection.getCatalog(), connection.getSchema(), table, null
            )) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(Connection connection,
                                String tableName,
                                String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String table : variants(tableName)) {
            try (ResultSet resultSet = metaData.getIndexInfo(
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
        return false;
    }

    private boolean isMySql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
    }

    private String[] variants(String value) {
        return new String[]{value, value.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT)};
    }

    private record MySqlTextCollation(String characterSet, String collation) {
    }
}
