package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase2SchemaTest {

    @Test
    void phase2SchemaIncludesAuthSessionTable() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase2-schema.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS sys_user_session");
        assertThat(script).contains("session_id VARCHAR(64) NOT NULL");
        assertThat(script).contains("refresh_token_hash VARCHAR(128) NOT NULL");
        assertThat(script).contains("refresh_expire_time DATETIME NOT NULL");
        assertThat(script).contains("UNIQUE KEY uk_user_session_session_id (session_id)");
        assertThat(script).contains("UNIQUE KEY uk_user_session_refresh_hash (refresh_token_hash)");
    }
}
