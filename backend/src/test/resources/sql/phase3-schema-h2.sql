DROP TABLE IF EXISTS crawler_task;
DROP TABLE IF EXISTS crawl_chapter;
DROP TABLE IF EXISTS crawl_rank;
DROP TABLE IF EXISTS crawl_book;

CREATE TABLE crawl_book (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    platform VARCHAR(20) NOT NULL,
    platform_book_id VARCHAR(100),
    book_name VARCHAR(200) NOT NULL,
    author VARCHAR(100),
    intro CLOB,
    book_url VARCHAR(500) NOT NULL,
    last_crawl_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_url ON crawl_book(platform, book_url);

CREATE TABLE crawl_rank (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    platform VARCHAR(20) NOT NULL,
    category VARCHAR(50) NOT NULL,
    rank_no INT NOT NULL,
    book_id BIGINT NOT NULL,
    book_name VARCHAR(200) NOT NULL,
    book_url VARCHAR(500) NOT NULL,
    author VARCHAR(100),
    intro CLOB,
    crawl_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE crawl_chapter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    platform VARCHAR(20) NOT NULL,
    book_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    chapter_title VARCHAR(255) NOT NULL,
    content CLOB,
    word_count INT DEFAULT 0,
    source_word_count INT,
    crawl_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_book_chapter ON crawl_chapter(book_id, chapter_no);

CREATE TABLE crawler_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_type VARCHAR(30) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    request_json CLOB,
    status TINYINT DEFAULT 0,
    error_message VARCHAR(500),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    config_value VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_key ON user_config(user_id, config_key);
