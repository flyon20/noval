SELECT 'phase30_missing_long_form_memory_tables' AS issue,
       5 - COUNT(*) AS violation_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
      'ai_project_memory_fact', 'ai_project_memory_evidence',
      'ai_project_memory_entity_alias', 'ai_project_summary_node',
      'ai_project_summary_evidence'
  )
HAVING violation_count > 0;

SELECT 'phase30_fact_evidence_scope_mismatch' AS issue, COUNT(*) AS violation_count
FROM ai_project_memory_evidence e
JOIN ai_project_memory_fact f ON f.fact_id = e.fact_id
WHERE e.user_id <> f.user_id
   OR e.project_id <> f.project_id
   OR e.work_id <> f.work_id
HAVING violation_count > 0;

SELECT 'phase30_fact_evidence_orphans' AS issue, COUNT(*) AS violation_count
FROM ai_project_memory_evidence e
LEFT JOIN ai_project_memory_fact f ON f.fact_id = e.fact_id
WHERE f.fact_id IS NULL
HAVING violation_count > 0;

SELECT 'phase30_invalid_fact_validity_range' AS issue, COUNT(*) AS violation_count
FROM ai_project_memory_fact
WHERE valid_from_chapter_no IS NOT NULL
  AND valid_to_chapter_no IS NOT NULL
  AND valid_to_chapter_no < valid_from_chapter_no
HAVING violation_count > 0;

SELECT 'phase30_invalid_summary_range' AS issue, COUNT(*) AS violation_count
FROM ai_project_summary_node
WHERE range_from_chapter IS NOT NULL
  AND range_to_chapter IS NOT NULL
  AND range_to_chapter < range_from_chapter
HAVING violation_count > 0;

SELECT 'phase30_summary_evidence_scope_mismatch' AS issue, COUNT(*) AS violation_count
FROM ai_project_summary_evidence e
JOIN ai_project_summary_node s ON s.summary_node_id = e.summary_node_id
WHERE e.user_id <> s.user_id
   OR e.project_id <> s.project_id
   OR e.work_id <> s.work_id
HAVING violation_count > 0;

SELECT 'phase30_summary_evidence_orphans' AS issue, COUNT(*) AS violation_count
FROM ai_project_summary_evidence e
LEFT JOIN ai_project_summary_node s ON s.summary_node_id = e.summary_node_id
LEFT JOIN ai_project_memory_evidence m ON m.evidence_id = e.evidence_id
LEFT JOIN ai_project_summary_node c ON c.summary_node_id = e.child_summary_node_id
WHERE s.summary_node_id IS NULL
   OR (e.evidence_id IS NOT NULL AND m.evidence_id IS NULL)
   OR (e.child_summary_node_id IS NOT NULL AND c.summary_node_id IS NULL)
   OR (e.evidence_id IS NULL AND e.child_summary_node_id IS NULL)
HAVING violation_count > 0;
