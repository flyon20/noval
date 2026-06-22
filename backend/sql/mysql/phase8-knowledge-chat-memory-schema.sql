-- Phase 8 knowledge chat memory schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase8-knowledge-chat-memory-schema.sql

CREATE TABLE IF NOT EXISTS knowledge_chat_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'chat memory id',
    conversation_id VARCHAR(64) NOT NULL COMMENT 'stable conversation id',
    user_id BIGINT NOT NULL COMMENT 'owner user id',
    summary TEXT COMMENT 'compressed conversation summary',
    last_question VARCHAR(1000) COMMENT 'last user question',
    last_answer TEXT COMMENT 'last assistant answer',
    last_book_name VARCHAR(255) COMMENT 'last referenced book name',
    last_intent VARCHAR(50) COMMENT 'last routed intent',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_knowledge_chat_memory_conversation (conversation_id),
    INDEX idx_knowledge_chat_memory_user_update (user_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge chat memory';
