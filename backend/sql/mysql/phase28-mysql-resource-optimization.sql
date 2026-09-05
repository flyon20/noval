-- Phase 28: evidence-backed MySQL hot-read optimization.

SET @phase28_crawl_book_lookup_index_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'crawl_book'
        )
        AND (
            SELECT COUNT(DISTINCT column_name)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'crawl_book'
              AND column_name IN ('platform', 'platform_book_id', 'deleted')
        ) = 3
        AND NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'crawl_book'
              AND index_name = 'idx_crawl_book_platform_book_deleted'
        ),
        'CREATE INDEX idx_crawl_book_platform_book_deleted ON crawl_book(platform, platform_book_id, deleted)',
        'SELECT 1'
    )
);
PREPARE phase28_stmt FROM @phase28_crawl_book_lookup_index_ddl;
EXECUTE phase28_stmt;
DEALLOCATE PREPARE phase28_stmt;

SET @phase28_crawl_rank_latest_index_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'crawl_rank'
        )
        AND (
            SELECT COUNT(DISTINCT column_name)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'crawl_rank'
              AND column_name IN ('platform', 'category', 'deleted', 'crawl_time', 'rank_no', 'id')
        ) = 6
        AND NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'crawl_rank'
              AND index_name = 'idx_crawl_rank_latest_lookup'
        ),
        'ALTER TABLE crawl_rank ADD INDEX idx_crawl_rank_latest_lookup(platform, category, deleted, crawl_time, rank_no, id), ALGORITHM=INPLACE, LOCK=NONE',
        'SELECT 1'
    )
);
PREPARE phase28_stmt FROM @phase28_crawl_rank_latest_index_ddl;
EXECUTE phase28_stmt;
DEALLOCATE PREPARE phase28_stmt;

SET @phase28_knowledge_chunk_source_status_index_ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'knowledge_chunk'
        )
        AND (
            SELECT COUNT(DISTINCT column_name)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'knowledge_chunk'
              AND column_name IN (
                  'source_type', 'source_ref_id', 'deleted', 'vector_status',
                  'chunk_strategy_version', 'embedding_model', 'embedding_dimension'
              )
        ) = 7
        AND NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'knowledge_chunk'
              AND index_name = 'idx_knowledge_chunk_source_status'
        ),
        'ALTER TABLE knowledge_chunk ADD INDEX idx_knowledge_chunk_source_status(source_type, source_ref_id, deleted, vector_status, chunk_strategy_version, embedding_model, embedding_dimension), ALGORITHM=INPLACE, LOCK=NONE',
        'SELECT 1'
    )
);
PREPARE phase28_stmt FROM @phase28_knowledge_chunk_source_status_index_ddl;
EXECUTE phase28_stmt;
DEALLOCATE PREPARE phase28_stmt;
