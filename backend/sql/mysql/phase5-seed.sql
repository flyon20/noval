-- Phase 5 seed data (dev only)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase5-seed.sql

INSERT INTO prompt_config
    (prompt_type, prompt_name, prompt_content, model_name, status, is_default, dify_workflow_id, dify_api_key_ref, output_json_schema, output_example_json, post_process_type, parse_config_json, deleted)
VALUES
    (
        'theme',
        'default-theme',
        'Analyze the exact selected rank board for the last three snapshots and return valid JSON only: {{content}}',
        'dify',
        1,
        1,
        '',
        'DIFY_API_KEY',
        '{"type":"object","properties":{"analysisType":{"type":"string"},"platform":{"type":"string"},"channelCode":{"type":"string"},"boardCode":{"type":"string"},"boardName":{"type":"string"},"summary":{"type":"string"},"historicalWordCloud":{"type":"array"},"themeTable":{"type":"array"},"snapshotComparisons":{"type":"array"},"hotBooks":{"type":"array"},"insightCards":{"type":"array"},"comparisonSummary":{"type":"string"},"historyAnalysisCount":{"type":"integer"},"detailContent":{"type":"string"}},"required":["analysisType","summary","historicalWordCloud","themeTable","snapshotComparisons","hotBooks","insightCards","comparisonSummary","historyAnalysisCount"]}',
        '{"analysisType":"theme","platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","boardName":"Urban Brain","summary":"Urban-brain remains the clearest direction across the latest board snapshots.","historicalWordCloud":[{"name":"urban-brain","value":24},{"name":"system-flow","value":15}],"themeTable":[{"theme":"urban-brain","count":3,"trend":"rising"},{"theme":"system-flow","count":2,"trend":"stable"}],"snapshotComparisons":[{"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","change":"holding"}],"hotBooks":[{"bookName":"Brain City King","author":"Author One","rankLabel":"#1","reason":"Keeps leading the board"}],"insightCards":[{"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"}],"comparisonSummary":"Urban-brain has become the clearest board-level direction.","historyAnalysisCount":3,"detailContent":"Detailed board trend analysis."}',
        'json_extract',
        '{"parser":"json","trimMarkdownFence":true}',
        0
    )
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
    (6001, 5001, '2026-03-20 11:30:00', 2, 0),
    (6002, 5001, '2026-03-19 11:30:00', 2, 0),
    (6003, 5001, '2026-03-18 11:30:00', 2, 0)
ON DUPLICATE KEY UPDATE
    record_count = VALUES(record_count),
    deleted = VALUES(deleted),
    update_time = CURRENT_TIMESTAMP;

INSERT INTO crawl_rank
    (id, platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
VALUES
    (2101, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 1, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', '2026-03-20 11:30:00', '2026-03-20 11:30:00', 0),
    (2102, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', '2026-03-20 11:30:00', '2026-03-20 11:30:00', 0),
    (2103, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6002, 1, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', '2026-03-19 11:30:00', '2026-03-19 11:30:00', 0),
    (2104, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6002, 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', '2026-03-19 11:30:00', '2026-03-19 11:30:00', 0),
    (2105, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6003, 1, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', '2026-03-18 11:30:00', '2026-03-18 11:30:00', 0),
    (2106, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6003, 2, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', '2026-03-18 11:30:00', '2026-03-18 11:30:00', 0)
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

INSERT INTO analysis_result
    (id, user_id, platform, book_id, channel_code, board_code, snapshot_id, analysis_type, chapter_count, prompt_config_id, model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted)
VALUES
    (
        3004,
        1,
        'fanqie',
        1001,
        'male-new',
        'urban-brain',
        6001,
        'theme',
        0,
        4,
        'dify',
        'Detailed board trend analysis for the latest three urban-brain snapshots.',
        '{"analysisType":"theme","platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","boardName":"Urban Brain","summary":"Urban-brain remains the clearest direction across the latest board snapshots.","historicalWordCloud":[{"name":"urban-brain","value":24},{"name":"system-flow","value":15}],"themeTable":[{"theme":"urban-brain","count":3,"trend":"rising"},{"theme":"system-flow","count":2,"trend":"stable"}],"snapshotComparisons":[{"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","change":"holding"},{"snapshotTime":"2026-03-19 11:30:00","topTheme":"urban-brain","change":"rising"},{"snapshotTime":"2026-03-18 11:30:00","topTheme":"system-flow","change":"baseline"}],"hotBooks":[{"bookName":"Brain City King","author":"Author One","rankLabel":"#1","reason":"Keeps leading the board"}],"insightCards":[{"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"},{"label":"Lead title","value":"Brain City King","note":"Most representative latest book"}],"comparisonSummary":"Urban-brain has become the clearest board-level direction across the last three snapshots.","historyAnalysisCount":3,"trendPreview":"Urban-brain continues to dominate this board.","detailContent":"Detailed board trend analysis for the last three snapshots."}',
        160,
        600,
        '2026-03-20 12:30:00',
        '2026-03-20 12:30:00',
        0
    )
ON DUPLICATE KEY UPDATE
    channel_code = VALUES(channel_code),
    board_code = VALUES(board_code),
    snapshot_id = VALUES(snapshot_id),
    prompt_config_id = VALUES(prompt_config_id),
    model_name = VALUES(model_name),
    result_content = VALUES(result_content),
    result_json = VALUES(result_json),
    token_used = VALUES(token_used),
    cost_time = VALUES(cost_time),
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
    ('crawler.http.timeout-seconds', '20', 'crawler', 'Python crawler page fetch timeout in seconds', 1, 0),
    ('crawler.chapter.fetch-workers', '3', 'crawler', 'Python crawler chapter fetch workers', 1, 0),
    ('crawler.chapter.force-refresh.user-max-times', '3', 'crawler', 'Maximum chapter force refresh times for normal users in current rank cache window', 1, 0),
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
