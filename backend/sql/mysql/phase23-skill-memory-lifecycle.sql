-- Phase 23: governed Skill lifecycle and trusted Memory lifecycle.
-- This upgrade is additive and preserves legacy status values for existing API clients.

DELIMITER $$

DROP PROCEDURE IF EXISTS noval_phase23_add_column_if_missing $$
CREATE PROCEDURE noval_phase23_add_column_if_missing(
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
        SET @phase23_ddl = p_ddl;
        PREPARE phase23_stmt FROM @phase23_ddl;
        EXECUTE phase23_stmt;
        DEALLOCATE PREPARE phase23_stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase23_add_index_if_missing $$
CREATE PROCEDURE noval_phase23_add_index_if_missing(
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
        SET @phase23_ddl = p_ddl;
        PREPARE phase23_stmt FROM @phase23_ddl;
        EXECUTE phase23_stmt;
        DEALLOCATE PREPARE phase23_stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_phase23_add_single_column_unique_if_missing $$
CREATE PROCEDURE noval_phase23_add_single_column_unique_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = p_table_name
        GROUP BY index_name
        HAVING MIN(non_unique) = 0
           AND COUNT(*) = 1
           AND MAX(CASE WHEN column_name = p_column_name THEN 1 ELSE 0 END) = 1
    ) THEN
        SET @phase23_ddl = p_ddl;
        PREPARE phase23_stmt FROM @phase23_ddl;
        EXECUTE phase23_stmt;
        DEALLOCATE PREPARE phase23_stmt;
    END IF;
END $$

DELIMITER ;

-- Skill candidates retain status as the legacy API projection. lifecycle_status
-- is the new source of truth: DRAFT / APPROVED / ACTIVE / REVOKED.
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'lifecycle_status',
    'ALTER TABLE ai_skill_candidate ADD COLUMN lifecycle_status VARCHAR(30) NULL AFTER status');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'version',
    'ALTER TABLE ai_skill_candidate ADD COLUMN version VARCHAR(80) NULL AFTER title');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'content_hash',
    'ALTER TABLE ai_skill_candidate ADD COLUMN content_hash CHAR(64) NULL AFTER content');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'input_schema_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN input_schema_json JSON NULL AFTER eval_result_json');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'output_schema_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN output_schema_json JSON NULL AFTER input_schema_json');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'allowed_tools_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN allowed_tools_json JSON NULL AFTER output_schema_json');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'rollout_policy_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN rollout_policy_json JSON NULL AFTER allowed_tools_json');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'approved_by',
    'ALTER TABLE ai_skill_candidate ADD COLUMN approved_by BIGINT NULL AFTER review_note');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'approved_at',
    'ALTER TABLE ai_skill_candidate ADD COLUMN approved_at TIMESTAMP NULL AFTER approved_by');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'revoked_by',
    'ALTER TABLE ai_skill_candidate ADD COLUMN revoked_by BIGINT NULL AFTER approved_at');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'revoked_at',
    'ALTER TABLE ai_skill_candidate ADD COLUMN revoked_at TIMESTAMP NULL AFTER revoked_by');
CALL noval_phase23_add_column_if_missing('ai_skill_candidate', 'rollback_version',
    'ALTER TABLE ai_skill_candidate ADD COLUMN rollback_version VARCHAR(80) NULL AFTER revoked_at');
CALL noval_phase23_add_index_if_missing('ai_skill_candidate', 'idx_ai_skill_candidate_lifecycle',
    'ALTER TABLE ai_skill_candidate ADD INDEX idx_ai_skill_candidate_lifecycle (skill_id, lifecycle_status, updated_at)');

UPDATE ai_skill_candidate
SET lifecycle_status = CASE UPPER(TRIM(COALESCE(status, 'PENDING')))
    WHEN 'PENDING' THEN 'DRAFT'
    WHEN 'DRAFT' THEN 'DRAFT'
    WHEN 'APPROVED' THEN 'APPROVED'
    WHEN 'ROLLED_BACK' THEN 'REVOKED'
    WHEN 'PUBLISHED' THEN 'ACTIVE'
    WHEN 'ACTIVE' THEN 'ACTIVE'
    WHEN 'DISABLED' THEN 'REVOKED'
    WHEN 'REVOKED' THEN 'REVOKED'
    WHEN 'REJECTED' THEN 'REVOKED'
    ELSE 'DRAFT'
