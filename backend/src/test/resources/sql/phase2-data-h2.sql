INSERT INTO sys_user (id, username, password, status, deleted) VALUES
  (1, 'admin', '{noop}admin123', 1, 0),
  (2, 'writer', '{noop}writer123', 1, 0);

INSERT INTO sys_role (id, role_name, role_code, status, deleted) VALUES
  (1, '管理员', 'ADMIN', 1, 0),
  (2, '普通用户', 'USER', 1, 0);

INSERT INTO sys_user_role (user_id, role_id) VALUES
  (1, 1),
  (2, 2);

