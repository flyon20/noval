package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.KnowledgeMemoryCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMemoryCandidateServiceTest {

    @Test
    void shouldPersistProjectAndUserMemoryCandidates() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryCandidateService service = new KnowledgeMemoryCandidateService(jdbcTemplate);

        int saved = service.persistCandidates(
            900L,
            7L,
            List.of(
                Map.of("scope", "project", "type", "constraint", "content", "不后宫；前三章快节奏", "confidence", 0.82, "sourceTraceId", "trace-1"),
                Map.of("scope", "user", "type", "preference", "content", "番茄男频都市脑洞", "confidence", 0.78),
                Map.of("scope", "discard", "type", "preference", "content", "ignore me", "confidence", 0.9)
            ),
            "trace-fallback"
        );

        assertThat(saved).isEqualTo(2);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select project_id, user_id, candidate_type, content, status, source_trace_id from ai_memory_candidate order by id"
        );
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
            .containsEntry("PROJECT_ID", 900L)
            .containsEntry("USER_ID", 7L)
            .containsEntry("CANDIDATE_TYPE", "project.constraint")
            .containsEntry("STATUS", "candidate")
            .containsEntry("SOURCE_TRACE_ID", "trace-1");
        assertThat(rows.get(1))
            .containsEntry("CANDIDATE_TYPE", "user.preference")
            .containsEntry("STATUS", "candidate")
            .containsEntry("SOURCE_TRACE_ID", "trace-fallback");
    }

    @Test
    void shouldPersistUserAndThreadCandidatesWithoutProjectButSkipProjectCandidates() {
        JdbcTemplate jdbcTemplate = KnowledgeMemoryServiceTest.jdbcTemplate();
        KnowledgeMemoryCandidateService service = new KnowledgeMemoryCandidateService(jdbcTemplate);

        int saved = service.persistCandidates(
            null,
            7L,
            List.of(
                Map.of("scope", "user", "type", "preference", "content", "user fact", "candidateKey", "user-1"),
                Map.of("scope", "thread", "type", "decision", "content", "thread fact", "candidateKey", "thread-1"),
                Map.of("scope", "project", "type", "fact", "content", "cannot save", "candidateKey", "project-1")
            ),
            "trace-null-project"
        );

        assertThat(saved).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
            "select scope, project_id, candidate_key from ai_memory_candidate order by id"
        )).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("PROJECT_ID")).isNull();
            assertThat(row.get("CANDIDATE_KEY")).isNotNull();
        });
    }

    private JdbcTemplate jdbcTemplate() {
        JdbcTemplate jdbcTemplate = KnowledgeProjectServiceTest.jdbcTemplate();
        jdbcTemplate.execute("create table ai_memory_candidate (" +
            "id bigint auto_increment primary key," +
            "project_id bigint not null," +
            "user_id bigint not null," +
            "candidate_type varchar(40) not null," +
            "content clob not null," +
            "status varchar(30) not null default 'PENDING'," +
            "source_trace_id varchar(80)," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp)");
        return jdbcTemplate;
    }
}