END
WHERE lifecycle_status IS NULL OR lifecycle_status NOT IN ('DRAFT', 'APPROVED', 'ACTIVE', 'REVOKED');

UPDATE ai_skill_candidate
SET version = COALESCE(NULLIF(TRIM(version), ''), NULLIF(JSON_UNQUOTE(JSON_EXTRACT(eval_result_json, '$.version')), ''), CONCAT('legacy-', id))
WHERE version IS NULL OR TRIM(version) = '';

UPDATE ai_skill_candidate
SET content_hash = SHA2(CONVERT(content USING utf8mb4), 256)
WHERE content IS NOT NULL AND (content_hash IS NULL OR CHAR_LENGTH(content_hash) <> 64);

UPDATE ai_skill_candidate
SET input_schema_json = COALESCE(input_schema_json, JSON_EXTRACT(eval_result_json, '$.inputSchema'), JSON_EXTRACT(eval_result_json, '$.input_schema'))
WHERE input_schema_json IS NULL AND eval_result_json IS NOT NULL;
UPDATE ai_skill_candidate
SET output_schema_json = COALESCE(output_schema_json, JSON_EXTRACT(eval_result_json, '$.outputSchema'), JSON_EXTRACT(eval_result_json, '$.output_schema'))
WHERE output_schema_json IS NULL AND eval_result_json IS NOT NULL;
UPDATE ai_skill_candidate
SET allowed_tools_json = COALESCE(allowed_tools_json, JSON_EXTRACT(eval_result_json, '$.allowedTools'), JSON_EXTRACT(eval_result_json, '$.allowed_tools'), JSON_ARRAY())
WHERE allowed_tools_json IS NULL;
UPDATE ai_skill_candidate
SET rollout_policy_json = COALESCE(rollout_policy_json, JSON_EXTRACT(eval_result_json, '$.rolloutPolicy'), JSON_EXTRACT(eval_result_json, '$.rollout_policy'))
WHERE rollout_policy_json IS NULL AND eval_result_json IS NOT NULL;

CREATE TABLE IF NOT EXISTS ai_skill_lifecycle_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id VARCHAR(120) NOT NULL,
    candidate_id BIGINT NULL,
    related_candidate_id BIGINT NULL,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    version VARCHAR(80) NULL,
    content_hash CHAR(64) NULL,
    actor_user_id BIGINT NULL,
    source_trace_id VARCHAR(80) NULL,
    details_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_skill_lifecycle_audit_candidate (candidate_id, created_at),
    INDEX idx_ai_skill_lifecycle_audit_skill (skill_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='immutable governed Skill lifecycle audit';

-- Runtime Skill rows are one active projection per skill_id. A Run pins this
-- immutable version/hash in its trace before execution; later activation does
-- not mutate that Run's snapshot.
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'content_hash',
    'ALTER TABLE ai_runtime_skill ADD COLUMN content_hash CHAR(64) NULL AFTER content');
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'input_schema_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN input_schema_json JSON NULL AFTER output_contract_json');
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'output_schema_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN output_schema_json JSON NULL AFTER input_schema_json');
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'rollout_policy_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN rollout_policy_json JSON NULL AFTER output_schema_json');
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'activated_by',
    'ALTER TABLE ai_runtime_skill ADD COLUMN activated_by BIGINT NULL AFTER source_trace_id');
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'activated_at',
    'ALTER TABLE ai_runtime_skill ADD COLUMN activated_at TIMESTAMP NULL AFTER activated_by');
CALL noval_phase23_add_column_if_missing('ai_runtime_skill', 'rollback_version',
    'ALTER TABLE ai_runtime_skill ADD COLUMN rollback_version VARCHAR(80) NULL AFTER activated_at');
CALL noval_phase23_add_index_if_missing('ai_runtime_skill', 'idx_ai_runtime_skill_hash',
    'ALTER TABLE ai_runtime_skill ADD INDEX idx_ai_runtime_skill_hash (skill_id, content_hash)');

