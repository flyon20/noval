-- Phase 11: RAG evaluation, golden dataset, and trace observability schema.
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase11-rag-eval-observability.sql

CREATE TABLE IF NOT EXISTS ai_eval_golden_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'golden case id',
    suite_name VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'suite name',
    case_key VARCHAR(128) NOT NULL COMMENT 'stable case key',
    case_type VARCHAR(50) NOT NULL COMMENT 'trend / mixed_creation / single_book / etc.',
    question TEXT NOT NULL COMMENT 'evaluation question',
    request_json JSON NOT NULL COMMENT 'full request payload for replay',
    expected_intent VARCHAR(80) COMMENT 'expected domain intent',
    expected_answer_mode VARCHAR(80) COMMENT 'expected answer mode',
    expected_sub_intents JSON COMMENT 'expected sub intents',
    relevant_source_ids JSON COMMENT 'expected relevant source ids',
    required_source_types JSON COMMENT 'required source types',
    forbidden_claims JSON COMMENT 'claims that must not appear in the answer',
    answer_rubric JSON COMMENT 'human rubric or judge rubric',
    retrieval_thresholds JSON COMMENT 'metric thresholds for retrieval gate',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED / DELETED',
    tags JSON COMMENT 'case tags',
    notes VARCHAR(500) COMMENT 'operator notes',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_ai_eval_golden_case_suite_key (suite_name, case_key),
    INDEX idx_ai_eval_golden_case_suite_type (suite_name, case_type, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='golden evaluation cases';

CREATE TABLE IF NOT EXISTS ai_eval_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'evaluation run id',
    run_key VARCHAR(128) NOT NULL COMMENT 'stable run key',
    suite_name VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'suite name',
    runner_name VARCHAR(100) NOT NULL COMMENT 'runner name',
    evaluator_name VARCHAR(100) NOT NULL COMMENT 'evaluator name',
    model_name VARCHAR(100) COMMENT 'judge or answer model name',
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / PASSED / FAILED',
    total_cases INT NOT NULL DEFAULT 0 COMMENT 'total cases',
    passed_cases INT NOT NULL DEFAULT 0 COMMENT 'passed cases',
    failed_cases INT NOT NULL DEFAULT 0 COMMENT 'failed cases',
    metrics_json JSON COMMENT 'aggregated metrics',
    settings_json JSON COMMENT 'evaluation settings',
    started_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'start time',
    finished_at DATETIME COMMENT 'finish time',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    UNIQUE KEY uk_ai_eval_run_run_key (run_key),
    INDEX idx_ai_eval_run_suite_status (suite_name, status, deleted, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='evaluation run summary';

CREATE TABLE IF NOT EXISTS ai_eval_case_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'evaluation case result id',
    run_id BIGINT NOT NULL COMMENT 'evaluation run id',
    case_id BIGINT COMMENT 'golden case id',
    case_key VARCHAR(128) NOT NULL COMMENT 'stable case key',
    status VARCHAR(20) NOT NULL COMMENT 'PASSED / FAILED',
    intent VARCHAR(80) COMMENT 'observed intent',
    answer_mode VARCHAR(80) COMMENT 'observed answer mode',
    retrieval_metrics JSON COMMENT 'retrieval metrics',
    faithfulness_json JSON COMMENT 'faithfulness result',
    failures JSON COMMENT 'failure list',
    trace_id VARCHAR(64) COMMENT 'trace id',
    checkpoint_thread_id VARCHAR(128) COMMENT 'LangGraph checkpoint thread id',
    response_json JSON COMMENT 'full response payload',
    answer_text MEDIUMTEXT COMMENT 'final answer text',
    duration_ms INT NOT NULL DEFAULT 0 COMMENT 'case duration',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    INDEX idx_ai_eval_case_result_run (run_id, status, deleted),
    INDEX idx_ai_eval_case_result_case (case_key, status, deleted),
    INDEX idx_ai_eval_case_result_trace (trace_id, checkpoint_thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='evaluation case result';

CREATE TABLE IF NOT EXISTS ai_eval_trace_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'trace event id',
    run_id BIGINT NOT NULL COMMENT 'evaluation run id',
    case_id BIGINT COMMENT 'golden case id',
    case_key VARCHAR(128) NOT NULL COMMENT 'stable case key',
    trace_id VARCHAR(64) COMMENT 'trace id',
    checkpoint_thread_id VARCHAR(128) COMMENT 'LangGraph checkpoint thread id',
    node_name VARCHAR(100) NOT NULL COMMENT 'graph node name',
    event_type VARCHAR(50) NOT NULL COMMENT 'input / output / error / metrics',
    sequence_no INT NOT NULL DEFAULT 0 COMMENT 'event order',
    duration_ms INT NOT NULL DEFAULT 0 COMMENT 'node duration',
    input_json JSON COMMENT 'node input snapshot',
    output_json JSON COMMENT 'node output snapshot',
    error_message VARCHAR(500) COMMENT 'error detail',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    INDEX idx_ai_eval_trace_run_case (run_id, case_key, sequence_no),
    INDEX idx_ai_eval_trace_trace (trace_id, checkpoint_thread_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='evaluation trace events';
