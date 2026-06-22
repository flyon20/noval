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
    review_note VARCHAR(500),
    source_trace_id VARCHAR(80),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_skill_candidate_status (status, eval_status)
);
