CREATE TABLE IF NOT EXISTS ai_chat_run (
    run_id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    status VARCHAR(20)
);

ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS trigger_message_id BIGINT;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS project_id BIGINT;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS deleted TINYINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS progress_phase VARCHAR(40);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS response_message_id BIGINT;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS request_id VARCHAR(80);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS attempt_no INT NOT NULL DEFAULT 1;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS parent_run_id VARCHAR(64);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS next_sequence_no BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS snapshot_sequence_no BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS agent_version VARCHAR(80);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(40);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS resource_budget_json CLOB;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS legacy_conversation_id VARCHAR(160);
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS update_time TIMESTAMP;
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS migration_order_at TIMESTAMP
    GENERATED ALWAYS AS (COALESCE(queued_at, create_time, TIMESTAMP '1970-01-01 00:00:00'));
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS migration_activity_at TIMESTAMP
    GENERATED ALWAYS AS (COALESCE(update_time, finished_at, queued_at, create_time, TIMESTAMP '1970-01-01 00:00:00'));
ALTER TABLE ai_chat_run ADD COLUMN IF NOT EXISTS migration_legacy_key VARCHAR(160)
    GENERATED ALWAYS AS (CASE
        WHEN TRIM(COALESCE(legacy_conversation_id, conversation_id, '')) = ''
            THEN CONCAT('__EMPTY__:', run_id)
        ELSE COALESCE(legacy_conversation_id, conversation_id)
    END);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_chat_run_request_attempt
    ON ai_chat_run(request_id, attempt_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_chat_run_user_idempotency
    ON ai_chat_run(user_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_lease
    ON ai_chat_run(status, lease_expires_at, heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_migration_order
    ON ai_chat_run(deleted, migration_order_at, run_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_migration_repair
    ON ai_chat_run(deleted, trigger_message_id, migration_order_at, run_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_migration_response
    ON ai_chat_run(deleted, response_message_id, status, progress_phase, migration_order_at, run_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_run_legacy_list
    ON ai_chat_run(user_id, deleted, project_id, migration_legacy_key, migration_activity_at, run_id);

CREATE TABLE IF NOT EXISTS ai_conversation (
    conversation_id VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)),
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_message_id BIGINT,
    last_run_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMP,
    CONSTRAINT uk_ai_conversation_scope UNIQUE(conversation_id, user_id, project_scope_id)
);

ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS project_id BIGINT;
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS project_scope_id BIGINT
    GENERATED ALWAYS AS (COALESCE(project_id, -1));
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS title VARCHAR(200) NOT NULL DEFAULT 'New conversation';
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS last_message_id BIGINT;
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS last_run_id VARCHAR(64);
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;
ALTER TABLE ai_conversation ADD CONSTRAINT IF NOT EXISTS uk_ai_conversation_scope
    UNIQUE(conversation_id, user_id, project_scope_id);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_user_updated
    ON ai_conversation(user_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_ai_conversation_project_updated
    ON ai_conversation(user_id, project_id, status, updated_at);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)),
    run_id VARCHAR(64),
    role VARCHAR(20) NOT NULL,
    content CLOB,
    content_json CLOB,
    token_count INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_ai_chat_message_conversation_scope
        FOREIGN KEY (conversation_id, user_id, project_scope_id)
        REFERENCES ai_conversation(conversation_id, user_id, project_scope_id)
);

ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS project_id BIGINT;
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS project_scope_id BIGINT
    GENERATED ALWAYS AS (COALESCE(project_id, -1));
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS run_id VARCHAR(64);
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS content CLOB;
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS content_json CLOB;
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS token_count INT;
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_chat_message ADD COLUMN IF NOT EXISTS deleted TINYINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_message ADD CONSTRAINT IF NOT EXISTS fk_ai_chat_message_conversation_scope
    FOREIGN KEY (conversation_id, user_id, project_scope_id)
    REFERENCES ai_conversation(conversation_id, user_id, project_scope_id);

CREATE INDEX IF NOT EXISTS idx_ai_chat_message_conversation
    ON ai_chat_message(conversation_id, deleted, message_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_message_run
    ON ai_chat_message(run_id, deleted);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_chat_message_request_role
    ON ai_chat_message(conversation_id, run_id, role);

CREATE TABLE IF NOT EXISTS ai_chat_run_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_idempotency_key VARCHAR(200) NOT NULL,
    payload CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_chat_run_event_sequence UNIQUE(run_id, sequence_no),
    CONSTRAINT uk_ai_chat_run_event_idempotency UNIQUE(run_id, event_idempotency_key)
);

ALTER TABLE ai_chat_run_event ADD COLUMN IF NOT EXISTS run_id VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE ai_chat_run_event ADD COLUMN IF NOT EXISTS sequence_no BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run_event ADD COLUMN IF NOT EXISTS event_type VARCHAR(20) NOT NULL DEFAULT 'PROGRESS';
ALTER TABLE ai_chat_run_event ADD COLUMN IF NOT EXISTS event_idempotency_key VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE ai_chat_run_event ADD COLUMN IF NOT EXISTS payload CLOB;
ALTER TABLE ai_chat_run_event ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_chat_run_event ADD CONSTRAINT IF NOT EXISTS uk_ai_chat_run_event_sequence
    UNIQUE(run_id, sequence_no);
ALTER TABLE ai_chat_run_event ADD CONSTRAINT IF NOT EXISTS uk_ai_chat_run_event_idempotency
    UNIQUE(run_id, event_idempotency_key);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_event_created
    ON ai_chat_run_event(run_id, created_at);

CREATE TABLE IF NOT EXISTS ai_chat_run_outbox (
    outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_idempotency_key VARCHAR(200) NOT NULL,
    payload CLOB,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    dead_retry_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_chat_run_outbox_idempotency UNIQUE(run_id, event_idempotency_key)
);

ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS event_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS run_id VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS sequence_no BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS event_type VARCHAR(20) NOT NULL DEFAULT 'PROGRESS';
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS event_idempotency_key VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS payload CLOB;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS dead_retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000);
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_chat_run_outbox ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_chat_run_outbox ADD CONSTRAINT IF NOT EXISTS uk_ai_chat_run_outbox_idempotency
    UNIQUE(run_id, event_idempotency_key);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_pending
    ON ai_chat_run_outbox(status, available_at, outbox_id);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_dispatch_pending_attempt
    ON ai_chat_run_outbox(event_type, status, attempt_count, available_at, outbox_id);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_dispatch_reclaim_attempt
    ON ai_chat_run_outbox(event_type, status, attempt_count, updated_at, outbox_id);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_pending_execution_recovery
    ON ai_chat_run(status, deleted, update_time, run_id);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_execute_recovery
    ON ai_chat_run_outbox(event_type, status, updated_at, run_id, attempt_count, outbox_id);

CREATE INDEX IF NOT EXISTS idx_ai_chat_run_outbox_terminal_dead_recovery
    ON ai_chat_run_outbox(event_type, status, dead_retry_count, updated_at, outbox_id);

CREATE TABLE IF NOT EXISTS ai_chat_run_admission_guard (
    mode VARCHAR(40) PRIMARY KEY,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ai_chat_run_admission_guard ADD COLUMN IF NOT EXISTS updated_at
    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

MERGE INTO ai_chat_run_admission_guard(mode, updated_at) KEY(mode)
VALUES('FAST', CURRENT_TIMESTAMP);
MERGE INTO ai_chat_run_admission_guard(mode, updated_at) KEY(mode)
VALUES('DEEP', CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS ai_conversation_legacy_map (
    map_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)),
    legacy_conversation_id VARCHAR(160) NOT NULL,
    canonical_conversation_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_conversation_legacy_scope
        UNIQUE(user_id, project_scope_id, legacy_conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_legacy_canonical
    ON ai_conversation_legacy_map(canonical_conversation_id);

CREATE TABLE IF NOT EXISTS ai_conversation_migration_state (
    state_key VARCHAR(80) PRIMARY KEY,
    last_queued_at TIMESTAMP,
    last_run_id VARCHAR(64),
    processed_run_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_conversation_migration_lock (
    lock_name VARCHAR(80) PRIMARY KEY,
    lock_owner VARCHAR(80),
    lease_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
