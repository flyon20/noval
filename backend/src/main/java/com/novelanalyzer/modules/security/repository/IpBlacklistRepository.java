package com.novelanalyzer.modules.security.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Repository
public class IpBlacklistRepository {

    private final JdbcTemplate jdbcTemplate;

    public IpBlacklistRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isBlacklisted(String ipAddress) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM sys_ip_blacklist
            WHERE ip_address = ?
              AND status = 1
              AND (expire_time IS NULL OR expire_time > CURRENT_TIMESTAMP)
            """,
            Integer.class,
            ipAddress
        );
        return count != null && count > 0;
    }

    public void upsertBlockedIp(String ipAddress, String reason, LocalDateTime expireTime) {
        int updated = jdbcTemplate.update(
            """
            UPDATE sys_ip_blacklist
            SET reason = ?, expire_time = ?, status = 1, update_time = CURRENT_TIMESTAMP
            WHERE ip_address = ?
            """,
            reason,
            expireTime == null ? null : Timestamp.valueOf(expireTime),
            ipAddress
        );
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                """
                INSERT INTO sys_ip_blacklist
                (ip_address, reason, expire_time, status, create_time, update_time)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                ipAddress,
                reason,
                expireTime == null ? null : Timestamp.valueOf(expireTime)
            );
        } catch (DuplicateKeyException ex) {
            jdbcTemplate.update(
                """
                UPDATE sys_ip_blacklist
                SET reason = ?, expire_time = ?, status = 1, update_time = CURRENT_TIMESTAMP
                WHERE ip_address = ?
                """,
                reason,
                expireTime == null ? null : Timestamp.valueOf(expireTime),
                ipAddress
            );
        }
    }
}

