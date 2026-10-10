-- Durable chat Run execution upgrade for databases that already applied Phase 18.

CREATE TABLE IF NOT EXISTS ai_chat_run_outbox (
    outbox_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_idempotency_key VARCHAR(200) NOT NULL,
    payload JSON NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    dead_retry_count INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_chat_run_outbox_idempotency (run_id, event_idempotency_key),
    INDEX idx_ai_chat_run_outbox_pending (status, available_at, outbox_id),
    INDEX idx_ai_chat_run_outbox_dispatch_pending (event_type, status, available_at, outbox_id),
    INDEX idx_ai_chat_run_outbox_dispatch_reclaim (event_type, status, updated_at, outbox_id),
    INDEX idx_ai_chat_run_outbox_dispatch_pending_attempt
        (event_type, status, attempt_count, available_at, outbox_id),
    INDEX idx_ai_chat_run_outbox_dispatch_reclaim_attempt
        (event_type, status, attempt_count, updated_at, outbox_id),
    INDEX idx_ai_chat_run_outbox_execute_recovery
        (event_type, status, updated_at, run_id, attempt_count, outbox_id),
    INDEX idx_ai_chat_run_outbox_terminal_dead_recovery
        (event_type, status, dead_retry_count, updated_at, outbox_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='transactional chat run event outbox';

CREATE TABLE IF NOT EXISTS ai_chat_run_admission_guard (
    mode VARCHAR(40) PRIMARY KEY,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='serializes chat run admission by execution mode';

DROP PROCEDURE IF EXISTS noval_phase19_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE noval_phase19_add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @noval_phase19_column_sql = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_definition
        );
        PREPARE noval_phase19_column_statement FROM @noval_phase19_column_sql;
        EXECUTE noval_phase19_column_statement;
        DEALLOCATE PREPARE noval_phase19_column_statement;
    END IF;
END$$
DELIMITER ;

CALL noval_phase19_add_column_if_missing(
    'ai_chat_run_outbox',
    'dead_retry_count',
    '`dead_retry_count` INT NOT NULL DEFAULT 0 AFTER `attempt_count`'
);

DROP PROCEDURE IF EXISTS noval_phase19_add_column_if_missing;

INSERT INTO ai_chat_run_admission_guard(mode, updated_at)
VALUES('FAST', CURRENT_TIMESTAMP), ('DEEP', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE updated_at = updated_at;

DROP PROCEDURE IF EXISTS noval_phase19_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE noval_phase19_add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_definition VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @noval_phase19_sql = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD ', p_index_definition
        );
        PREPARE noval_phase19_statement FROM @noval_phase19_sql;
        EXECUTE noval_phase19_statement;
        DEALLOCATE PREPARE noval_phase19_statement;
    END IF;
END$$
DELIMITER ;

CALL noval_phase19_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_dispatch_pending',
    'INDEX `idx_ai_chat_run_outbox_dispatch_pending` (`event_type`, `status`, `available_at`, `outbox_id`)'
);
CALL noval_phase19_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_dispatch_reclaim',
    'INDEX `idx_ai_chat_run_outbox_dispatch_reclaim` (`event_type`, `status`, `updated_at`, `outbox_id`)'
);
CALL noval_phase19_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_dispatch_pending_attempt',
    'INDEX `idx_ai_chat_run_outbox_dispatch_pending_attempt` (`event_type`, `status`, `attempt_count`, `available_at`, `outbox_id`)'
);
CALL noval_phase19_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_dispatch_reclaim_attempt',
    'INDEX `idx_ai_chat_run_outbox_dispatch_reclaim_attempt` (`event_type`, `status`, `attempt_count`, `updated_at`, `outbox_id`)'
);
CALL noval_phase19_add_index_if_missing(
    'ai_chat_run',
    'idx_ai_chat_run_pending_execution_recovery',
    'INDEX `idx_ai_chat_run_pending_execution_recovery` (`status`, `deleted`, `update_time`, `run_id`)'
);
CALL noval_phase19_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_execute_recovery',
    'INDEX `idx_ai_chat_run_outbox_execute_recovery` (`event_type`, `status`, `updated_at`, `run_id`, `attempt_count`, `outbox_id`)'
);
CALL noval_phase19_add_index_if_missing(
    'ai_chat_run_outbox',
    'idx_ai_chat_run_outbox_terminal_dead_recovery',
    'INDEX `idx_ai_chat_run_outbox_terminal_dead_recovery` (`event_type`, `status`, `dead_retry_count`, `updated_at`, `outbox_id`)'
);

