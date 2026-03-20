package com.novelanalyzer.modules.security.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertOperationLog(Long userId,
                                   String username,
                                   String module,
                                   String operationType,
                                   String operationDesc,
                                   int status,
                                   String errorMessage,
                                   String operationIp) {
        jdbcTemplate.update(
            """
            INSERT INTO sys_operation_log
            (user_id, username, module, operation_type, operation_desc, status, error_message, operation_ip, operation_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """,
            userId,
            username,
            module,
            operationType,
            operationDesc,
            status,
            errorMessage,
            operationIp
        );
    }
}

