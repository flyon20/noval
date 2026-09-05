package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase20SchemaTest {

    private static final Path MYSQL_SCRIPTS = Path.of("..", "sql", "mysql");

    @Test
    void phase20ExpandsEvalCaseIdentityColumnsForFullProfileHashes() throws Exception {
        String script = Files.readString(
            MYSQL_SCRIPTS.resolve("phase20-agent-tool-governance.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script)
            .contains("ALTER TABLE ai_eval_case_result")
            .contains("ALTER TABLE ai_eval_trace_event")
            .contains("MODIFY COLUMN case_key VARCHAR(255) NOT NULL")
            .doesNotContain("MODIFY COLUMN case_key VARCHAR(128)");
    }

    @Test
    void phase11CreatesEvalTablesBeforePhase20AltersThemInMysqlInitdbOrder() throws Exception {
        List<String> scriptNames;
        try (var scripts = Files.list(MYSQL_SCRIPTS)) {
            scriptNames = scripts
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .map(path -> path.getFileName().toString())
                .sorted(Comparator.naturalOrder())
                .toList();
        }

        assertThat(scriptNames.indexOf("phase11-rag-eval-observability.sql"))
            .isLessThan(scriptNames.indexOf("phase20-agent-tool-governance.sql"));
    }
}
