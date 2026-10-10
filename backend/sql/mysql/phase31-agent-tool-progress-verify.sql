-- No rows means the additive snapshot column is ready for the new Backend.
SELECT 'phase31_missing_or_incompatible_tool_progress_column' AS issue,
       1 - COUNT(*) AS violation_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ai_chat_run'
  AND column_name = 'tool_progress_json'
  AND data_type = 'json'
  AND is_nullable = 'YES'
HAVING violation_count <> 0;
