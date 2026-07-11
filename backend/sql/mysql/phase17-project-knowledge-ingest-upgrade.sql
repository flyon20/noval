DELIMITER $$

DROP PROCEDURE IF EXISTS noval_add_column_if_missing $$
CREATE PROCEDURE noval_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = p_table_name AND column_name = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

CALL noval_add_column_if_missing(
    'ai_project_vector_chunk',
    'chunk_text',
    'chunk_text MEDIUMTEXT NULL AFTER qdrant_point_id'
);

DROP PROCEDURE IF EXISTS noval_add_column_if_missing;
