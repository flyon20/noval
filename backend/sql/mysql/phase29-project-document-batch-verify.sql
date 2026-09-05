SELECT 'phase29_missing_document_batch_tables' AS issue,
       8 - COUNT(*) AS violation_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
      'ai_project_document_batch', 'ai_project_document_file',
      'ai_project_document', 'ai_project_document_generation',
      'ai_project_document_section', 'ai_project_document_question',
      'ai_project_document_batch_outbox', 'ai_project_entity_evidence'
  )
HAVING violation_count > 0;

SELECT 'phase29_document_file_scope_mismatch' AS issue, COUNT(*) AS violation_count
FROM ai_project_document_file f
JOIN ai_project_document_batch b ON b.batch_id = f.batch_id
WHERE f.user_id <> b.user_id
   OR f.project_id <> b.project_id
   OR f.work_id <> b.work_id
HAVING violation_count > 0;

SELECT 'phase29_outbox_orphans' AS issue, COUNT(*) AS violation_count
FROM ai_project_document_batch_outbox o
LEFT JOIN ai_project_document_batch b ON b.batch_id = o.batch_id
WHERE b.batch_id IS NULL
HAVING violation_count > 0;

SELECT 'phase29_missing_vector_document_columns' AS issue,
       5 - COUNT(*) AS violation_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ai_project_vector_chunk'
  AND column_name IN ('document_id', 'document_generation_id', 'section_id', 'profile_type', 'evidence_scope')
HAVING violation_count > 0;

SELECT 'phase29_invalid_ready_batch' AS issue, COUNT(*) AS violation_count
FROM ai_project_document_batch
WHERE status = 'READY'
  AND (pending_questions <> 0 OR failed_files <> 0 OR indexed_files < parsed_files)
HAVING violation_count > 0;

SELECT 'phase29_missing_search_document_columns' AS issue,
       3 - COUNT(*) AS violation_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ai_project_search_document'
  AND column_name IN ('source_document_id', 'document_generation_id', 'section_id')
HAVING violation_count > 0;

SELECT 'phase29_active_document_search_scope_mismatch' AS issue, COUNT(*) AS violation_count
FROM ai_project_search_document sd
WHERE sd.document_generation_id IS NOT NULL
  AND sd.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1
      FROM ai_project_document d
      JOIN ai_project_document_generation g
        ON g.document_generation_id = d.active_generation_id
       AND g.status = 'ACTIVE'
      JOIN ai_project_document_section s
        ON s.section_id = sd.section_id
       AND s.document_generation_id = g.document_generation_id
       AND s.status = 'ACTIVE'
      WHERE d.document_id = sd.source_document_id
        AND d.user_id = sd.user_id
        AND d.project_id = sd.project_id
        AND d.work_id = sd.work_id
        AND d.status = 'ACTIVE'
  )
HAVING violation_count > 0;
