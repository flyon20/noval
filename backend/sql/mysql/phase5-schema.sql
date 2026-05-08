-- Phase 5 database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase5-schema.sql

CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'system config id',
    config_key VARCHAR(100) NOT NULL COMMENT 'config key',
    config_value TEXT COMMENT 'config value',
    config_type VARCHAR(50) COMMENT 'config type',
    description VARCHAR(200) COMMENT 'config description',
    is_editable TINYINT DEFAULT 1 COMMENT '0 readonly 1 editable',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_config_key (config_key),
    INDEX idx_config_type (config_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='system config';

CREATE TABLE IF NOT EXISTS rank_board (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'rank board id',
    platform VARCHAR(20) NOT NULL COMMENT 'platform',
    channel_code VARCHAR(50) NOT NULL COMMENT 'channel code',
    board_code VARCHAR(50) NOT NULL COMMENT 'board code',
    board_name VARCHAR(100) NOT NULL COMMENT 'board name',
    description VARCHAR(255) COMMENT 'board description',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_platform_channel_board (platform, channel_code, board_code),
    INDEX idx_platform_channel (platform, channel_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='shared rank board';

CREATE TABLE IF NOT EXISTS rank_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'rank snapshot id',
    rank_board_id BIGINT NOT NULL COMMENT 'rank board id',
    snapshot_time DATETIME NOT NULL COMMENT 'snapshot time',
    record_count INT DEFAULT 0 COMMENT 'record count',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_board_snapshot_time (rank_board_id, snapshot_time),
    INDEX idx_rank_board_id (rank_board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='rank snapshot';

CREATE TABLE IF NOT EXISTS user_rank_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'user rank preference id',
    user_id BIGINT NOT NULL COMMENT 'user id',
    platform VARCHAR(20) NOT NULL COMMENT 'platform',
    channel_code VARCHAR(50) NOT NULL COMMENT 'channel code',
    board_code VARCHAR(50) NOT NULL COMMENT 'board code',
    rank_fetch_count INT DEFAULT 30 COMMENT 'preferred rank fetch count',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_user_platform (user_id, platform),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user rank preference';

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER $$

CREATE PROCEDURE add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `',
            REPLACE(p_table_name, '`', '``'),
            '` ADD COLUMN `',
            REPLACE(p_column_name, '`', '``'),
            '` ',
            p_column_definition
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_column_if_missing(
    'user_rank_preference',
    'rank_fetch_count',
    'INT DEFAULT 30 COMMENT ''preferred rank fetch count'''
);

CALL add_column_if_missing(
    'crawl_rank',
    'snapshot_id',
    'BIGINT COMMENT ''rank snapshot id'''
);
CALL add_column_if_missing(
    'crawl_rank',
    'channel_code',
    'VARCHAR(50) COMMENT ''channel code'''
);
CALL add_column_if_missing(
    'crawl_rank',
    'board_code',
    'VARCHAR(50) COMMENT ''board code'''
);

CALL add_column_if_missing(
    'analysis_result',
    'channel_code',
    'VARCHAR(50) COMMENT ''channel code'''
);
CALL add_column_if_missing(
    'analysis_result',
    'board_code',
    'VARCHAR(50) COMMENT ''board code'''
);
CALL add_column_if_missing(
    'analysis_result',
    'snapshot_id',
    'BIGINT COMMENT ''rank snapshot id'''
);
CALL add_column_if_missing(
    'analysis_result',
    'result_json',
    'JSON COMMENT ''structured result json'''
);

CALL add_column_if_missing(
    'prompt_config',
    'scope_type',
    'VARCHAR(20) NOT NULL DEFAULT ''SYSTEM'' COMMENT ''SYSTEM / USER_COPY'''
);
CALL add_column_if_missing(
    'prompt_config',
    'owner_user_id',
    'BIGINT COMMENT ''owner user id for USER_COPY'''
);
CALL add_column_if_missing(
    'prompt_config',
    'source_prompt_config_id',
    'BIGINT COMMENT ''source prompt config id'''
);
CALL add_column_if_missing(
    'prompt_config',
    'input_json_schema',
    'JSON COMMENT ''input json schema'''
);
CALL add_column_if_missing(
    'prompt_config',
    'input_example_json',
    'JSON COMMENT ''input example json'''
);
CALL add_column_if_missing(
    'prompt_config',
    'output_json_schema',
    'JSON COMMENT ''output json schema'''
);
CALL add_column_if_missing(
    'prompt_config',
    'output_example_json',
    'JSON COMMENT ''output example json'''
);
CALL add_column_if_missing(
    'prompt_config',
    'post_process_type',
    'VARCHAR(50) COMMENT ''post process type'''
);
CALL add_column_if_missing(
    'prompt_config',
    'parse_config_json',
    'JSON COMMENT ''parse config json'''
);

CREATE TABLE IF NOT EXISTS prompt_publish_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'publish version id',
    version_no BIGINT NOT NULL COMMENT 'version number',
    published_by BIGINT COMMENT 'published by user id',
    publish_note VARCHAR(255) COMMENT 'publish note',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_publish_version_no (version_no),
    INDEX idx_publish_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='prompt publish version';

CREATE TABLE IF NOT EXISTS prompt_publish_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'publish item id',
    publish_version_id BIGINT NOT NULL COMMENT 'publish version id',
    prompt_type VARCHAR(50) NOT NULL COMMENT 'prompt type',
    prompt_config_id BIGINT NOT NULL COMMENT 'prompt config id',
    prompt_name VARCHAR(100) NOT NULL COMMENT 'prompt name',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_publish_item_version_type (publish_version_id, prompt_type),
    INDEX idx_publish_item_prompt_type (prompt_type),
    INDEX idx_prompt_config_id (prompt_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='prompt publish item';

CREATE TABLE IF NOT EXISTS user_prompt_binding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'user prompt binding id',
    user_id BIGINT NOT NULL COMMENT 'user id',
    prompt_type VARCHAR(50) NOT NULL COMMENT 'prompt type',
    binding_mode VARCHAR(20) NOT NULL COMMENT 'GLOBAL / USER_COPY',
    bound_prompt_config_id BIGINT COMMENT 'bound user prompt config id when USER_COPY',
    last_selected_prompt_config_id BIGINT COMMENT 'last explicit selected prompt config id',
    effective_prompt_config_id BIGINT COMMENT 'current effective prompt config id',
    publish_version_id BIGINT COMMENT 'active publish version id',
    fallback_warning VARCHAR(255) COMMENT 'fallback warning for current binding',
    status TINYINT DEFAULT 1 COMMENT '0 inactive 1 active',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_user_prompt_type (user_id, prompt_type),
    INDEX idx_user_prompt_type_status (user_id, prompt_type, status),
    INDEX idx_bound_prompt_config_id (bound_prompt_config_id),
    INDEX idx_effective_prompt_config_id (effective_prompt_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user prompt binding';

CREATE TABLE IF NOT EXISTS user_prompt_effective_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'effective history id',
    user_id BIGINT NOT NULL COMMENT 'user id',
    prompt_type VARCHAR(50) NOT NULL COMMENT 'prompt type',
    publish_version_id BIGINT COMMENT 'publish version id',
    binding_mode VARCHAR(20) COMMENT 'GLOBAL / USER_COPY',
    bound_prompt_config_id BIGINT COMMENT 'bound prompt config id',
    effective_prompt_config_id BIGINT NOT NULL COMMENT 'effective prompt config id',
    effective_source VARCHAR(50) NOT NULL COMMENT 'effective source',
    previous_effective_prompt_config_id BIGINT COMMENT 'previous effective prompt config id',
    selected_model_key VARCHAR(100) COMMENT 'selected model key',
    fallback TINYINT DEFAULT 0 COMMENT 'whether fallback happened',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    INDEX idx_user_prompt_create_time (user_id, prompt_type, create_time),
    INDEX idx_publish_version_id (publish_version_id),
    INDEX idx_effective_prompt_config_id (effective_prompt_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user prompt effective history';

CREATE TABLE IF NOT EXISTS async_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'async job id',
    job_type VARCHAR(50) NOT NULL COMMENT 'job type',
    job_key VARCHAR(255) NOT NULL COMMENT 'deduplication job key',
    resource_key VARCHAR(255) COMMENT 'resource key',
    request_json JSON COMMENT 'request payload',
    status VARCHAR(20) NOT NULL COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / CANCELLED',
    trigger_user_id BIGINT COMMENT 'trigger user id',
    result_ref_type VARCHAR(50) COMMENT 'result reference type',
    result_ref_id BIGINT COMMENT 'result reference id',
    result_summary VARCHAR(255) COMMENT 'result summary',
    error_message VARCHAR(500) COMMENT 'error message',
    retry_count INT DEFAULT 0 COMMENT 'retry count',
    started_at DATETIME COMMENT 'started at',
    finished_at DATETIME COMMENT 'finished at',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    INDEX idx_async_job_type_key_time (job_type, job_key, create_time),
    INDEX idx_async_job_resource_key (resource_key),
    INDEX idx_async_job_status_time (status, create_time),
    INDEX idx_async_job_trigger_user_time (trigger_user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='async job';

DROP PROCEDURE IF EXISTS add_column_if_missing;
