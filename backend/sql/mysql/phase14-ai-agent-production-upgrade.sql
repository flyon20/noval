-- Phase 14: AI agent production upgrade for existing databases.
-- Usage after extracting a release on an existing server:
--   docker compose --env-file /etc/opt/noval/ssl/.env exec -T mysql \
--     sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer' \
--     < backend/sql/mysql/phase14-ai-agent-production-upgrade.sql
--
-- This file is intentionally idempotent. Phase 11/12 create tables for new
-- databases, while this phase adds columns/indexes that older existing tables
-- do not receive from CREATE TABLE IF NOT EXISTS.

DELIMITER $$

DROP PROCEDURE IF EXISTS noval_add_column_if_missing $$
CREATE PROCEDURE noval_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @noval_ddl = p_ddl;
        PREPARE noval_stmt FROM @noval_ddl;
        EXECUTE noval_stmt;
        DEALLOCATE PREPARE noval_stmt;
    END IF;
END $$

DROP PROCEDURE IF EXISTS noval_add_index_if_missing $$
CREATE PROCEDURE noval_add_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_ddl TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @noval_ddl = p_ddl;
        PREPARE noval_stmt FROM @noval_ddl;
        EXECUTE noval_stmt;
        DEALLOCATE PREPARE noval_stmt;
    END IF;
END $$

DELIMITER ;

CALL noval_add_column_if_missing('ai_eval_run', 'progress_current',
    'ALTER TABLE ai_eval_run ADD COLUMN progress_current INT NOT NULL DEFAULT 0 AFTER failed_cases');
CALL noval_add_column_if_missing('ai_eval_run', 'progress_total',
    'ALTER TABLE ai_eval_run ADD COLUMN progress_total INT NOT NULL DEFAULT 0 AFTER progress_current');
CALL noval_add_column_if_missing('ai_eval_run', 'progress_message',
    'ALTER TABLE ai_eval_run ADD COLUMN progress_message VARCHAR(500) NULL AFTER progress_total');
CALL noval_add_column_if_missing('ai_eval_run', 'cancel_requested',
    'ALTER TABLE ai_eval_run ADD COLUMN cancel_requested TINYINT(1) NOT NULL DEFAULT 0 AFTER progress_message');
CALL noval_add_column_if_missing('ai_eval_run', 'cancelled_at',
    'ALTER TABLE ai_eval_run ADD COLUMN cancelled_at DATETIME NULL AFTER cancel_requested');
CALL noval_add_column_if_missing('ai_eval_run', 'retry_count',
    'ALTER TABLE ai_eval_run ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER cancelled_at');
CALL noval_add_column_if_missing('ai_eval_run', 'max_retries',
    'ALTER TABLE ai_eval_run ADD COLUMN max_retries INT NOT NULL DEFAULT 3 AFTER retry_count');
CALL noval_add_column_if_missing('ai_eval_run', 'next_retry_at',
    'ALTER TABLE ai_eval_run ADD COLUMN next_retry_at DATETIME NULL AFTER max_retries');
CALL noval_add_column_if_missing('ai_eval_run', 'last_heartbeat_at',
    'ALTER TABLE ai_eval_run ADD COLUMN last_heartbeat_at DATETIME NULL AFTER next_retry_at');
CALL noval_add_column_if_missing('ai_eval_run', 'error_message',
    'ALTER TABLE ai_eval_run ADD COLUMN error_message VARCHAR(1000) NULL AFTER last_heartbeat_at');
CALL noval_add_column_if_missing('ai_eval_run', 'queued_at',
    'ALTER TABLE ai_eval_run ADD COLUMN queued_at DATETIME DEFAULT CURRENT_TIMESTAMP AFTER settings_json');
CALL noval_add_index_if_missing('ai_eval_run', 'idx_ai_eval_run_recovery',
    'ALTER TABLE ai_eval_run ADD INDEX idx_ai_eval_run_recovery (status, last_heartbeat_at, retry_count, deleted)');

CALL noval_add_column_if_missing('ai_skill_candidate', 'eval_result_json',
    'ALTER TABLE ai_skill_candidate ADD COLUMN eval_result_json JSON NULL AFTER eval_status');
CALL noval_add_column_if_missing('ai_skill_candidate', 'required_tool_pass_rate',
    'ALTER TABLE ai_skill_candidate ADD COLUMN required_tool_pass_rate DECIMAL(5,4) NULL AFTER eval_result_json');
