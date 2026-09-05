ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(30);
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS version VARCHAR(80);
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS input_schema_json CLOB;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS output_schema_json CLOB;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS allowed_tools_json CLOB;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS rollout_policy_json CLOB;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS approved_by BIGINT;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS revoked_by BIGINT;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;
ALTER TABLE ai_skill_candidate ADD COLUMN IF NOT EXISTS rollback_version VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_ai_skill_candidate_lifecycle ON ai_skill_candidate(skill_id, lifecycle_status, updated_at);
UPDATE ai_skill_candidate SET lifecycle_status = CASE UPPER(status)
    WHEN 'PENDING' THEN 'DRAFT' WHEN 'DRAFT' THEN 'DRAFT'
    WHEN 'APPROVED' THEN 'APPROVED'
    WHEN 'PUBLISHED' THEN 'ACTIVE' WHEN 'ACTIVE' THEN 'ACTIVE'
    ELSE 'REVOKED' END
WHERE lifecycle_status IS NULL;

ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS input_schema_json CLOB;
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS output_schema_json CLOB;
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS rollout_policy_json CLOB;
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS activated_by BIGINT;
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS activated_at TIMESTAMP;
ALTER TABLE ai_runtime_skill ADD COLUMN IF NOT EXISTS rollback_version VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_ai_runtime_skill_hash ON ai_runtime_skill(skill_id, content_hash);

DELETE FROM ai_runtime_skill
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY skill_id
                   ORDER BY CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END DESC, updated_at DESC, id DESC
               ) AS duplicate_rank
        FROM ai_runtime_skill
    ) ranked_runtime
    WHERE duplicate_rank > 1
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_runtime_skill_skill_phase23 ON ai_runtime_skill(skill_id);

UPDATE ai_runtime_skill
SET status = 'DISABLED', disabled_at = CURRENT_TIMESTAMP
WHERE status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_skill_candidate candidate
      WHERE candidate.id = ai_runtime_skill.candidate_id
        AND candidate.skill_id = ai_runtime_skill.skill_id
        AND candidate.version = ai_runtime_skill.version
        AND candidate.content_hash = ai_runtime_skill.content_hash
  );

UPDATE ai_skill_candidate
SET lifecycle_status = 'ACTIVE', status = 'PUBLISHED'
WHERE id IN (SELECT candidate_id FROM ai_runtime_skill WHERE status = 'ACTIVE' AND candidate_id IS NOT NULL);
UPDATE ai_skill_candidate
SET lifecycle_status = 'REVOKED', status = 'ROLLED_BACK'
WHERE lifecycle_status = 'ACTIVE'
  AND skill_id IN (SELECT skill_id FROM ai_runtime_skill WHERE status = 'ACTIVE')
  AND id NOT IN (SELECT candidate_id FROM ai_runtime_skill WHERE status = 'ACTIVE' AND candidate_id IS NOT NULL);
UPDATE ai_skill_candidate
SET lifecycle_status = 'APPROVED', status = 'APPROVED'
WHERE lifecycle_status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_runtime_skill runtime_skill
      WHERE runtime_skill.status = 'ACTIVE'
        AND runtime_skill.candidate_id = ai_skill_candidate.id
        AND runtime_skill.skill_id = ai_skill_candidate.skill_id
  );
