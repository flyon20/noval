-- Phase 7 knowledge database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase7-knowledge-schema.sql

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'knowledge document id',
    source_type VARCHAR(50) NOT NULL COMMENT 'INTRO / CHAPTER / RANK / ANALYSIS',
    source_ref_id BIGINT COMMENT 'source table id',
    platform VARCHAR(20) NOT NULL COMMENT 'platform',
    book_id BIGINT COMMENT 'crawl book id',
    title VARCHAR(255) NOT NULL COMMENT 'document title',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / INDEXED / FAILED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_knowledge_document_source (source_type, source_ref_id, platform, book_id),
    INDEX idx_knowledge_document_book (book_id, source_type, status),
    INDEX idx_knowledge_document_platform_status (platform, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge document';

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'knowledge chunk id',
    document_id BIGINT NOT NULL COMMENT 'knowledge document id',
    chunk_key VARCHAR(100) NOT NULL COMMENT 'stable chunk key within document',
    source_type VARCHAR(50) NOT NULL COMMENT 'INTRO / CHAPTER / RANK / ANALYSIS',
    source_ref_id BIGINT COMMENT 'source table id',
    book_id BIGINT COMMENT 'crawl book id',
    chapter_no INT COMMENT 'chapter number',
    analysis_type VARCHAR(50) COMMENT 'analysis type',
    content_hash CHAR(64) NOT NULL COMMENT 'sha256 content hash',
    chunk_text MEDIUMTEXT NOT NULL COMMENT 'chunk text for embedding and citations',
    token_count INT DEFAULT 0 COMMENT 'estimated token count',
    vector_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / INDEXED / FAILED',
    qdrant_point_id VARCHAR(100) COMMENT 'qdrant point id',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_knowledge_chunk_document_key (document_id, chunk_key),
    UNIQUE KEY uk_knowledge_chunk_qdrant_point (qdrant_point_id),
    INDEX idx_knowledge_chunk_book_source (book_id, source_type, chapter_no),
    INDEX idx_knowledge_chunk_hash_status (content_hash, vector_status),
    INDEX idx_knowledge_chunk_document_status (document_id, vector_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge chunk';
