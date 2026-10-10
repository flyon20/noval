-- H2 contract for Phase 25. MySQL FULLTEXT/ntgram is exercised by static checks;
-- H2 service tests use the bounded lexical alternative.

CREATE TABLE IF NOT EXISTS ai_project_search_document (
    document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    generation_id BIGINT NOT NULL,
    chapter_version INT NOT NULL,
    scene_id BIGINT NULL,
    source_id BIGINT NULL,
    document_type VARCHAR(40) NOT NULL,
    document_key VARCHAR(320) NOT NULL,
    title VARCHAR(500) NULL,
    aliases VARCHAR(1000) NULL,
    content CLOB NULL,
    content_hash VARCHAR(64) NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_search_document_scope_key UNIQUE(user_id, project_id, work_id, generation_id, document_key)
);

CREATE INDEX IF NOT EXISTS idx_ai_project_search_document_scope
    ON ai_project_search_document(user_id, project_id, work_id, generation_id, status, chapter_id);
CREATE INDEX IF NOT EXISTS idx_ai_project_search_document_alias
    ON ai_project_search_document(user_id, project_id, work_id, aliases);

CREATE TABLE IF NOT EXISTS ai_project_story_node (
    node_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    generation_id BIGINT NOT NULL,
    source_chapter_id BIGINT NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    canonical_key VARCHAR(240) NOT NULL,
    display_name VARCHAR(500) NOT NULL,
    aliases VARCHAR(1000) NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_story_node_scope UNIQUE(user_id, project_id, work_id, node_type, canonical_key, generation_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_project_story_node_scope
    ON ai_project_story_node(user_id, project_id, work_id, generation_id, status, node_type);
CREATE INDEX IF NOT EXISTS idx_ai_project_story_node_alias
    ON ai_project_story_node(user_id, project_id, work_id, aliases);

CREATE TABLE IF NOT EXISTS ai_project_story_edge (
    edge_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    generation_id BIGINT NOT NULL,
    edge_key VARCHAR(320) NOT NULL,
    from_node_id BIGINT NOT NULL,
    to_node_id BIGINT NOT NULL,
    relation_type VARCHAR(80) NOT NULL,
    relation_group VARCHAR(80) NOT NULL,
    evidence_chapter_id BIGINT NOT NULL,
    evidence_scene_id BIGINT NULL,
    evidence_ref VARCHAR(500) NULL,
    valid_from_chapter_no INT NULL,
    valid_to_chapter_no INT NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_story_edge_scope_key UNIQUE(user_id, project_id, work_id, generation_id, edge_key)
);

CREATE INDEX IF NOT EXISTS idx_ai_project_story_edge_forward
    ON ai_project_story_edge(user_id, project_id, work_id, generation_id, status, from_node_id);
CREATE INDEX IF NOT EXISTS idx_ai_project_story_edge_reverse
    ON ai_project_story_edge(user_id, project_id, work_id, generation_id, status, to_node_id);

INSERT INTO ai_project_search_document(
    user_id, project_id, work_id, chapter_id, generation_id, chapter_version,
    document_type, document_key, title, aliases, content, content_hash, confidence, status
)
SELECT c.user_id, c.project_id, c.work_id, c.chapter_id, g.generation_id, c.version,
       'CHAPTER', CONCAT('chapter:', c.chapter_id), c.title,
       CONCAT('|', COALESCE(c.title, ''), '|'), c.content, c.content_hash, 1.0000, 'ACTIVE'
FROM ai_project_chapter_head h
JOIN ai_project_ingest_generation g ON g.generation_id = h.active_generation_id
JOIN ai_project_chapter c ON c.chapter_id = h.active_chapter_id
WHERE h.tombstoned_at IS NULL
  AND g.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_search_document d
      WHERE d.user_id = c.user_id AND d.project_id = c.project_id AND d.work_id = c.work_id
        AND d.generation_id = g.generation_id AND d.document_key = CONCAT('chapter:', c.chapter_id)
  );

INSERT INTO ai_project_search_document(
    user_id, project_id, work_id, chapter_id, generation_id, chapter_version,
    scene_id, source_id, document_type, document_key, title, aliases, content,
    content_hash, confidence, status
)
SELECT s.user_id, s.project_id, s.work_id, s.chapter_id, s.generation_id, s.chapter_version,
       s.scene_id, s.scene_id, 'SCENE', CONCAT('scene:', s.scene_id),
       CONCAT(COALESCE(c.title, ''), ' scene ', s.scene_no),
       CONCAT('|scene-', s.scene_no, '|'), s.summary,
       CONCAT('scene:', s.scene_id), s.confidence, 'ACTIVE'
FROM ai_project_scene s
JOIN ai_project_chapter_head h ON h.user_id = s.user_id AND h.project_id = s.project_id
    AND h.work_id = s.work_id AND h.active_chapter_id = s.chapter_id
    AND h.active_generation_id = s.generation_id AND h.tombstoned_at IS NULL
JOIN ai_project_ingest_generation g ON g.generation_id = s.generation_id AND g.status = 'ACTIVE'
JOIN ai_project_chapter c ON c.chapter_id = s.chapter_id
WHERE s.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_search_document d
      WHERE d.user_id = s.user_id AND d.project_id = s.project_id AND d.work_id = s.work_id
        AND d.generation_id = s.generation_id AND d.document_key = CONCAT('scene:', s.scene_id)
  );
