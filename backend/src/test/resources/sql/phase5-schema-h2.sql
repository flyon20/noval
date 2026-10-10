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
CREATE INDEX IF NOT EXISTS idx_crawl_rank_snapshot_lookup
    ON crawl_rank(snapshot_id, deleted, platform, rank_no, id);

CREATE TABLE IF NOT EXISTS crawler_rank_refresh_commit (
    idempotency_hash VARCHAR(64) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    channel_code VARCHAR(50) NOT NULL,
    board_code VARCHAR(50) NOT NULL,
    snapshot_id BIGINT NOT NULL,
    snapshot_time TIMESTAMP NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    reused TINYINT NOT NULL DEFAULT 0,
    refresh_limited TINYINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS crawler_rank_refresh_fence (
    rank_board_id BIGINT PRIMARY KEY,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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

DROP TABLE IF EXISTS async_job_dedup_archive;
DROP TABLE IF EXISTS async_job;
DROP TABLE IF EXISTS analysis_result_search_doc;

CREATE TABLE analysis_result_search_doc (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    analysis_result_id BIGINT NOT NULL,
    user_id BIGINT,
    platform VARCHAR(20) NOT NULL,
    book_id BIGINT,
    book_name VARCHAR(255),
    analysis_type VARCHAR(50),
    channel_code VARCHAR(50),
    board_code VARCHAR(50),
    chapter_count INT,
    model_name VARCHAR(100),
    search_text CLOB NOT NULL,
    structured_terms CLOB,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_history_search_result ON analysis_result_search_doc(analysis_result_id);
CREATE INDEX IF NOT EXISTS idx_history_search_user_time ON analysis_result_search_doc(user_id, deleted, create_time, id);

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
    queue_published_at TIMESTAMP,
    queue_published_attempt INT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_async_job_type_key_time ON async_job(job_type, job_key, create_time);
CREATE UNIQUE INDEX IF NOT EXISTS uk_async_job_type_key_active ON async_job(job_type, job_key, deleted);
CREATE INDEX IF NOT EXISTS idx_async_job_resource_key ON async_job(resource_key);
CREATE INDEX IF NOT EXISTS idx_async_job_status_time ON async_job(status, create_time);
CREATE INDEX IF NOT EXISTS idx_async_job_trigger_user_time ON async_job(trigger_user_id, create_time);

CREATE TABLE IF NOT EXISTS async_job_dedup_archive (
    archive_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    migration_key VARCHAR(64) NOT NULL,
    source_async_job_id BIGINT NOT NULL,
    survivor_async_job_id BIGINT NOT NULL,
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
    retry_count INT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    queue_published_at TIMESTAMP,
    queue_published_attempt INT,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    deleted TINYINT,
    survivor_status VARCHAR(20) NOT NULL,
    survivor_create_time TIMESTAMP,
    survivor_update_time TIMESTAMP,
    selection_policy VARCHAR(100) NOT NULL,
    archive_reason VARCHAR(100) NOT NULL,
    archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_async_job_dedup_archive_source
    ON async_job_dedup_archive(migration_key, source_async_job_id);
CREATE INDEX IF NOT EXISTS idx_async_job_dedup_archive_survivor
    ON async_job_dedup_archive(survivor_async_job_id);
CREATE INDEX IF NOT EXISTS idx_async_job_dedup_archive_group
    ON async_job_dedup_archive(job_type, job_key, deleted);

DROP TABLE IF EXISTS ai_chat_run;
CREATE TABLE ai_chat_run (
    run_id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    conversation_id VARCHAR(80) NOT NULL,
    question CLOB,
    request_json CLOB,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress_phase VARCHAR(40),
    progress_message VARCHAR(500),
    answer CLOB,
    result_json CLOB,
    trace_id VARCHAR(80),
    source_count INT DEFAULT 0,
    error_message VARCHAR(1000),
    cancel_requested BOOLEAN DEFAULT FALSE,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    queued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_user_conversation ON ai_chat_run(user_id, conversation_id, deleted, queued_at);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_user_status ON ai_chat_run(user_id, status, deleted, queued_at);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_project ON ai_chat_run(project_id, deleted, queued_at);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_trace ON ai_chat_run(trace_id);
