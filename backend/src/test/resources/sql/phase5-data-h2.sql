INSERT INTO prompt_config (id, prompt_type, prompt_name, prompt_content, model_name, status, is_default, output_json_schema, output_example_json, post_process_type, parse_config_json, deleted)
VALUES
    (4, 'theme', 'default-theme', 'Please analyze the recent rank snapshots and summarize the theme trend: {{content}}', 'dify', 1, 1, '{"type":"object","properties":{"analysisType":{"type":"string"},"themeTable":{"type":"array"}},"required":["analysisType","themeTable"]}', '{"analysisType":"theme","themeTable":[{"theme":"urban-brain","count":1,"trend":"up"}],"comparisonSummary":"sample"}', 'json_extract', '{"parser":"json","trimMarkdownFence":true}', 0);

INSERT INTO system_config (id, config_key, config_value, config_type, description, is_editable, deleted)
VALUES
    (1, 'ai.provider.type', 'openai-compatible', 'ai', 'AI provider type', 1, 0),
    (2, 'ai.timeout.millis', '15000', 'ai', 'AI request timeout in milliseconds', 1, 0),
    (3, 'ai.openai-compatible.base-url', '', 'ai', 'OpenAI compatible base URL, blank means fallback to application config', 1, 0),
    (4, 'ai.openai-compatible.default-model', 'deepseek-chat', 'ai', 'Default OpenAI compatible model name', 1, 0),
    (5, 'ai.openai-compatible.streaming-enabled', 'false', 'ai', 'Whether OpenAI compatible streaming is enabled', 1, 0),
    (6, 'crawler.default.chapter-count', '3', 'crawler', 'Default crawler chapter count', 1, 0),
    (7, 'crawler.http.timeout-seconds', '20', 'crawler', 'Python crawler page fetch timeout in seconds', 1, 0),
    (8, 'crawler.chapter.fetch-workers', '3', 'crawler', 'Python crawler chapter fetch workers', 1, 0),
    (9, 'crawler.chapter.force-refresh.user-max-times', '3', 'crawler', 'Maximum chapter force refresh times for normal users in current rank cache window', 1, 0),
    (10, 'crawler.rank.refresh-days', '5', 'crawler', 'Rank refresh days', 1, 0),
    (11, 'crawler.rank.force-cooldown-days', '2', 'crawler', 'Rank force refresh cooldown days', 1, 0),
    (12, 'crawler.rank.force-max-times', '2', 'crawler', 'Rank force refresh max times', 1, 0),
    (13, 'crawler.book.refresh-days', '7', 'crawler', 'Book refresh days', 1, 0),
    (14, 'analysis.reanalyze.cooldown-hours', '0', 'analysis', 'Analysis reanalyze cooldown hours', 1, 0);

INSERT INTO crawl_book (id, platform, platform_book_id, book_name, author, intro, book_url, deleted)
VALUES
    (1002, 'fanqie', 'fanqie-demo-1002', 'Book Two', 'Author Two', 'Intro for book two', 'https://fanqienovel.com/page/demo-1002', 0),
    (1003, 'fanqie', 'fanqie-demo-1003', 'Book Three', 'Author Three', 'Intro for book three', 'https://fanqienovel.com/page/demo-1003', 0);

INSERT INTO rank_board (id, platform, channel_code, board_code, board_name, description, deleted)
VALUES
    (5001, 'fanqie', 'male-new', 'urban-brain', 'Urban Brain', 'Shared board foundation for phase 5', 0);

INSERT INTO rank_snapshot (id, rank_board_id, snapshot_time, record_count, deleted)
VALUES
    (6001, 5001, TIMESTAMP '2026-03-20 11:30:00', 2, 0);

INSERT INTO crawl_rank (id, platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
VALUES
    (2001, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 1, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-18 10:00:00', TIMESTAMP '2026-03-18 10:00:00', 0),
    (2002, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 2, 1002, 'Book Two', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Intro two', TIMESTAMP '2026-03-18 10:00:00', TIMESTAMP '2026-03-18 10:00:00', 0),
    (2003, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 1, 1002, 'Book Two', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Intro two', TIMESTAMP '2026-03-19 10:00:00', TIMESTAMP '2026-03-19 10:00:00', 0),
    (2004, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 2, 1003, 'Book Three', 'https://fanqienovel.com/page/demo-1003', 'Author Three', 'Intro three', TIMESTAMP '2026-03-19 10:00:00', TIMESTAMP '2026-03-19 10:00:00', 0),
    (2005, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 1, 1003, 'Book Three', 'https://fanqienovel.com/page/demo-1003', 'Author Three', 'Intro three', TIMESTAMP '2026-03-20 10:00:00', TIMESTAMP '2026-03-20 10:00:00', 0),
    (2006, 'fanqie', 'male-hot-a', NULL, NULL, NULL, 2, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-20 10:00:00', TIMESTAMP '2026-03-20 10:00:00', 0),
    (2007, 'fanqie', 'male-new-a', NULL, NULL, NULL, 1, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-20 11:00:00', TIMESTAMP '2026-03-20 11:00:00', 0),
    (2101, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 1, 1001, 'Book One', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Intro one', TIMESTAMP '2026-03-20 11:30:00', TIMESTAMP '2026-03-20 11:30:00', 0),
    (2102, 'fanqie', 'male-new-a', 'male-new', 'urban-brain', 6001, 2, 1002, 'Book Two', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Intro two', TIMESTAMP '2026-03-20 11:30:00', TIMESTAMP '2026-03-20 11:30:00', 0);

INSERT INTO analysis_result
    (id, user_id, platform, book_id, analysis_type, chapter_count, prompt_config_id, model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted)
VALUES
    (3004, 1, 'fanqie', 1001, 'theme', 0, 4, 'dify', 'theme result for fanqie', '{"analysisType":"theme","wordCloud":[{"name":"西幻","value":18},{"name":"冒险","value":12}],"themeDistribution":[{"name":"西幻","value":2},{"name":"热血","value":1}],"themeTable":[{"theme":"西幻","count":2,"trend":"up"},{"theme":"热血","count":1,"trend":"down"}],"comparisonSummary":"最近三次榜单题材从热血升级到西幻冒险","snapshotComparison":[{"snapshotTime":"2026-03-18 10:00:00","topTheme":"热血","change":"baseline"},{"snapshotTime":"2026-03-19 10:00:00","topTheme":"热血冒险","change":"up"},{"snapshotTime":"2026-03-20 10:00:00","topTheme":"西幻冒险","change":"up"}]}', 160, 600, TIMESTAMP '2026-03-20 12:30:00', TIMESTAMP '2026-03-20 12:30:00', 0);
