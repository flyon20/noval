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
        List<AuthUserEntity> users = jdbcTemplate.query(
            "SELECT id, username, password, status FROM sys_user WHERE username = ? AND deleted = 0 LIMIT 1",
            AUTH_USER_ROW_MAPPER,
            username
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

