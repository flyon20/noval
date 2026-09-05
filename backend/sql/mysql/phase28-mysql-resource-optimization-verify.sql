SELECT 'phase28_missing_crawl_book_lookup_index' AS issue,
       IF(COUNT(*) = 0, 1, 0) AS violation_count
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'crawl_book'
    GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        = 'platform,platform_book_id,deleted'
) matching_index
HAVING violation_count > 0;

SELECT 'phase28_missing_crawl_rank_latest_index' AS issue,
       IF(COUNT(*) = 0, 1, 0) AS violation_count
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'crawl_rank'
    GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        = 'platform,category,deleted,crawl_time,rank_no,id'
) matching_index
HAVING violation_count > 0;

SELECT 'phase28_missing_knowledge_chunk_source_status_index' AS issue,
       IF(COUNT(*) = 0, 1, 0) AS violation_count
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_chunk'
    GROUP BY index_name
    HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        = 'source_type,source_ref_id,deleted,vector_status,chunk_strategy_version,embedding_model,embedding_dimension'
) matching_index
HAVING violation_count > 0;
