DROP TABLE IF EXISTS system_config;

CREATE TABLE system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL,
    config_value CLOB,
    config_type VARCHAR(50),
    description VARCHAR(200),
    is_editable TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX uk_config_key ON system_config(config_key);

ALTER TABLE analysis_result ADD COLUMN IF NOT EXISTS result_json CLOB;
