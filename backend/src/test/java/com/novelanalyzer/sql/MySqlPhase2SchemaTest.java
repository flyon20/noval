package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase2SchemaTest {

    @Test
    void phase2SchemaIncludesPhoneAuthAndSmsAuditTables() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase2-schema.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script).contains("phone VARCHAR(20) NOT NULL");
        assertThat(script).contains("UNIQUE KEY uk_phone (phone)");
        assertThat(script).contains("phone_verified TINYINT DEFAULT 1");
        assertThat(script).contains("password_updated_time DATETIME");
        assertThat(script).contains("login_type VARCHAR(20)");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS sys_user_session");
        assertThat(script).contains("session_id VARCHAR(64) NOT NULL");
        assertThat(script).contains("refresh_token_hash VARCHAR(128) NOT NULL");
        assertThat(script).contains("refresh_expire_time DATETIME NOT NULL");
        assertThat(script).contains("UNIQUE KEY uk_user_session_session_id (session_id)");
        assertThat(script).contains("UNIQUE KEY uk_user_session_refresh_hash (refresh_token_hash)");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS sys_sms_code_log");
        assertThat(script).contains("biz_type VARCHAR(32) NOT NULL");
        assertThat(script).contains("provider VARCHAR(32) NOT NULL DEFAULT 'aliyun-pnvs'");
        assertThat(script).contains("out_id VARCHAR(64) NOT NULL");
    }
}
