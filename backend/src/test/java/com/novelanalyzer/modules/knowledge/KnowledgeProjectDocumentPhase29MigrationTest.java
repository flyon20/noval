package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeProjectDocumentPhase29MigrationTest {

    private static final List<String> TABLES = List.of(
        "ai_project_document_batch",
        "ai_project_document_file",
        "ai_project_document",
        "ai_project_document_generation",
        "ai_project_document_section",
        "ai_project_document_question",
        "ai_project_document_batch_outbox",
        "ai_project_entity_evidence"
    );

    @Test
    void declaresPersistentBatchContractsAndIsReEntrantOnH2() throws Exception {
        String mysql = Files.readString(resolveSql("phase29-project-document-batch.sql"), StandardCharsets.UTF_8);
        assertThat(mysql).contains(TABLES.toArray(String[]::new));
        assertThat(mysql).contains(
            "document_id",
            "section_id",
            "profile_type",
            "source_document_id",
            "document_generation_id",
            "fencing_token",
            "idempotency_key",
            "available_at",
            "content_blob LONGBLOB NOT NULL"
        ).doesNotContain("DROP TABLE");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        runScript(jdbc, resolveResource("sql/phase16-project-knowledge-rag-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase29-project-document-batch-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase29-project-document-batch-h2.sql"));

        for (String table : TABLES) {
            Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?",
                Integer.class,
                table
            );
            assertThat(count).as(table).isEqualTo(1);
        }
    }

    private Path resolveSql(String name) throws Exception {
        for (Path candidate : List.of(
            Path.of("sql/mysql").resolve(name),
            Path.of("backend/sql/mysql").resolve(name),
            Path.of("..", "sql/mysql").resolve(name)
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new java.nio.file.NoSuchFileException(name);
    }

    private Path resolveResource(String name) throws Exception {
        for (Path candidate : List.of(
            Path.of("src/test/resources").resolve(name),
            Path.of("backend/src/test/resources").resolve(name),
            Path.of("..", "src/test/resources").resolve(name)
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new java.nio.file.NoSuchFileException(name);
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:phase29_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        return source;
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        for (String statement : Files.readString(path, StandardCharsets.UTF_8).split(";")) {
            String executable = statement.trim();
            if (!executable.isEmpty()) {
                jdbc.execute(executable);
            }
        }
    }
}
