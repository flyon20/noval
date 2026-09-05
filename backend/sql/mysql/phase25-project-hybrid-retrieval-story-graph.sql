-- Phase 25: project hybrid retrieval documents and evidence-backed story graph.
-- Additive only. Search documents are visible only through chapter heads whose
-- generation is ACTIVE; the backfill below therefore cannot expose retired text.

CREATE TABLE IF NOT EXISTS ai_project_search_document (
    document_id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
    content LONGTEXT NULL,
    content_hash CHAR(64) NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_search_document_scope_key (user_id, project_id, work_id, generation_id, document_key),
    INDEX idx_ai_project_search_document_scope (user_id, project_id, work_id, generation_id, status, chapter_id),
    INDEX idx_ai_project_search_document_alias (user_id, project_id, work_id, aliases(128)),
    FULLTEXT KEY ft_ai_project_search_document_text (title, aliases, content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='active generation project retrieval documents';

CREATE TABLE IF NOT EXISTS ai_project_story_node (
    node_id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_story_node_scope (user_id, project_id, work_id, node_type, canonical_key, generation_id),
    INDEX idx_ai_project_story_node_scope (user_id, project_id, work_id, generation_id, status, node_type),
    INDEX idx_ai_project_story_node_alias (user_id, project_id, work_id, aliases(128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='evidence-backed story graph nodes';

CREATE TABLE IF NOT EXISTS ai_project_story_edge (
    edge_id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_story_edge_scope_key (user_id, project_id, work_id, generation_id, edge_key),
    INDEX idx_ai_project_story_edge_forward (user_id, project_id, work_id, generation_id, status, from_node_id),
    INDEX idx_ai_project_story_edge_reverse (user_id, project_id, work_id, generation_id, status, to_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='directed story graph relations with chapter evidence';

-- Deterministic, re-entrant backfill for the current chapter projection.
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
ON DUPLICATE KEY UPDATE
    title = VALUES(title), aliases = VALUES(aliases), content = VALUES(content),
    content_hash = VALUES(content_hash), chapter_version = VALUES(chapter_version),
    status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO ai_project_search_document(
    user_id, project_id, work_id, chapter_id, generation_id, chapter_version,
    scene_id, source_id, document_type, document_key, title, aliases, content,
    content_hash, confidence, status
)
SELECT s.user_id, s.project_id, s.work_id, s.chapter_id, s.generation_id, s.chapter_version,
       s.scene_id, s.scene_id, 'SCENE', CONCAT('scene:', s.scene_id),
       CONCAT(COALESCE(c.title, ''), ' scene ', s.scene_no),
       CONCAT('|scene-', s.scene_no, '|'), s.summary,
       SHA2(CONCAT(s.scene_id, ':', COALESCE(s.summary, '')), 256), s.confidence, 'ACTIVE'
FROM ai_project_scene s
JOIN ai_project_chapter_head h ON h.user_id = s.user_id AND h.project_id = s.project_id
    AND h.work_id = s.work_id AND h.active_chapter_id = s.chapter_id
    AND h.active_generation_id = s.generation_id AND h.tombstoned_at IS NULL
JOIN ai_project_ingest_generation g ON g.generation_id = s.generation_id AND g.status = 'ACTIVE'
JOIN ai_project_chapter c ON c.chapter_id = s.chapter_id
WHERE s.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    title = VALUES(title), aliases = VALUES(aliases), content = VALUES(content),
    content_hash = VALUES(content_hash), chapter_version = VALUES(chapter_version),
    confidence = VALUES(confidence), status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;
