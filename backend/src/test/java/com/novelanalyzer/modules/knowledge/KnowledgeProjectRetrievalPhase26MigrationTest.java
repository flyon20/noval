package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeProjectRetrievalPhase26MigrationTest {

    @Test
    void shouldDeclareEvalObservabilityFeedbackAndDiagnosticContracts() throws Exception {
        String mysql = Files.readString(resolveSql("phase26-project-retrieval-eval-observability.sql"), StandardCharsets.UTF_8);
        assertThat(mysql).contains(
            "ai_project_retrieval_eval_baseline",
            "ai_project_knowledge_feedback",
            "ai_agent_resource_diagnostic",
            "review_status",
            "partial_flush",
            "tool_dedupe_prevented",
            "vector_latency_ms",
            "queue_wait_ms"
        );
        assertThat(mysql).doesNotContain("DROP TABLE");
        assertThat(mysql.toLowerCase()).doesNotContain("confirmed memory");
        assertThat(mysql).contains("never promote Memory to CONFIRMED");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        runScript(jdbc, resolveResource("sql/phase26-project-retrieval-eval-observability-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase26-project-retrieval-eval-observability-h2.sql"));

        jdbc.update(
            "insert into ai_project_knowledge_feedback(" +
                "user_id, project_id, work_id, generation_id, feedback_type, target_type, target_key, " +
                "old_value_json, new_value_json, evidence_json, operator_user_id, review_status" +
            ") values(1, 2, 3, 4, 'RECALL_ERROR', 'CHAPTER', 'chapter:12', '{}', '{}', '{}', 9, 'PENDING')"
        );
        Integer feedbackCount = jdbc.queryForObject("select count(*) from ai_project_knowledge_feedback", Integer.class);
        Integer baselineTable = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'ai_project_retrieval_eval_baseline'",
            Integer.class
        );
        Integer diagnosticTable = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'ai_agent_resource_diagnostic'",
            Integer.class
        );
        assertThat(feedbackCount).isEqualTo(1);
        assertThat(baselineTable).isEqualTo(1);
        assertThat(diagnosticTable).isEqualTo(1);
        String status = jdbc.queryForObject("select review_status from ai_project_knowledge_feedback", String.class);
        assertThat(status).isEqualTo("PENDING");
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
        dataSource.setUrl("jdbc:h2:mem:phase26_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
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
