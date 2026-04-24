package com.novelanalyzer.modules.auth.repository;

import com.novelanalyzer.modules.auth.model.SmsCodeLogEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SmsCodeLogRepository {

    private static final RowMapper<SmsCodeLogEntity> SMS_CODE_LOG_ROW_MAPPER = (rs, rowNum) -> {
        SmsCodeLogEntity entity = new SmsCodeLogEntity();
        entity.setId(rs.getLong("id"));
        entity.setPhone(rs.getString("phone"));
        entity.setBizType(rs.getString("biz_type"));
        entity.setProvider(rs.getString("provider"));
        entity.setOutId(rs.getString("out_id"));
        entity.setRequestId(rs.getString("request_id"));
        entity.setBizId(rs.getString("biz_id"));
        entity.setSchemeName(rs.getString("scheme_name"));
        entity.setStatus(rs.getString("status"));
        entity.setVerifyResult(rs.getString("verify_result"));
        entity.setSendIp(rs.getString("send_ip"));
        entity.setTraceId(rs.getString("trace_id"));
        entity.setMessage(rs.getString("message"));
        entity.setExpireTime(toLocalDateTime(rs.getTimestamp("expire_time")));
        entity.setVerifiedTime(toLocalDateTime(rs.getTimestamp("verified_time")));
        entity.setConsumedTime(toLocalDateTime(rs.getTimestamp("consumed_time")));
        return entity;
    };

    private final JdbcTemplate jdbcTemplate;

    public SmsCodeLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(SmsCodeLogEntity entity) {
        jdbcTemplate.update(
            """
            INSERT INTO sys_sms_code_log (
                phone, biz_type, provider, out_id, request_id, biz_id, scheme_name, status,
                verify_result, send_ip, trace_id, message, expire_time, verified_time, consumed_time,
                create_time, update_time, deleted
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """,
            entity.getPhone(),
            entity.getBizType(),
            entity.getProvider(),
            entity.getOutId(),
            entity.getRequestId(),
            entity.getBizId(),
            entity.getSchemeName(),
            entity.getStatus(),
            entity.getVerifyResult(),
            entity.getSendIp(),
            entity.getTraceId(),
            entity.getMessage(),
            entity.getExpireTime(),
            entity.getVerifiedTime(),
            entity.getConsumedTime()
        );
    }

    public Optional<SmsCodeLogEntity> findLatestActive(String phone, String bizType) {
        List<SmsCodeLogEntity> items = jdbcTemplate.query(
            """
            SELECT *
            FROM sys_sms_code_log
            WHERE phone = ?
              AND biz_type = ?
              AND deleted = 0
              AND consumed_time IS NULL
            ORDER BY id DESC
            LIMIT 1
            """,
            SMS_CODE_LOG_ROW_MAPPER,
            phone,
            bizType
        );
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
    }

    public Optional<SmsCodeLogEntity> findByOutId(String outId) {
        List<SmsCodeLogEntity> items = jdbcTemplate.query(
            """
            SELECT *
            FROM sys_sms_code_log
            WHERE out_id = ?
              AND deleted = 0
            LIMIT 1
            """,
            SMS_CODE_LOG_ROW_MAPPER,
            outId
        );
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
    }

    public List<SmsCodeLogEntity> findRecentUnconsumed(String phone, String bizType, int limit) {
        return jdbcTemplate.query(
            """
            SELECT *
            FROM sys_sms_code_log
            WHERE phone = ?
              AND biz_type = ?
              AND deleted = 0
              AND consumed_time IS NULL
            ORDER BY id DESC
            LIMIT ?
            """,
            SMS_CODE_LOG_ROW_MAPPER,
            phone,
            bizType,
            limit
        );
    }

    public void markVerified(String outId, String verifyResult) {
        jdbcTemplate.update(
            """
            UPDATE sys_sms_code_log
            SET status = ?, verify_result = ?, verified_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE out_id = ? AND deleted = 0
            """,
            "VERIFIED",
            verifyResult,
            outId
        );
    }

    public void markConsumed(String outId) {
        jdbcTemplate.update(
            """
            UPDATE sys_sms_code_log
            SET status = ?, consumed_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE out_id = ? AND deleted = 0
            """,
            "CONSUMED",
            outId
        );
    }

    public void markFailed(String outId, String status, String message) {
        jdbcTemplate.update(
            """
            UPDATE sys_sms_code_log
            SET status = ?, message = ?, update_time = CURRENT_TIMESTAMP
            WHERE out_id = ? AND deleted = 0
            """,
            status,
            message,
            outId
        );
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
