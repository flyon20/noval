-- Phase 4 database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase4-schema.sql

CREATE TABLE IF NOT EXISTS prompt_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'prompt config id',
    prompt_type VARCHAR(50) NOT NULL COMMENT 'prompt type',
    prompt_name VARCHAR(100) NOT NULL COMMENT 'prompt name',
    prompt_content TEXT NOT NULL COMMENT 'prompt content',
    model_name VARCHAR(50) DEFAULT 'dify' COMMENT 'model name',
    temperature DECIMAL(3,2) DEFAULT 0.70 COMMENT 'temperature',
    max_tokens INT DEFAULT 2000 COMMENT 'max tokens',
    status TINYINT DEFAULT 1 COMMENT '0 disabled 1 enabled',
    is_default TINYINT DEFAULT 0 COMMENT '0 no 1 yes',
    dify_workflow_id VARCHAR(100) COMMENT 'dify workflow id',
    dify_api_key_ref VARCHAR(100) COMMENT 'dify api key ref',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_prompt_type_name (prompt_type, prompt_name),
    INDEX idx_prompt_type (prompt_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='prompt config';

CREATE TABLE IF NOT EXISTS analysis_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'analysis result id',
    user_id BIGINT COMMENT 'user id',
    platform VARCHAR(20) NOT NULL COMMENT 'platform',
    book_id BIGINT NOT NULL COMMENT 'book id',
    analysis_type VARCHAR(50) NOT NULL COMMENT 'analysis type',
    chapter_count INT DEFAULT 3 COMMENT 'chapter count',
    prompt_config_id BIGINT COMMENT 'prompt config id',
    model_name VARCHAR(50) COMMENT 'model name',
    result_content MEDIUMTEXT COMMENT 'analysis text content',
    result_json JSON COMMENT 'analysis structured result',
    token_used INT DEFAULT 0 COMMENT 'token used',
    cost_time BIGINT DEFAULT 0 COMMENT 'cost time milliseconds',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    INDEX idx_user_id (user_id),
    INDEX idx_book_type (book_id, analysis_type),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='analysis result';
