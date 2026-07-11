CREATE TABLE IF NOT EXISTS ai_memory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    conversation_id VARCHAR(80),
    scope VARCHAR(30) NOT NULL,
    memory_type VARCHAR(60) NOT NULL,
    content CLOB NOT NULL,
    summary CLOB,
    confidence DOUBLE,
    status VARCHAR(30) NOT NULL DEFAULT 'confirmed',
    source_trace_id VARCHAR(80),
    expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_item_user_scope ON ai_memory_item(user_id, scope, status);
CREATE INDEX IF NOT EXISTS idx_ai_memory_item_project ON ai_memory_item(project_id, scope, status);
CREATE INDEX IF NOT EXISTS idx_ai_memory_item_conversation ON ai_memory_item(conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_memory_item_trace ON ai_memory_item(source_trace_id);

ALTER TABLE ai_memory_candidate ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE ai_memory_candidate ALTER COLUMN candidate_type VARCHAR(80);
ALTER TABLE ai_memory_candidate ALTER COLUMN status SET DEFAULT 'candidate';

ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(80);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS scope VARCHAR(30);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS memory_type VARCHAR(60);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS summary CLOB;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS confidence DOUBLE;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_ai_memory_candidate_user_scope ON ai_memory_candidate(user_id, scope, status);
CREATE INDEX IF NOT EXISTS idx_ai_memory_candidate_conversation ON ai_memory_candidate(conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_memory_candidate_expires ON ai_memory_candidate(status, expires_at);

CREATE TABLE IF NOT EXISTS ai_conversation_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    summary CLOB NOT NULL,
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_conversation_summary UNIQUE(conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_summary_project ON ai_conversation_summary(project_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_ai_conversation_summary_trace ON ai_conversation_summary(source_trace_id);
