package com.novelanalyzer.modules.auth.repository;

import com.novelanalyzer.modules.auth.model.AuthSessionEntity;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthSessionRepository {

    private static final RowMapper<AuthSessionEntity> AUTH_SESSION_ROW_MAPPER = (rs, rowNum) -> {
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId(rs.getLong("id"));
        session.setUserId(rs.getLong("user_id"));
        session.setSessionId(rs.getString("session_id"));
        session.setRefreshTokenHash(rs.getString("refresh_token_hash"));
        session.setStatus(rs.getInt("status"));
        session.setDeviceLabel(rs.getString("device_label"));
        session.setUserAgent(rs.getString("user_agent"));
        session.setLoginIp(rs.getString("login_ip"));
        session.setLastActiveTime(toLocalDateTime(rs.getTimestamp("last_active_time")));
        session.setLastRefreshTime(toLocalDateTime(rs.getTimestamp("last_refresh_time")));
        session.setRefreshExpireTime(toLocalDateTime(rs.getTimestamp("refresh_expire_time")));
        session.setRevokeReason(rs.getString("revoke_reason"));
        session.setRevokedAt(toLocalDateTime(rs.getTimestamp("revoked_at")));
        session.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        session.setUpdateTime(toLocalDateTime(rs.getTimestamp("update_time")));
        session.setDeleted(rs.getInt("deleted"));
        session.setVersion(rs.getInt("version"));
        return session;
    };

    private final JdbcTemplate jdbcTemplate;

    public AuthSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertSession(AuthSessionEntity session) {
        jdbcTemplate.update(
            """
            INSERT INTO sys_user_session (
                user_id, session_id, refresh_token_hash, status, device_label, user_agent, login_ip,
                last_active_time, last_refresh_time, refresh_expire_time, create_time, update_time, deleted, version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
            """,
            session.getUserId(),
            session.getSessionId(),
            session.getRefreshTokenHash(),
            session.getStatus(),
            session.getDeviceLabel(),
            session.getUserAgent(),
            session.getLoginIp(),
            session.getLastActiveTime(),
            session.getLastRefreshTime(),
            session.getRefreshExpireTime()
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM sys_user_session WHERE session_id = ? AND deleted = 0 LIMIT 1",
            Long.class,
            session.getSessionId()
        );
    }

    public List<AuthSessionEntity> findActiveSessionsByUserId(Long userId) {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM sys_user_session
            WHERE user_id = ? AND status = ? AND deleted = 0
            ORDER BY last_active_time DESC, id DESC
            """,
            AUTH_SESSION_ROW_MAPPER,
            userId,
            AuthSessionStatus.ACTIVE
        );
    }

    public Optional<AuthSessionEntity> findActiveSessionBySessionId(String sessionId) {
        List<AuthSessionEntity> sessions = jdbcTemplate.query(
            """
            SELECT *
            FROM sys_user_session
            WHERE session_id = ? AND status = ? AND deleted = 0
            LIMIT 1
            """,
            AUTH_SESSION_ROW_MAPPER,
            sessionId,
            AuthSessionStatus.ACTIVE
        );
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sessions.get(0));
    }

    public Optional<AuthSessionEntity> findActiveSessionByRefreshTokenHash(String refreshTokenHash) {
        List<AuthSessionEntity> sessions = jdbcTemplate.query(
            """
            SELECT *
            FROM sys_user_session
            WHERE refresh_token_hash = ? AND status = ? AND deleted = 0
            LIMIT 1
            """,
            AUTH_SESSION_ROW_MAPPER,
            refreshTokenHash,
            AuthSessionStatus.ACTIVE
        );
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sessions.get(0));
    }

    public boolean updateSessionOnRefresh(String sessionId,
                                          String newRefreshTokenHash,
                                          LocalDateTime lastRefreshTime,
                                          LocalDateTime refreshExpireTime) {
        int affected = jdbcTemplate.update(
            """
            UPDATE sys_user_session
            SET refresh_token_hash = ?,
                last_refresh_time = ?,
                last_active_time = ?,
                refresh_expire_time = ?,
                update_time = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE session_id = ? AND status = ? AND deleted = 0
            """,
            newRefreshTokenHash,
            lastRefreshTime,
            lastRefreshTime,
            refreshExpireTime,
            sessionId,
            AuthSessionStatus.ACTIVE
        );
        return affected > 0;
    }

    public boolean revokeSession(String sessionId, int revokedStatus, String reason, LocalDateTime revokedAt) {
        int affected = jdbcTemplate.update(
            """
            UPDATE sys_user_session
            SET status = ?,
                revoke_reason = ?,
                revoked_at = ?,
                update_time = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE session_id = ? AND status = ? AND deleted = 0
            """,
            revokedStatus,
            reason,
            revokedAt,
            sessionId,
            AuthSessionStatus.ACTIVE
        );
        return affected > 0;
    }

    public Optional<AuthSessionEntity> findOldestActiveSessionForUser(Long userId) {
        List<AuthSessionEntity> sessions = jdbcTemplate.query(
            """
            SELECT *
            FROM sys_user_session
            WHERE user_id = ? AND status = ? AND deleted = 0
            ORDER BY last_active_time ASC, create_time ASC, id ASC
            LIMIT 1
            """,
            AUTH_SESSION_ROW_MAPPER,
            userId,
            AuthSessionStatus.ACTIVE
        );
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sessions.get(0));
    }

    public boolean updateLastActiveTime(String sessionId, LocalDateTime lastActiveTime) {
        int affected = jdbcTemplate.update(
            """
            UPDATE sys_user_session
            SET last_active_time = ?, update_time = CURRENT_TIMESTAMP, version = version + 1
            WHERE session_id = ? AND status = ? AND deleted = 0
            """,
            lastActiveTime,
            sessionId,
            AuthSessionStatus.ACTIVE
        );
        return affected > 0;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
