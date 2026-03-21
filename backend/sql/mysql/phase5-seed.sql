-- Phase 5 seed data (dev only)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase5-seed.sql

INSERT INTO prompt_config
    (prompt_type, prompt_name, prompt_content, model_name, status, is_default, dify_workflow_id, dify_api_key_ref, deleted)
VALUES
    ('theme', 'default-theme', 'Please analyze the recent rank snapshots and summarize the theme trend: {{content}}', 'dify', 1, 1, '', 'DIFY_API_KEY', 0)
ON DUPLICATE KEY UPDATE
    prompt_content = VALUES(prompt_content),
    model_name = VALUES(model_name),
    status = VALUES(status),
    is_default = VALUES(is_default),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;

INSERT INTO system_config
    (config_key, config_value, config_type, description, is_editable, deleted)
VALUES
    ('ai.provider.type', 'openai-compatible', 'ai', 'AI provider type', 1, 0),
    ('ai.timeout.millis', '15000', 'ai', 'AI request timeout in milliseconds', 1, 0),
    ('ai.openai-compatible.base-url', '', 'ai', 'OpenAI compatible base URL, blank means fallback to application config', 1, 0),
    ('ai.openai-compatible.default-model', 'deepseek-chat', 'ai', 'Default OpenAI compatible model name', 1, 0),
    ('ai.openai-compatible.streaming-enabled', 'false', 'ai', 'Whether OpenAI compatible streaming is enabled', 1, 0),
    ('crawler.default.chapter-count', '3', 'crawler', 'Default crawler chapter count', 1, 0),
    ('crawler.rank.refresh-days', '5', 'crawler', 'Rank refresh days', 1, 0),
    ('crawler.rank.force-cooldown-days', '2', 'crawler', 'Rank force refresh cooldown days', 1, 0),
    ('crawler.rank.force-max-times', '2', 'crawler', 'Rank force refresh max times', 1, 0),
    ('crawler.book.refresh-days', '7', 'crawler', 'Book refresh days', 1, 0),
    ('analysis.reanalyze.cooldown-hours', '0', 'analysis', 'Analysis reanalyze cooldown hours', 1, 0),
    ('security.audit.enabled', 'true', 'security', 'Whether audit logging is enabled', 1, 0)
ON DUPLICATE KEY UPDATE
    config_value = VALUES(config_value),
    config_type = VALUES(config_type),
    description = VALUES(description),
    is_editable = VALUES(is_editable),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;
