-- Phase 24: asynchronous project ingest + Generation activation truth.
-- Additive only. Do not modify Phase 1-23 history scripts.

DELIMITER $$

DROP PROCEDURE IF EXISTS noval_phase24_add_column_if_missing $$
CREATE PROCEDURE noval_phase24_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND column_name = p_column_name
    ) THEN
        SET @ddl = p_ddl;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase24_add_index_if_missing $$
CREATE PROCEDURE noval_phase24_add_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND index_name = p_index_name
    ) THEN
        SET @ddl = p_ddl;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

CREATE TABLE IF NOT EXISTS ai_project_chapter_head (
    head_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    active_chapter_id BIGINT NULL,
    active_generation_id BIGINT NULL,
    optimistic_version BIGINT NOT NULL DEFAULT 0,
    tombstoned_at DATETIME NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_chapter_head_scope (user_id, project_id, work_id, chapter_no),
    INDEX idx_ai_project_chapter_head_active (project_id, work_id, active_generation_id),
    INDEX idx_ai_project_chapter_head_tombstone (tombstoned_at, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='logical chapter active generation head';

CREATE TABLE IF NOT EXISTS ai_project_ingest_generation (
    generation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    chapter_version INT NOT NULL DEFAULT 1,
    content_hash VARCHAR(128) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PREPARED',
    scene_count INT NOT NULL DEFAULT 0,
    vector_count INT NOT NULL DEFAULT 0,
    entity_count INT NOT NULL DEFAULT 0,
    expected_scene_count INT NULL,
    expected_vector_count INT NULL,
    expected_entity_count INT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME NULL,
    heartbeat_at DATETIME NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(1000) NULL,
    activated_at DATETIME NULL,
    retired_at DATETIME NULL,
    cleanup_status VARCHAR(30) NULL,
    cleanup_error VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_ingest_generation_chapter (chapter_id, status),
    INDEX idx_ai_project_ingest_generation_scope (user_id, project_id, work_id, chapter_no, status),
    INDEX idx_ai_project_ingest_generation_lease (status, lease_expires_at, generation_id),
    INDEX idx_ai_project_ingest_generation_cleanup (cleanup_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='project chapter content generation lifecycle';

CREATE TABLE IF NOT EXISTS ai_project_ingest_outbox (
    outbox_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ingest_job_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    payload JSON NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_ingest_outbox (ingest_job_id, event_type, attempt),
    INDEX idx_ai_project_ingest_outbox_pending (status, available_at, outbox_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='project ingest queue outbox';

CREATE TABLE IF NOT EXISTS ai_project_extraction_candidate (
    candidate_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NULL,
    generation_id BIGINT NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    payload JSON NOT NULL,
    evidence_refs JSON NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    review_note VARCHAR(500) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_extraction_candidate_gen (generation_id, status),
    INDEX idx_ai_project_extraction_candidate_scope (user_id, project_id, work_id, status),
    INDEX idx_ai_project_extraction_candidate_type (project_id, entity_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='project extraction review candidates';

CREATE TABLE IF NOT EXISTS ai_project_tombstone (
    tombstone_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NULL,
    chapter_no INT NULL,
    scope_type VARCHAR(30) NOT NULL,
    cleanup_stage VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
    retry_count INT NOT NULL DEFAULT 0,
    alert_after_at DATETIME NULL,
    alerted_at DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_tombstone_scope (user_id, project_id, work_id, chapter_no),
    INDEX idx_ai_project_tombstone_cleanup (cleanup_stage, alert_after_at, tombstone_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='project/work/chapter tombstone cleanup';

-- Extend existing ingest job table.
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'idempotency_key',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN idempotency_key VARCHAR(200) NULL AFTER chapter_id');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'generation_id',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN generation_id BIGINT NULL AFTER idempotency_key');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'chapter_no',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN chapter_no INT NULL AFTER generation_id');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'content_hash',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN content_hash VARCHAR(128) NULL AFTER chapter_no');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'parser_version',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN parser_version VARCHAR(64) NULL AFTER content_hash');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'attempt',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN attempt INT NOT NULL DEFAULT 1 AFTER parser_version');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'max_attempts',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN max_attempts INT NOT NULL DEFAULT 3 AFTER attempt');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'lease_owner',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN lease_owner VARCHAR(128) NULL AFTER max_attempts');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'lease_expires_at',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN lease_expires_at DATETIME NULL AFTER lease_owner');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'heartbeat_at',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN heartbeat_at DATETIME NULL AFTER lease_expires_at');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'fencing_token',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0 AFTER heartbeat_at');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'next_retry_at',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN next_retry_at DATETIME NULL AFTER fencing_token');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'queue_published_attempt',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN queue_published_attempt INT NOT NULL DEFAULT 0 AFTER next_retry_at');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'stage',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN stage VARCHAR(40) NULL AFTER queue_published_attempt');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'error_code',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN error_code VARCHAR(64) NULL AFTER stage');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'title',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN title VARCHAR(200) NULL AFTER error_code');
CALL noval_phase24_add_column_if_missing('ai_project_ingest_job', 'source_type',
    'ALTER TABLE ai_project_ingest_job ADD COLUMN source_type VARCHAR(40) NULL AFTER title');

CALL noval_phase24_add_index_if_missing('ai_project_ingest_job', 'uk_ai_project_ingest_job_idempotency',
    'ALTER TABLE ai_project_ingest_job ADD UNIQUE INDEX uk_ai_project_ingest_job_idempotency (user_id, idempotency_key)');
CALL noval_phase24_add_index_if_missing('ai_project_ingest_job', 'idx_ai_project_ingest_job_lease',
    'ALTER TABLE ai_project_ingest_job ADD INDEX idx_ai_project_ingest_job_lease (status, lease_expires_at, ingest_job_id)');
CALL noval_phase24_add_index_if_missing('ai_project_ingest_job', 'idx_ai_project_ingest_job_generation',
    'ALTER TABLE ai_project_ingest_job ADD INDEX idx_ai_project_ingest_job_generation (generation_id, status)');
CALL noval_phase24_add_index_if_missing('ai_project_ingest_job', 'idx_ai_project_ingest_job_user_active',
    'ALTER TABLE ai_project_ingest_job ADD INDEX idx_ai_project_ingest_job_user_active (user_id, status, updated_at)');

-- Projection scope columns for generation-aware retrieval.
CALL noval_phase24_add_column_if_missing('ai_project_scene', 'generation_id',
    'ALTER TABLE ai_project_scene ADD COLUMN generation_id BIGINT NULL AFTER chapter_id');
CALL noval_phase24_add_column_if_missing('ai_project_scene', 'chapter_version',
    'ALTER TABLE ai_project_scene ADD COLUMN chapter_version INT NULL AFTER generation_id');
CALL noval_phase24_add_column_if_missing('ai_project_scene', 'status',
    'ALTER TABLE ai_project_scene ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER chapter_version');
CALL noval_phase24_add_index_if_missing('ai_project_scene', 'idx_ai_project_scene_generation',
    'ALTER TABLE ai_project_scene ADD INDEX idx_ai_project_scene_generation (generation_id, status)');

CALL noval_phase24_add_column_if_missing('ai_project_vector_chunk', 'generation_id',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN generation_id BIGINT NULL AFTER chapter_id');
CALL noval_phase24_add_column_if_missing('ai_project_vector_chunk', 'chapter_version',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN chapter_version INT NULL AFTER generation_id');
CALL noval_phase24_add_column_if_missing('ai_project_vector_chunk', 'status',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER chapter_version');
CALL noval_phase24_add_index_if_missing('ai_project_vector_chunk', 'idx_ai_project_vector_generation',
    'ALTER TABLE ai_project_vector_chunk ADD INDEX idx_ai_project_vector_generation (generation_id, status)');

CALL noval_phase24_add_column_if_missing('ai_project_character_state', 'generation_id',
    'ALTER TABLE ai_project_character_state ADD COLUMN generation_id BIGINT NULL AFTER chapter_id');
CALL noval_phase24_add_column_if_missing('ai_project_character_state', 'chapter_version',
    'ALTER TABLE ai_project_character_state ADD COLUMN chapter_version INT NULL AFTER generation_id');
CALL noval_phase24_add_column_if_missing('ai_project_character_state', 'status',
    'ALTER TABLE ai_project_character_state ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER chapter_version');

CALL noval_phase24_add_column_if_missing('ai_project_world_rule', 'generation_id',
    'ALTER TABLE ai_project_world_rule ADD COLUMN generation_id BIGINT NULL AFTER work_id');
CALL noval_phase24_add_column_if_missing('ai_project_world_rule', 'chapter_version',
    'ALTER TABLE ai_project_world_rule ADD COLUMN chapter_version INT NULL AFTER generation_id');
CALL noval_phase24_add_column_if_missing('ai_project_world_rule', 'status_proj',
    'ALTER TABLE ai_project_world_rule ADD COLUMN status_proj VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER chapter_version');

CALL noval_phase24_add_column_if_missing('ai_project_foreshadowing', 'generation_id',
    'ALTER TABLE ai_project_foreshadowing ADD COLUMN generation_id BIGINT NULL AFTER work_id');
CALL noval_phase24_add_column_if_missing('ai_project_foreshadowing', 'chapter_version',
    'ALTER TABLE ai_project_foreshadowing ADD COLUMN chapter_version INT NULL AFTER generation_id');

CALL noval_phase24_add_column_if_missing('ai_project_timeline_event', 'generation_id',
    'ALTER TABLE ai_project_timeline_event ADD COLUMN generation_id BIGINT NULL AFTER chapter_id');
CALL noval_phase24_add_column_if_missing('ai_project_timeline_event', 'chapter_version',
    'ALTER TABLE ai_project_timeline_event ADD COLUMN chapter_version INT NULL AFTER generation_id');
CALL noval_phase24_add_column_if_missing('ai_project_timeline_event', 'status',
    'ALTER TABLE ai_project_timeline_event ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER chapter_version');

-- Deterministic baseline backfill for existing ACTIVE chapters (re-entrant).
-- phase24:h2-backfill-start
INSERT INTO ai_project_ingest_generation(
    user_id, project_id, work_id, chapter_id, chapter_no, chapter_version,
    content_hash, parser_version, status, scene_count, vector_count, entity_count, activated_at
)
SELECT c.user_id, c.project_id, c.work_id, c.chapter_id, c.chapter_no, c.version,
       c.content_hash, 'legacy-baseline', 'ACTIVE', 0, 0, 0, c.updated_at
FROM ai_project_chapter c
WHERE c.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_ingest_generation g
      WHERE g.chapter_id = c.chapter_id AND g.parser_version = 'legacy-baseline'
  );

INSERT INTO ai_project_chapter_head(
    user_id, project_id, work_id, chapter_no, active_chapter_id, active_generation_id, optimistic_version
)
SELECT c.user_id, c.project_id, c.work_id, c.chapter_no, c.chapter_id, g.generation_id, 0
FROM ai_project_chapter c
JOIN ai_project_ingest_generation g
  ON g.chapter_id = c.chapter_id AND g.parser_version = 'legacy-baseline' AND g.status = 'ACTIVE'
WHERE c.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_chapter_head h
      WHERE h.user_id = c.user_id AND h.project_id = c.project_id
        AND h.work_id = c.work_id AND h.chapter_no = c.chapter_no
  );
-- phase24:h2-backfill-end

DROP PROCEDURE IF EXISTS noval_phase24_add_column_if_missing;
DROP PROCEDURE IF EXISTS noval_phase24_add_index_if_missing;