UPDATE ai_runtime_skill runtime_skill
LEFT JOIN ai_skill_candidate candidate ON candidate.id = runtime_skill.candidate_id
SET runtime_skill.version = COALESCE(NULLIF(TRIM(runtime_skill.version), ''), candidate.version, CONCAT('legacy-', runtime_skill.id))
WHERE runtime_skill.version IS NULL OR TRIM(runtime_skill.version) = '';
UPDATE ai_runtime_skill
SET content_hash = SHA2(CONVERT(content USING utf8mb4), 256)
WHERE content IS NOT NULL AND (content_hash IS NULL OR CHAR_LENGTH(content_hash) <> 64);
UPDATE ai_runtime_skill runtime_skill
LEFT JOIN ai_skill_candidate candidate ON candidate.id = runtime_skill.candidate_id
SET runtime_skill.input_schema_json = COALESCE(runtime_skill.input_schema_json, candidate.input_schema_json),
    runtime_skill.output_schema_json = COALESCE(runtime_skill.output_schema_json, candidate.output_schema_json),
    runtime_skill.rollout_policy_json = COALESCE(runtime_skill.rollout_policy_json, candidate.rollout_policy_json),
    runtime_skill.allowed_tools_json = COALESCE(runtime_skill.allowed_tools_json, candidate.allowed_tools_json, JSON_ARRAY())
WHERE runtime_skill.candidate_id IS NOT NULL;

-- A legacy projection is trusted only when it is still the exact projection of
-- its candidate. Self-consistent re-hashing of drifted runtime content must not
-- turn an out-of-band edit into an approved Skill.
UPDATE ai_runtime_skill runtime_skill
LEFT JOIN ai_skill_candidate candidate ON candidate.id = runtime_skill.candidate_id
SET runtime_skill.status = 'DISABLED', runtime_skill.disabled_at = CURRENT_TIMESTAMP
WHERE runtime_skill.status = 'ACTIVE'
  AND (
      candidate.id IS NULL
      OR runtime_skill.skill_id <> candidate.skill_id
      OR NOT (runtime_skill.version <=> candidate.version)
      OR NOT (runtime_skill.content_hash <=> candidate.content_hash)
      OR NOT (runtime_skill.input_schema_json <=> candidate.input_schema_json)
      OR NOT (runtime_skill.output_schema_json <=> candidate.output_schema_json)
      OR NOT (runtime_skill.allowed_tools_json <=> candidate.allowed_tools_json)
      OR NOT (runtime_skill.rollout_policy_json <=> candidate.rollout_policy_json)
      OR NOT (runtime_skill.eval_result_json <=> candidate.eval_result_json)
  );

-- Historical installations created through additive upgrades may not have the
-- Phase 12 unique key. Keep the newest ACTIVE projection (then newest row),
-- remove duplicates, and restore the one-row-per-skill invariant.
DELETE stale
FROM ai_runtime_skill stale
JOIN ai_runtime_skill keeper
  ON keeper.skill_id = stale.skill_id
 AND (
      (CASE WHEN keeper.status = 'ACTIVE' THEN 1 ELSE 0 END)
          > (CASE WHEN stale.status = 'ACTIVE' THEN 1 ELSE 0 END)
   OR (
      (CASE WHEN keeper.status = 'ACTIVE' THEN 1 ELSE 0 END)
          = (CASE WHEN stale.status = 'ACTIVE' THEN 1 ELSE 0 END)
      AND COALESCE(keeper.updated_at, TIMESTAMP('1970-01-01 00:00:00'))
          > COALESCE(stale.updated_at, TIMESTAMP('1970-01-01 00:00:00'))
   )
   OR (
      (CASE WHEN keeper.status = 'ACTIVE' THEN 1 ELSE 0 END)
          = (CASE WHEN stale.status = 'ACTIVE' THEN 1 ELSE 0 END)
      AND COALESCE(keeper.updated_at, TIMESTAMP('1970-01-01 00:00:00'))
          = COALESCE(stale.updated_at, TIMESTAMP('1970-01-01 00:00:00'))
      AND keeper.id > stale.id
   )
 );