UPDATE ai_runtime_skill
SET status = 'DISABLED', disabled_at = CURRENT_TIMESTAMP
WHERE status = 'ACTIVE'
  AND (candidate_id IS NULL OR candidate_id NOT IN (SELECT id FROM ai_skill_candidate));

ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(30);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS fact_key VARCHAR(160);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS candidate_key VARCHAR(200);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS provenance_json CLOB;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS evidence_json CLOB;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS source_evidence_ids_json CLOB;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS source_chapter_versions_json CLOB;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS index_generation VARCHAR(80);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(80);
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS supersedes_id BIGINT;
ALTER TABLE ai_memory_candidate ADD COLUMN IF NOT EXISTS conflicts_with_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_ai_memory_candidate_fact_lifecycle ON ai_memory_candidate(user_id, project_id, fact_key, lifecycle_status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_memory_candidate_idempotency ON ai_memory_candidate(user_id, candidate_key);
UPDATE ai_memory_candidate SET lifecycle_status = CASE LOWER(status)
    WHEN 'confirmed' THEN 'CONFIRMED' WHEN 'rejected' THEN 'REJECTED'
    WHEN 'expired' THEN 'STALE' WHEN 'stale' THEN 'STALE'
    WHEN 'superseded' THEN 'SUPERSEDED' ELSE 'CANDIDATE' END
WHERE lifecycle_status IS NULL;

ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(30);
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS fact_key VARCHAR(160);
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS provenance_json CLOB;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS evidence_json CLOB;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS source_evidence_ids_json CLOB;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS source_chapter_versions_json CLOB;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS index_generation VARCHAR(80);
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(80);
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS supersedes_id BIGINT;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS confirmed_by BIGINT;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMP;
ALTER TABLE ai_memory_item ADD COLUMN IF NOT EXISTS stale_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_ai_memory_item_fact_lifecycle ON ai_memory_item(user_id, project_id, fact_key, lifecycle_status);
UPDATE ai_memory_item SET lifecycle_status = CASE LOWER(status)
    WHEN 'confirmed' THEN 'CONFIRMED' WHEN 'approved' THEN 'CONFIRMED'
    WHEN 'rejected' THEN 'REJECTED' WHEN 'superseded' THEN 'SUPERSEDED'
    WHEN 'stale' THEN 'STALE' WHEN 'expired' THEN 'STALE' WHEN 'deleted' THEN 'STALE'
    ELSE 'STALE' END
WHERE lifecycle_status IS NULL;

CREATE TABLE IF NOT EXISTS ai_skill_lifecycle_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id VARCHAR(120) NOT NULL,
    candidate_id BIGINT,
    related_candidate_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    version VARCHAR(80),
    content_hash VARCHAR(64),
    actor_user_id BIGINT,
    source_trace_id VARCHAR(80),
    details_json CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_skill_lifecycle_audit_candidate ON ai_skill_lifecycle_audit(candidate_id, created_at);

INSERT INTO ai_skill_lifecycle_audit(
    skill_id, candidate_id, related_candidate_id, event_type, previous_status, new_status,
    version, content_hash, source_trace_id, details_json
)
SELECT previous.skill_id, previous.id, current.id, 'REPLACED', 'ACTIVE', 'REVOKED',
       previous.version, previous.content_hash, previous.source_trace_id, '{"source":"phase23-migration"}'
FROM ai_skill_candidate current
JOIN ai_skill_candidate previous ON previous.skill_id = current.skill_id
WHERE current.lifecycle_status = 'ACTIVE'
  AND previous.status = 'ROLLED_BACK'
  AND previous.id = (
      SELECT previous_version.id
      FROM ai_skill_candidate previous_version
      WHERE previous_version.skill_id = current.skill_id AND previous_version.status = 'ROLLED_BACK'
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
       candidate.version, candidate.content_hash, candidate.source_trace_id, '{"source":"phase23-migration"}'
FROM ai_skill_candidate candidate
WHERE candidate.lifecycle_status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_skill_lifecycle_audit audit
      WHERE audit.candidate_id = candidate.id
        AND audit.event_type IN ('ACTIVATED', 'ROLLED_BACK_TO')
  );

CREATE TABLE IF NOT EXISTS ai_memory_lifecycle_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    memory_id BIGINT,
    candidate_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    actor_user_id BIGINT,
    source_trace_id VARCHAR(80),
    details_json CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_memory_lifecycle_audit_memory ON ai_memory_lifecycle_audit(memory_id, created_at);
