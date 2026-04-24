INSERT INTO sys_user (id, username, password, phone, phone_verified, status, deleted, password_updated_time) VALUES
  (1, '管理员', '{noop}admin123', '13800138000', 1, 1, 0, CURRENT_TIMESTAMP),
  (2, '作者', '{noop}writer123', '13800138001', 1, 1, 0, CURRENT_TIMESTAMP);

INSERT INTO sys_role (id, role_name, role_code, status, deleted) VALUES
  (1, '管理员', 'ADMIN', 1, 0),
  (2, '普通用户', 'USER', 1, 0);

INSERT INTO sys_user_role (user_id, role_id) VALUES
  (1, 1),
  (2, 2);

