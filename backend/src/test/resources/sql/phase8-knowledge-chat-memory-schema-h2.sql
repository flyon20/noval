DROP TABLE IF EXISTS knowledge_chat_memory;

CREATE TABLE knowledge_chat_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    summary CLOB,
    last_question VARCHAR(1000),
    last_answer CLOB,
    last_book_name VARCHAR(255),
    last_intent VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_chat_memory_conversation ON knowledge_chat_memory(conversation_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chat_memory_user_update ON knowledge_chat_memory(user_id, update_time);