CALL noval_phase23_add_single_column_unique_if_missing('ai_runtime_skill', 'skill_id',
    'ALTER TABLE ai_runtime_skill ADD UNIQUE KEY uk_ai_runtime_skill_skill_phase23 (skill_id)');

-- Align candidate lifecycle with the surviving runtime projection and close
-- duplicate ACTIVE candidates before governed row locking takes over.
UPDATE ai_skill_candidate candidate
JOIN ai_runtime_skill runtime_skill ON runtime_skill.candidate_id = candidate.id
SET candidate.lifecycle_status = 'ACTIVE', candidate.status = 'PUBLISHED'
WHERE runtime_skill.status = 'ACTIVE';

UPDATE ai_skill_candidate candidate
JOIN ai_runtime_skill runtime_skill
  ON runtime_skill.skill_id = candidate.skill_id AND runtime_skill.status = 'ACTIVE'
SET candidate.lifecycle_status = CASE WHEN candidate.id = runtime_skill.candidate_id THEN 'ACTIVE' ELSE 'REVOKED' END,
    candidate.status = CASE WHEN candidate.id = runtime_skill.candidate_id THEN 'PUBLISHED' ELSE 'ROLLED_BACK' END
WHERE candidate.lifecycle_status = 'ACTIVE';

-- An ACTIVE candidate without the matching ACTIVE runtime projection was never
-- actually published (or lost its legacy projection). Return it to the last
-- reviewable state so an administrator can publish it again.
UPDATE ai_skill_candidate candidate
LEFT JOIN ai_runtime_skill runtime_skill
  ON runtime_skill.candidate_id = candidate.id
 AND runtime_skill.skill_id = candidate.skill_id
 AND runtime_skill.status = 'ACTIVE'
SET candidate.lifecycle_status = 'APPROVED', candidate.status = 'APPROVED'
WHERE candidate.lifecycle_status = 'ACTIVE' AND runtime_skill.id IS NULL;

UPDATE ai_skill_candidate candidate
JOIN (
    SELECT skill_id, MAX(id) AS keep_id
    FROM ai_skill_candidate
    WHERE lifecycle_status = 'ACTIVE'
    GROUP BY skill_id
    HAVING COUNT(*) > 1
) duplicate_active ON duplicate_active.skill_id = candidate.skill_id
SET candidate.lifecycle_status = 'REVOKED', candidate.status = 'ROLLED_BACK'
WHERE candidate.lifecycle_status = 'ACTIVE' AND candidate.id <> duplicate_active.keep_id;

UPDATE ai_runtime_skill runtime_skill
LEFT JOIN ai_skill_candidate candidate ON candidate.id = runtime_skill.candidate_id
SET runtime_skill.status = 'DISABLED', runtime_skill.disabled_at = CURRENT_TIMESTAMP
WHERE runtime_skill.status = 'ACTIVE' AND candidate.id IS NULL;

-- Seed only relationships that legacy status proves were actually published.
-- APPROVED-only candidates are deliberately excluded from rollback history.
INSERT INTO ai_skill_lifecycle_audit(
    skill_id, candidate_id, related_candidate_id, event_type, previous_status, new_status,
    version, content_hash, source_trace_id, details_json
)
SELECT previous.skill_id, previous.id, current.id, 'REPLACED', 'ACTIVE', 'REVOKED',
       previous.version, previous.content_hash, previous.source_trace_id,
       JSON_OBJECT('source', 'phase23-migration')
FROM ai_skill_candidate current
JOIN ai_skill_candidate previous ON previous.skill_id = current.skill_id
WHERE current.lifecycle_status = 'ACTIVE'
  AND previous.status = 'ROLLED_BACK'
  AND previous.id = (
      SELECT previous_version.id
      FROM ai_skill_candidate previous_version
      WHERE previous_version.skill_id = current.skill_id
        AND previous_version.status = 'ROLLED_BACK'
      ORDER BY previous_version.updated_at DESC, previous_version.id DESC
      LIMIT 1
  )
  AND NOT EXISTS (
      SELECT 1 FROM ai_skill_lifecycle_audit audit
      WHERE audit.candidate_id = previous.id
        AND audit.related_candidate_id = current.id
        AND audit.event_type = 'REPLACED'
  );

