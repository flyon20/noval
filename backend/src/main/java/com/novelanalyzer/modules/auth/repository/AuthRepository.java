package com.novelanalyzer.modules.auth.repository;

import com.novelanalyzer.modules.auth.model.AuthUserEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AuthRepository {

    private static final RowMapper<AuthUserEntity> AUTH_USER_ROW_MAPPER = (rs, rowNum) -> {
        AuthUserEntity user = new AuthUserEntity();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPassword(rs.getString("password"));
        user.setStatus(rs.getInt("status"));
        return user;
    };

    private final JdbcTemplate jdbcTemplate;

    public AuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthUserEntity> findActiveUserByUsername(String username) {
        AuthUserEntity user = findUserByUsername(username).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public Optional<AuthUserEntity> findUserByUsername(String username) {
        List<AuthUserEntity> users = jdbcTemplate.query(
            "SELECT id, username, password, status FROM sys_user WHERE username = ? AND deleted = 0 LIMIT 1",
            AUTH_USER_ROW_MAPPER,
            username
        );
        if (users.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(users.get(0));
    }

    public Optional<AuthUserEntity> findActiveUserById(Long userId) {
        List<AuthUserEntity> users = jdbcTemplate.query(
            "SELECT id, username, password, status FROM sys_user WHERE id = ? AND deleted = 0 LIMIT 1",
            AUTH_USER_ROW_MAPPER,
            userId
        );
        if (users.isEmpty()) {
            return Optional.empty();
        }
        AuthUserEntity user = users.get(0);
        if (user.getStatus() == null || user.getStatus() != 1) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public boolean existsUserByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE username = ?",
            Integer.class,
            username
        );
        return count != null && count > 0;
    }

    public Long insertUser(String username, String encodedPassword) {
        jdbcTemplate.update(
            """
            INSERT INTO sys_user (username, password, status, deleted, create_time, update_time, version)
            VALUES (?, ?, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """,
            username,
            encodedPassword
        );

        return jdbcTemplate.queryForObject(
            "SELECT id FROM sys_user WHERE username = ? LIMIT 1",
            Long.class,
            username
        );
    }

    public Optional<Long> findActiveRoleIdByCode(String roleCode) {
        List<Long> roleIds = jdbcTemplate.queryForList(
            "SELECT id FROM sys_role WHERE role_code = ? AND deleted = 0 AND status = 1 LIMIT 1",
            Long.class,
            roleCode
        );
        if (roleIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(roleIds.get(0));
    }

    public void insertUserRole(Long userId, Long roleId) {
        jdbcTemplate.update(
            "INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP)",
            userId,
            roleId
        );
    }

    public List<String> findRoleCodesByUserId(Long userId) {
        return jdbcTemplate.queryForList(
            """
            SELECT r.role_code
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = ? AND r.deleted = 0 AND r.status = 1
            """,
            String.class,
            userId
        );
    }

    public void updateLastLoginTime(Long userId) {
        jdbcTemplate.update(
            "UPDATE sys_user SET last_login_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP WHERE id = ?",
            userId
        );
    }

    public void insertLoginLog(Long userId, String username, String loginIp, int status, String message) {
        jdbcTemplate.update(
            """
            INSERT INTO sys_login_log (user_id, username, login_ip, status, message, login_time)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """,
            userId,
            username,
            loginIp,
            status,
            message
        );
    }

}
