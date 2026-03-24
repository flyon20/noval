INSERT INTO prompt_config (id, prompt_type, prompt_name, prompt_content, model_name, status, is_default, output_json_schema, output_example_json, post_process_type, parse_config_json, deleted)
VALUES
    (
        4,
        'theme',
        'default-theme',
        'Analyze the exact selected rank board for the last three snapshots and return valid JSON only: {{content}}',
        'dify',
        1,
        1,
        '{"type":"object","properties":{"analysisType":{"type":"string"},"platform":{"type":"string"},"channelCode":{"type":"string"},"boardCode":{"type":"string"},"boardName":{"type":"string"},"summary":{"type":"string"},"historicalWordCloud":{"type":"array"},"themeTable":{"type":"array"},"snapshotComparisons":{"type":"array"},"hotBooks":{"type":"array"},"insightCards":{"type":"array"},"comparisonSummary":{"type":"string"},"historyAnalysisCount":{"type":"integer"},"detailContent":{"type":"string"}},"required":["analysisType","summary","historicalWordCloud","themeTable","snapshotComparisons","hotBooks","insightCards","comparisonSummary","historyAnalysisCount"]}',
        '{"analysisType":"theme","platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","boardName":"Urban Brain","summary":"Urban-brain remains the clearest direction across the latest board snapshots.","historicalWordCloud":[{"name":"urban-brain","value":24},{"name":"system-flow","value":15}],"themeTable":[{"theme":"urban-brain","count":3,"trend":"rising"},{"theme":"system-flow","count":2,"trend":"stable"}],"snapshotComparisons":[{"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","change":"holding"}],"hotBooks":[{"bookName":"Brain City King","author":"Author One","rankLabel":"#1","reason":"Keeps leading the board"}],"insightCards":[{"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"}],"comparisonSummary":"Urban-brain has become the clearest board-level direction.","historyAnalysisCount":3,"detailContent":"Detailed board trend analysis."}',
        'json_extract',
        '{"parser":"json","trimMarkdownFence":true}',
        0
    );

INSERT INTO system_config (id, config_key, config_value, config_type, description, is_editable, deleted)
VALUES
    (1, 'ai.provider.type', 'openai-compatible', 'ai', 'AI provider type', 1, 0),
    (2, 'ai.timeout.millis', '15000', 'ai', 'AI request timeout in milliseconds', 1, 0),
    (3, 'ai.openai-compatible.base-url', '', 'ai', 'OpenAI compatible base URL, blank means fallback to application config', 1, 0),
    (4, 'ai.openai-compatible.default-model', 'deepseek-chat', 'ai', 'Default OpenAI compatible model name', 1, 0),
    (5, 'ai.openai-compatible.streaming-enabled', 'true', 'ai', 'Whether OpenAI compatible streaming is enabled', 1, 0),
    (6, 'crawler.default.chapter-count', '3', 'crawler', 'Default crawler chapter count', 1, 0),
    (7, 'crawler.http.timeout-seconds', '20', 'crawler', 'Python crawler page fetch timeout in seconds', 1, 0),
    (8, 'crawler.chapter.fetch-workers', '3', 'crawler', 'Python crawler chapter fetch workers', 1, 0),
    (9, 'crawler.chapter.force-refresh.user-max-times', '3', 'crawler', 'Maximum chapter force refresh times for normal users in current rank cache window', 1, 0),
    (10, 'crawler.rank.refresh-days', '5', 'crawler', 'Rank refresh days', 1, 0),
    (11, 'crawler.rank.force-cooldown-days', '2', 'crawler', 'Rank force refresh cooldown days', 1, 0),
    (12, 'crawler.rank.force-max-times', '2', 'crawler', 'Rank force refresh max times', 1, 0),
    (13, 'crawler.book.refresh-days', '7', 'crawler', 'Book refresh days', 1, 0),
    (14, 'analysis.reanalyze.cooldown-hours', '0', 'analysis', 'Analysis reanalyze cooldown hours', 1, 0),
    (15, 'analysis.chunk.max-input-tokens', '32000', 'analysis', 'Approximate max input tokens before analysis switches to chunk mode', 1, 0),
    (16, 'analysis.chunk.target-input-tokens', '24000', 'analysis', 'Approximate target input tokens for each chunked analysis request', 1, 0),
    (17, 'analysis.chunk.parallelism', '3', 'analysis', 'Maximum parallel chunk analysis requests', 1, 0);

