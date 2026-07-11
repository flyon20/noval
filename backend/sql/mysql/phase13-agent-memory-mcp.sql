CREATE TABLE IF NOT EXISTS ai_memory_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    conversation_id VARCHAR(80),
    scope VARCHAR(30) NOT NULL,
    memory_type VARCHAR(60) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    summary MEDIUMTEXT,
    confidence DOUBLE,
    status VARCHAR(30) NOT NULL DEFAULT 'confirmed',
    source_trace_id VARCHAR(80),
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    INDEX idx_ai_memory_item_user_scope (user_id, scope, status),
    INDEX idx_ai_memory_item_project (project_id, scope, status),
    INDEX idx_ai_memory_item_conversation (conversation_id, status),
    INDEX idx_ai_memory_item_trace (source_trace_id)
);

ALTER TABLE ai_memory_candidate
    MODIFY COLUMN project_id BIGINT NULL,
    MODIFY COLUMN candidate_type VARCHAR(80) NULL,
    MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'candidate';

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'conversation_id'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN conversation_id VARCHAR(80) NULL AFTER user_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'scope'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN scope VARCHAR(30) NULL AFTER conversation_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'memory_type'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN memory_type VARCHAR(60) NULL AFTER scope'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'summary'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN summary MEDIUMTEXT NULL AFTER content'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'confidence'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN confidence DOUBLE NULL AFTER summary'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'expires_at'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN expires_at TIMESTAMP NULL AFTER source_trace_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND column_name = 'deleted_at'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD COLUMN deleted_at TIMESTAMP NULL AFTER updated_at'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND index_name = 'idx_ai_memory_candidate_user_scope'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD INDEX idx_ai_memory_candidate_user_scope (user_id, scope, status)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND index_name = 'idx_ai_memory_candidate_conversation'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD INDEX idx_ai_memory_candidate_conversation (conversation_id, status)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_memory_candidate'
          AND index_name = 'idx_ai_memory_candidate_expires'
    ),
    'DO 0',
    'ALTER TABLE ai_memory_candidate ADD INDEX idx_ai_memory_candidate_expires (status, expires_at)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS ai_conversation_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    summary MEDIUMTEXT NOT NULL,
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_conversation_summary (conversation_id, user_id),
    INDEX idx_ai_conversation_summary_project (project_id, updated_at),
    INDEX idx_ai_conversation_summary_trace (source_trace_id)
);
