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

DROP PROCEDURE IF EXISTS add_column_if_missing;
