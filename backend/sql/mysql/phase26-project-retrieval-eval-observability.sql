-- Phase 26: project retrieval eval observability, baselines, and feedback audit.
-- Additive only. Feedback rows never promote Memory to CONFIRMED.

CREATE TABLE IF NOT EXISTS ai_project_retrieval_eval_baseline (
    baseline_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    suite_name VARCHAR(100) NOT NULL,
    baseline_key VARCHAR(128) NOT NULL,
    generation_scope VARCHAR(64) NULL,
    cohort_json JSON NULL,
    metrics_json JSON NOT NULL,
    confidence_json JSON NULL,
    gate_json JSON NULL,
    corpus_version VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    notes VARCHAR(500) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_project_retrieval_eval_baseline (suite_name, baseline_key),
    INDEX idx_ai_project_retrieval_eval_baseline_status (suite_name, status, deleted, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='project retrieval release baselines';

CREATE TABLE IF NOT EXISTS ai_project_knowledge_feedback (
    feedback_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NULL,
    generation_id BIGINT NULL,
    conversation_id VARCHAR(128) NULL,
    trace_id VARCHAR(64) NULL,
    feedback_type VARCHAR(40) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_key VARCHAR(240) NOT NULL,
    old_value_json JSON NULL,
    new_value_json JSON NULL,
    evidence_json JSON NULL,
    operator_user_id BIGINT NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    notes VARCHAR(500) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_ai_project_knowledge_feedback_scope (user_id, project_id, work_id, generation_id, deleted),
    INDEX idx_ai_project_knowledge_feedback_type (feedback_type, review_status, created_at),
    INDEX idx_ai_project_knowledge_feedback_target (target_type, target_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='project knowledge correction feedback audit';

CREATE TABLE IF NOT EXISTS ai_agent_resource_diagnostic (
    diagnostic_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    project_id BIGINT NULL,
    run_id VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    generation_id BIGINT NULL,
    partial_flush TINYINT(1) NOT NULL DEFAULT 0,
    event_name VARCHAR(80) NULL,
    mysql_write_ms INT NULL,
    tool_dedupe_prevented TINYINT(1) NOT NULL DEFAULT 0,
    crawl_reuse TINYINT(1) NOT NULL DEFAULT 0,
    vector_latency_ms INT NULL,
    active_run_count INT NULL,
    queue_wait_ms INT NULL,
    degradation_reasons JSON NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_agent_resource_diagnostic_trace (trace_id, created_at),
    INDEX idx_ai_agent_resource_diagnostic_run (run_id, created_at),
    INDEX idx_ai_agent_resource_diagnostic_scope (user_id, project_id, generation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='bounded agent resource diagnostics';
