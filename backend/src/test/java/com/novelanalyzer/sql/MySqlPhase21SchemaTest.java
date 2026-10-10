package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase21SchemaTest {

    private static final Path MYSQL_SCRIPTS = Path.of("..", "sql", "mysql");
    private static final Pattern UNGUARDED_OPTIONAL_SCHEMA_STATEMENT = Pattern.compile(
        "(?im)^\\s*(?:CREATE\\s+INDEX\\s+idx_crawl_rank_snapshot_lookup\\s+ON\\s+crawl_rank"
            + "|ALTER\\s+TABLE\\s+ai_eval_(?:case_result|trace_event)"
            + "|UPDATE\\s+system_config)\\b"
    );

    @Test
    void phase21RunsBeforeLegacyBaseSchemasInMysqlInitdbLexicalOrder() throws Exception {
        List<String> scriptNames;
        try (var scripts = Files.list(MYSQL_SCRIPTS)) {
            scriptNames = scripts
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .map(path -> path.getFileName().toString())
                .sorted(Comparator.naturalOrder())
                .toList();
        }

        assertThat(scriptNames.indexOf("phase21-agent-task7-production-hardening.sql"))
            .isLessThan(scriptNames.indexOf("phase3-schema.sql"));
        assertThat(scriptNames.indexOf("phase21-agent-task7-production-hardening.sql"))
            .isLessThan(scriptNames.indexOf("phase4-schema.sql"));
        assertThat(scriptNames.indexOf("phase21-agent-task7-production-hardening.sql"))
            .isLessThan(scriptNames.indexOf("phase5-schema.sql"));
    }

    @Test
    void phase21GuardsEveryUpgradeThatDependsOnALaterSchema() throws Exception {
        String script = Files.readString(
            MYSQL_SCRIPTS.resolve("phase21-agent-task7-production-hardening.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script)
            .contains("INFORMATION_SCHEMA.TABLES")
            .contains("INFORMATION_SCHEMA.COLUMNS")
            .contains("INFORMATION_SCHEMA.STATISTICS")
            .contains("TABLE_NAME = 'crawl_rank'")
            .contains("TABLE_NAME = 'system_config'")
            .contains("TABLE_NAME = 'ai_eval_case_result'")
            .contains("TABLE_NAME = 'ai_eval_trace_event'")
            .contains("PREPARE phase21_stmt")
            .contains("DEALLOCATE PREPARE phase21_stmt");
        assertThat(UNGUARDED_OPTIONAL_SCHEMA_STATEMENT.matcher(script).find()).isFalse();
    }
}
