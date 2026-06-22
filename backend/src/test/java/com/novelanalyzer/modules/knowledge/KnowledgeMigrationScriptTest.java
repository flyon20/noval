package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMigrationScriptTest {

    @Test
    void phase9MigrationShouldMarkExistingChunksAsLegacyForRebuild() throws Exception {
        Path baseDir = Path.of(System.getProperty("user.dir"));
        Path migrationPath = baseDir.resolve("sql/mysql/phase9-knowledge-index-metadata-migration.sql");
        if (!Files.exists(migrationPath)) {
            migrationPath = baseDir.getParent().resolve("sql/mysql/phase9-knowledge-index-metadata-migration.sql");
        }
        String sql = Files.readString(
            migrationPath,
            StandardCharsets.UTF_8
        );

        assertThat(sql).contains("DEFAULT ''legacy-v1''");
        assertThat(sql).contains("SET chunk_strategy_version = 'legacy-v1'");
        assertThat(sql).doesNotContain("DEFAULT ''rag-v2''");
    }
}
