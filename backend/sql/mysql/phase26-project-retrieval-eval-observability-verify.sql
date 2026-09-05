-- Phase 26 verify: eval baseline, feedback, resource diagnostics tables.
-- All mismatch_count rows must be 0.

SELECT 'phase26_missing_core_tables' AS check_name,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name IN (
             'ai_project_retrieval_eval_baseline',
             'ai_project_knowledge_feedback',
             'ai_agent_resource_diagnostic'
           )) < 3 AS mismatch_count
UNION ALL
SELECT 'phase26_feedback_without_project_scope',
       COUNT(*)
FROM ai_project_knowledge_feedback f
WHERE f.project_id IS NULL OR f.user_id IS NULL
UNION ALL
SELECT 'phase26_eval_baseline_missing_identity',
       COUNT(*)
FROM ai_project_retrieval_eval_baseline b
WHERE b.suite_name IS NULL OR TRIM(b.suite_name) = ''
   OR b.baseline_key IS NULL OR TRIM(b.baseline_key) = ''
   OR b.metrics_json IS NULL
   OR b.corpus_version IS NULL OR TRIM(b.corpus_version) = ''
UNION ALL
SELECT 'phase26_resource_diagnostic_invalid_json',
       COUNT(*)
FROM ai_agent_resource_diagnostic d
WHERE (d.degradation_reasons IS NOT NULL AND JSON_VALID(d.degradation_reasons) = 0)
   OR (d.payload_json IS NOT NULL AND JSON_VALID(d.payload_json) = 0);
