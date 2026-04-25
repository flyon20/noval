package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPromptGovernanceSchemaTest {

    @Test
    void phase4SchemaIncludesPromptGovernanceScopeOwnershipAndSourceColumns() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase4-schema.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script).contains("scope_type");
        assertThat(script).contains("owner_user_id");
        assertThat(script).contains("source_prompt_config_id");
    }

    @Test
    void phase5SchemaIncludesPromptGovernancePublishAndBindingTables() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase5-schema.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS prompt_publish_version");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS prompt_publish_item");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS user_prompt_binding");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS user_prompt_effective_history");
        assertThat(script).contains("UNIQUE KEY uk_publish_version_no");
        assertThat(script).contains("UNIQUE KEY uk_publish_item_version_type");
        assertThat(script).contains("UNIQUE KEY uk_user_prompt_type");
    }
}
