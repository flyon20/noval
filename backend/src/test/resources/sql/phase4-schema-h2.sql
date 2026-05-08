DROP TABLE IF EXISTS analysis_result;
DROP TABLE IF EXISTS prompt_config;

CREATE TABLE prompt_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt_type VARCHAR(50) NOT NULL,
    prompt_name VARCHAR(100) NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    owner_user_id BIGINT,
    source_prompt_config_id BIGINT,
    prompt_content CLOB NOT NULL,
    model_name VARCHAR(50) DEFAULT 'dify',
    temperature DECIMAL(3,2) DEFAULT 0.70,
    max_tokens INT DEFAULT 6000,
    status TINYINT DEFAULT 1,
    is_default TINYINT DEFAULT 0,
    dify_workflow_id VARCHAR(100),
    dify_api_key_ref VARCHAR(100),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_prompt_type_name ON prompt_config(prompt_type, prompt_name);
CREATE INDEX IF NOT EXISTS idx_scope_type ON prompt_config(scope_type);
CREATE INDEX IF NOT EXISTS idx_owner_user_id ON prompt_config(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_source_prompt_config_id ON prompt_config(source_prompt_config_id);

CREATE TABLE analysis_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    platform VARCHAR(20) NOT NULL,
    book_id BIGINT NOT NULL,
    analysis_type VARCHAR(50) NOT NULL,
    chapter_count INT DEFAULT 3,
    prompt_config_id BIGINT,
    model_name VARCHAR(50),
    result_content CLOB,
    result_json CLOB,
    token_used INT DEFAULT 0,
    cost_time BIGINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
