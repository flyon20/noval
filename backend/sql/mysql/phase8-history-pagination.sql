SET @schema_name = DATABASE();

SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'analysis_result'
      AND INDEX_NAME = 'idx_analysis_history_user_time'
);
SET @ddl = IF(
    @index_exists = 0,
    'CREATE INDEX idx_analysis_history_user_time ON analysis_result(user_id, deleted, create_time, id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'analysis_result'
      AND INDEX_NAME = 'idx_analysis_history_book_type_time'
);
SET @ddl = IF(
    @index_exists = 0,
    'CREATE INDEX idx_analysis_history_book_type_time ON analysis_result(book_id, analysis_type, deleted, create_time, id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'analysis_result'
      AND INDEX_NAME = 'idx_analysis_history_board_type_time'
);
SET @ddl = IF(
    @index_exists = 0,
    'CREATE INDEX idx_analysis_history_board_type_time ON analysis_result(platform, channel_code, board_code, analysis_type, deleted, create_time, id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
