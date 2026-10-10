-- Phase 24 verify: generation uniqueness, outbox, tombstone, ownership.
-- All mismatch_count rows must be 0 for a healthy migration.

SELECT 'phase24_missing_core_tables' AS check_name,
       (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_name IN (
             'ai_project_chapter_head',
             'ai_project_ingest_generation',
             'ai_project_ingest_outbox',
             'ai_project_extraction_candidate',
             'ai_project_tombstone'
           )) < 5 AS mismatch_count
UNION ALL
SELECT 'phase24_duplicate_active_generation_per_chapter',
       COUNT(*) FROM (
         SELECT user_id, project_id, work_id, chapter_no
         FROM ai_project_ingest_generation
         WHERE status = 'ACTIVE'
         GROUP BY user_id, project_id, work_id, chapter_no
         HAVING COUNT(*) > 1
       ) d
UNION ALL
SELECT 'phase24_head_without_active_generation',
       COUNT(*)
FROM ai_project_chapter_head h
WHERE h.tombstoned_at IS NULL
  AND h.active_generation_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_ingest_generation g
      WHERE g.generation_id = h.active_generation_id
        AND g.status = 'ACTIVE'
  )
UNION ALL
SELECT 'phase24_active_generation_without_head',
       COUNT(*)
FROM ai_project_ingest_generation g
WHERE g.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM ai_project_chapter_head h
      WHERE h.user_id = g.user_id
        AND h.project_id = g.project_id
        AND h.work_id = g.work_id
        AND h.chapter_no = g.chapter_no
        AND h.active_generation_id = g.generation_id
        AND h.tombstoned_at IS NULL
  )
UNION ALL
SELECT 'phase24_tombstoned_head_still_active_visible',
       COUNT(*)
FROM ai_project_chapter_head h
WHERE h.tombstoned_at IS NOT NULL
  AND h.active_generation_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM ai_project_ingest_generation g
      WHERE g.generation_id = h.active_generation_id
        AND g.status = 'ACTIVE'
  )
UNION ALL
SELECT 'phase24_outbox_orphans',
       COUNT(*)
FROM ai_project_ingest_outbox o
WHERE NOT EXISTS (
    SELECT 1 FROM ai_project_ingest_job j WHERE j.ingest_job_id = o.ingest_job_id
);
