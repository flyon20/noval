DROP TABLE IF EXISTS ai_eval_trace_event;
DROP TABLE IF EXISTS ai_eval_case_result;
DROP TABLE IF EXISTS ai_eval_run;
DROP TABLE IF EXISTS ai_eval_golden_case;

CREATE TABLE ai_eval_golden_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    suite_name VARCHAR(100) NOT NULL DEFAULT 'default',
    case_key VARCHAR(128) NOT NULL,
    case_type VARCHAR(50) NOT NULL,
    question CLOB NOT NULL,
    request_json CLOB NOT NULL,
    expected_intent VARCHAR(80),
    expected_answer_mode VARCHAR(80),
    expected_sub_intents CLOB,
    relevant_source_ids CLOB,
    required_source_types CLOB,
    forbidden_claims CLOB,
    answer_rubric CLOB,
    retrieval_thresholds CLOB,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    tags CLOB,
    notes VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    UNIQUE (suite_name, case_key)
);

CREATE TABLE ai_eval_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_key VARCHAR(128) NOT NULL,
    suite_name VARCHAR(100) NOT NULL DEFAULT 'default',
    runner_name VARCHAR(100) NOT NULL,
    evaluator_name VARCHAR(100) NOT NULL,
    model_name VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    total_cases INT NOT NULL DEFAULT 0,
    passed_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    metrics_json CLOB,
    settings_json CLOB,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    UNIQUE (run_key)
);

CREATE TABLE ai_eval_case_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    case_id BIGINT,
    case_key VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    intent VARCHAR(80),
    answer_mode VARCHAR(80),
    retrieval_metrics CLOB,
    faithfulness_json CLOB,
    failures CLOB,
    trace_id VARCHAR(64),
    checkpoint_thread_id VARCHAR(128),
    response_json CLOB,
    answer_text CLOB,
    duration_ms INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE ai_eval_trace_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    case_id BIGINT,
    case_key VARCHAR(128) NOT NULL,
    trace_id VARCHAR(64),
    checkpoint_thread_id VARCHAR(128),
    node_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    sequence_no INT NOT NULL DEFAULT 0,
    duration_ms INT NOT NULL DEFAULT 0,
    input_json CLOB,
    output_json CLOB,
    error_message VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
