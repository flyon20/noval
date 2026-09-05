package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeProjectIngestPhase24MigrationTest {

    @Test
    void shouldDeclareGenerationContractsAndBeReEntrantOnH2() throws Exception {
        String mysql = Files.readString(resolveSql("phase24-project-ingest-generation.sql"), StandardCharsets.UTF_8);
        assertThat(mysql).contains(
            "ai_project_chapter_head",
            "ai_project_ingest_generation",
            "ai_project_ingest_outbox",
            "ai_project_extraction_candidate",
            "ai_project_tombstone",
            "idempotency_key",
            "fencing_token",
            "phase24:h2-backfill-start",
            "phase24:h2-backfill-end"
        );
        assertThat(mysql).doesNotContain("DROP TABLE");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        runScript(jdbc, resolveResource("sql/phase16-project-knowledge-rag-h2.sql"));
        jdbc.update("insert into ai_project_work(user_id, project_id, title, status) values(1, 1, 'w', 'ACTIVE')");
        jdbc.update("insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status) values(1,1,1,1,'t','c','h',1,'upload',1,'ACTIVE')");
        runScript(jdbc, resolveResource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase24-project-ingest-generation-h2.sql"));

        Integer genCount = jdbc.queryForObject("select count(*) from ai_project_ingest_generation where parser_version = 'legacy-baseline'", Integer.class);
        Integer headCount = jdbc.queryForObject("select count(*) from ai_project_chapter_head", Integer.class);
        assertThat(genCount).isEqualTo(1);
        assertThat(headCount).isEqualTo(1);
    }

    private Path resolveSql(String name) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("sql/mysql").resolve(name),
            Path.of("backend/sql/mysql").resolve(name),
            Path.of("..", "sql/mysql").resolve(name)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new java.nio.file.NoSuchFileException(name);
    }

    private Path resolveResource(String classpathRelative) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("src/test/resources").resolve(classpathRelative),
            Path.of("backend/src/test/resources").resolve(classpathRelative),
            Path.of("..", "src/test/resources").resolve(classpathRelative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        var url = getClass().getClassLoader().getResource(classpathRelative.replace("\\", "/"));
        if (url != null) {
            return Path.of(url.toURI());
        }
        throw new java.nio.file.NoSuchFileException(classpathRelative);
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:phase24_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == '﻿') {
            sql = sql.substring(1);
        }
        for (String stmt : sql.split(";")) {
            StringBuilder cleaned = new StringBuilder();
            for (String line : stmt.split("\\R")) {
                if (!line.trim().startsWith("--")) {
                    cleaned.append(line).append('\n');
                }
            }
            String executable = cleaned.toString().trim();
            if (executable.isEmpty()) {
                continue;
            }
            try {
                jdbc.execute(executable);
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (!msg.contains("already exists") && !msg.contains("duplicate")) {
                    throw ex;
                }
            }
        }
    }
}
