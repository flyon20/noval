-- Phase 3 database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase3-schema.sql

CREATE TABLE IF NOT EXISTS crawl_book (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    platform VARCHAR(20) NOT NULL COMMENT '平台 fanqie/qidian',
    platform_book_id VARCHAR(100) COMMENT '平台书籍ID',
    book_name VARCHAR(200) NOT NULL COMMENT '书名',
    author VARCHAR(100) COMMENT '作者',
    intro TEXT COMMENT '简介',
    book_url VARCHAR(500) NOT NULL COMMENT '书籍链接',
    last_crawl_time DATETIME COMMENT '最后抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    UNIQUE KEY uk_platform_url (platform, book_url(255)),
    INDEX idx_platform (platform),
    INDEX idx_book_name (book_name),
    INDEX idx_last_crawl_time (last_crawl_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书籍详情表';

CREATE TABLE IF NOT EXISTS crawl_rank (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    platform VARCHAR(20) NOT NULL COMMENT '平台',
    category VARCHAR(50) NOT NULL COMMENT '榜单分类',
    rank_no INT NOT NULL COMMENT '名次',
    book_id BIGINT NOT NULL COMMENT '书籍ID',
    book_name VARCHAR(200) NOT NULL COMMENT '书名',
    book_url VARCHAR(500) NOT NULL COMMENT '书籍链接',
    author VARCHAR(100) COMMENT '作者',
    intro TEXT COMMENT '简介',
    crawl_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    INDEX idx_platform_category (platform, category),
    INDEX idx_book_id (book_id),
    INDEX idx_crawl_time (crawl_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='榜单数据表';

CREATE TABLE IF NOT EXISTS crawl_chapter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    platform VARCHAR(20) NOT NULL COMMENT '平台',
    book_id BIGINT NOT NULL COMMENT '书籍ID',
    chapter_no INT NOT NULL COMMENT '章节序号',
    chapter_title VARCHAR(255) NOT NULL COMMENT '章节标题',
    content MEDIUMTEXT COMMENT '章节内容',
    word_count INT DEFAULT 0 COMMENT '字数',
    crawl_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    UNIQUE KEY uk_book_chapter (book_id, chapter_no),
    INDEX idx_book_id (book_id),
    INDEX idx_crawl_time (crawl_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='章节内容表';

CREATE TABLE IF NOT EXISTS crawler_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_type VARCHAR(30) NOT NULL COMMENT '任务类型 rank/book/chapter',
    platform VARCHAR(20) NOT NULL COMMENT '平台',
    request_json JSON COMMENT '请求参数',
    status TINYINT DEFAULT 0 COMMENT '状态 0待执行 1执行中 2成功 3失败',
    error_message VARCHAR(500) COMMENT '错误信息',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_task_type (task_type),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='爬虫任务表';

