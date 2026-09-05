-- Phase 18 Task 1: additive conversation/message/event truth schema.
-- This migration only expands the schema. Legacy tables and columns remain available.

CREATE TABLE IF NOT EXISTS ai_conversation (
    conversation_id VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)) STORED,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_message_id BIGINT NULL,
    last_run_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    archived_at DATETIME NULL,
    UNIQUE KEY uk_ai_conversation_scope (conversation_id, user_id, project_scope_id),
    INDEX idx_ai_conversation_user_updated (user_id, status, updated_at),
    INDEX idx_ai_conversation_project_updated (user_id, project_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='durable user conversation';

CREATE TABLE IF NOT EXISTS ai_chat_message (
    message_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)) STORED,
    run_id VARCHAR(64) NULL,
    role VARCHAR(20) NOT NULL,
    content MEDIUMTEXT NULL,
    content_json JSON NULL,
    token_count INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_ai_chat_message_conversation (conversation_id, deleted, message_id),
    INDEX idx_ai_chat_message_run (run_id, deleted),
    UNIQUE KEY uk_ai_chat_message_request_role (conversation_id, run_id, role),
    CONSTRAINT fk_ai_chat_message_conversation_scope
        FOREIGN KEY (conversation_id, user_id, project_scope_id)
        REFERENCES ai_conversation(conversation_id, user_id, project_scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='complete ordered chat message history';

CREATE TABLE IF NOT EXISTS ai_chat_run_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_idempotency_key VARCHAR(200) NOT NULL,
    payload JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_chat_run_event_sequence (run_id, sequence_no),
    UNIQUE KEY uk_ai_chat_run_event_idempotency (run_id, event_idempotency_key),
    INDEX idx_ai_chat_run_event_created (run_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='append-only chat run events';

CREATE TABLE IF NOT EXISTS ai_chat_run_outbox (
    outbox_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_idempotency_key VARCHAR(200) NOT NULL,
    payload JSON NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_chat_run_outbox_idempotency (run_id, event_idempotency_key),
    INDEX idx_ai_chat_run_outbox_pending (status, available_at, outbox_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='transactional chat run event outbox';

CREATE TABLE IF NOT EXISTS ai_chat_run_admission_guard (
    mode VARCHAR(40) PRIMARY KEY,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='serializes chat run admission by execution mode';

CREATE TABLE IF NOT EXISTS ai_conversation_legacy_map (
    map_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)) STORED,
    legacy_conversation_id VARCHAR(160) NOT NULL,
    canonical_conversation_id VARCHAR(80) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_conversation_legacy_scope (user_id, project_scope_id, legacy_conversation_id),
    INDEX idx_ai_conversation_legacy_canonical (canonical_conversation_id),
    CONSTRAINT fk_ai_conversation_legacy_canonical
        FOREIGN KEY (canonical_conversation_id) REFERENCES ai_conversation(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='stable legacy conversation id mapping';

CREATE TABLE IF NOT EXISTS ai_conversation_migration_state (
    state_key VARCHAR(80) PRIMARY KEY,
    last_queued_at DATETIME NULL,
    last_run_id VARCHAR(64) NULL,
    processed_run_count BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='reentrant conversation backfill high-water state';

CREATE TABLE IF NOT EXISTS ai_conversation_migration_lock (
    lock_name VARCHAR(80) PRIMARY KEY,
    lock_owner VARCHAR(80) NULL,
    lease_until DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='distributed conversation migration operation lock';

DELIMITER $$

DROP PROCEDURE IF EXISTS noval_phase18_add_column_if_missing $$
CREATE PROCEDURE noval_phase18_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND column_name = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase18_add_index_if_missing $$
CREATE PROCEDURE noval_phase18_add_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_index_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND index_name = p_index_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase18_add_constraint_if_missing $$
CREATE PROCEDURE noval_phase18_add_constraint_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_constraint_name VARCHAR(128),
    IN p_constraint_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = DATABASE() AND table_name = p_table_name
          AND constraint_name = p_constraint_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_constraint_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase18_assert_empty_if_column_missing $$
CREATE PROCEDURE noval_phase18_assert_empty_if_column_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128)
)
BEGIN
    DECLARE v_message VARCHAR(255);
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND column_name = p_column_name
    ) THEN
        SET @phase18_row_count = 0;
        SET @ddl = CONCAT('SELECT COUNT(1) INTO @phase18_row_count FROM `', p_table_name, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        IF @phase18_row_count > 0 THEN
            SET v_message = CONCAT(
                'phase18 refuses populated partial table ', p_table_name,
                ': missing required column ', p_column_name
            );
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
        END IF;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase18_add_primary_key_if_missing $$
CREATE PROCEDURE noval_phase18_add_primary_key_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_primary_key_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_schema = DATABASE() AND table_name = p_table_name
          AND constraint_type = 'PRIMARY KEY'
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_primary_key_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase18_ensure_auto_increment $$
CREATE PROCEDURE noval_phase18_ensure_auto_increment(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND column_name = p_column_name
          AND extra NOT LIKE '%auto_increment%'
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_table_name, '` MODIFY COLUMN `', p_column_name,
            '` BIGINT NOT NULL AUTO_INCREMENT'
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

CALL noval_phase18_assert_empty_if_column_missing('ai_conversation', 'conversation_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_conversation', 'user_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_message', 'message_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_message', 'conversation_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_message', 'user_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_event', 'event_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_event', 'run_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_event', 'sequence_no');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_event', 'event_idempotency_key');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_outbox', 'outbox_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_outbox', 'run_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_outbox', 'event_idempotency_key');
CALL noval_phase18_assert_empty_if_column_missing('ai_chat_run_admission_guard', 'mode');

CALL noval_phase18_add_column_if_missing(
    'ai_conversation', 'conversation_id', 'conversation_id VARCHAR(80) NOT NULL'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_conversation', 'PRIMARY KEY (`conversation_id`)'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_message', 'message_id', 'message_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_chat_message', 'PRIMARY KEY (`message_id`)'
);
CALL noval_phase18_ensure_auto_increment('ai_chat_message', 'message_id');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_event', 'event_id', 'event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_chat_run_event', 'PRIMARY KEY (`event_id`)'
);
CALL noval_phase18_ensure_auto_increment('ai_chat_run_event', 'event_id');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'outbox_id', 'outbox_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_chat_run_outbox', 'PRIMARY KEY (`outbox_id`)'
);
CALL noval_phase18_ensure_auto_increment('ai_chat_run_outbox', 'outbox_id');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_admission_guard', 'mode', 'mode VARCHAR(40) NOT NULL'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_chat_run_admission_guard', 'PRIMARY KEY (`mode`)'
);

CALL noval_phase18_add_column_if_missing('ai_conversation', 'user_id', 'user_id BIGINT NOT NULL DEFAULT 0');
CALL noval_phase18_add_column_if_missing('ai_conversation', 'project_id', 'project_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_conversation',
    'project_scope_id',
    'project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)) STORED'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation', 'title', 'title VARCHAR(200) NOT NULL DEFAULT ''New conversation'''
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation', 'status', 'status VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'''
);
CALL noval_phase18_add_column_if_missing('ai_conversation', 'last_message_id', 'last_message_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing('ai_conversation', 'last_run_id', 'last_run_id VARCHAR(64) NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_conversation', 'created_at', 'created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation',
    'updated_at',
    'updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_column_if_missing('ai_conversation', 'archived_at', 'archived_at DATETIME NULL');
CALL noval_phase18_add_index_if_missing(
    'ai_conversation',
    'uk_ai_conversation_scope',
    'UNIQUE INDEX `uk_ai_conversation_scope` (`conversation_id`, `user_id`, `project_scope_id`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_conversation',
    'idx_ai_conversation_user_updated',
    'INDEX `idx_ai_conversation_user_updated` (`user_id`, `status`, `updated_at`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_conversation',
    'idx_ai_conversation_project_updated',
    'INDEX `idx_ai_conversation_project_updated` (`user_id`, `project_id`, `status`, `updated_at`)'
);

CALL noval_phase18_add_column_if_missing(
    'ai_chat_message', 'conversation_id', 'conversation_id VARCHAR(80) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing('ai_chat_message', 'user_id', 'user_id BIGINT NOT NULL DEFAULT 0');
CALL noval_phase18_add_column_if_missing('ai_chat_message', 'project_id', 'project_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_message',
    'project_scope_id',
    'project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)) STORED'
);
CALL noval_phase18_add_column_if_missing('ai_chat_message', 'run_id', 'run_id VARCHAR(64) NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_message', 'role', 'role VARCHAR(20) NOT NULL DEFAULT ''USER'''
);
CALL noval_phase18_add_column_if_missing('ai_chat_message', 'content', 'content MEDIUMTEXT NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_message', 'content_json', 'content_json JSON NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_message', 'token_count', 'token_count INT NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_message', 'created_at', 'created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_message', 'deleted', 'deleted TINYINT(1) NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_message',
    'idx_ai_chat_message_conversation',
    'INDEX `idx_ai_chat_message_conversation` (`conversation_id`, `deleted`, `message_id`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_message',
    'idx_ai_chat_message_run',
    'INDEX `idx_ai_chat_message_run` (`run_id`, `deleted`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_message',
    'uk_ai_chat_message_request_role',
    'UNIQUE INDEX `uk_ai_chat_message_request_role` (`conversation_id`, `run_id`, `role`)'
);
CALL noval_phase18_add_constraint_if_missing(
    'ai_chat_message',
    'fk_ai_chat_message_conversation_scope',
    'CONSTRAINT `fk_ai_chat_message_conversation_scope` FOREIGN KEY (`conversation_id`, `user_id`, `project_scope_id`) REFERENCES `ai_conversation` (`conversation_id`, `user_id`, `project_scope_id`)'
);

CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_event', 'run_id', 'run_id VARCHAR(64) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_event', 'sequence_no', 'sequence_no BIGINT NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_event', 'event_type', 'event_type VARCHAR(20) NOT NULL DEFAULT ''PROGRESS'''
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_event',
    'event_idempotency_key',
    'event_idempotency_key VARCHAR(200) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing('ai_chat_run_event', 'payload', 'payload JSON NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_event', 'created_at', 'created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run_event',
    'uk_ai_chat_run_event_sequence',
    'UNIQUE INDEX `uk_ai_chat_run_event_sequence` (`run_id`, `sequence_no`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run_event',
    'uk_ai_chat_run_event_idempotency',
    'UNIQUE INDEX `uk_ai_chat_run_event_idempotency` (`run_id`, `event_idempotency_key`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run_event',
    'idx_ai_chat_run_event_created',
    'INDEX `idx_ai_chat_run_event_created` (`run_id`, `created_at`)'
);

CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'event_id', 'event_id BIGINT NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'run_id', 'run_id VARCHAR(64) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'sequence_no', 'sequence_no BIGINT NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'event_type', 'event_type VARCHAR(20) NOT NULL DEFAULT ''PROGRESS'''
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox',
    'event_idempotency_key',
    'event_idempotency_key VARCHAR(200) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing('ai_chat_run_outbox', 'payload', 'payload JSON NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'status', 'status VARCHAR(20) NOT NULL DEFAULT ''PENDING'''
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'attempt_count', 'attempt_count INT NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox',
    'available_at',
    'available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'published_at', 'published_at DATETIME NULL'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox', 'last_error', 'last_error VARCHAR(1000) NULL'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox',
    'created_at',
    'created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_outbox',
    'updated_at',
    'updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run_outbox',
    'uk_ai_chat_run_outbox_idempotency',
    'UNIQUE INDEX `uk_ai_chat_run_outbox_idempotency` (`run_id`, `event_idempotency_key`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_pending',
    'INDEX `idx_ai_chat_run_outbox_pending` (`status`, `available_at`, `outbox_id`)'
);

CALL noval_phase18_add_column_if_missing(
    'ai_chat_run_admission_guard',
    'updated_at',
    'updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
);

INSERT INTO ai_chat_run_admission_guard(mode, updated_at)
VALUES('FAST', CURRENT_TIMESTAMP), ('DEEP', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE updated_at = updated_at;

CALL noval_phase18_assert_empty_if_column_missing('ai_conversation_legacy_map', 'map_id');
CALL noval_phase18_assert_empty_if_column_missing('ai_conversation_legacy_map', 'user_id');
CALL noval_phase18_assert_empty_if_column_missing(
    'ai_conversation_legacy_map', 'legacy_conversation_id'
);
CALL noval_phase18_assert_empty_if_column_missing(
    'ai_conversation_legacy_map', 'canonical_conversation_id'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map', 'map_id', 'map_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_conversation_legacy_map', 'PRIMARY KEY (`map_id`)'
);
CALL noval_phase18_ensure_auto_increment('ai_conversation_legacy_map', 'map_id');
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map', 'user_id', 'user_id BIGINT NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map', 'project_id', 'project_id BIGINT NULL'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map',
    'project_scope_id',
    'project_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(project_id, -1)) STORED'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map',
    'legacy_conversation_id',
    'legacy_conversation_id VARCHAR(160) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map',
    'canonical_conversation_id',
    'canonical_conversation_id VARCHAR(80) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_legacy_map',
    'created_at',
    'created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
);
CALL noval_phase18_add_index_if_missing(
    'ai_conversation_legacy_map',
    'uk_ai_conversation_legacy_scope',
    'UNIQUE INDEX `uk_ai_conversation_legacy_scope` (`user_id`, `project_scope_id`, `legacy_conversation_id`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_conversation_legacy_map',
    'idx_ai_conversation_legacy_canonical',
    'INDEX `idx_ai_conversation_legacy_canonical` (`canonical_conversation_id`)'
);
CALL noval_phase18_add_constraint_if_missing(
    'ai_conversation_legacy_map',
    'fk_ai_conversation_legacy_canonical',
    'CONSTRAINT `fk_ai_conversation_legacy_canonical` FOREIGN KEY (`canonical_conversation_id`) REFERENCES `ai_conversation` (`conversation_id`)'
);

CALL noval_phase18_assert_empty_if_column_missing('ai_conversation_migration_state', 'state_key');
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_migration_state', 'state_key', 'state_key VARCHAR(80) NOT NULL'
);
CALL noval_phase18_add_primary_key_if_missing(
    'ai_conversation_migration_state', 'PRIMARY KEY (`state_key`)'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_migration_state', 'last_queued_at', 'last_queued_at DATETIME NULL'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_migration_state', 'last_run_id', 'last_run_id VARCHAR(64) NULL'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_migration_state',
    'processed_run_count',
    'processed_run_count BIGINT NOT NULL DEFAULT 0'
);
CALL noval_phase18_add_column_if_missing(
    'ai_conversation_migration_state',
    'updated_at',
    'updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
);

CALL noval_phase18_add_column_if_missing('ai_chat_run', 'user_id', 'user_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'project_id', 'project_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run', 'conversation_id', 'conversation_id VARCHAR(80) NOT NULL DEFAULT '''''
);
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'deleted', 'deleted TINYINT NOT NULL DEFAULT 0');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'status', 'status VARCHAR(20) NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run', 'progress_phase', 'progress_phase VARCHAR(40) NULL'
);
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'trigger_message_id', 'trigger_message_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'response_message_id', 'response_message_id BIGINT NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'request_id', 'request_id VARCHAR(80) NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'attempt_no', 'attempt_no INT NOT NULL DEFAULT 1');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'parent_run_id', 'parent_run_id VARCHAR(64) NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'lease_owner', 'lease_owner VARCHAR(128) NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'lease_expires_at', 'lease_expires_at DATETIME NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'fencing_token', 'fencing_token BIGINT NOT NULL DEFAULT 0');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'heartbeat_at', 'heartbeat_at DATETIME NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'next_sequence_no', 'next_sequence_no BIGINT NOT NULL DEFAULT 0');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'snapshot_sequence_no', 'snapshot_sequence_no BIGINT NOT NULL DEFAULT 0');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'agent_version', 'agent_version VARCHAR(80) NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'execution_mode', 'execution_mode VARCHAR(40) NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'resource_budget_json', 'resource_budget_json JSON NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'idempotency_key', 'idempotency_key VARCHAR(160) NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run', 'legacy_conversation_id', 'legacy_conversation_id VARCHAR(160) NULL'
);
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'queued_at', 'queued_at DATETIME NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'finished_at', 'finished_at DATETIME NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'create_time', 'create_time DATETIME NULL');
CALL noval_phase18_add_column_if_missing('ai_chat_run', 'update_time', 'update_time DATETIME NULL');
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run',
    'migration_order_at',
    'migration_order_at DATETIME GENERATED ALWAYS AS (COALESCE(queued_at, create_time, ''1970-01-01 00:00:00'')) STORED'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run',
    'migration_activity_at',
    'migration_activity_at DATETIME GENERATED ALWAYS AS (COALESCE(update_time, finished_at, queued_at, create_time, ''1970-01-01 00:00:00'')) STORED'
);
CALL noval_phase18_add_column_if_missing(
    'ai_chat_run',
    'migration_legacy_key',
    'migration_legacy_key VARCHAR(160) GENERATED ALWAYS AS (CASE WHEN TRIM(COALESCE(legacy_conversation_id, conversation_id, '''')) = '''' THEN CONCAT(''__EMPTY__:'', run_id) ELSE COALESCE(legacy_conversation_id, conversation_id) END) STORED'
);

CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'uk_ai_chat_run_request_attempt',
    'UNIQUE INDEX `uk_ai_chat_run_request_attempt` (`request_id`, `attempt_no`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'uk_ai_chat_run_user_idempotency',
    'UNIQUE INDEX `uk_ai_chat_run_user_idempotency` (`user_id`, `idempotency_key`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'idx_ai_chat_run_lease',
    'INDEX `idx_ai_chat_run_lease` (`status`, `lease_expires_at`, `heartbeat_at`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'idx_ai_chat_run_migration_order',
    'INDEX `idx_ai_chat_run_migration_order` (`deleted`, `migration_order_at`, `run_id`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'idx_ai_chat_run_migration_repair',
    'INDEX `idx_ai_chat_run_migration_repair` (`deleted`, `trigger_message_id`, `migration_order_at`, `run_id`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'idx_ai_chat_run_migration_response',
    'INDEX `idx_ai_chat_run_migration_response` (`deleted`, `response_message_id`, `status`, `progress_phase`, `migration_order_at`, `run_id`)'
);
CALL noval_phase18_add_index_if_missing(
    'ai_chat_run',
    'idx_ai_chat_run_legacy_list',
    'INDEX `idx_ai_chat_run_legacy_list` (`user_id`, `deleted`, `project_id`, `migration_legacy_key`, `migration_activity_at`, `run_id`)'
);

DROP PROCEDURE IF EXISTS noval_phase18_add_index_if_missing;
DROP PROCEDURE IF EXISTS noval_phase18_add_constraint_if_missing;
DROP PROCEDURE IF EXISTS noval_phase18_add_primary_key_if_missing;
DROP PROCEDURE IF EXISTS noval_phase18_ensure_auto_increment;
DROP PROCEDURE IF EXISTS noval_phase18_assert_empty_if_column_missing;
DROP PROCEDURE IF EXISTS noval_phase18_add_column_if_missing;
