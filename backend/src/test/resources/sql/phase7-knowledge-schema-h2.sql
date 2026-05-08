DROP TABLE IF EXISTS knowledge_chunk;
DROP TABLE IF EXISTS knowledge_document;

CREATE TABLE knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type VARCHAR(50) NOT NULL,
    source_ref_id BIGINT,
    platform VARCHAR(20) NOT NULL,
    book_id BIGINT,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_document_source ON knowledge_document(source_type, source_ref_id, platform, book_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_book ON knowledge_document(book_id, source_type, status);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_platform_status ON knowledge_document(platform, status);

CREATE TABLE knowledge_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    chunk_key VARCHAR(100) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_ref_id BIGINT,
    book_id BIGINT,
    chapter_no INT,
    analysis_type VARCHAR(50),
    content_hash CHAR(64) NOT NULL,
    chunk_text CLOB NOT NULL,
    token_count INT DEFAULT 0,
    vector_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    qdrant_point_id VARCHAR(100),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_chunk_document_key ON knowledge_chunk(document_id, chunk_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_chunk_qdrant_point ON knowledge_chunk(qdrant_point_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_book_source ON knowledge_chunk(book_id, source_type, chapter_no);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_hash_status ON knowledge_chunk(content_hash, vector_status);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_document_status ON knowledge_chunk(document_id, vector_status);
