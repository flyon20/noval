package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlInitOrderTest {

    private static final Path REPOSITORY_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path MYSQL_SCRIPTS = REPOSITORY_ROOT.resolve("backend/sql/mysql");
    private static final Path INIT_SCRIPT = REPOSITORY_ROOT.resolve("docker/mysql/00-initialize-noval.sh");
    private static final Path COMPOSE_FILE = REPOSITORY_ROOT.resolve("docker-compose.yml");
    private static final Pattern SCRIPT_ENTRY = Pattern.compile("(?m)^\\s{4}\\\"([^\\\"]+\\.sql)\\\"$");

    private static final List<String> EXPECTED_INIT_ORDER = List.of(
        "phase2-schema.sql",
        "phase2-seed.sql",
        "phase3-schema.sql",
        "phase4-schema.sql",
        "phase4-seed.sql",
        "phase5-schema.sql",
        "phase5-seed.sql",
        "phase5-prompt-governance-repair.sql",
        "phase6-schema.sql",
        "phase7-knowledge-schema.sql",
        "phase8-history-pagination.sql",
        "phase8-knowledge-chat-memory-schema.sql",
        "phase9-knowledge-index-metadata-migration.sql",
        "phase10-history-search-index.sql",
        "phase11-rag-eval-observability.sql",
        "phase12-webnovel-agent-project-trace.sql",
        "phase13-agent-memory-mcp.sql",
        "phase14-ai-agent-production-upgrade.sql",
        "phase15-ai-chat-run-production.sql",
        "phase16-project-knowledge-rag.sql",
        "phase17-project-knowledge-ingest-upgrade.sql",
        "phase18-agent-harness-conversation-rag.sql",
        "phase19-durable-chat-run-execution.sql",
        "phase20-agent-tool-governance.sql",
        "phase21-agent-task7-production-hardening.sql",
        "phase22-agent-task7-review-hardening.sql",
        "phase23-skill-memory-lifecycle.sql",
        "phase24-project-ingest-generation.sql",
        "phase25-project-hybrid-retrieval-story-graph.sql",
        "phase26-project-retrieval-eval-observability.sql",
        "phase27-agent-skill-contract.sql",
        "phase28-mysql-resource-optimization.sql",
        "phase29-project-document-batch.sql",
        "phase30-long-form-memory-foundation.sql"
    );

    @Test
    void initializesEveryRunnableSqlExactlyOnceInDependencyOrder() throws Exception {
        String initScript = Files.readString(INIT_SCRIPT, StandardCharsets.UTF_8);
        List<String> configuredScripts = SCRIPT_ENTRY.matcher(initScript)
            .results()
            .map(match -> match.group(1))
            .toList();

        List<String> runnableScripts;
        try (var scripts = Files.list(MYSQL_SCRIPTS)) {
            runnableScripts = scripts
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".sql"))
                .filter(name -> !name.toLowerCase(Locale.ROOT).contains("verify"))
                .sorted()
                .toList();
        }

        assertThat(configuredScripts).containsExactlyElementsOf(EXPECTED_INIT_ORDER);
        assertThat(configuredScripts).doesNotHaveDuplicates();
        assertThat(configuredScripts).containsExactlyInAnyOrderElementsOf(runnableScripts);
        assertThat(configuredScripts).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("verify"));
        assertThat(configuredScripts).allSatisfy(name -> assertThat(MYSQL_SCRIPTS.resolve(name)).isRegularFile());
    }

    @Test
    void initializationEntryIsFailFastAndUsesOfficialMysqlEntrypointFunction() throws Exception {
        String initScript = Files.readString(INIT_SCRIPT, StandardCharsets.UTF_8);

        assertThat(initScript)
            .contains("if ! declare -F docker_process_sql")
            .contains("if [[ ! -r \"${sql_path}\" ]]")
            .contains("if ! docker_process_sql < \"${sql_path}\"; then")
            .contains("exit 1")
            .doesNotContain("MYSQL_ROOT_PASSWORD")
            .doesNotContain("mysql -u")
            .doesNotContain("phase18-agent-harness-conversation-rag-verify.sql");
    }

    @Test
    void composeMountsOnlyTheOrderedEntryIntoMysqlInitdb() throws Exception {
        String compose = Files.readString(COMPOSE_FILE, StandardCharsets.UTF_8).replace('\\', '/');

        assertThat(compose)
            .contains("./backend/sql/mysql:/opt/noval/sql/mysql:ro")
            .contains("./docker/mysql/00-initialize-noval.sh:/docker-entrypoint-initdb.d/00-initialize-noval.sh:ro")
            .doesNotContain("./backend/sql/mysql:/docker-entrypoint-initdb.d");
        assertThat(Pattern.compile("/docker-entrypoint-initdb\\.d").matcher(compose).results()).hasSize(1);
    }

    @Test
    void composeAppliesPhase18ThroughPhase30ToExistingVolumesBeforeBackendStarts() throws Exception {
        String compose = Files.readString(COMPOSE_FILE, StandardCharsets.UTF_8).replace('\\', '/');
        String mysql = serviceBlock(compose, "mysql");
        String migration = serviceBlock(compose, "schema-migrate");
        String backend = serviceBlock(compose, "backend");

        assertThat(mysql)
            .contains("healthcheck:")
            .contains("MALLOC_ARENA_MAX: \"${MYSQL_MALLOC_ARENA_MAX:-4}\"")
            .contains("cpus: \"${MYSQL_CPU_LIMIT:-0.50}\"")
            .contains("MYSQL_PWD=\"$${MYSQL_PASSWORD}\"")
            .contains("--performance-schema-digests-size=${MYSQL_PERFORMANCE_SCHEMA_DIGESTS_SIZE:-2000}")
            .contains("--performance-schema-events-statements-history-long-size=${MYSQL_PERFORMANCE_SCHEMA_HISTORY_LONG_SIZE:-1000}")
            .contains("SELECT 1");
        assertThat(migration)
            .contains("mysql:")
            .contains("condition: service_healthy")
            .contains("MYSQL_PWD: ${MYSQL_PASSWORD:-CHANGE_ME_WITH_A_STRONG_APP_PASSWORD}")
            .contains("./backend/sql/mysql:/opt/noval/sql/mysql:ro")
            .contains("phase18-agent-harness-conversation-rag.sql")
            .contains("phase19-durable-chat-run-execution.sql")
            .contains("phase20-agent-tool-governance.sql")
            .contains("phase21-agent-task7-production-hardening.sql")
            .contains("phase22-agent-task7-review-hardening.sql")
            .contains("phase23-skill-memory-lifecycle.sql")
            .contains("phase24-project-ingest-generation.sql")
            .contains("phase25-project-hybrid-retrieval-story-graph.sql")
            .contains("phase26-project-retrieval-eval-observability.sql")
            .contains("phase27-agent-skill-contract.sql")
            .contains("phase28-mysql-resource-optimization.sql")
            .contains("phase29-project-document-batch.sql")
            .contains("phase30-long-form-memory-foundation.sql")
            .contains("restart: \"no\"")
            .doesNotContain("MYSQL_ROOT_PASSWORD", "-p$", "--password");
        assertThat(Pattern.compile("\\bmysql --protocol=TCP\\b").matcher(migration).results()).hasSize(13);
        assertThat(Pattern.compile("\\bexec mysql\\b").matcher(migration).results()).hasSize(1);
        assertThat(migration.indexOf("phase18-agent-harness-conversation-rag.sql"))
            .isLessThan(migration.indexOf("phase19-durable-chat-run-execution.sql"));
        assertThat(migration.indexOf("phase19-durable-chat-run-execution.sql"))
            .isLessThan(migration.indexOf("phase20-agent-tool-governance.sql"));
        assertThat(migration.indexOf("phase20-agent-tool-governance.sql"))
            .isLessThan(migration.indexOf("phase21-agent-task7-production-hardening.sql"));
        assertThat(migration.indexOf("phase21-agent-task7-production-hardening.sql"))
            .isLessThan(migration.indexOf("phase22-agent-task7-review-hardening.sql"));
        assertThat(migration.indexOf("phase22-agent-task7-review-hardening.sql"))
            .isLessThan(migration.indexOf("phase23-skill-memory-lifecycle.sql"));
        assertThat(migration.indexOf("phase23-skill-memory-lifecycle.sql"))
            .isLessThan(migration.indexOf("phase24-project-ingest-generation.sql"));
        assertThat(migration.indexOf("phase24-project-ingest-generation.sql"))
            .isLessThan(migration.indexOf("phase25-project-hybrid-retrieval-story-graph.sql"));
        assertThat(migration.indexOf("phase25-project-hybrid-retrieval-story-graph.sql"))
            .isLessThan(migration.indexOf("phase26-project-retrieval-eval-observability.sql"));
        assertThat(migration.indexOf("phase26-project-retrieval-eval-observability.sql"))
            .isLessThan(migration.indexOf("phase27-agent-skill-contract.sql"));
        assertThat(migration.indexOf("phase27-agent-skill-contract.sql"))
            .isLessThan(migration.indexOf("phase28-mysql-resource-optimization.sql"));
        assertThat(migration.indexOf("phase28-mysql-resource-optimization.sql"))
            .isLessThan(migration.indexOf("phase29-project-document-batch.sql"));
        assertThat(migration.indexOf("phase29-project-document-batch.sql"))
            .isLessThan(migration.indexOf("exec mysql"));
        assertThat(migration.indexOf("exec mysql"))
            .isLessThan(migration.indexOf("phase30-long-form-memory-foundation.sql"));
        assertThat(backend)
            .contains("schema-migrate:")
            .contains("condition: service_completed_successfully");
    }

    private String serviceBlock(String compose, String service) {
        Pattern pattern = Pattern.compile(
            "(?ms)^  " + Pattern.quote(service) + ":\\R(.*?)(?=^  [a-zA-Z0-9_-]+:|^volumes:)"
        );
        Matcher matcher = pattern.matcher(compose);
        assertThat(matcher.find()).as("compose service %s", service).isTrue();
        return matcher.group();
    }
}
