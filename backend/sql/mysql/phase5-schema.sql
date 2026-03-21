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

ALTER TABLE analysis_result
    ADD COLUMN IF NOT EXISTS result_json JSON COMMENT 'structured result json';
