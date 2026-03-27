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
}
