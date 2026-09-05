-- Phase 25 verify: search documents + story graph ownership/orphans.
-- All mismatch_count rows must be 0.

SELECT 'phase25_missing_core_tables' AS check_name,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name IN (
             'ai_project_search_document',
             'ai_project_story_node',
             'ai_project_story_edge'
           )) < 3 AS mismatch_count
UNION ALL
SELECT 'phase25_story_edge_orphan_from_node',
       COUNT(*)
FROM ai_project_story_edge e
WHERE e.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_story_node n
      WHERE n.node_id = e.from_node_id
        AND n.user_id = e.user_id
        AND n.project_id = e.project_id
        AND n.work_id = e.work_id
  )
UNION ALL
SELECT 'phase25_story_edge_orphan_to_node',
       COUNT(*)
FROM ai_project_story_edge e
WHERE e.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_story_node n
      WHERE n.node_id = e.to_node_id
        AND n.user_id = e.user_id
        AND n.project_id = e.project_id
        AND n.work_id = e.work_id
  )
UNION ALL
SELECT 'phase25_search_document_scope_mismatch',
       COUNT(*)
FROM ai_project_search_document d
WHERE d.status = 'ACTIVE'
  AND d.generation_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_ingest_generation g
      WHERE g.generation_id = d.generation_id
        AND g.user_id = d.user_id
        AND g.project_id = d.project_id
        AND g.work_id = d.work_id
  )
UNION ALL
SELECT 'phase25_story_node_without_generation',
       COUNT(*)
FROM ai_project_story_node n
WHERE n.status = 'ACTIVE'
  AND n.generation_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_ingest_generation g
      WHERE g.generation_id = n.generation_id
  );
