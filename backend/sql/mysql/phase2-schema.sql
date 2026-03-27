-- Phase 2 database schema (MySQL 8.0)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase2-schema.sql

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码(Bcrypt或{noop}前缀)',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    avatar VARCHAR(255) COMMENT '头像URL',
    status TINYINT DEFAULT 1 COMMENT '状态 0禁用 1正常',
    last_login_time DATETIME COMMENT '最后登录时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    version INT DEFAULT 0 COMMENT '乐观锁版本号',
    INDEX idx_username (username),
    INDEX idx_phone (phone),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(50) NOT NULL UNIQUE COMMENT '角色编码',
    description VARCHAR(200) COMMENT '角色描述',
    status TINYINT DEFAULT 1 COMMENT '状态 0禁用 1正常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    INDEX idx_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    parent_id BIGINT DEFAULT 0 COMMENT '父级菜单ID',
    menu_name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    menu_path VARCHAR(100) COMMENT '菜单路径',
    permission VARCHAR(100) COMMENT '权限标识',
    type TINYINT DEFAULT 1 COMMENT '类型 1目录 2菜单 3按钮',
    icon VARCHAR(100) COMMENT '图标',
    sort INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '状态 0禁用 1正常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删除 1已删除',
    INDEX idx_parent_id (parent_id),
    INDEX idx_permission (permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user_id (user_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    INDEX idx_role_id (role_id),
    INDEX idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT COMMENT '用户ID',
    username VARCHAR(50) COMMENT '用户名',
    login_ip VARCHAR(50) COMMENT '登录IP',
    login_location VARCHAR(100) COMMENT '登录地点',
    browser VARCHAR(50) COMMENT '浏览器',
    os VARCHAR(50) COMMENT '操作系统',
    status TINYINT DEFAULT 1 COMMENT '状态 0失败 1成功',
    message VARCHAR(500) COMMENT '提示消息',
    login_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    INDEX idx_user_id (user_id),
    INDEX idx_login_time (login_time),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表';

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id BIGINT COMMENT '用户ID',
    username VARCHAR(50) COMMENT '用户名',
    module VARCHAR(50) COMMENT '功能模块',
    operation_type VARCHAR(50) COMMENT '操作类型',
    operation_desc VARCHAR(200) COMMENT '操作描述',
    request_json JSON COMMENT '请求参数(JSON)',
    response_json JSON COMMENT '响应数据(JSON)',
    status TINYINT DEFAULT 1 COMMENT '状态 0失败 1成功',
    error_message VARCHAR(500) COMMENT '错误信息',
    cost_time BIGINT COMMENT '耗时(毫秒)',
    operation_ip VARCHAR(50) COMMENT '操作IP',
    operation_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_operation_time (operation_time),
    INDEX idx_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

CREATE TABLE IF NOT EXISTS sys_ip_blacklist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    ip_address VARCHAR(50) NOT NULL COMMENT 'IP地址',
    reason VARCHAR(200) COMMENT '封禁原因',
    expire_time DATETIME COMMENT '解封时间(NULL为永久)',
    status TINYINT DEFAULT 1 COMMENT '状态 0解封 1封禁',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_ip (ip_address),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP黑名单表';

CREATE TABLE IF NOT EXISTS sys_user_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'auth session id',
    user_id BIGINT NOT NULL COMMENT 'user id',
    session_id VARCHAR(64) NOT NULL COMMENT 'session identifier',
    refresh_token_hash VARCHAR(128) NOT NULL COMMENT 'opaque refresh token hash',
    status TINYINT DEFAULT 1 COMMENT '1 active 2 revoked 3 kicked',
    device_label VARCHAR(100) COMMENT 'device label',
    user_agent VARCHAR(255) COMMENT 'user agent',
    login_ip VARCHAR(50) COMMENT 'login ip',
    last_active_time DATETIME COMMENT 'last active time',
    last_refresh_time DATETIME COMMENT 'last refresh time',
    refresh_expire_time DATETIME NOT NULL COMMENT 'refresh token expire time',
    revoke_reason VARCHAR(200) COMMENT 'revoke reason',
    revoked_at DATETIME COMMENT 'revoked at',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
    deleted TINYINT DEFAULT 0 COMMENT 'logic delete flag',
    version INT DEFAULT 0 COMMENT 'optimistic lock version',
    UNIQUE KEY uk_user_session_session_id (session_id),
    UNIQUE KEY uk_user_session_refresh_hash (refresh_token_hash),
    INDEX idx_user_session_user_status (user_id, status, deleted),
    INDEX idx_user_session_user_active_time (user_id, last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='auth user session';

