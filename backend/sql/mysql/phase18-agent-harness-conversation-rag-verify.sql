-- Phase 18 conversation backfill verification.
-- All rows in the mismatch section must report zero before increasing read rollout.

SELECT
    (SELECT COUNT(1) FROM ai_chat_run WHERE deleted = 0) AS legacy_run_count,
    (SELECT COUNT(1) FROM ai_chat_message WHERE role = 'USER' AND deleted = 0) AS user_message_count,
    (SELECT COUNT(1) FROM ai_chat_run r WHERE r.deleted = 0 AND r.answer IS NOT NULL
        AND TRIM(r.answer) <> '' AND (
            r.status = 'ANSWERED' OR (
                r.status IN ('FAILED', 'CANCELLED')
                AND r.progress_phase IN ('answer', 'compose', 'done')
                AND r.result_json IS NOT NULL
                AND REPLACE(TRIM(r.result_json), ' ', '') NOT IN ('', '{}', 'null')
            )
        )) AS expected_assistant_message_count,
    (SELECT COUNT(1) FROM ai_chat_message WHERE role = 'ASSISTANT' AND deleted = 0)
        AS assistant_message_count,
    (SELECT COUNT(1) FROM ai_conversation) AS conversation_count,
    (SELECT COUNT(1) FROM ai_conversation_legacy_map) AS legacy_mapping_count,
    (SELECT COUNT(1) FROM (
        SELECT r.user_id, COALESCE(r.project_id, -1),
            CASE WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
                THEN CONCAT('__EMPTY__:', r.run_id)
                ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END
        FROM ai_chat_run r
        WHERE r.deleted = 0 AND (
            r.legacy_conversation_id IS NOT NULL
            OR r.trigger_message_id IS NULL
            OR r.request_id LIKE 'legacy-%'
        )
        GROUP BY r.user_id, COALESCE(r.project_id, -1),
            CASE WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
                THEN CONCAT('__EMPTY__:', r.run_id)
                ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END
    ) expected) AS expected_conversation_count;

SELECT 'ownership_mismatch' AS check_name, COUNT(1) AS mismatch_count
FROM ai_chat_message m
LEFT JOIN ai_conversation c ON c.conversation_id = m.conversation_id
WHERE c.conversation_id IS NULL
   OR c.user_id <> m.user_id
   OR COALESCE(c.project_id, -1) <> COALESCE(m.project_id, -1)
UNION ALL
SELECT 'missing_user_message', COUNT(1)
FROM ai_chat_run r
WHERE r.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM ai_chat_message m
      WHERE m.run_id = r.run_id AND m.role = 'USER' AND m.deleted = 0
  )
UNION ALL
SELECT 'unexpected_user_message', COUNT(1)
FROM ai_chat_message m
WHERE m.role = 'USER' AND m.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM ai_chat_run r WHERE r.run_id = m.run_id AND r.deleted = 0
  )
UNION ALL
SELECT 'missing_assistant_message', COUNT(1)
FROM ai_chat_run r
WHERE r.deleted = 0 AND r.answer IS NOT NULL AND TRIM(r.answer) <> ''
  AND (
      r.status = 'ANSWERED' OR (
          r.status IN ('FAILED', 'CANCELLED')
          AND r.progress_phase IN ('answer', 'compose', 'done')
          AND r.result_json IS NOT NULL
          AND REPLACE(TRIM(r.result_json), ' ', '') NOT IN ('', '{}', 'null')
      )
  )
  AND NOT EXISTS (
      SELECT 1 FROM ai_chat_message m
      WHERE m.run_id = r.run_id AND m.role = 'ASSISTANT' AND m.deleted = 0
  )
UNION ALL
SELECT 'unexpected_assistant_message', COUNT(1)
FROM ai_chat_message m
JOIN ai_chat_run r ON r.run_id = m.run_id AND r.deleted = 0
WHERE m.role = 'ASSISTANT' AND m.deleted = 0
  AND NOT (
      r.answer IS NOT NULL AND TRIM(r.answer) <> '' AND (
          r.status = 'ANSWERED' OR (
              r.status IN ('FAILED', 'CANCELLED')
              AND r.progress_phase IN ('answer', 'compose', 'done')
              AND r.result_json IS NOT NULL
              AND REPLACE(TRIM(r.result_json), ' ', '') NOT IN ('', '{}', 'null')
          )
      )
  )
UNION ALL
SELECT 'content_hash_mismatch', COUNT(1)
FROM ai_chat_message m
JOIN ai_chat_run r ON r.run_id = m.run_id AND r.deleted = 0
WHERE m.deleted = 0 AND m.role IN ('USER', 'ASSISTANT')
  AND SHA2(COALESCE(m.content, ''), 256) <>
      SHA2(COALESCE(CASE WHEN m.role = 'USER' THEN r.question ELSE r.answer END, ''), 256)
