package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentTraceService;
import com.novelanalyzer.modules.knowledge.vo.GoldenCandidateDraftVO;
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
    void shouldPersistWorkerSnakeCaseTraceIdAndExecutedNodes() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.of(
            "taskGraph", Map.of("tasks", List.of(Map.of("type", "outline_building"))),
            "trace", Map.of(
                "trace_id", "trace-snake-admin",
                "executedRuntimeNodes", List.of("assemble_context", "compose_answer"),
                "nodes", List.of(Map.of("name", "assemble_context", "status", "completed"))
            )
        ));

        service.persistFromChat(7L, null, "conv-snake-admin", "question", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        KnowledgeAgentTraceVO detail = service.detailForAdmin(service.listForAdmin().get(0).getId());

        assertThat(detail.getTraceId()).isEqualTo("trace-snake-admin");
        assertThat(detail.getResultJson()).contains("executedRuntimeNodes");
    }

    @Test
    void shouldExposeExpandedTraceSectionsFromResultJsonWithoutSchemaChanges() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of(Map.of("type", "market_scan")))),
            Map.entry("toolRuns", List.of(Map.of(
                "name", "rank.lookup",
                "status", "succeeded",
                "output", Map.of("sources", List.of(Map.of("snapshotTime", "2026-06-22T00:00:00")))
            ))),
            Map.entry("evidencePackSummary", Map.of("factCount", 1)),
            Map.entry("intentDecision", Map.of("primaryIntent", "market_scan")),
            Map.entry("contextUsed", Map.of("projectMemoryKeys", List.of("premise"), "threadSummary", true)),
            Map.entry("memoryUsed", Map.of("project", true, "thread", true)),
            Map.entry("memoryDiagnostics", Map.of("layers", Map.of("projectMemory", Map.of("status", "loaded")))),
            Map.entry("retrievalDiagnostics", Map.of("selectedCount", 2, "reasonTags", List.of("trend_quota_selection"))),
            Map.entry("sourcePolicy", Map.of("freshness", "latest", "snapshotTime", "2026-06-22T00:00:00")),
            Map.entry("supervisorDecision", Map.of("status", "answerable")),
            Map.entry("memoryCandidates", List.of(Map.of("scope", "project", "content", "likes fast starts"))),
            Map.entry("trace", Map.of("traceId", "trace-expanded"))
        ));

        service.persistFromChat(7L, 11L, "conv-expanded", "question", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        KnowledgeAgentTraceVO detail = service.detailForAdmin(service.listForAdmin().get(0).getId());

        assertThat(detail.getIntentDecision()).contains("market_scan");
        assertThat(detail.getContextUsed()).contains("projectMemoryKeys");
        assertThat(detail.getMemoryUsed()).contains("project");
        assertThat(detail.getMemoryDiagnostics()).contains("projectMemory");
        assertThat(detail.getRetrievalDiagnostics()).contains("trend_quota_selection");
        assertThat(detail.getSourcePolicy()).contains("latest");
        assertThat(detail.getSupervisorDecision()).contains("answerable");
        assertThat(detail.getMemoryCandidates()).contains("likes fast starts");
        assertThat(detail.getSnapshotTime()).isEqualTo("2026-06-22T00:00:00");
    }

    @Test
    void shouldExposePhaseNineTraceSectionsFromNestedResultJson() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of(
                "nodes", List.of(Map.of("name", "intent"), Map.of("name", "mcp")),
                "edges", List.of(Map.of("from", "intent", "to", "planner"))
            )),
            Map.entry("intentDecision", Map.of(
                "primaryIntent", "mixed_creation_research",
                "confidence", 0.93,
                "taskGraph", Map.of("mode", "handoff")
            )),
            Map.entry("mcpToolCalls", List.of(Map.of(
                "name", "rank.lookup",
                "status", "succeeded",
                "permissionDecision", Map.of("allowed", true)
            ))),
            Map.entry("toolPermissionDecisions", List.of(Map.of(
                "tool", "rank.lookup",
                "allowed", true,
                "reason", "within route"
            ))),
            Map.entry("evidenceContract", Map.of(
                "status", "verified_latest",
                "selectedSnapshotGroup", Map.of("snapshotTime", "2026-06-23T00:00:00"),
                "rejectedSnapshotGroups", List.of(Map.of("snapshotTime", "2026-06-22T00:00:00"))
            )),
            Map.entry("selectedSnapshotGroup", Map.of("snapshotTime", "2026-06-23T00:00:00", "source", "rank.research_pack")),
            Map.entry("rejectedSnapshotGroups", List.of(Map.of("snapshotTime", "2026-06-22T00:00:00", "source", "rank.lookup"))),
            Map.entry("memoryUsed", Map.of("project", true, "semantic", true)),
            Map.entry("memoryCandidates", List.of(Map.of("scope", "project", "content", "likes fast starts"))),
            Map.entry("specialistAgentResults", List.of(Map.of(
                "agentName", "OutlineAgent",
                "status", "completed",
                "summary", "outline ready"
            ))),
            Map.entry("selectedExperts", List.of(Map.of(
                "name", "market_scan",
                "reason", "intent:mixed_creation_research"
            ))),
            Map.entry("expertRouter", Map.of(
                "reasoningMode", "fast",
                "maxParallel", 3,
                "selectedExperts", List.of(Map.of(
                    "name", "market_scan",
                    "reason", "intent:mixed_creation_research"
                ))
            )),
            Map.entry("supervisorDecision", Map.of("status", "answerable")),
            Map.entry("finalAnswerBoundary", Map.of("status", "bounded", "notes", "no unsupported facts")),
            Map.entry("trace", Map.of("traceId", "trace-phase-nine"))
        ));

        service.persistFromChat(7L, 11L, "conv-phase-nine", "question", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        KnowledgeAgentTraceVO detail = service.detailForAdmin(service.listForAdmin().get(0).getId());

        assertThat(detail.getIntentDecision()).contains("mixed_creation_research");
        assertThat(detail.getTaskGraph()).contains("intent");
        assertThat(detail.getMcpToolCalls()).contains("rank.lookup");
        assertThat(detail.getToolPermissionDecisions()).contains("within route");
        assertThat(detail.getEvidenceContract()).contains("verified_latest");
        assertThat(detail.getSelectedSnapshotGroup()).contains("2026-06-23T00:00:00");
        assertThat(detail.getRejectedSnapshotGroups()).contains("2026-06-22T00:00:00");
        assertThat(detail.getMemoryUsed()).contains("semantic");
        assertThat(detail.getMemoryCandidates()).contains("likes fast starts");
        assertThat(detail.getSpecialistAgentResults()).contains("OutlineAgent");
        assertThat(detail.getSelectedExperts()).contains("market_scan");
        assertThat(detail.getExpertRouter()).contains("intent:mixed_creation_research");
        assertThat(detail.getSupervisorDecision()).contains("answerable");
        assertThat(detail.getFinalAnswerBoundary()).contains("bounded");
    }

    @Test
    void shouldCreateDraftGoldenCandidateFromTraceWithoutPublishing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("Use fresh rank evidence and keep the opening hook concrete.");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("nodes", List.of(Map.of("name", "market_scan")))),
            Map.entry("toolRuns", List.of(Map.of("name", "rank.lookup", "status", "succeeded"))),
            Map.entry("selectedSkills", List.of("webnovel-market-scan", "webnovel-opening-hook")),
            Map.entry("evidenceContract", Map.of("status", "verified_latest", "requiredSources", List.of("rank.lookup"))),
            Map.entry("supervisorDecision", Map.of("status", "answerable", "summary", "fresh evidence verified")),
            Map.entry("finalAnswerBoundary", Map.of("status", "bounded", "summary", "no unsupported facts")),
            Map.entry("trace", Map.of("traceId", "trace-golden"))
        ));

        service.persistFromChat(7L, 11L, "conv-golden", "How should this book open?", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        GoldenCandidateDraftVO draft = service.createGoldenCandidateDraft(service.listForAdmin().get(0).getId());

        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getTraceId()).isEqualTo("trace-golden");
        assertThat(draft.getQuestion()).isEqualTo("How should this book open?");
        assertThat(draft.getAnswer()).contains("fresh rank evidence");
        assertThat(draft.getTraceSummary()).contains("fresh evidence verified");
        assertThat(draft.getSelectedSkills()).contains("webnovel-market-scan", "webnovel-opening-hook");
        assertThat(draft.getSelectedTools()).contains("rank.lookup");
        assertThat(draft.getEvidenceContract()).contains("verified_latest");
    }

    @Test
    void shouldPageAndFilterAgentTracesForAdmin() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        for (int index = 1; index <= 12; index++) {
            KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
            response.setStatus(index % 2 == 0 ? "answered" : "insufficient_evidence");
            response.setResultJson(Map.of(
                "taskGraph", Map.of("tasks", List.of(Map.of("type", "market_scan"))),
                "toolRuns", List.of(Map.of("name", "knowledge.vector_search", "status", "succeeded")),
                "evidencePackSummary", Map.of("factCount", index),
                "trace", Map.of("traceId", "trace-" + index)
            ));
            service.persistFromChat(7L, 11L, "conv-" + index, "都市脑洞问题 " + index, response);
        }

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        var page = service.listForAdmin(2, 5, "answered", "都市脑洞");

        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(5);
        assertThat(page.getTotal()).isEqualTo(6);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getStatus()).isEqualTo("answered");
        assertThat(page.getItems().get(0).getTaskGraph()).isNull();
        assertThat(page.getItems().get(0).getToolRuns()).isNull();
    }

    @Test
    void shouldExposeResultJsonOnPagedTraceSummaryForHealthBlocks() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("degraded_model_fallback");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of(Map.of("type", "outline_building")))),
            Map.entry("toolRuns", List.of(Map.of("name", "rank.lookup", "status", "succeeded"))),
            Map.entry("fallbackUsed", true),
            Map.entry("degraded", true),
            Map.entry("providerCalls", List.of(Map.of(
                "node", "compose_answer",
                "status", "failed",
                "errorType", "TimeoutException"
            ))),
            Map.entry("trace", Map.of(
                "traceId", "trace-health-summary",
                "health", Map.of(
                    "model", "fallback_used",
                    "tools", "succeeded",
                    "memory", "skipped",
                    "experts", "skipped"
                )
            ))
        ));

        service.persistFromChat(7L, null, "conv-health", "需要一份大纲", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        var page = service.listForAdmin(1, 20, null, null);
        KnowledgeAgentTraceVO summary = page.getItems().get(0);

        assertThat(summary.getResultJson()).contains("trace-health-summary");
        assertThat(summary.getResultJson()).contains("fallbackUsed");
        assertThat(summary.getResultJson()).contains("providerCalls");
        assertThat(summary.getResultJson()).contains("fallback_used");
        assertThat(summary.getTaskGraph()).isNull();
        assertThat(summary.getToolRuns()).isNull();
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
