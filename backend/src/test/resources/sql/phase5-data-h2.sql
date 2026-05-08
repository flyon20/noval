DROP TABLE IF EXISTS user_prompt_effective_history;
DROP TABLE IF EXISTS user_prompt_binding;
DROP TABLE IF EXISTS prompt_publish_item;
DROP TABLE IF EXISTS prompt_publish_version;

CREATE TABLE prompt_publish_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_no BIGINT NOT NULL,
    published_by BIGINT,
    publish_note VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_version_no ON prompt_publish_version(version_no);
CREATE INDEX IF NOT EXISTS idx_publish_create_time ON prompt_publish_version(create_time);

CREATE TABLE prompt_publish_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    publish_version_id BIGINT NOT NULL,
    prompt_type VARCHAR(50) NOT NULL,
    prompt_config_id BIGINT NOT NULL,
    prompt_name VARCHAR(100) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_item_version_type ON prompt_publish_item(publish_version_id, prompt_type);
CREATE INDEX IF NOT EXISTS idx_publish_item_prompt_type ON prompt_publish_item(prompt_type);
CREATE INDEX IF NOT EXISTS idx_publish_item_prompt_config_id ON prompt_publish_item(prompt_config_id);

CREATE TABLE user_prompt_binding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    prompt_type VARCHAR(50) NOT NULL,
    binding_mode VARCHAR(20) NOT NULL,
    bound_prompt_config_id BIGINT,
    last_selected_prompt_config_id BIGINT,
    effective_prompt_config_id BIGINT,
    publish_version_id BIGINT,
    fallback_warning VARCHAR(255),
    status TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_prompt_type ON user_prompt_binding(user_id, prompt_type);
CREATE INDEX IF NOT EXISTS idx_user_prompt_type_status ON user_prompt_binding(user_id, prompt_type, status);
CREATE INDEX IF NOT EXISTS idx_bound_prompt_config_id ON user_prompt_binding(bound_prompt_config_id);
CREATE INDEX IF NOT EXISTS idx_effective_prompt_config_id ON user_prompt_binding(effective_prompt_config_id);

CREATE TABLE user_prompt_effective_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    prompt_type VARCHAR(50) NOT NULL,
    publish_version_id BIGINT,
    binding_mode VARCHAR(20),
    bound_prompt_config_id BIGINT,
    effective_prompt_config_id BIGINT NOT NULL,
    effective_source VARCHAR(50) NOT NULL,
    previous_effective_prompt_config_id BIGINT,
    selected_model_key VARCHAR(100),
    fallback TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_user_prompt_create_time ON user_prompt_effective_history(user_id, prompt_type, create_time);
CREATE INDEX IF NOT EXISTS idx_publish_version_id ON user_prompt_effective_history(publish_version_id);
CREATE INDEX IF NOT EXISTS idx_effective_prompt_config_id ON user_prompt_effective_history(effective_prompt_config_id);

