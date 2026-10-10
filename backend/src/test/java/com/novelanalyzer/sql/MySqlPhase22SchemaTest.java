package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase22SchemaTest {

    private static final Path SCRIPT = Path.of("..", "sql", "mysql", "phase22-agent-task7-review-hardening.sql");

    @Test
    void guardsAsyncJobQueueColumnsAndIndexesForExistingVolumes() throws Exception {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        assertThat(sql)
            .contains("INFORMATION_SCHEMA.TABLES")
            .contains("INFORMATION_SCHEMA.COLUMNS")
            .contains("INFORMATION_SCHEMA.STATISTICS")
            .contains("COLUMN_NAME = 'queue_published_at'")
            .contains("COLUMN_NAME = 'queue_published_attempt'")
            .contains("CREATE UNIQUE INDEX uk_async_job_type_key_active")
            .contains("CREATE INDEX idx_async_job_queue_recovery")
            .contains("PREPARE phase22_stmt")
            .contains("DEALLOCATE PREPARE phase22_stmt");
    }

    @Test
    void archivesLegacyDuplicatesBeforeDeletingAndAddingLogicalJobUniqueness() throws Exception {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        int archiveTablePosition = sql.indexOf("CREATE TABLE IF NOT EXISTS async_job_dedup_archive");
        int transactionPosition = sql.indexOf("START TRANSACTION");
        int archivePosition = sql.indexOf("INSERT INTO async_job_dedup_archive");
        int deletePosition = sql.indexOf("DELETE source_job FROM async_job source_job");
        int commitPosition = sql.indexOf("COMMIT;");
        int uniquePosition = sql.indexOf("CREATE UNIQUE INDEX uk_async_job_type_key_active");

        assertThat(archiveTablePosition).isGreaterThanOrEqualTo(0);
        assertThat(transactionPosition).isGreaterThan(archiveTablePosition);
        assertThat(archivePosition).isGreaterThan(transactionPosition);
        assertThat(deletePosition).isGreaterThan(archivePosition);
        assertThat(commitPosition).isGreaterThan(deletePosition);
        assertThat(uniquePosition).isGreaterThan(commitPosition);
        assertThat(sql)
            .contains("source_async_job_id")
            .contains("survivor_async_job_id")
            .contains("request_json LONGTEXT")
            .contains("queue_published_attempt")
            .contains("uk_async_job_dedup_archive_source")
            .contains("ranked.dedupe_rank > 1")
            .contains("NOT EXISTS (SELECT 1 FROM async_job_dedup_archive")
            .doesNotContain("DELETE older FROM async_job older JOIN async_job newer");
    }

    @Test
    void selectsSurvivorByStatusThenStableTimeAndIdTieBreakers() throws Exception {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        int running = sql.indexOf("WHEN ''RUNNING'' THEN 1");
        int pending = sql.indexOf("WHEN ''PENDING'' THEN 2");
        int success = sql.indexOf("WHEN ''SUCCESS'' THEN 3");
        int failed = sql.indexOf("WHEN ''FAILED'' THEN 4");
        int cancelled = sql.indexOf("WHEN ''CANCELLED'' THEN 5");

        assertThat(running).isGreaterThanOrEqualTo(0);
        assertThat(pending).isGreaterThan(running);
        assertThat(success).isGreaterThan(pending);
        assertThat(failed).isGreaterThan(success);
        assertThat(cancelled).isGreaterThan(failed);
        assertThat(sql)
            .contains("COALESCE(update_time, create_time, ''1970-01-01 00:00:00'') DESC")
            .contains("COALESCE(create_time, ''1970-01-01 00:00:00'') DESC")
            .contains("id DESC")
            .contains("status-priority-update-time-create-time-id-v1");
    }

    @Test
    void cutsOverOnlyTheOldConversationReadDefault() throws Exception {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        assertThat(sql)
            .contains("ai.conversation.read-rollout-percent")
            .contains("TRIM(config_value) = ''0''")
            .contains("config_value = ''100''")
            .contains("LOWER(TRIM(config_type)) = ''ai''")
            .contains("HEX(CONVERT(TRIM(description) USING utf8mb4))")
            .contains("436F6E766572736174696F6E2F4D65737361676520E696B0E8AFBBE8B7AFE5BE84E781B0E5BAA6E6AF94E4BE8BEFBC9A30E380813130E38081353020E6889620313030E38082")
            .contains("COALESCE(is_editable, 1) = 1")
            .contains("COALESCE(deleted, 0) = 0")
            .contains("create_time = update_time")
            .contains("@phase22_conversation_rollout_dml");
    }
}