UNION ALL
SELECT 'conversation_last_message_mismatch', COUNT(1)
FROM ai_conversation c
WHERE (c.last_message_id IS NULL AND EXISTS (
        SELECT 1 FROM ai_chat_message present
        WHERE present.conversation_id = c.conversation_id AND present.deleted = 0
    )) OR (c.last_message_id IS NOT NULL AND c.last_message_id <> COALESCE((
        SELECT latest.message_id FROM ai_chat_message latest
        WHERE latest.conversation_id = c.conversation_id AND latest.deleted = 0
        ORDER BY latest.created_at DESC, latest.message_id DESC LIMIT 1
    ), -1))
UNION ALL
SELECT 'run_scope_mismatch', COUNT(1)
FROM ai_chat_run r
JOIN ai_conversation c ON c.conversation_id = r.conversation_id
WHERE r.deleted = 0
  AND (r.user_id <> c.user_id OR COALESCE(r.project_id, -1) <> COALESCE(c.project_id, -1))
UNION ALL
SELECT 'mapping_scope_mismatch', COUNT(1)
FROM ai_conversation_legacy_map lm
JOIN ai_conversation c ON c.conversation_id = lm.canonical_conversation_id
WHERE lm.user_id <> c.user_id
   OR COALESCE(lm.project_id, -1) <> COALESCE(c.project_id, -1)
UNION ALL
SELECT 'conversation_count_mismatch', IF(
    (SELECT COUNT(DISTINCT canonical_conversation_id) FROM ai_conversation_legacy_map) =
    (SELECT COUNT(1) FROM (
        SELECT r.user_id, COALESCE(r.project_id, -1),
            CASE WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
                THEN CONCAT('__EMPTY__:', r.run_id)
                ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END
        FROM ai_chat_run r
        WHERE r.deleted = 0 AND (
            r.legacy_conversation_id IS NOT NULL
            OR r.trigger_message_id IS NULL
            OR r.request_id LIKE 'legacy-%'
        )
        GROUP BY r.user_id, COALESCE(r.project_id, -1),
            CASE WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
                THEN CONCAT('__EMPTY__:', r.run_id)
                ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END
    ) expected),
    0,
    1
)
UNION ALL
SELECT 'mapping_count_mismatch', COUNT(1)
FROM (
    SELECT expected.user_id, expected.project_scope_id, expected.legacy_key,
        COUNT(m.map_id) AS mapping_count
    FROM (
        SELECT r.user_id, COALESCE(r.project_id, -1) AS project_scope_id,
            CASE WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
                THEN CONCAT('__EMPTY__:', r.run_id)
                ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END AS legacy_key
        FROM ai_chat_run r
        WHERE r.deleted = 0 AND (
            r.legacy_conversation_id IS NOT NULL
            OR r.trigger_message_id IS NULL
            OR r.request_id LIKE 'legacy-%'
        )
        GROUP BY r.user_id, COALESCE(r.project_id, -1),
            CASE WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
                THEN CONCAT('__EMPTY__:', r.run_id)
                ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END
    ) expected
    LEFT JOIN ai_conversation_legacy_map m ON m.user_id = expected.user_id
        AND m.project_scope_id = expected.project_scope_id
        AND m.legacy_conversation_id = expected.legacy_key
    GROUP BY expected.user_id, expected.project_scope_id, expected.legacy_key
    HAVING COUNT(m.map_id) <> 1
) mismatched
UNION ALL
SELECT 'unexpected_mapping', COUNT(1)
FROM ai_conversation_legacy_map m
WHERE NOT EXISTS (
    SELECT 1 FROM ai_chat_run r
    WHERE r.deleted = 0 AND r.user_id = m.user_id
      AND (r.legacy_conversation_id IS NOT NULL
           OR r.trigger_message_id IS NULL
           OR r.request_id LIKE 'legacy-%')
      AND COALESCE(r.project_id, -1) = m.project_scope_id
      AND m.legacy_conversation_id = CASE
          WHEN TRIM(COALESCE(r.legacy_conversation_id, r.conversation_id, '')) = ''
              THEN CONCAT('__EMPTY__:', r.run_id)
          ELSE COALESCE(r.legacy_conversation_id, r.conversation_id) END
);

SELECT state_key, last_queued_at, last_run_id, processed_run_count, updated_at
FROM ai_conversation_migration_state
WHERE state_key = 'phase18-conversation-backfill';