INSERT INTO ai_skill_lifecycle_audit(
    skill_id, candidate_id, event_type, previous_status, new_status,
    version, content_hash, source_trace_id, details_json
)
SELECT candidate.skill_id, candidate.id, 'ACTIVATED', 'APPROVED', 'ACTIVE',
       candidate.version, candidate.content_hash, candidate.source_trace_id,
       JSON_OBJECT('source', 'phase23-migration')
FROM ai_skill_candidate candidate
WHERE candidate.lifecycle_status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_skill_lifecycle_audit audit
      WHERE audit.candidate_id = candidate.id
        AND audit.event_type IN ('ACTIVATED', 'ROLLED_BACK_TO')
  );

-- Memory candidate status is preserved for compatibility while lifecycle_status
-- is canonical. Model-extracted candidates never become CONFIRMED implicitly.
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'lifecycle_status',
    'ALTER TABLE ai_memory_candidate ADD COLUMN lifecycle_status VARCHAR(30) NULL AFTER status');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'fact_key',
    'ALTER TABLE ai_memory_candidate ADD COLUMN fact_key VARCHAR(160) NULL AFTER memory_type');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'candidate_key',
    'ALTER TABLE ai_memory_candidate ADD COLUMN candidate_key VARCHAR(200) NULL AFTER fact_key');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'provenance_json',
    'ALTER TABLE ai_memory_candidate ADD COLUMN provenance_json JSON NULL AFTER source_trace_id');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'evidence_json',
    'ALTER TABLE ai_memory_candidate ADD COLUMN evidence_json JSON NULL AFTER provenance_json');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'source_evidence_ids_json',
    'ALTER TABLE ai_memory_candidate ADD COLUMN source_evidence_ids_json JSON NULL AFTER evidence_json');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'source_chapter_versions_json',
    'ALTER TABLE ai_memory_candidate ADD COLUMN source_chapter_versions_json JSON NULL AFTER source_evidence_ids_json');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'index_generation',
    'ALTER TABLE ai_memory_candidate ADD COLUMN index_generation VARCHAR(80) NULL AFTER source_chapter_versions_json');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'extractor_version',
    'ALTER TABLE ai_memory_candidate ADD COLUMN extractor_version VARCHAR(80) NULL AFTER index_generation');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'supersedes_id',
    'ALTER TABLE ai_memory_candidate ADD COLUMN supersedes_id BIGINT NULL AFTER extractor_version');
CALL noval_phase23_add_column_if_missing('ai_memory_candidate', 'conflicts_with_id',
    'ALTER TABLE ai_memory_candidate ADD COLUMN conflicts_with_id BIGINT NULL AFTER supersedes_id');
CALL noval_phase23_add_index_if_missing('ai_memory_candidate', 'idx_ai_memory_candidate_fact_lifecycle',
    'ALTER TABLE ai_memory_candidate ADD INDEX idx_ai_memory_candidate_fact_lifecycle (user_id, project_id, fact_key, lifecycle_status)');
CALL noval_phase23_add_index_if_missing('ai_memory_candidate', 'uk_ai_memory_candidate_idempotency',
    'ALTER TABLE ai_memory_candidate ADD UNIQUE INDEX uk_ai_memory_candidate_idempotency (user_id, candidate_key)');

UPDATE ai_memory_candidate
SET lifecycle_status = CASE LOWER(TRIM(COALESCE(status, 'candidate')))
    WHEN 'candidate' THEN 'CANDIDATE'
    WHEN 'pending' THEN 'CANDIDATE'
    WHEN 'approved' THEN 'CANDIDATE'
    WHEN 'confirmed' THEN 'CONFIRMED'
    WHEN 'rejected' THEN 'REJECTED'
    WHEN 'superseded' THEN 'SUPERSEDED'
    WHEN 'expired' THEN 'STALE'
    WHEN 'stale' THEN 'STALE'
    ELSE 'CANDIDATE'
