-- Phase 20: authoritative rank refresh idempotency and database fencing.

CREATE TABLE IF NOT EXISTS crawler_rank_refresh_commit (
    idempotency_hash VARCHAR(64) PRIMARY KEY COMMENT 'hash of caller scope and idempotency key',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT 'hash of refresh arguments',
    channel_code VARCHAR(50) NOT NULL,
    board_code VARCHAR(50) NOT NULL,
    snapshot_id BIGINT NOT NULL,
    snapshot_time DATETIME NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    reused TINYINT(1) NOT NULL DEFAULT 0,
    refresh_limited TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rank_refresh_commit_snapshot (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='committed rank refresh idempotency results';

CREATE TABLE IF NOT EXISTS crawler_rank_refresh_fence (
    rank_board_id BIGINT PRIMARY KEY,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='rank refresh database fencing token';

-- Candidate Eval identities append a full SHA-256 profile fingerprint to the
-- stable golden case key and can exceed the Phase 11 128-character limit.
ALTER TABLE ai_eval_case_result
    MODIFY COLUMN case_key VARCHAR(255) NOT NULL COMMENT 'stable case key';

ALTER TABLE ai_eval_trace_event
    MODIFY COLUMN case_key VARCHAR(255) NOT NULL COMMENT 'stable case key';
