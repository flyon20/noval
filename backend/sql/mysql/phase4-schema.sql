-- Phase 4 database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase4-schema.sql

CREATE TABLE IF NOT EXISTS prompt_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    prompt_type VARCHAR(50) NOT NULL COMMENT '提示词类型',
    prompt_name VARCHAR(100) NOT NULL COMMENT '配置名称',
    prompt_content TEXT NOT NULL COMMENT '提示词内容',
    model_name VARCHAR(50) DEFAULT 'dify' COMMENT '模型名称',
    temperature DECIMAL(3,2) DEFAULT 0.70 COMMENT '温度参数',
    max_tokens INT DEFAULT 2000 COMMENT '最大Token',
    status TINYINT DEFAULT 1 COMMENT '状态 0禁用 1启用',
    is_default TINYINT DEFAULT 0 COMMENT '是否默认 0否 1是',
    dify_workflow_id VARCHAR(100) COMMENT 'Dify工作流ID',
    dify_api_key_ref VARCHAR(100) COMMENT 'Dify API密钥引用ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    UNIQUE KEY uk_prompt_type_name (prompt_type, prompt_name),
    INDEX idx_prompt_type (prompt_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词配置表';

CREATE TABLE IF NOT EXISTS analysis_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT COMMENT '用户ID',
    platform VARCHAR(20) NOT NULL COMMENT '平台',
    book_id BIGINT NOT NULL COMMENT '书籍ID',
    analysis_type VARCHAR(50) NOT NULL COMMENT '分析类型 deconstruct/structure/plot',
    chapter_count INT DEFAULT 3 COMMENT '分析章节数',
    prompt_config_id BIGINT COMMENT '提示词配置ID',
    model_name VARCHAR(50) COMMENT '模型名称',
    result_content MEDIUMTEXT COMMENT '分析结果',
    token_used INT DEFAULT 0 COMMENT '消耗Token数',
    cost_time BIGINT DEFAULT 0 COMMENT '耗时毫秒',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    INDEX idx_user_id (user_id),
    INDEX idx_book_type (book_id, analysis_type),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析结果表';

