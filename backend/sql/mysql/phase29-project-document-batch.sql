-- Phase 29: durable project document batches, parsing generations and evidence lineage.

DROP PROCEDURE IF EXISTS noval_phase29_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE noval_phase29_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @ddl = p_ddl;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS noval_phase29_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE noval_phase29_add_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @ddl = p_ddl;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CREATE TABLE IF NOT EXISTS ai_project_document_batch (
    batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    manifest_hash VARCHAR(128) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'UPLOADING',
    stage VARCHAR(40) NOT NULL DEFAULT 'uploading',
    progress INT NOT NULL DEFAULT 0,
    total_files INT NOT NULL DEFAULT 0,
    stored_files INT NOT NULL DEFAULT 0,
    parsed_files INT NOT NULL DEFAULT 0,
    indexed_files INT NOT NULL DEFAULT 0,
    skipped_files INT NOT NULL DEFAULT 0,
    failed_files INT NOT NULL DEFAULT 0,
    pending_questions INT NOT NULL DEFAULT 0,
    total_bytes BIGINT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 1,
    max_attempts INT NOT NULL DEFAULT 4,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME NULL,
    heartbeat_at DATETIME NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(1000) NULL,
    completed_at DATETIME NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_document_batch_idempotency (user_id, idempotency_key),
    INDEX idx_ai_project_document_batch_scope (user_id, project_id, work_id, created_at),
    INDEX idx_ai_project_document_batch_lease (status, lease_expires_at, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='durable project document upload batch';

CREATE TABLE IF NOT EXISTS ai_project_document_file (
    file_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NULL,
    size_bytes BIGINT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    declared_kind VARCHAR(40) NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'STORED',
    content_blob LONGBLOB NOT NULL,
    document_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(1000) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_document_file_path (batch_id, relative_path),
    INDEX idx_ai_project_document_file_scope (user_id, project_id, work_id, status),
    INDEX idx_ai_project_document_file_hash (user_id, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='stored source file for a project document batch';

CREATE TABLE IF NOT EXISTS ai_project_document (
    document_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    active_generation_id BIGINT NULL,
    relative_path VARCHAR(512) NOT NULL,
    title VARCHAR(255) NULL,
    document_kind VARCHAR(40) NOT NULL,
    classification_confidence DECIMAL(5,4) NULL,
    classification_reasons JSON NULL,
    content_hash VARCHAR(128) NOT NULL,
    normalized_content LONGTEXT NOT NULL,
    metadata_json JSON NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PARSED_PENDING_INDEX',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_document_file_path (file_id, relative_path),
    INDEX idx_ai_project_document_scope (user_id, project_id, work_id, document_kind, status),
    INDEX idx_ai_project_document_batch (batch_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='canonical parsed project document';

CREATE TABLE IF NOT EXISTS ai_project_document_generation (
    document_generation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PREPARED',
    section_count INT NOT NULL DEFAULT 0,
    indexed_section_count INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME NULL,
    heartbeat_at DATETIME NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(1000) NULL,
    activated_at DATETIME NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_document_generation (document_id, parser_version, content_hash),
    INDEX idx_ai_project_document_generation_scope (user_id, project_id, work_id, status),
    INDEX idx_ai_project_document_generation_lease (status, lease_expires_at, document_generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='parsed and indexed generation of a project document';

CREATE TABLE IF NOT EXISTS ai_project_document_section (
    section_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    document_generation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    section_ordinal INT NOT NULL,
    title VARCHAR(255) NULL,
    section_kind VARCHAR(40) NOT NULL,
    start_offset INT NOT NULL DEFAULT 0,
    end_offset INT NOT NULL DEFAULT 0,
    content_hash VARCHAR(128) NOT NULL,
    content LONGTEXT NOT NULL,
    canonical_chapter_no INT NULL,
    ingest_job_id BIGINT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PARSED_PENDING_INDEX',
    metadata_json JSON NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_document_section_ordinal (document_generation_id, section_ordinal),
    INDEX idx_ai_project_document_section_scope (user_id, project_id, work_id, section_kind, status),
    INDEX idx_ai_project_document_section_ingest (ingest_job_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='typed section within a parsed project document generation';

CREATE TABLE IF NOT EXISTS ai_project_document_question (
    question_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    file_id BIGINT NULL,
    document_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    question_type VARCHAR(40) NOT NULL,
    prompt VARCHAR(500) NOT NULL,
    options_json JSON NULL,
    answer_json JSON NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    resolved_by BIGINT NULL,
    resolved_at DATETIME NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_document_question_batch (batch_id, status, question_id),
    INDEX idx_ai_project_document_question_scope (user_id, project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='bounded user confirmations for document classification';

CREATE TABLE IF NOT EXISTS ai_project_document_batch_outbox (
    outbox_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
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
    UNIQUE KEY uk_ai_project_document_batch_outbox (batch_id, event_type, attempt),
    INDEX idx_ai_project_document_batch_outbox_pending (status, available_at, outbox_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='durable document batch queue outbox';

CREATE TABLE IF NOT EXISTS ai_project_entity_evidence (
    entity_evidence_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    entity_id BIGINT NULL,
    document_id BIGINT NOT NULL,
    document_generation_id BIGINT NOT NULL,
    section_id BIGINT NULL,
    evidence_type VARCHAR(40) NOT NULL DEFAULT 'SOURCE',
    quote_text VARCHAR(2000) NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    confidence DECIMAL(5,4) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_entity_evidence_entity (user_id, project_id, entity_type, entity_id, status),
    INDEX idx_ai_project_entity_evidence_source (document_generation_id, section_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='structured entity to source document evidence linkage';

CALL noval_phase29_add_column_if_missing('ai_project_vector_chunk', 'document_id',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN document_id BIGINT NULL AFTER chapter_id');
CALL noval_phase29_add_column_if_missing('ai_project_vector_chunk', 'document_generation_id',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN document_generation_id BIGINT NULL AFTER document_id');
CALL noval_phase29_add_column_if_missing('ai_project_vector_chunk', 'section_id',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN section_id BIGINT NULL AFTER document_generation_id');
CALL noval_phase29_add_column_if_missing('ai_project_vector_chunk', 'profile_type',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN profile_type VARCHAR(40) NULL AFTER section_id');
CALL noval_phase29_add_column_if_missing('ai_project_vector_chunk', 'evidence_scope',
    'ALTER TABLE ai_project_vector_chunk ADD COLUMN evidence_scope VARCHAR(40) NULL AFTER profile_type');
CALL noval_phase29_add_index_if_missing('ai_project_vector_chunk', 'idx_ai_project_vector_document',
    'ALTER TABLE ai_project_vector_chunk ADD INDEX idx_ai_project_vector_document (document_generation_id, section_id, status)');

ALTER TABLE ai_project_search_document
    MODIFY COLUMN chapter_id BIGINT NULL,
    MODIFY COLUMN generation_id BIGINT NULL,
    MODIFY COLUMN chapter_version INT NULL;
CALL noval_phase29_add_column_if_missing('ai_project_search_document', 'source_document_id',
    'ALTER TABLE ai_project_search_document ADD COLUMN source_document_id BIGINT NULL AFTER source_id');
CALL noval_phase29_add_column_if_missing('ai_project_search_document', 'document_generation_id',
    'ALTER TABLE ai_project_search_document ADD COLUMN document_generation_id BIGINT NULL AFTER source_document_id');
CALL noval_phase29_add_column_if_missing('ai_project_search_document', 'section_id',
    'ALTER TABLE ai_project_search_document ADD COLUMN section_id BIGINT NULL AFTER document_generation_id');
CALL noval_phase29_add_index_if_missing('ai_project_search_document', 'uk_ai_project_search_document_generation_key',
    'ALTER TABLE ai_project_search_document ADD UNIQUE INDEX uk_ai_project_search_document_generation_key (user_id, project_id, work_id, document_generation_id, document_key)');
CALL noval_phase29_add_index_if_missing('ai_project_search_document', 'idx_ai_project_search_document_generation',
    'ALTER TABLE ai_project_search_document ADD INDEX idx_ai_project_search_document_generation (document_generation_id, section_id, status)');

DROP PROCEDURE IF EXISTS noval_phase29_add_column_if_missing;
DROP PROCEDURE IF EXISTS noval_phase29_add_index_if_missing;
