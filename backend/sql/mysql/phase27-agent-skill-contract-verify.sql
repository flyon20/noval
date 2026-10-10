SELECT 'phase27_missing_skill_contract_columns' AS issue, 6 - COUNT(*) AS violation_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('ai_skill_candidate', 'ai_runtime_skill')
  AND column_name IN ('description', 'requested_capabilities_json', 'skill_metadata_json')
HAVING violation_count > 0
UNION ALL
SELECT 'phase27_invalid_candidate_capability_json', COUNT(*)
FROM ai_skill_candidate
WHERE requested_capabilities_json IS NULL OR JSON_VALID(requested_capabilities_json) = 0
HAVING COUNT(*) > 0
UNION ALL
SELECT 'phase27_invalid_candidate_metadata_json', COUNT(*)
FROM ai_skill_candidate
WHERE skill_metadata_json IS NULL OR JSON_VALID(skill_metadata_json) = 0
HAVING COUNT(*) > 0
UNION ALL
SELECT 'phase27_invalid_runtime_capability_json', COUNT(*)
FROM ai_runtime_skill
WHERE requested_capabilities_json IS NULL OR JSON_VALID(requested_capabilities_json) = 0
HAVING COUNT(*) > 0
UNION ALL
SELECT 'phase27_invalid_runtime_metadata_json', COUNT(*)
FROM ai_runtime_skill
WHERE skill_metadata_json IS NULL OR JSON_VALID(skill_metadata_json) = 0
HAVING COUNT(*) > 0
UNION ALL
SELECT 'phase27_active_runtime_skill_missing_identity', COUNT(*)
FROM ai_runtime_skill
WHERE status = 'ACTIVE'
  AND (version IS NULL OR version = '' OR content_hash IS NULL OR content_hash = '')
HAVING COUNT(*) > 0;