END
WHERE lifecycle_status IS NULL OR lifecycle_status NOT IN ('CANDIDATE', 'CONFIRMED', 'REJECTED', 'SUPERSEDED', 'STALE');

-- The new memory item provenance permits deterministic revalidation after a
-- chapter generation changes without silently loading stale facts.
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'lifecycle_status',
    'ALTER TABLE ai_memory_item ADD COLUMN lifecycle_status VARCHAR(30) NULL AFTER status');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'fact_key',
    'ALTER TABLE ai_memory_item ADD COLUMN fact_key VARCHAR(160) NULL AFTER memory_type');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'provenance_json',
    'ALTER TABLE ai_memory_item ADD COLUMN provenance_json JSON NULL AFTER source_trace_id');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'evidence_json',
    'ALTER TABLE ai_memory_item ADD COLUMN evidence_json JSON NULL AFTER provenance_json');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'source_evidence_ids_json',
    'ALTER TABLE ai_memory_item ADD COLUMN source_evidence_ids_json JSON NULL AFTER evidence_json');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'source_chapter_versions_json',
    'ALTER TABLE ai_memory_item ADD COLUMN source_chapter_versions_json JSON NULL AFTER source_evidence_ids_json');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'index_generation',
    'ALTER TABLE ai_memory_item ADD COLUMN index_generation VARCHAR(80) NULL AFTER source_chapter_versions_json');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'extractor_version',
    'ALTER TABLE ai_memory_item ADD COLUMN extractor_version VARCHAR(80) NULL AFTER index_generation');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'supersedes_id',
    'ALTER TABLE ai_memory_item ADD COLUMN supersedes_id BIGINT NULL AFTER extractor_version');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'confirmed_by',
    'ALTER TABLE ai_memory_item ADD COLUMN confirmed_by BIGINT NULL AFTER supersedes_id');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'confirmed_at',
    'ALTER TABLE ai_memory_item ADD COLUMN confirmed_at TIMESTAMP NULL AFTER confirmed_by');
CALL noval_phase23_add_column_if_missing('ai_memory_item', 'stale_at',
    'ALTER TABLE ai_memory_item ADD COLUMN stale_at TIMESTAMP NULL AFTER confirmed_at');
CALL noval_phase23_add_index_if_missing('ai_memory_item', 'idx_ai_memory_item_fact_lifecycle',
    'ALTER TABLE ai_memory_item ADD INDEX idx_ai_memory_item_fact_lifecycle (user_id, project_id, fact_key, lifecycle_status)');

UPDATE ai_memory_item
SET lifecycle_status = CASE LOWER(TRIM(COALESCE(status, 'confirmed')))
    WHEN 'candidate' THEN 'CANDIDATE'
    WHEN 'pending' THEN 'CANDIDATE'
    WHEN 'confirmed' THEN 'CONFIRMED'
    WHEN 'approved' THEN 'CONFIRMED'
    WHEN 'rejected' THEN 'REJECTED'
    WHEN 'superseded' THEN 'SUPERSEDED'
    WHEN 'stale' THEN 'STALE'
    WHEN 'expired' THEN 'STALE'
    WHEN 'deleted' THEN 'STALE'
    ELSE 'STALE'
END
WHERE lifecycle_status IS NULL OR lifecycle_status NOT IN ('CANDIDATE', 'CONFIRMED', 'REJECTED', 'SUPERSEDED', 'STALE');

CREATE TABLE IF NOT EXISTS ai_memory_lifecycle_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memory_id BIGINT NULL,
    candidate_id BIGINT NULL,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    actor_user_id BIGINT NULL,
    source_trace_id VARCHAR(80) NULL,
    details_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_memory_lifecycle_audit_memory (memory_id, created_at),
    INDEX idx_ai_memory_lifecycle_audit_candidate (candidate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='immutable trusted memory lifecycle audit';

DROP PROCEDURE IF EXISTS noval_phase23_add_column_if_missing;
DROP PROCEDURE IF EXISTS noval_phase23_add_index_if_missing;
DROP PROCEDURE IF EXISTS noval_phase23_add_single_column_unique_if_missing;