DROP PROCEDURE IF EXISTS noval_phase19_add_index_if_missing;

-- Existing Phase 18 deployments may contain PENDING Runs created before durable
-- EXECUTE handoff existed. Backfill the event and outbox atomically and use the
-- same idempotency key as new Runs so repeated migration execution is harmless.
-- phase19:h2-backfill-start
DROP TABLE IF EXISTS noval_phase19_pending_execute_backfill;
-- Inherit run_id charset/collation from ai_chat_run. Declaring VARCHAR here
-- would use the database default and can break joins on restored databases.
CREATE TEMPORARY TABLE noval_phase19_pending_execute_backfill AS
SELECT run_id, CAST(0 AS SIGNED) AS sequence_no
FROM ai_chat_run
WHERE 1 = 0;
CREATE UNIQUE INDEX uk_noval_phase19_pending_execute_run
    ON noval_phase19_pending_execute_backfill(run_id);

START TRANSACTION;

UPDATE ai_chat_run
SET next_sequence_no = GREATEST(
    COALESCE(next_sequence_no, 0),
    COALESCE((
        SELECT MAX(existing_event.sequence_no)
        FROM ai_chat_run_event existing_event
        WHERE existing_event.run_id = ai_chat_run.run_id
    ), 0)
)
WHERE status = 'PENDING'
  AND deleted = 0;

INSERT INTO noval_phase19_pending_execute_backfill(run_id, sequence_no)
SELECT r.run_id,
       GREATEST(COALESCE(r.next_sequence_no, 0), COALESCE(MAX(e.sequence_no), 0)) + 1
FROM ai_chat_run r
LEFT JOIN ai_chat_run_event e ON e.run_id = r.run_id
WHERE r.status = 'PENDING'
  AND r.deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM ai_chat_run_event execute_event
      WHERE execute_event.run_id = r.run_id
        AND execute_event.event_idempotency_key = CONCAT('run:', r.run_id, ':execute')
  )
GROUP BY r.run_id, r.next_sequence_no;

UPDATE ai_chat_run
SET next_sequence_no = COALESCE((
    SELECT backfill.sequence_no
    FROM noval_phase19_pending_execute_backfill backfill
    WHERE backfill.run_id = ai_chat_run.run_id
), next_sequence_no)
WHERE status = 'PENDING'
  AND deleted = 0;

INSERT INTO ai_chat_run_event(
    run_id,
    sequence_no,
    event_type,
    event_idempotency_key,
    payload,
    created_at
)
SELECT backfill.run_id,
       backfill.sequence_no,
       'EXECUTE',
       CONCAT('run:', backfill.run_id, ':execute'),
       NULL,
       CURRENT_TIMESTAMP
FROM noval_phase19_pending_execute_backfill backfill
WHERE NOT EXISTS (
    SELECT 1
    FROM ai_chat_run_event existing_event
    WHERE existing_event.run_id = backfill.run_id
      AND existing_event.event_idempotency_key = CONCAT('run:', backfill.run_id, ':execute')
);

INSERT INTO ai_chat_run_outbox(
    event_id,
    run_id,
    sequence_no,
    event_type,
    event_idempotency_key,
    payload,
    status,
    attempt_count,
    available_at,
    created_at,
    updated_at
)
SELECT execute_event.event_id,
       execute_event.run_id,
       execute_event.sequence_no,
       'EXECUTE',
       execute_event.event_idempotency_key,
       execute_event.payload,
       'PENDING',
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM ai_chat_run_event execute_event
JOIN ai_chat_run pending_run ON pending_run.run_id = execute_event.run_id
WHERE pending_run.status = 'PENDING'
  AND pending_run.deleted = 0
  AND execute_event.event_type = 'EXECUTE'
  AND execute_event.event_idempotency_key = CONCAT('run:', pending_run.run_id, ':execute')
  AND NOT EXISTS (
      SELECT 1
      FROM ai_chat_run_outbox existing_outbox
      WHERE existing_outbox.run_id = execute_event.run_id
        AND existing_outbox.event_idempotency_key = execute_event.event_idempotency_key
  );

COMMIT;

DROP TABLE IF EXISTS noval_phase19_pending_execute_backfill;
-- phase19:h2-backfill-end
