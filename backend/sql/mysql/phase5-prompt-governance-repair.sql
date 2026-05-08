-- Repair prompt governance publish data for databases upgraded from older releases.
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase5-prompt-governance-repair.sql

INSERT INTO prompt_publish_version
    (version_no, published_by, publish_note, deleted)
SELECT
    next_version.version_no,
    1,
    'migration repair for prompt governance defaults',
    0
FROM (
    SELECT COALESCE(MAX(version_no), 0) + 1 AS version_no
    FROM prompt_publish_version
) next_version
WHERE NOT EXISTS (
    SELECT 1
    FROM prompt_publish_version
    WHERE deleted = 0
);

SET @prompt_publish_version_id := (
    SELECT id
    FROM prompt_publish_version
    WHERE deleted = 0
    ORDER BY version_no DESC, id DESC
    LIMIT 1
);

INSERT INTO prompt_publish_item
    (publish_version_id, prompt_type, prompt_config_id, prompt_name, deleted)
SELECT
    @prompt_publish_version_id,
    selected.prompt_type,
    selected.id,
    selected.prompt_name,
    0
FROM (
    SELECT pc.prompt_type, pc.id, pc.prompt_name
    FROM prompt_config pc
    JOIN (
        SELECT prompt_type, MIN(id) AS id
        FROM prompt_config
        WHERE scope_type = 'SYSTEM'
          AND status = 1
          AND deleted = 0
          AND prompt_type IN ('deconstruct', 'structure', 'plot', 'theme')
          AND (
              prompt_name = 'default'
              OR prompt_name = CONCAT('default-', prompt_type)
          )
        GROUP BY prompt_type
    ) default_prompt ON default_prompt.id = pc.id
) selected
ON DUPLICATE KEY UPDATE
    prompt_config_id = VALUES(prompt_config_id),
    prompt_name = VALUES(prompt_name),
    deleted = 0,
    update_time = CURRENT_TIMESTAMP;
