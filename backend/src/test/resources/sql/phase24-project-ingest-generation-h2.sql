-- Phase 24 H2 mirror for project ingest generation.

CREATE TABLE IF NOT EXISTS ai_project_chapter_head (
    head_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    active_chapter_id BIGINT NULL,
    active_generation_id BIGINT NULL,
    optimistic_version BIGINT NOT NULL DEFAULT 0,
    tombstoned_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_chapter_head_scope UNIQUE(user_id, project_id, work_id, chapter_no)
);

CREATE INDEX IF NOT EXISTS idx_ai_project_chapter_head_active ON ai_project_chapter_head(project_id, work_id, active_generation_id);
CREATE INDEX IF NOT EXISTS idx_ai_project_chapter_head_tombstone ON ai_project_chapter_head(tombstoned_at, project_id);

CREATE TABLE IF NOT EXISTS ai_project_ingest_generation (
    generation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    lease_expires_at TIMESTAMP NULL,
    heartbeat_at TIMESTAMP NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(1000) NULL,
    activated_at TIMESTAMP NULL,
    retired_at TIMESTAMP NULL,
    cleanup_status VARCHAR(30) NULL,
    cleanup_error VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_generation_chapter ON ai_project_ingest_generation(chapter_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_generation_scope ON ai_project_ingest_generation(user_id, project_id, work_id, chapter_no, status);
CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_generation_lease ON ai_project_ingest_generation(status, lease_expires_at, generation_id);

CREATE TABLE IF NOT EXISTS ai_project_ingest_outbox (
    outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ingest_job_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    payload CLOB NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_ingest_outbox UNIQUE(ingest_job_id, event_type, attempt)
);

CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_outbox_pending ON ai_project_ingest_outbox(status, available_at, outbox_id);

CREATE TABLE IF NOT EXISTS ai_project_extraction_candidate (
    candidate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NULL,
    generation_id BIGINT NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    payload CLOB NOT NULL,
    evidence_refs CLOB NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    review_note VARCHAR(500) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_project_extraction_candidate_gen ON ai_project_extraction_candidate(generation_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_project_extraction_candidate_scope ON ai_project_extraction_candidate(user_id, project_id, work_id, status);

CREATE TABLE IF NOT EXISTS ai_project_tombstone (
    tombstone_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NULL,
    chapter_no INT NULL,
    scope_type VARCHAR(30) NOT NULL,
    cleanup_stage VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
    retry_count INT NOT NULL DEFAULT 0,
    alert_after_at TIMESTAMP NULL,
    alerted_at TIMESTAMP NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_project_tombstone_scope ON ai_project_tombstone(user_id, project_id, work_id, chapter_no);
CREATE INDEX IF NOT EXISTS idx_ai_project_tombstone_cleanup ON ai_project_tombstone(cleanup_stage, alert_after_at, tombstone_id);

ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(200);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS chapter_no INT;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS content_hash VARCHAR(128);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS parser_version VARCHAR(64);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS attempt INT DEFAULT 1;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS max_attempts INT DEFAULT 3;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS fencing_token BIGINT DEFAULT 0;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS queue_published_attempt INT DEFAULT 0;
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS stage VARCHAR(40);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS title VARCHAR(200);
ALTER TABLE ai_project_ingest_job ADD COLUMN IF NOT EXISTS source_type VARCHAR(40);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_project_ingest_job_idempotency ON ai_project_ingest_job(user_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_job_lease ON ai_project_ingest_job(status, lease_expires_at, ingest_job_id);
CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_job_generation ON ai_project_ingest_job(generation_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_project_ingest_job_user_active ON ai_project_ingest_job(user_id, status, updated_at);

ALTER TABLE ai_project_scene ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_scene ADD COLUMN IF NOT EXISTS chapter_version INT;
ALTER TABLE ai_project_scene ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_ai_project_scene_generation ON ai_project_scene(generation_id, status);

ALTER TABLE ai_project_vector_chunk ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_vector_chunk ADD COLUMN IF NOT EXISTS chapter_version INT;
ALTER TABLE ai_project_vector_chunk ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_ai_project_vector_generation ON ai_project_vector_chunk(generation_id, status);

ALTER TABLE ai_project_character_state ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_character_state ADD COLUMN IF NOT EXISTS chapter_version INT;
ALTER TABLE ai_project_character_state ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'ACTIVE';

ALTER TABLE ai_project_world_rule ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_world_rule ADD COLUMN IF NOT EXISTS chapter_version INT;
ALTER TABLE ai_project_world_rule ADD COLUMN IF NOT EXISTS status_proj VARCHAR(30) DEFAULT 'ACTIVE';

ALTER TABLE ai_project_foreshadowing ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_foreshadowing ADD COLUMN IF NOT EXISTS chapter_version INT;

ALTER TABLE ai_project_timeline_event ADD COLUMN IF NOT EXISTS generation_id BIGINT;
ALTER TABLE ai_project_timeline_event ADD COLUMN IF NOT EXISTS chapter_version INT;
ALTER TABLE ai_project_timeline_event ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'ACTIVE';

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
