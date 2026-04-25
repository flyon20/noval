DROP TABLE IF EXISTS system_config;
DROP TABLE IF EXISTS rank_snapshot;
DROP TABLE IF EXISTS rank_board;
DROP TABLE IF EXISTS user_rank_preference;

CREATE TABLE system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL,
    config_value CLOB,
    config_type VARCHAR(50),
    description VARCHAR(200),
    is_editable TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_config_key ON system_config(config_key);

CREATE TABLE rank_board (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    platform VARCHAR(20) NOT NULL,
    channel_code VARCHAR(50) NOT NULL,
    board_code VARCHAR(50) NOT NULL,
    board_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_channel_board ON rank_board(platform, channel_code, board_code);

CREATE TABLE rank_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rank_board_id BIGINT NOT NULL,
    snapshot_time TIMESTAMP NOT NULL,
    record_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_board_snapshot_time ON rank_snapshot(rank_board_id, snapshot_time);

CREATE TABLE user_rank_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    platform VARCHAR(20) NOT NULL,
    channel_code VARCHAR(50) NOT NULL,
    board_code VARCHAR(50) NOT NULL,
    rank_fetch_count INT DEFAULT 30,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_platform ON user_rank_preference(user_id, platform);

ALTER TABLE crawl_rank ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE crawl_rank ADD COLUMN IF NOT EXISTS channel_code VARCHAR(50);
ALTER TABLE crawl_rank ADD COLUMN IF NOT EXISTS board_code VARCHAR(50);

ALTER TABLE analysis_result ADD COLUMN IF NOT EXISTS channel_code VARCHAR(50);
ALTER TABLE analysis_result ADD COLUMN IF NOT EXISTS board_code VARCHAR(50);
ALTER TABLE analysis_result ADD COLUMN IF NOT EXISTS snapshot_id BIGINT;
ALTER TABLE analysis_result ADD COLUMN IF NOT EXISTS result_json CLOB;

ALTER TABLE prompt_config ADD COLUMN IF NOT EXISTS output_json_schema CLOB;
ALTER TABLE prompt_config ADD COLUMN IF NOT EXISTS output_example_json CLOB;
ALTER TABLE prompt_config ADD COLUMN IF NOT EXISTS post_process_type VARCHAR(50);
ALTER TABLE prompt_config ADD COLUMN IF NOT EXISTS parse_config_json CLOB;
ALTER TABLE prompt_config ADD COLUMN IF NOT EXISTS input_json_schema CLOB;
ALTER TABLE prompt_config ADD COLUMN IF NOT EXISTS input_example_json CLOB;

DROP TABLE IF EXISTS async_job;

CREATE TABLE async_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_type VARCHAR(50) NOT NULL,
    job_key VARCHAR(255) NOT NULL,
    resource_key VARCHAR(255),
    request_json CLOB,
    status VARCHAR(20) NOT NULL,
    trigger_user_id BIGINT,
    result_ref_type VARCHAR(50),
    result_ref_id BIGINT,
    result_summary VARCHAR(255),
    error_message VARCHAR(500),
    retry_count INT DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_async_job_type_key_time ON async_job(job_type, job_key, create_time);
CREATE INDEX IF NOT EXISTS idx_async_job_resource_key ON async_job(resource_key);
CREATE INDEX IF NOT EXISTS idx_async_job_status_time ON async_job(status, create_time);
CREATE INDEX IF NOT EXISTS idx_async_job_trigger_user_time ON async_job(trigger_user_id, create_time);
