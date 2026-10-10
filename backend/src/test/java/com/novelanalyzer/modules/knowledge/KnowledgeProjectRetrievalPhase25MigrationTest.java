package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeProjectRetrievalPhase25MigrationTest {

    @Test
    void shouldDeclareHybridRetrievalContractsAndBackfillOnlyActiveChapterHeads() throws Exception {
        String mysql = Files.readString(resolveSql("phase25-project-hybrid-retrieval-story-graph.sql"), StandardCharsets.UTF_8);
        assertThat(mysql).contains(
            "ai_project_search_document",
            "ai_project_story_node",
            "ai_project_story_edge",
            "FULLTEXT KEY ft_ai_project_search_document_text",
            "WITH PARSER ngram",
            "uk_ai_project_story_node_scope",
            "idx_ai_project_story_edge_forward",
            "idx_ai_project_story_edge_reverse"
        );
        assertThat(mysql).doesNotContain("DROP TABLE");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        runScript(jdbc, resolveResource("sql/phase16-project-knowledge-rag-h2.sql"));
        jdbc.update("insert into ai_project_work(user_id, project_id, title, status) values(1, 1, 'work', 'ACTIVE')");
        jdbc.update("insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status) values(1, 1, 1, 1, 'chapter', 'active text', 'hash', 11, 'upload', 1, 'ACTIVE')");
        runScript(jdbc, resolveResource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));

        Integer documentCount = jdbc.queryForObject("select count(*) from ai_project_search_document", Integer.class);
        Integer activeGenerationDocuments = jdbc.queryForObject(
            "select count(*) from ai_project_search_document d join ai_project_chapter_head h on h.active_generation_id = d.generation_id where h.tombstoned_at is null",
            Integer.class
        );
        assertThat(documentCount).isEqualTo(1);
        assertThat(activeGenerationDocuments).isEqualTo(1);
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
        dataSource.setUrl("jdbc:h2:mem:phase25_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == '\ufeff') {
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
                String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (!message.contains("already exists") && !message.contains("duplicate")) {
                    throw ex;
                }
            }
        }
    }
}
