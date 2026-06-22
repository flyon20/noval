package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentTraceService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeAgentTraceServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldPersistTraceAndAllowAdminInspectionOnly() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.of(
            "taskGraph", Map.of("tasks", List.of(Map.of("type", "market_scan"))),
            "toolRuns", List.of(Map.of("name", "rank.lookup")),
            "evidencePackSummary", Map.of("factCount", 1),
            "perspectiveResults", List.of(Map.of("perspective", "market")),
            "trace", Map.of("traceId", "trace-001")
        ));

        service.persistFromChat(7L, 11L, "conv-1", "question", response);

        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        assertThatThrownBy(service::listForAdmin).isInstanceOf(BusinessException.class);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        List<KnowledgeAgentTraceVO> traces = service.listForAdmin();

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getTraceId()).isEqualTo("trace-001");
        KnowledgeAgentTraceVO detail = service.detailForAdmin(traces.get(0).getId());
        assertThat(detail.getTaskGraph()).contains("market_scan");
        assertThat(detail.getToolRuns()).contains("rank.lookup");
    }

    @Test
    void shouldExposeExpandedTraceSectionsFromResultJsonWithoutSchemaChanges() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.of(
            "taskGraph", Map.of("tasks", List.of(Map.of("type", "market_scan"))),
            "toolRuns", List.of(Map.of(
                "name", "rank.lookup",
                "status", "succeeded",
                "output", Map.of("sources", List.of(Map.of("snapshotTime", "2026-06-22T00:00:00")))
            )),
            "evidencePackSummary", Map.of("factCount", 1),
            "intentDecision", Map.of("primaryIntent", "market_scan"),
            "contextUsed", Map.of("projectMemoryKeys", List.of("premise"), "threadSummary", true),
            "memoryUsed", Map.of("project", true, "thread", true),
            "sourcePolicy", Map.of("freshness", "latest", "snapshotTime", "2026-06-22T00:00:00"),
            "supervisorDecision", Map.of("status", "answerable"),
            "memoryCandidates", List.of(Map.of("scope", "project", "content", "likes fast starts")),
            "trace", Map.of("traceId", "trace-expanded")
        ));

        service.persistFromChat(7L, 11L, "conv-expanded", "question", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        KnowledgeAgentTraceVO detail = service.detailForAdmin(service.listForAdmin().get(0).getId());

        assertThat(detail.getIntentDecision()).contains("market_scan");
        assertThat(detail.getContextUsed()).contains("projectMemoryKeys");
        assertThat(detail.getMemoryUsed()).contains("project");
        assertThat(detail.getSourcePolicy()).contains("latest");
        assertThat(detail.getSupervisorDecision()).contains("answerable");
        assertThat(detail.getMemoryCandidates()).contains("likes fast starts");
        assertThat(detail.getSnapshotTime()).isEqualTo("2026-06-22T00:00:00");
    }

    static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:trace-test-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_agent_trace (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(80) not null," +
            "user_id bigint not null," +
            "project_id bigint," +
            "conversation_id varchar(80)," +
            "question clob," +
            "status varchar(40)," +
            "task_graph_json clob," +
            "tool_runs_json clob," +
            "evidence_pack_json clob," +
            "perspective_results_json clob," +
            "result_json clob," +
            "created_at timestamp default current_timestamp)");
        return jdbcTemplate;
    }
}
