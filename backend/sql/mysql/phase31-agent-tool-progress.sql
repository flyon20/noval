-- Apply before deploying the tool-progress snapshot reader/writer.
-- Additive host metadata only; keep this column on application rollback.
DROP PROCEDURE IF EXISTS noval_phase31_add_tool_progress;
DELIMITER $$
CREATE PROCEDURE noval_phase31_add_tool_progress()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'ai_chat_run'
          AND column_name = 'tool_progress_json'
    ) THEN
        ALTER TABLE ai_chat_run ADD COLUMN tool_progress_json JSON NULL;
    END IF;
END$$
DELIMITER ;
CALL noval_phase31_add_tool_progress();
DROP PROCEDURE IF EXISTS noval_phase31_add_tool_progress;
