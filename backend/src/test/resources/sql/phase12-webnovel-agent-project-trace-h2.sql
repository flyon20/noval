CREATE TABLE IF NOT EXISTS ai_project (
    project_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_project_user_status ON ai_project(user_id, status);

CREATE TABLE IF NOT EXISTS ai_project_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_conversation UNIQUE(project_id, conversation_id)
);

CREATE TABLE IF NOT EXISTS ai_agent_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    conversation_id VARCHAR(80),
    question CLOB,
    status VARCHAR(40),
    task_graph_json CLOB,
    tool_runs_json CLOB,
    evidence_pack_json CLOB,
    perspective_results_json CLOB,
    result_json CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_project ON ai_agent_trace(project_id, id);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_trace_id ON ai_agent_trace(trace_id);

CREATE TABLE IF NOT EXISTS ai_agent_cache_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(80) NOT NULL,
    cache_scope VARCHAR(60) NOT NULL,
    node_name VARCHAR(120),
    expert_name VARCHAR(120),
    cache_key_hash VARCHAR(128),
    cache_status VARCHAR(20) NOT NULL,
    prompt_prefix_hash VARCHAR(128),
    prompt_prefix_stable BOOLEAN,
    duration_ms INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_cache_event_trace ON ai_agent_cache_event(trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_agent_cache_event_scope_status ON ai_agent_cache_event(cache_scope, cache_status, created_at);

CREATE TABLE IF NOT EXISTS ai_agent_token_metric (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(80) NOT NULL,
    node_name VARCHAR(120),
    expert_name VARCHAR(120),
    model_name VARCHAR(120),
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    token_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_token_metric_trace ON ai_agent_token_metric(trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_agent_token_metric_node ON ai_agent_token_metric(node_name, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_agent_token_metric_expert ON ai_agent_token_metric(expert_name, created_at);

CREATE TABLE IF NOT EXISTS ai_project_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    memory_key VARCHAR(120) NOT NULL,
    memory_value CLOB,
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_project_memory_key UNIQUE(project_id, memory_key)
);

CREATE TABLE IF NOT EXISTS ai_memory_candidate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    candidate_type VARCHAR(40) NOT NULL,
    content CLOB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_skill_candidate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id VARCHAR(120) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content CLOB,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    eval_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    eval_result_json CLOB,
    required_tool_pass_rate DECIMAL(5,4),
    evidence_pass_rate DECIMAL(5,4),
    faithfulness_pass_rate DECIMAL(5,4),
    review_note VARCHAR(500),
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_runtime_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT,
    skill_id VARCHAR(120) NOT NULL,
    version VARCHAR(80),
    title VARCHAR(200),
    content CLOB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    intents_json CLOB,
    triggers_json CLOB,
    allowed_tools_json CLOB,
    required_evidence_json CLOB,
    prompt_fragment CLOB,
    guardrails_json CLOB,
    negative_rules_json CLOB,
    output_contract_json CLOB,
    eval_result_json CLOB,
    source_trace_id VARCHAR(80),
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_runtime_skill_skill UNIQUE(skill_id),
    CONSTRAINT uk_ai_runtime_skill_candidate UNIQUE(candidate_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_runtime_skill_status ON ai_runtime_skill(status, updated_at);
