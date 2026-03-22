INSERT INTO prompt_config (id, prompt_type, prompt_name, prompt_content, model_name, status, is_default, deleted)
VALUES
    (1, 'deconstruct', 'default-deconstruct', '请基于以下小说正文进行拆文分析，重点输出：1. 核心卖点；2. 开篇钩子；3. 人物关系与冲突；4. 节奏与爽点；5. 可优化点。\n\n{{content}}', 'deepseek-chat', 1, 1, 0),
    (2, 'structure', 'default-structure', '请对文本进行结构分析：{{content}}', 'dify', 1, 1, 0),
    (3, 'plot', 'default-plot', '请对文本进行情节分析：{{content}}', 'dify', 1, 1, 0);

INSERT INTO crawl_book (id, platform, platform_book_id, book_name, author, intro, book_url, deleted)
VALUES
    (1001, 'fanqie', 'fanqie-demo-1001', '测试书籍', '测试作者', '测试简介', 'https://fanqienovel.com/page/demo-1001', 0);

INSERT INTO crawl_chapter (platform, book_id, chapter_no, chapter_title, content, word_count, deleted)
VALUES
    ('fanqie', 1001, 1, '第一章', '第一章节内容', 5, 0),
    ('fanqie', 1001, 2, '第二章', '第二章节内容', 5, 0),
    ('fanqie', 1001, 3, '第三章', '第三章节内容', 5, 0);

INSERT INTO analysis_result
    (id, user_id, platform, book_id, analysis_type, chapter_count, prompt_config_id, model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted)
VALUES
    (3001, 1, 'fanqie', 1001, 'deconstruct', 3, 1, 'dify', 'deconstruct result for book one', '{"analysisType":"deconstruct","summary":"deconstruct result for book one"}', 120, 500, TIMESTAMP '2026-03-18 12:00:00', TIMESTAMP '2026-03-18 12:00:00', 0),
    (3002, 1, 'fanqie', 1001, 'structure', 3, 2, 'dify', 'structure result for book one', '{"analysisType":"structure","summary":"structure result for book one"}', 130, 520, TIMESTAMP '2026-03-19 12:00:00', TIMESTAMP '2026-03-19 12:00:00', 0),
    (3003, 1, 'fanqie', 1002, 'plot', 3, 3, 'dify', 'plot result for book two', '{"analysisType":"plot","summary":"plot result for book two"}', 140, 540, TIMESTAMP '2026-03-20 12:00:00', TIMESTAMP '2026-03-20 12:00:00', 0);
