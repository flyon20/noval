INSERT INTO prompt_config (id, prompt_type, prompt_name, prompt_content, model_name, status, is_default, deleted)
VALUES
    (1, 'deconstruct', 'default-deconstruct', '请对文本进行拆文分析：{{content}}', 'dify', 1, 1, 0),
    (2, 'structure', 'default-structure', '请对文本进行结构分析：{{content}}', 'dify', 1, 1, 0),
    (3, 'plot', 'default-plot', '请对文本进行情节分析：{{content}}', 'dify', 1, 1, 0);

INSERT INTO crawl_book (id, platform, platform_book_id, book_name, author, intro, book_url, deleted)
VALUES
    (1001, 'fanqie', 'fanqie-demo-1001', '测试书籍', '测试作者', '测试简介', 'https://fanqienovel.com/page/demo-1001', 0);

INSERT INTO crawl_chapter (platform, book_id, chapter_no, chapter_title, content, word_count, deleted)
VALUES
    ('fanqie', 1001, 1, '第一章', '第一章内容', 5, 0),
    ('fanqie', 1001, 2, '第二章', '第二章内容', 5, 0),
    ('fanqie', 1001, 3, '第三章', '第三章内容', 5, 0);

