package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase5SchemaTest {

    @Test
    void phase5SchemaAvoidsUnsupportedAlterTableIfNotExistsSyntax() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase5-schema.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script).doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(script).contains("INFORMATION_SCHEMA.COLUMNS");
    }

    @Test
    void phase5PromptGovernanceRepairPublishesAllDefaultPromptTypesDynamically() throws Exception {
        String repairScript = Files.readString(
            Path.of("..", "sql", "mysql", "phase5-prompt-governance-repair.sql"),
            StandardCharsets.UTF_8
        );
        String seedScript = Files.readString(
            Path.of("..", "sql", "mysql", "phase5-seed.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(repairScript)
            .contains("prompt_publish_version")
            .contains("prompt_publish_item")
            .contains("prompt_type IN ('deconstruct', 'structure', 'plot', 'theme')")
            .contains("prompt_name = 'default'")
            .contains("prompt_name = CONCAT('default-', prompt_type)")
            .doesNotContain("'theme', 4");

        assertThat(seedScript)
            .contains("prompt_type IN ('deconstruct', 'structure', 'plot', 'theme')")
            .contains("prompt_name = CONCAT('default-', prompt_type)")
            .contains("effective_prompt_config_id")
            .doesNotContain("'theme', 4");
    }
}