INSERT INTO crawl_book (id, platform, platform_book_id, book_name, author, intro, book_url, deleted)
VALUES
    (1002, 'fanqie', 'fanqie-demo-1002', 'System Runner', 'Author Two', 'A fast system-upgrade urban title.', 'https://fanqienovel.com/page/demo-1002', 0),
    (1003, 'fanqie', 'fanqie-demo-1003', 'Fantasy Climber', 'Author Three', 'A fantasy climbing story.', 'https://fanqienovel.com/page/demo-1003', 0);

INSERT INTO rank_board (id, platform, channel_code, board_code, board_name, description, deleted)
VALUES
    (5001, 'fanqie', 'male-new', 'urban-brain', 'Urban Brain', 'Shared board foundation for phase 5', 0);

INSERT INTO rank_snapshot (id, rank_board_id, snapshot_time, record_count, deleted)
VALUES
    (6001, 5001, TIMESTAMP '2026-03-20 11:30:00', 2, 0),
    (6002, 5001, TIMESTAMP '2026-03-19 11:30:00', 2, 0),
    (6003, 5001, TIMESTAMP '2026-03-18 11:30:00', 2, 0);

INSERT INTO crawl_rank (id, platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
VALUES
    (2001, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 1, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-18 10:00:00', TIMESTAMP '2026-03-18 10:00:00', 0),
    (2002, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Intro two', TIMESTAMP '2026-03-18 10:00:00', TIMESTAMP '2026-03-18 10:00:00', 0),
    (2003, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 1, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Intro two', TIMESTAMP '2026-03-19 10:00:00', TIMESTAMP '2026-03-19 10:00:00', 0),
    (2004, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 2, 1003, 'Fantasy Climber', 'https://fanqienovel.com/page/demo-1003', 'Author Three', 'Intro three', TIMESTAMP '2026-03-19 10:00:00', TIMESTAMP '2026-03-19 10:00:00', 0),
    (2005, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 1, 1003, 'Fantasy Climber', 'https://fanqienovel.com/page/demo-1003', 'Author Three', 'Intro three', TIMESTAMP '2026-03-20 10:00:00', TIMESTAMP '2026-03-20 10:00:00', 0),
    (2006, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 2, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-20 10:00:00', TIMESTAMP '2026-03-20 10:00:00', 0),
    (2007, 'fanqie', 'male-new-a', NULL, NULL, NULL, 1, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-20 11:00:00', TIMESTAMP '2026-03-20 11:00:00', 0),
    (2101, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 1, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', TIMESTAMP '2026-03-20 11:30:00', TIMESTAMP '2026-03-20 11:30:00', 0),
    (2102, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', TIMESTAMP '2026-03-20 11:30:00', TIMESTAMP '2026-03-20 11:30:00', 0),
    (2103, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6002, 1, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', TIMESTAMP '2026-03-19 11:30:00', TIMESTAMP '2026-03-19 11:30:00', 0),
    (2104, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6002, 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', TIMESTAMP '2026-03-19 11:30:00', TIMESTAMP '2026-03-19 11:30:00', 0),
    (2105, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6003, 1, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', TIMESTAMP '2026-03-18 11:30:00', TIMESTAMP '2026-03-18 11:30:00', 0),
    (2106, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6003, 2, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', TIMESTAMP '2026-03-18 11:30:00', TIMESTAMP '2026-03-18 11:30:00', 0);

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
        TIMESTAMP '2026-03-20 12:30:00',
        TIMESTAMP '2026-03-20 12:30:00',
        0
    );
