-- Phase 2 seed data (dev only)
-- Usage:
--   mysql -h127.0.0.1 -uroot -p novel_analyzer < backend/sql/mysql/phase2-seed.sql
-- Notes:
--   {noop} password prefix is for local dev bootstrap only.
--   Replace with bcrypt in production.

INSERT INTO sys_user (id, username, password, phone, phone_verified, status, deleted, password_updated_time)
VALUES
    (1, '管理员', '{noop}admin123', '13800138000', 1, 1, 0, CURRENT_TIMESTAMP),
    (2, '作者', '{noop}writer123', '13800138001', 1, 1, 0, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    phone = VALUES(phone),
    phone_verified = VALUES(phone_verified),
    status = VALUES(status),
    deleted = VALUES(deleted),
    password_updated_time = VALUES(password_updated_time);

INSERT INTO sys_role (id, role_name, role_code, status, deleted)
VALUES
    (1, 'Admin', 'ADMIN', 1, 0),
    (2, 'User', 'USER', 1, 0)
ON DUPLICATE KEY UPDATE
    role_name = VALUES(role_name),
    status = VALUES(status),
    deleted = VALUES(deleted);

INSERT IGNORE INTO sys_user_role (user_id, role_id)
VALUES
    (1, 1),
    (2, 2);
