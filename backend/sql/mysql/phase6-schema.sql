-- Phase 6 database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase6-schema.sql

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
