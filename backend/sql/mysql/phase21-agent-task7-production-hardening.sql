-- Phase 21: Task 7 production hardening and existing-volume compatibility.
--
-- Fresh volumes execute this file after the base schemas through the ordered
-- docker/mysql/00-initialize-noval.sh entrypoint. Metadata guards keep the file
-- safe for standalone/manual runs against partial or legacy databases.
-- Existing volumes receive the same upgrades from the backend compatibility
-- runner because initdb scripts only run for a new data volume.

CREATE TABLE IF NOT EXISTS crawler_rank_refresh_commit (
    idempotency_hash VARCHAR(64) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    channel_code VARCHAR(50) NOT NULL,
    board_code VARCHAR(50) NOT NULL,
    snapshot_id BIGINT NOT NULL,
    snapshot_time DATETIME NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    reused TINYINT(1) NOT NULL DEFAULT 0,
    refresh_limited TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rank_refresh_commit_snapshot (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crawler_rank_refresh_fence (
    rank_board_id BIGINT PRIMARY KEY,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @phase21_rank_index_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'crawl_rank'
        )
        AND (
            SELECT COUNT(DISTINCT COLUMN_NAME)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'crawl_rank'
              AND COLUMN_NAME IN ('snapshot_id', 'deleted', 'platform', 'rank_no', 'id')
        ) = 5
        AND NOT EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'crawl_rank'
              AND INDEX_NAME = 'idx_crawl_rank_snapshot_lookup'
        ),
        'CREATE INDEX idx_crawl_rank_snapshot_lookup ON crawl_rank(snapshot_id, deleted, platform, rank_no, id)',
        'SELECT 1'
    )
);
PREPARE phase21_stmt FROM @phase21_rank_index_ddl;
EXECUTE phase21_stmt;
DEALLOCATE PREPARE phase21_stmt;

SET @phase21_eval_result_case_key_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'ai_eval_case_result'
        )
        AND EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'ai_eval_case_result'
              AND COLUMN_NAME = 'case_key'
              AND DATA_TYPE = 'varchar'
              AND CHARACTER_MAXIMUM_LENGTH < 255
        ),
        'ALTER TABLE ai_eval_case_result MODIFY COLUMN case_key VARCHAR(255) NOT NULL',
        'SELECT 1'
    )
);
PREPARE phase21_stmt FROM @phase21_eval_result_case_key_ddl;
EXECUTE phase21_stmt;
DEALLOCATE PREPARE phase21_stmt;

SET @phase21_eval_trace_case_key_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'ai_eval_trace_event'
        )
        AND EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'ai_eval_trace_event'
              AND COLUMN_NAME = 'case_key'
              AND DATA_TYPE = 'varchar'
              AND CHARACTER_MAXIMUM_LENGTH < 255
        ),
        'ALTER TABLE ai_eval_trace_event MODIFY COLUMN case_key VARCHAR(255) NOT NULL',
        'SELECT 1'
    )
);
PREPARE phase21_stmt FROM @phase21_eval_trace_case_key_ddl;
EXECUTE phase21_stmt;
DEALLOCATE PREPARE phase21_stmt;

-- Existing config_key = 'crawler.rank.refresh-days' rows with
-- TRIM(config_value) = '5' are migrated to config_value = '3'.
SET @phase21_rank_freshness_dml = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'system_config'
        )
        AND (
            SELECT COUNT(DISTINCT COLUMN_NAME)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'system_config'
              AND COLUMN_NAME IN ('config_key', 'config_value', 'update_time')
        ) = 3,
        'UPDATE system_config SET config_value = ''3'', update_time = CURRENT_TIMESTAMP WHERE config_key = ''crawler.rank.refresh-days'' AND TRIM(config_value) = ''5''',
        'SELECT 1'
    )
);
PREPARE phase21_stmt FROM @phase21_rank_freshness_dml;
EXECUTE phase21_stmt;
DEALLOCATE PREPARE phase21_stmt;
