-- Phase 9 knowledge index metadata migration (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase9-knowledge-index-metadata-migration.sql
--
-- This migration is safe for existing data. It adds metadata required to decide
-- whether an existing knowledge chunk must be re-embedded after chunking or
-- embedding configuration changes.

SET @schema_name = DATABASE();

SET @column_exists = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'knowledge_chunk'
      AND COLUMN_NAME = 'chunk_strategy_version'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE knowledge_chunk ADD COLUMN chunk_strategy_version VARCHAR(50) NOT NULL DEFAULT ''legacy-v1'' COMMENT ''chunk splitting strategy version'' AFTER token_count',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'knowledge_chunk'
      AND COLUMN_NAME = 'embedding_model'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE knowledge_chunk ADD COLUMN embedding_model VARCHAR(100) COMMENT ''embedding model used for vector'' AFTER chunk_strategy_version',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'knowledge_chunk'
      AND COLUMN_NAME = 'embedding_dimension'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE knowledge_chunk ADD COLUMN embedding_dimension INT DEFAULT 0 COMMENT ''embedding vector dimension'' AFTER embedding_model',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE knowledge_chunk
SET chunk_strategy_version = 'legacy-v1'
WHERE chunk_strategy_version IS NULL OR chunk_strategy_version = '';

SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'knowledge_chunk'
      AND INDEX_NAME = 'idx_knowledge_chunk_strategy_status'
);
SET @ddl = IF(
    @index_exists = 0,
    'CREATE INDEX idx_knowledge_chunk_strategy_status ON knowledge_chunk(chunk_strategy_version, embedding_model, embedding_dimension, vector_status)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
