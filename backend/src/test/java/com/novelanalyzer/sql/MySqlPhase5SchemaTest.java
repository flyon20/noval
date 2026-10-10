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
    void phase5SchemaIncludesAuditableAsyncJobDedupeArchive() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase5-schema.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script)
            .contains("CREATE TABLE IF NOT EXISTS async_job_dedup_archive")
            .contains("source_async_job_id BIGINT NOT NULL")
            .contains("survivor_async_job_id BIGINT NOT NULL")
            .contains("request_json LONGTEXT")
            .contains("uk_async_job_dedup_archive_source");
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

    @Test
    void phase5SeedUsesThreeDayRankFreshness() throws Exception {
        String seedScript = Files.readString(
            Path.of("..", "sql", "mysql", "phase5-seed.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(seedScript)
            .contains("('crawler.rank.refresh-days', '3'")
            .doesNotContain("('crawler.rank.refresh-days', '5'");
    }

    @Test
    void phase21BackfillsExistingFiveDayFreshnessAndTask7GovernanceSchema() throws Exception {
        String script = Files.readString(
            Path.of("..", "sql", "mysql", "phase21-agent-task7-production-hardening.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(script)
            .contains("crawler_rank_refresh_commit")
            .contains("crawler_rank_refresh_fence")
            .contains("idx_crawl_rank_snapshot_lookup")
            .contains("INFORMATION_SCHEMA.STATISTICS")
            .contains("VARCHAR(255) NOT NULL")
            .contains("config_key = 'crawler.rank.refresh-days'")
            .contains("TRIM(config_value) = '5'")
            .contains("config_value = '3'");
    }
}
