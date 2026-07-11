CREATE TABLE IF NOT EXISTS ai_project (
    project_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS ai_project_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_conversation (project_id, conversation_id),
    INDEX idx_ai_project_conversation_user (user_id, conversation_id)
);

CREATE TABLE IF NOT EXISTS ai_agent_trace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    conversation_id VARCHAR(80),
    question MEDIUMTEXT,
    status VARCHAR(40),
    task_graph_json MEDIUMTEXT,
    tool_runs_json MEDIUMTEXT,
    evidence_pack_json MEDIUMTEXT,
    perspective_results_json MEDIUMTEXT,
    result_json MEDIUMTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_agent_trace_created (created_at),
    INDEX idx_ai_agent_trace_project (project_id, id),
    INDEX idx_ai_agent_trace_trace_id (trace_id)
);

CREATE TABLE IF NOT EXISTS ai_agent_cache_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(80) NOT NULL,
    cache_scope VARCHAR(60) NOT NULL COMMENT 'intent/task_graph/tool/evidence/specialist',
    node_name VARCHAR(120),
    expert_name VARCHAR(120),
    cache_key_hash VARCHAR(128),
    cache_status VARCHAR(20) NOT NULL COMMENT 'HIT/MISS/BYPASS',
    prompt_prefix_hash VARCHAR(128),
    prompt_prefix_stable TINYINT(1),
    duration_ms INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_agent_cache_event_trace (trace_id),
    INDEX idx_ai_agent_cache_event_scope_status (cache_scope, cache_status, created_at)
);

CREATE TABLE IF NOT EXISTS ai_agent_token_metric (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(80) NOT NULL,
    node_name VARCHAR(120),
    expert_name VARCHAR(120),
    model_name VARCHAR(120),
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    token_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_agent_token_metric_trace (trace_id),
    INDEX idx_ai_agent_token_metric_node (node_name, created_at),
    INDEX idx_ai_agent_token_metric_expert (expert_name, created_at)
);

CREATE TABLE IF NOT EXISTS ai_project_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    memory_key VARCHAR(120) NOT NULL,
    memory_value MEDIUMTEXT,
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_memory_key (project_id, memory_key)
);

CREATE TABLE IF NOT EXISTS ai_memory_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    candidate_type VARCHAR(40) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_memory_candidate_project (project_id, status)
);

CREATE TABLE IF NOT EXISTS ai_skill_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id VARCHAR(120) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content MEDIUMTEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    eval_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    eval_result_json JSON COMMENT 'structured skill eval result payload',
    required_tool_pass_rate DECIMAL(5,4) COMMENT 'required tool invocation pass rate',
    evidence_pass_rate DECIMAL(5,4) COMMENT 'evidence contract pass rate',
    faithfulness_pass_rate DECIMAL(5,4) COMMENT 'faithfulness pass rate',
    review_note VARCHAR(500),
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_skill_candidate_status (status, eval_status)
);

CREATE TABLE IF NOT EXISTS ai_runtime_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    candidate_id BIGINT,
    skill_id VARCHAR(120) NOT NULL,
    version VARCHAR(80),
    title VARCHAR(200),
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / ROLLED_BACK',
    intents_json JSON,
    triggers_json JSON,
    allowed_tools_json JSON,
    required_evidence_json JSON,
    prompt_fragment MEDIUMTEXT,
    guardrails_json JSON,
    negative_rules_json JSON,
    output_contract_json JSON,
    eval_result_json JSON,
    source_trace_id VARCHAR(80),
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_runtime_skill_skill (skill_id),
    UNIQUE KEY uk_ai_runtime_skill_candidate (candidate_id),
    INDEX idx_ai_runtime_skill_status (status, updated_at),
    INDEX idx_ai_runtime_skill_source_trace (source_trace_id)
);
