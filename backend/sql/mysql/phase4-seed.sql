-- Phase 4 seed data (dev only)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase4-seed.sql

INSERT INTO prompt_config
    (prompt_type, prompt_name, prompt_content, model_name, status, is_default, dify_workflow_id, dify_api_key_ref, deleted)
VALUES
    ('deconstruct', 'default-deconstruct', '请从结构、人物、卖点角度拆解以下内容：{{content}}', 'dify', 1, 1, '', 'DIFY_API_KEY', 0),
    ('structure', 'default-structure', '请对以下内容输出结构分析：{{content}}', 'dify', 1, 1, '', 'DIFY_API_KEY', 0),
    ('plot', 'default-plot', '请对以下内容输出情节分析：{{content}}', 'dify', 1, 1, '', 'DIFY_API_KEY', 0)
ON DUPLICATE KEY UPDATE
    prompt_content = VALUES(prompt_content),
    model_name = VALUES(model_name),
    status = VALUES(status),
    is_default = VALUES(is_default),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;

