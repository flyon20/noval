-- Phase 22: Task 7 final-review reliability hardening.

-- The durable Conversation/Message read path is production-ready. Migrate only
-- the exact untouched legacy default. Value 0 alone is not sufficient because
-- an operator may have explicitly selected it.
SET @phase22_conversation_rollout_dml = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'system_config'
        )
        AND (
            SELECT COUNT(DISTINCT COLUMN_NAME)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'system_config'
              AND COLUMN_NAME IN (
                  'config_key', 'config_value', 'config_type', 'description',
                  'is_editable', 'create_time', 'update_time', 'deleted'
              )
        ) = 8,
        'UPDATE system_config SET config_value = ''100'', update_time = CURRENT_TIMESTAMP WHERE config_key = ''ai.conversation.read-rollout-percent'' AND TRIM(config_value) = ''0'' AND LOWER(TRIM(config_type)) = ''ai'' AND HEX(CONVERT(TRIM(description) USING utf8mb4)) = ''436F6E766572736174696F6E2F4D65737361676520E696B0E8AFBBE8B7AFE5BE84E781B0E5BAA6E6AF94E4BE8BEFBC9A30E380813130E38081353020E6889620313030E38082'' AND COALESCE(is_editable, 1) = 1 AND COALESCE(deleted, 0) = 0 AND create_time IS NOT NULL AND update_time IS NOT NULL AND create_time = update_time',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_conversation_rollout_dml;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;

SET @phase22_queue_published_at_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_job'
        )
        AND NOT EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'async_job'
              AND COLUMN_NAME = 'queue_published_at'
        ),
        'ALTER TABLE async_job ADD COLUMN queue_published_at DATETIME NULL AFTER finished_at',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_queue_published_at_ddl;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;

SET @phase22_queue_published_attempt_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_job'
        )
        AND NOT EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'async_job'
              AND COLUMN_NAME = 'queue_published_attempt'
        ),
        'ALTER TABLE async_job ADD COLUMN queue_published_attempt INT NULL AFTER queue_published_at',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_queue_published_attempt_ddl;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;

