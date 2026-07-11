CREATE TABLE IF NOT EXISTS ai_project_work (
    work_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    alias VARCHAR(500),
    genre VARCHAR(80),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_work_project (project_id, status, updated_at),
    INDEX idx_ai_project_work_user_title (user_id, title)
);

CREATE TABLE IF NOT EXISTS ai_project_chapter (
    chapter_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    title VARCHAR(200),
    content MEDIUMTEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    word_count INT NOT NULL DEFAULT 0,
    source_type VARCHAR(40) NOT NULL DEFAULT 'upload',
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_chapter_hash (work_id, chapter_no, content_hash),
    INDEX idx_ai_project_chapter_work (work_id, chapter_no, version),
    INDEX idx_ai_project_chapter_project (project_id, status, updated_at)
);

CREATE TABLE IF NOT EXISTS ai_project_scene (
    scene_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    scene_no INT NOT NULL,
    summary MEDIUMTEXT,
    pov VARCHAR(120),
    location VARCHAR(200),
    time_marker VARCHAR(200),
    start_offset INT,
    end_offset INT,
    confidence DECIMAL(5,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_scene_chapter (chapter_id, scene_no),
    INDEX idx_ai_project_scene_project (project_id, work_id)
);

CREATE TABLE IF NOT EXISTS ai_project_character (
    character_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    aliases VARCHAR(500),
    role VARCHAR(80),
    current_state MEDIUMTEXT,
    desire MEDIUMTEXT,
    risk_notes MEDIUMTEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_character_work (work_id, name),
    INDEX idx_ai_project_character_project (project_id, status)
);

CREATE TABLE IF NOT EXISTS ai_project_character_state (
    state_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    character_id BIGINT,
    character_name VARCHAR(120) NOT NULL,
    chapter_id BIGINT,
    chapter_no INT,
    scene_id BIGINT,
    state_summary MEDIUMTEXT,
    motivation MEDIUMTEXT,
    conflict_note MEDIUMTEXT,
    confidence DECIMAL(5,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_character_state_work (work_id, character_name, chapter_no)
);

CREATE TABLE IF NOT EXISTS ai_project_world_rule (
    rule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    rule_type VARCHAR(80),
    title VARCHAR(200) NOT NULL,
    content MEDIUMTEXT,
    first_chapter_no INT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    confidence DECIMAL(5,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_world_rule_work (work_id, rule_type, status)
);

CREATE TABLE IF NOT EXISTS ai_project_foreshadowing (
    foreshadowing_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content MEDIUMTEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    planted_chapter_no INT,
    paid_off_chapter_no INT,
    importance VARCHAR(30),
    evidence_refs JSON,
    confidence DECIMAL(5,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_foreshadowing_work (work_id, status, planted_chapter_no),
    INDEX idx_ai_project_foreshadowing_project (project_id, status)
);

CREATE TABLE IF NOT EXISTS ai_project_timeline_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT,
    chapter_no INT,
    scene_id BIGINT,
    event_order INT,
    title VARCHAR(200) NOT NULL,
    summary MEDIUMTEXT,
    causal_refs JSON,
    confidence DECIMAL(5,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_timeline_work (work_id, chapter_no, event_order)
);

CREATE TABLE IF NOT EXISTS ai_project_ingest_job (
    ingest_job_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT,
    job_type VARCHAR(60) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    progress INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(500),
    result_json JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_project_ingest_job_work (work_id, status, updated_at),
    INDEX idx_ai_project_ingest_job_project (project_id, status)
);

CREATE TABLE IF NOT EXISTS ai_project_vector_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT,
    scene_id BIGINT,
    source_type VARCHAR(60) NOT NULL,
    source_id BIGINT,
    content_hash VARCHAR(128) NOT NULL,
    qdrant_point_id VARCHAR(160) NOT NULL,
    chunk_text MEDIUMTEXT,
    visibility VARCHAR(30) NOT NULL DEFAULT 'private',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_project_vector_point (qdrant_point_id),
    INDEX idx_ai_project_vector_work (work_id, source_type, source_id),
    INDEX idx_ai_project_vector_project (project_id, source_type)
);
