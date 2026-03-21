-- Phase 5 seed data (dev only)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase5-seed.sql

INSERT INTO prompt_config
    (prompt_type, prompt_name, prompt_content, model_name, status, is_default, dify_workflow_id, dify_api_key_ref, output_json_schema, output_example_json, post_process_type, parse_config_json, deleted)
VALUES
    ('theme', 'default-theme', 'Please analyze the recent rank snapshots and summarize the theme trend: {{content}}', 'dify', 1, 1, '', 'DIFY_API_KEY', '{"type":"object","properties":{"analysisType":{"type":"string"},"themeTable":{"type":"array"}},"required":["analysisType","themeTable"]}', '{"analysisType":"theme","themeTable":[{"theme":"urban-brain","count":1,"trend":"up"}],"comparisonSummary":"sample"}', 'json_extract', '{"parser":"json","trimMarkdownFence":true}', 0)
ON DUPLICATE KEY UPDATE
    prompt_content = VALUES(prompt_content),
    model_name = VALUES(model_name),
    status = VALUES(status),
    is_default = VALUES(is_default),
    output_json_schema = VALUES(output_json_schema),
    output_example_json = VALUES(output_example_json),
    post_process_type = VALUES(post_process_type),
    parse_config_json = VALUES(parse_config_json),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;

INSERT INTO rank_board
    (id, platform, channel_code, board_code, board_name, description, deleted)
VALUES
    (5001, 'fanqie', 'male-new', 'urban-brain', 'Urban Brain', 'Shared board foundation for phase 5', 0)
ON DUPLICATE KEY UPDATE
    board_name = VALUES(board_name),
    description = VALUES(description),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;

INSERT INTO rank_snapshot
    (id, rank_board_id, snapshot_time, record_count, deleted)
VALUES
    (6001, 5001, '2026-03-20 11:30:00', 2, 0)
ON DUPLICATE KEY UPDATE
    record_count = VALUES(record_count),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;

INSERT INTO crawl_rank
    (id, platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
VALUES
    (2101, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 1, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', '2026-03-20 11:30:00', '2026-03-20 11:30:00', 0),
    (2102, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 2, 1002, 'Book Two', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Intro two', '2026-03-20 11:30:00', '2026-03-20 11:30:00', 0)
ON DUPLICATE KEY UPDATE
    snapshot_id = VALUES(snapshot_id),
    channel_code = VALUES(channel_code),
    board_code = VALUES(board_code),
    rank_no = VALUES(rank_no),
    book_name = VALUES(book_name),
    book_url = VALUES(book_url),
    author = VALUES(author),
    intro = VALUES(intro),
    crawl_time = VALUES(crawl_time),
    deleted = VALUES(deleted);

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