CALL noval_add_column_if_missing('ai_skill_candidate', 'evidence_pass_rate',
    'ALTER TABLE ai_skill_candidate ADD COLUMN evidence_pass_rate DECIMAL(5,4) NULL AFTER required_tool_pass_rate');
CALL noval_add_column_if_missing('ai_skill_candidate', 'faithfulness_pass_rate',
    'ALTER TABLE ai_skill_candidate ADD COLUMN faithfulness_pass_rate DECIMAL(5,4) NULL AFTER evidence_pass_rate');

CALL noval_add_column_if_missing('ai_runtime_skill', 'candidate_id',
    'ALTER TABLE ai_runtime_skill ADD COLUMN candidate_id BIGINT NULL AFTER id');
CALL noval_add_column_if_missing('ai_runtime_skill', 'skill_id',
    'ALTER TABLE ai_runtime_skill ADD COLUMN skill_id VARCHAR(120) NULL AFTER candidate_id');
CALL noval_add_column_if_missing('ai_runtime_skill', 'version',
    'ALTER TABLE ai_runtime_skill ADD COLUMN version VARCHAR(80) NULL AFTER skill_id');
CALL noval_add_column_if_missing('ai_runtime_skill', 'title',
    'ALTER TABLE ai_runtime_skill ADD COLUMN title VARCHAR(200) NULL AFTER version');
CALL noval_add_column_if_missing('ai_runtime_skill', 'content',
    'ALTER TABLE ai_runtime_skill ADD COLUMN content MEDIUMTEXT NULL AFTER title');
CALL noval_add_column_if_missing('ai_runtime_skill', 'status',
    'ALTER TABLE ai_runtime_skill ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''ACTIVE'' AFTER content');
CALL noval_add_column_if_missing('ai_runtime_skill', 'intents_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN intents_json JSON NULL AFTER status');
CALL noval_add_column_if_missing('ai_runtime_skill', 'triggers_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN triggers_json JSON NULL AFTER intents_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'allowed_tools_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN allowed_tools_json JSON NULL AFTER triggers_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'required_evidence_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN required_evidence_json JSON NULL AFTER allowed_tools_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'prompt_fragment',
    'ALTER TABLE ai_runtime_skill ADD COLUMN prompt_fragment MEDIUMTEXT NULL AFTER required_evidence_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'guardrails_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN guardrails_json JSON NULL AFTER prompt_fragment');
CALL noval_add_column_if_missing('ai_runtime_skill', 'negative_rules_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN negative_rules_json JSON NULL AFTER guardrails_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'output_contract_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN output_contract_json JSON NULL AFTER negative_rules_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'eval_result_json',
    'ALTER TABLE ai_runtime_skill ADD COLUMN eval_result_json JSON NULL AFTER output_contract_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'source_trace_id',
    'ALTER TABLE ai_runtime_skill ADD COLUMN source_trace_id VARCHAR(80) NULL AFTER eval_result_json');
CALL noval_add_column_if_missing('ai_runtime_skill', 'published_at',
    'ALTER TABLE ai_runtime_skill ADD COLUMN published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP AFTER source_trace_id');
CALL noval_add_column_if_missing('ai_runtime_skill', 'disabled_at',
    'ALTER TABLE ai_runtime_skill ADD COLUMN disabled_at TIMESTAMP NULL AFTER published_at');
CALL noval_add_column_if_missing('ai_runtime_skill', 'created_at',
    'ALTER TABLE ai_runtime_skill ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP AFTER disabled_at');
CALL noval_add_column_if_missing('ai_runtime_skill', 'updated_at',
    'ALTER TABLE ai_runtime_skill ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at');
CALL noval_add_index_if_missing('ai_runtime_skill', 'idx_ai_runtime_skill_status',
    'ALTER TABLE ai_runtime_skill ADD INDEX idx_ai_runtime_skill_status (status, updated_at)');
CALL noval_add_index_if_missing('ai_runtime_skill', 'idx_ai_runtime_skill_source_trace',
    'ALTER TABLE ai_runtime_skill ADD INDEX idx_ai_runtime_skill_source_trace (source_trace_id)');

DROP PROCEDURE IF EXISTS noval_add_column_if_missing;
DROP PROCEDURE IF EXISTS noval_add_index_if_missing;