MERGE INTO prompt_config KEY(id) VALUES
    (1, 'deconstruct', 'default', 'SYSTEM', NULL, NULL, '请基于以下小说正文进行拆文分析：{{content}}', 'deepseek-chat', 0.70, 6000, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '{"type":"object","properties":{"analysisType":{"type":"string"},"summary":{"type":"string"},"sellingPoints":{"type":"array"}}}', '{"analysisType":"deconstruct","summary":"summary","sellingPoints":["hook"]}', 'json_extract', '{"parser":"json","trimMarkdownFence":true}', '{"type":"object","properties":{"bookName":{"type":"string"},"chapters":{"type":"array"}}}', '{"bookName":"示例书名","chapters":[{"title":"第一章","content":"正文"}]}'),
    (2, 'structure', 'default', 'SYSTEM', NULL, NULL, '请对以下小说内容进行结构分析：{{content}}', 'deepseek-chat', 0.70, 6000, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '{"type":"object","properties":{"analysisType":{"type":"string"},"summary":{"type":"string"},"structureStages":{"type":"array"}}}', '{"analysisType":"structure","summary":"summary","structureStages":["stage"]}', 'json_extract', '{"parser":"json","trimMarkdownFence":true}', '{"type":"object","properties":{"bookName":{"type":"string"},"chapters":{"type":"array"}}}', '{"bookName":"示例书名","chapters":[{"title":"第一章","content":"正文"}]}'),
    (3, 'plot', 'default', 'SYSTEM', NULL, NULL, '请对以下小说内容进行情节分析：{{content}}', 'deepseek-chat', 0.70, 6000, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '{"type":"object","properties":{"analysisType":{"type":"string"},"summary":{"type":"string"},"plotBeats":{"type":"array"}}}', '{"analysisType":"plot","summary":"summary","plotBeats":["beat"]}', 'json_extract', '{"parser":"json","trimMarkdownFence":true}', '{"type":"object","properties":{"bookName":{"type":"string"},"chapters":{"type":"array"}}}', '{"bookName":"示例书名","chapters":[{"title":"第一章","content":"正文"}]}'),
    (4, 'theme', 'default', 'SYSTEM', NULL, NULL, 'Analyze the exact selected rank board for the last three snapshots and return valid JSON only: {{content}}', 'deepseek-chat', 0.70, 6000, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '{"type":"object","properties":{"analysisType":{"type":"string"},"platform":{"type":"string"},"channelCode":{"type":"string"},"boardCode":{"type":"string"},"boardName":{"type":"string"},"summary":{"type":"string"},"boardSummary":{"type":"string"},"trendPreview":{"type":"string"},"detailContent":{"type":"string"},"historicalWordCloud":{"type":"array"},"themeDistribution":{"type":"array"},"themeTable":{"type":"array"},"hotBooks":{"type":"array"},"insightCards":{"type":"array"},"snapshotComparisons":{"type":"array"},"comparisonSummary":{"type":"string"},"historyAnalysisCount":{"type":"integer"}},"required":["analysisType","summary","boardSummary","historicalWordCloud","themeDistribution","themeTable","hotBooks","insightCards","snapshotComparisons","comparisonSummary","historyAnalysisCount"]}', '{"analysisType":"theme","platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","boardName":"Urban Brain","summary":"Urban-brain remains the clearest direction across the latest board snapshots.","boardSummary":"This board keeps concentrating on urban-brain and system-flow hybrids, with the top title staying highly stable.","trendPreview":"Urban-brain continues to dominate this board.","detailContent":"Detailed board trend analysis.","historicalWordCloud":[{"name":"urban-brain","value":24},{"name":"system-flow","value":15}],"themeDistribution":[{"theme":"urban-brain","count":3,"ratio":50.0},{"theme":"system-flow","count":2,"ratio":33.3}],"themeTable":[{"theme":"urban-brain","count":3,"ratio":50.0,"trend":"rising","representativeBooks":[{"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"The highest-ranked representative title in this lane."}]},{"theme":"system-flow","count":2,"ratio":33.3,"trend":"stable","representativeBooks":[{"theme":"system-flow","bookName":"System Runner","author":"Author Two","rankNo":2,"reason":"A stable follow-up theme with clear retention."}]}],"snapshotComparisons":[{"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","topThemeRatio":50.0,"leadBookName":"Brain City King","change":"holding"}],"hotBooks":[{"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"Keeps leading the board"}],"insightCards":[{"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"},{"label":"Lead title","value":"Brain City King","note":"Most representative latest book"}],"comparisonSummary":"Urban-brain has become the clearest board-level direction.","historyAnalysisCount":3}', 'json_extract', '{"parser":"json","trimMarkdownFence":true}', '{"type":"object","properties":{"platform":{"type":"string"},"channelCode":{"type":"string"},"boardCode":{"type":"string"},"boardName":{"type":"string"},"snapshotCount":{"type":"integer"},"snapshots":{"type":"array"}},"required":["platform","channelCode","boardCode","boardName","snapshotCount","snapshots"]}', '{"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","boardName":"Urban Brain","snapshotCount":3,"snapshots":[{"snapshotTime":"2026-03-20 11:30:00","recordCount":30,"ranks":[{"rankNo":1,"bookId":1001,"bookName":"Brain City King","author":"Author One","intro":"Urban-brain city upgrade story"}]}]}');

INSERT INTO prompt_publish_version (id, version_no, published_by, publish_note, deleted)
VALUES (1, 1, 1, 'initial publish', 0);

INSERT INTO prompt_publish_item (id, publish_version_id, prompt_type, prompt_config_id, prompt_name, deleted)
VALUES
    (1, 1, 'deconstruct', 1, 'default', 0),
    (2, 1, 'structure', 2, 'default', 0),
    (3, 1, 'plot', 3, 'default', 0),
    (4, 1, 'theme', 4, 'default', 0);

INSERT INTO user_prompt_binding (
    id, user_id, prompt_type, binding_mode, bound_prompt_config_id,
    last_selected_prompt_config_id, effective_prompt_config_id, publish_version_id, fallback_warning, status, deleted
)
VALUES
    (1, 1, 'theme', 'GLOBAL', NULL, NULL, 4, 1, NULL, 1, 0);

INSERT INTO user_prompt_effective_history (
    id, user_id, prompt_type, publish_version_id, binding_mode, bound_prompt_config_id,
    effective_prompt_config_id, effective_source, previous_effective_prompt_config_id,
    selected_model_key, fallback, deleted
)
VALUES
    (1, 1, 'theme', 1, 'GLOBAL', NULL, 4, 'GLOBAL_PUBLISHED', NULL, 'deepseek-chat', 0, 0);

INSERT INTO async_job (
    id, job_type, job_key, resource_key, request_json, status, trigger_user_id,
    result_ref_type, result_ref_id, result_summary, error_message, retry_count,
    started_at, finished_at, deleted
)
VALUES
    (
        1,
        'trend_analysis',
        'trend:fanqie:male-new:urban-brain:6001:4:deepseek-chat',
        'trend:fanqie:male-new:urban-brain',
        '{"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain"}',
        'SUCCESS',
        1,
        'analysis_result',
        3004,
        'cached trend result',
        NULL,
        0,
        TIMESTAMP '2026-03-20 12:30:00',
        TIMESTAMP '2026-03-20 12:31:00',
        0
    );