-- Preserve every duplicate loser before enforcing logical uniqueness. The
-- archive stores the complete source row plus the survivor decision so a
-- partially executed migration can safely continue on the next run.
CREATE TABLE IF NOT EXISTS async_job_dedup_archive (
    archive_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'archive row id',
    migration_key VARCHAR(64) NOT NULL COMMENT 'idempotent migration key',
    source_async_job_id BIGINT NOT NULL COMMENT 'archived async_job.id',
    survivor_async_job_id BIGINT NOT NULL COMMENT 'selected survivor async_job.id',
    job_type VARCHAR(50) NOT NULL COMMENT 'source job type',
    job_key VARCHAR(255) NOT NULL COMMENT 'source logical job key',
    resource_key VARCHAR(255) COMMENT 'source resource key',
    request_json LONGTEXT COMMENT 'source request payload',
    status VARCHAR(20) NOT NULL COMMENT 'source status',
    trigger_user_id BIGINT COMMENT 'source trigger user id',
    result_ref_type VARCHAR(50) COMMENT 'source result reference type',
    result_ref_id BIGINT COMMENT 'source result reference id',
    result_summary VARCHAR(255) COMMENT 'source result summary',
    error_message VARCHAR(500) COMMENT 'source error message',
    retry_count INT COMMENT 'source retry generation',
    started_at DATETIME COMMENT 'source started time',
    finished_at DATETIME COMMENT 'source finished time',
    queue_published_at DATETIME COMMENT 'source queue publication time',
    queue_published_attempt INT COMMENT 'source queue publication generation',
    create_time DATETIME COMMENT 'source create time',
    update_time DATETIME COMMENT 'source update time',
    deleted TINYINT COMMENT 'source logical delete flag',
    survivor_status VARCHAR(20) NOT NULL COMMENT 'survivor status at selection time',
    survivor_create_time DATETIME COMMENT 'survivor create time at selection time',
    survivor_update_time DATETIME COMMENT 'survivor update time at selection time',
    selection_policy VARCHAR(100) NOT NULL COMMENT 'deterministic survivor policy',
    archive_reason VARCHAR(100) NOT NULL COMMENT 'archive reason',
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'archive time',
    UNIQUE KEY uk_async_job_dedup_archive_source (migration_key, source_async_job_id),
    INDEX idx_async_job_dedup_archive_survivor (survivor_async_job_id),
    INDEX idx_async_job_dedup_archive_group (job_type, job_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='async job duplicate migration archive';

START TRANSACTION;

SET @phase22_async_job_archive_dml = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_job'
        ),
        'INSERT INTO async_job_dedup_archive (migration_key, source_async_job_id, survivor_async_job_id, job_type, job_key, resource_key, request_json, status, trigger_user_id, result_ref_type, result_ref_id, result_summary, error_message, retry_count, started_at, finished_at, queue_published_at, queue_published_attempt, create_time, update_time, deleted, survivor_status, survivor_create_time, survivor_update_time, selection_policy, archive_reason, archived_at) SELECT ''phase22-async-job-dedupe-v1'', ranked.id, ranked.survivor_async_job_id, ranked.job_type, ranked.job_key, ranked.resource_key, ranked.request_json, ranked.status, ranked.trigger_user_id, ranked.result_ref_type, ranked.result_ref_id, ranked.result_summary, ranked.error_message, ranked.retry_count, ranked.started_at, ranked.finished_at, ranked.queue_published_at, ranked.queue_published_attempt, ranked.create_time, ranked.update_time, ranked.deleted, ranked.survivor_status, ranked.survivor_create_time, ranked.survivor_update_time, ''status-priority-update-time-create-time-id-v1'', ''duplicate logical async job'', CURRENT_TIMESTAMP FROM (SELECT scored.*, FIRST_VALUE(id) OVER (PARTITION BY job_type, job_key, deleted ORDER BY status_priority ASC, COALESCE(update_time, create_time, ''1970-01-01 00:00:00'') DESC, COALESCE(create_time, ''1970-01-01 00:00:00'') DESC, id DESC) AS survivor_async_job_id, FIRST_VALUE(status) OVER (PARTITION BY job_type, job_key, deleted ORDER BY status_priority ASC, COALESCE(update_time, create_time, ''1970-01-01 00:00:00'') DESC, COALESCE(create_time, ''1970-01-01 00:00:00'') DESC, id DESC) AS survivor_status, FIRST_VALUE(create_time) OVER (PARTITION BY job_type, job_key, deleted ORDER BY status_priority ASC, COALESCE(update_time, create_time, ''1970-01-01 00:00:00'') DESC, COALESCE(create_time, ''1970-01-01 00:00:00'') DESC, id DESC) AS survivor_create_time, FIRST_VALUE(update_time) OVER (PARTITION BY job_type, job_key, deleted ORDER BY status_priority ASC, COALESCE(update_time, create_time, ''1970-01-01 00:00:00'') DESC, COALESCE(create_time, ''1970-01-01 00:00:00'') DESC, id DESC) AS survivor_update_time, ROW_NUMBER() OVER (PARTITION BY job_type, job_key, deleted ORDER BY status_priority ASC, COALESCE(update_time, create_time, ''1970-01-01 00:00:00'') DESC, COALESCE(create_time, ''1970-01-01 00:00:00'') DESC, id DESC) AS dedupe_rank FROM (SELECT source_job.*, CASE UPPER(TRIM(status)) WHEN ''RUNNING'' THEN 1 WHEN ''PENDING'' THEN 2 WHEN ''SUCCESS'' THEN 3 WHEN ''FAILED'' THEN 4 WHEN ''CANCELLED'' THEN 5 ELSE 6 END AS status_priority FROM async_job source_job) scored) ranked WHERE ranked.dedupe_rank > 1 AND NOT EXISTS (SELECT 1 FROM async_job_dedup_archive archived WHERE archived.migration_key = ''phase22-async-job-dedupe-v1'' AND archived.source_async_job_id = ranked.id)',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_async_job_archive_dml;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;

SET @phase22_async_job_delete_archived_dml = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_job'
        ),
        'DELETE source_job FROM async_job source_job JOIN async_job_dedup_archive archived ON archived.migration_key = ''phase22-async-job-dedupe-v1'' AND archived.source_async_job_id = source_job.id JOIN async_job survivor ON survivor.id = archived.survivor_async_job_id AND survivor.job_type = archived.job_type AND survivor.job_key = archived.job_key AND (survivor.deleted = archived.deleted OR (survivor.deleted IS NULL AND archived.deleted IS NULL))',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_async_job_delete_archived_dml;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;

COMMIT;

SET @phase22_async_job_unique_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_job'
        )
        AND NOT EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'async_job'
              AND INDEX_NAME = 'uk_async_job_type_key_active'
        ),
        'CREATE UNIQUE INDEX uk_async_job_type_key_active ON async_job(job_type, job_key, deleted)',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_async_job_unique_ddl;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;

SET @phase22_async_job_recovery_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_job'
        )
        AND (
            SELECT COUNT(DISTINCT COLUMN_NAME)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'async_job'
              AND COLUMN_NAME IN (
                  'job_type', 'status', 'queue_published_at',
                  'started_at', 'update_time', 'id'
              )
        ) = 6
        AND NOT EXISTS (
            SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'async_job'
              AND INDEX_NAME = 'idx_async_job_queue_recovery'
        ),
        'CREATE INDEX idx_async_job_queue_recovery ON async_job(job_type, status, queue_published_at, started_at, update_time, id)',
        'SELECT 1'
    )
);
PREPARE phase22_stmt FROM @phase22_async_job_recovery_ddl;
EXECUTE phase22_stmt;
DEALLOCATE PREPARE phase22_stmt;
