package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeAgentSkillPhase27MigrationTest {

    @Test
    void shouldBackfillSkillDescriptorsAndRemainIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:phase27_skill;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        )) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbcTemplate.execute("""
                create table ai_skill_candidate (
                    id bigint auto_increment primary key,
                    title varchar(200),
                    allowed_tools_json clob,
                    version varchar(80),
                    content_hash varchar(64)
                )
                """);
            jdbcTemplate.execute("""
                create table ai_runtime_skill (
                    id bigint auto_increment primary key,
                    title varchar(200),
                    allowed_tools_json clob,
                    version varchar(80),
                    content_hash varchar(64),
                    status varchar(20)
                )
                """);
            jdbcTemplate.update(
                "insert into ai_skill_candidate(title, allowed_tools_json, version, content_hash) values (?, ?, ?, ?)",
                "Market Scan",
                "[\"rank.lookup\"]",
                "1.0.0",
                "a".repeat(64)
            );
            jdbcTemplate.update(
                "insert into ai_runtime_skill(title, allowed_tools_json, version, content_hash, status) values (?, ?, ?, ?, ?)",
                "Project Memory",
                "[\"memory.project_context\"]",
                "1.0.0",
                "b".repeat(64),
                "ACTIVE"
            );

            ClassPathResource script = new ClassPathResource("sql/phase27-agent-skill-contract-h2.sql");
            ScriptUtils.executeSqlScript(connection, script);
            ScriptUtils.executeSqlScript(connection, script);

            Map<String, Object> candidate = jdbcTemplate.queryForMap(
                "select description, requested_capabilities_json, skill_metadata_json from ai_skill_candidate where id = 1"
            );
            Map<String, Object> runtime = jdbcTemplate.queryForMap(
                "select description, requested_capabilities_json, skill_metadata_json from ai_runtime_skill where id = 1"
            );

            assertThat(candidate.get("description")).isEqualTo("Market Scan");
            assertThat(String.valueOf(candidate.get("requested_capabilities_json"))).contains("market.read");
            assertThat(String.valueOf(candidate.get("skill_metadata_json"))).contains("legacyFormat");
            assertThat(runtime.get("description")).isEqualTo("Project Memory");
            assertThat(String.valueOf(runtime.get("requested_capabilities_json"))).contains("memory.project.read");
            assertThat(String.valueOf(runtime.get("skill_metadata_json"))).contains("legacyFormat");
        }
    }
}