INSERT INTO system_config
    (id, config_key, config_value, config_type, description, is_editable, deleted)
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
    (17, 'analysis.chunk.parallelism', '3', 'analysis', 'Maximum parallel chunk analysis requests', 1, 0),
    (18, 'auth.bootstrap-admin-phones', '15599316908', 'auth', 'Comma-separated admin phone bootstrap list', 1, 0);

MERGE INTO rank_board KEY(id) VALUES
    (5001, 'fanqie', 'male-new', 'urban-brain', 'Urban Brain', 'Shared board foundation for phase 5', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

MERGE INTO rank_snapshot KEY(id) VALUES
    (6001, 5001, TIMESTAMP '2026-03-20 11:30:00', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    (6002, 5001, TIMESTAMP '2026-03-19 11:30:00', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    (6003, 5001, TIMESTAMP '2026-03-18 11:30:00', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

MERGE INTO crawl_book KEY(id) VALUES
    (1001, 'fanqie', 'fanqie-demo-1001', 'Brain City King', 'Author One', 'Urban-brain city upgrade story', 'https://fanqienovel.com/page/demo-1001', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    (1002, 'fanqie', 'fanqie-demo-1002', 'System Runner', 'Author Two', 'Fast system-flow title', 'https://fanqienovel.com/page/demo-1002', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    (1003, 'fanqie', 'fanqie-demo-1003', 'Fantasy Climber', 'Author Three', 'A fantasy climbing story', 'https://fanqienovel.com/page/demo-1003', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

MERGE INTO crawl_rank KEY(id) VALUES
    (2101, 'fanqie', 'male-new-a', 1, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', TIMESTAMP '2026-03-20 11:30:00', CURRENT_TIMESTAMP, 0, 6001, 'male-new', 'urban-brain'),
    (2102, 'fanqie', 'male-new-a', 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', TIMESTAMP '2026-03-20 11:30:00', CURRENT_TIMESTAMP, 0, 6001, 'male-new', 'urban-brain'),
    (2103, 'fanqie', 'male-new-a', 1, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', TIMESTAMP '2026-03-19 11:30:00', CURRENT_TIMESTAMP, 0, 6002, 'male-new', 'urban-brain'),
    (2104, 'fanqie', 'male-new-a', 2, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', TIMESTAMP '2026-03-19 11:30:00', CURRENT_TIMESTAMP, 0, 6002, 'male-new', 'urban-brain'),
    (2105, 'fanqie', 'male-new-a', 1, 1002, 'System Runner', 'https://fanqienovel.com/page/demo-1002', 'Author Two', 'Fast system-flow title', TIMESTAMP '2026-03-18 11:30:00', CURRENT_TIMESTAMP, 0, 6003, 'male-new', 'urban-brain'),
    (2106, 'fanqie', 'male-new-a', 2, 1001, 'Brain City King', 'https://fanqienovel.com/page/demo-1001', 'Author One', 'Urban-brain city upgrade story', TIMESTAMP '2026-03-18 11:30:00', CURRENT_TIMESTAMP, 0, 6003, 'male-new', 'urban-brain');

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
        'deepseek-chat',
        'Detailed board trend analysis for the latest three urban-brain snapshots.',
        '{"analysisType":"theme","platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","boardName":"Urban Brain","summary":"Urban-brain remains the clearest direction across the latest board snapshots.","boardSummary":"This board keeps concentrating on urban-brain and system-flow hybrids, with the top title staying highly stable.","historicalWordCloud":[{"name":"urban-brain","value":24},{"name":"system-flow","value":15}],"themeDistribution":[{"theme":"urban-brain","count":3,"ratio":50.0},{"theme":"system-flow","count":2,"ratio":33.3}],"themeTable":[{"theme":"urban-brain","count":3,"ratio":50.0,"trend":"rising","representativeBooks":[{"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"Keeps leading the board"}]},{"theme":"system-flow","count":2,"ratio":33.3,"trend":"stable","representativeBooks":[{"theme":"system-flow","bookName":"System Runner","author":"Author Two","rankNo":2,"reason":"Stable presence in the same board lane"}]}],"snapshotComparisons":[{"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","topThemeRatio":50.0,"leadBookName":"Brain City King","change":"holding"},{"snapshotTime":"2026-03-19 11:30:00","topTheme":"urban-brain","topThemeRatio":50.0,"leadBookName":"Brain City King","change":"rising"},{"snapshotTime":"2026-03-18 11:30:00","topTheme":"system-flow","topThemeRatio":33.3,"leadBookName":"System Runner","change":"baseline"}],"hotBooks":[{"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"Keeps leading the board"}],"insightCards":[{"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"},{"label":"Lead title","value":"Brain City King","note":"Most representative latest book"}],"comparisonSummary":"Urban-brain has become the clearest board-level direction across the last three snapshots.","historyAnalysisCount":3,"trendPreview":"Urban-brain continues to dominate this board.","detailContent":"Detailed board trend analysis for the last three snapshots."}',
        160,
        600,
        TIMESTAMP '2026-03-20 12:30:00',
        TIMESTAMP '2026-03-20 12:30:00',
        0
    );
