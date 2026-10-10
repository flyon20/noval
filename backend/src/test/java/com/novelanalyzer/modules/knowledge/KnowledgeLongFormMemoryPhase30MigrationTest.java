package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeLongFormMemoryPhase30MigrationTest {

    private static final List<String> TABLES = List.of(
        "ai_project_memory_fact",
        "ai_project_memory_evidence",
        "ai_project_memory_entity_alias",
        "ai_project_summary_node",
        "ai_project_summary_evidence"
    );

    @Test
    void declaresAdditiveLongFormMemoryContractsAndIsReEntrantOnH2() throws Exception {
        String mysql = Files.readString(resolveSql("phase30-long-form-memory-foundation.sql"), StandardCharsets.UTF_8);
        assertThat(mysql).contains(TABLES.toArray(String[]::new));
        assertThat(mysql).contains(
            "canonical_key", "supersedes_fact_id", "valid_from_chapter_no",
            "valid_to_chapter_no", "summary_version", "source_hash"
        ).doesNotContain("DROP TABLE");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        Path h2 = resolveResource("sql/phase30-long-form-memory-foundation-h2.sql");
        runScript(jdbc, h2);
        runScript(jdbc, h2);

        for (String table : TABLES) {
            Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?",
                Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
    }

    private Path resolveSql(String name) throws Exception {
        for (Path candidate : List.of(Path.of("sql/mysql").resolve(name),
            Path.of("backend/sql/mysql").resolve(name), Path.of("..", "sql/mysql").resolve(name))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new java.nio.file.NoSuchFileException(name);
    }

    private Path resolveResource(String name) throws Exception {
        for (Path candidate : List.of(Path.of("src/test/resources").resolve(name),
            Path.of("backend/src/test/resources").resolve(name), Path.of("..", "src/test/resources").resolve(name))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new java.nio.file.NoSuchFileException(name);
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:phase30_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        return source;
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        for (String statement : Files.readString(path, StandardCharsets.UTF_8).split(";")) {
            String executable = statement.trim();
            if (!executable.isEmpty()) jdbc.execute(executable);
        }
    }
}
