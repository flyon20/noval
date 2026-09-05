package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(detail.getMemoryCandidates()).contains("project").doesNotContain("likes fast starts");
        assertThat(detail.getResultJson()).doesNotContain("likes fast starts");
        assertThat(detail.getSnapshotTime()).isEqualTo("2026-06-22T00:00:00");
    }

    @Test
    void shouldExposeTruthfulSkillMediationWithoutSkillBodiesAndPreferBomForGoldenDraft() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of(Map.of("type", "outline_building")))),
            Map.entry("toolRuns", List.of()),
            Map.entry("selectedSkills", List.of("legacy-should-not-win")),
            Map.entry("skillMediation", Map.ofEntries(
                Map.entry("candidateCount", 2),
                Map.entry("eligibleCount", 2),
                Map.entry("activatedCount", 1),
                Map.entry("rejectedCount", 1),
                Map.entry("eligibleSkillIds", List.of("webnovel-outline-building", "webnovel-market-scan")),
                Map.entry("activatedSkillIds", List.of("webnovel-outline-building")),
                Map.entry("records", List.of(
                    Map.ofEntries(
                        Map.entry("skillId", "webnovel-outline-building"),
                        Map.entry("version", "1.2.0"),
                        Map.entry("state", "ACTIVATED"),
                        Map.entry("candidateReasons", List.of("intent:outline_building")),
                        Map.entry("rejectionReasons", List.of()),
                        Map.entry("bodyInjected", true),
                        Map.entry("instructions", "PRIVATE_ACTIVATED_SKILL_BODY")
                    ),
                    Map.ofEntries(
                        Map.entry("skillId", "webnovel-market-scan"),
                        Map.entry("version", "2.0.0"),
                        Map.entry("state", "REJECTED"),
                        Map.entry("candidateReasons", List.of("task:market_scan")),
                        Map.entry("rejectionReasons", List.of("budget")),
                        Map.entry("bodyInjected", false),
                        Map.entry("instructions", Map.of("summary", "PRIVATE_REJECTED_SKILL_BODY"))
                    )
                ))
            )),
            Map.entry("skillBom", Map.of("skills", List.of(Map.ofEntries(
                Map.entry("skillId", "webnovel-outline-building"),
                Map.entry("version", "1.2.0"),
                Map.entry("contentHash", "a".repeat(64)),
                Map.entry("status", "ACTIVE"),
                Map.entry("source", "backend")
            )))),
            Map.entry("trace", Map.of("traceId", "trace-skill-mediation"))
        ));

        service.persistFromChat(7L, 11L, "conv-skill-mediation", "build an outline", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        KnowledgeAgentTraceVO detail = service.detailForAdmin(service.listForAdmin().get(0).getId());
        GoldenCandidateDraftVO draft = service.createGoldenCandidateDraft(detail.getId());

        assertThat(detail.getSkillMediation())
            .contains("candidateCount", "eligibleCount", "activatedCount", "rejectedCount")
            .contains("webnovel-outline-building", "webnovel-market-scan", "ACTIVATED", "REJECTED", "budget", "bodyInjected")
            .doesNotContain("PRIVATE_ACTIVATED_SKILL_BODY", "PRIVATE_REJECTED_SKILL_BODY", "instructions");
        assertThat(detail.getSkillBom()).contains("webnovel-outline-building", "1.2.0").doesNotContain("legacy-should-not-win");
        assertThat(detail.getResultJson()).doesNotContain("PRIVATE_ACTIVATED_SKILL_BODY", "PRIVATE_REJECTED_SKILL_BODY", "instructions");
        assertThat(draft.getSelectedSkills()).containsExactly("webnovel-outline-building");
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
        assertThat(detail.getMemoryCandidates()).doesNotContain("likes fast starts");
        assertThat(detail.getResultJson()).doesNotContain("likes fast starts");
        assertThat(detail.getSelectedSnapshotGroup()).contains("2026-06-23T00:00:00");
        assertThat(detail.getRejectedSnapshotGroups()).contains("2026-06-22T00:00:00");
        assertThat(detail.getMemoryUsed()).contains("semantic");
        assertThat(detail.getSpecialistAgentResults()).contains("OutlineAgent");
        assertThat(detail.getSelectedExperts()).contains("market_scan");
        assertThat(detail.getExpertRouter()).contains("intent:mixed_creation_research");
        assertThat(detail.getSupervisorDecision()).contains("answerable");
        assertThat(detail.getFinalAnswerBoundary()).contains("bounded");
    }

    @Test
    void shouldKeepMemoryBodiesOutOfTopLevelAndNestedTracePayloads() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of())),
            Map.entry("toolRuns", List.of()),
            Map.entry("memoryCandidatePayloads", List.of(Map.of(
                "content", "TOP_LEVEL_MEMORY_BODY",
                "body", "TOP_LEVEL_BODY",
                "novelText", "TOP_LEVEL_NOVEL_TEXT"
            ))),
            Map.entry("privateMemoryPayload", Map.of("source", "UNKNOWN_MEMORY_BODY")),
            Map.entry("memoryContext", Map.of(
                "items", List.of(Map.of(
                    "memoryId", 12,
                    "Content", "NESTED_CASE_VARIANT_BODY",
                    "body", "NESTED_BODY",
                    "novelText", "NESTED_NOVEL_TEXT",
                    "reason", "REASON_MEMORY_BODY",
                    "sourceTraceId", "trace-memory-source"
                ), "LIST_MEMORY_BODY")
            )),
            Map.entry("memoryDiagnostics", Map.of(
                "candidatePersistence", Map.of(
                    "saved", 1,
                    "payload", Map.of("content", "DIAGNOSTIC_MEMORY_BODY"),
                    "backendFallback", Map.of(
                        "status", "failed",
                        "errorType", "IllegalStateException",
                        "message", "PRIVATE_DATABASE_FAILURE_DETAIL"
                    )
                )
            )),
            Map.entry("contextUsed", Map.of(
                "memoryContext", Map.of("items", List.of(Map.of("body", "CONTEXT_USED_MEMORY_BODY")))
            )),
            Map.entry("trace", Map.of(
                "traceId", "trace-memory-redaction",
                "memoryContext", "SCALAR_MEMORY_BODY",
                "memoryUsed", Map.of(
                    "items", List.of(Map.of("novelText", "TRACE_MEMORY_BODY", "factKey", "safe.fact.key"))
                ),
                "memoryCandidatePayloads", List.of(Map.of("content", "TRACE_CANDIDATE_BODY"))
            ))
        ));

        service.persistFromChat(7L, 11L, "conv-memory-redaction", "question", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        String resultJson = service.detailForAdmin(service.listForAdmin().get(0).getId()).getResultJson();
        assertThat(resultJson)
            .contains("trace-memory-source", "safe.fact.key", "IllegalStateException")
            .doesNotContain(
                "TOP_LEVEL_MEMORY_BODY",
                "TOP_LEVEL_BODY",
                "TOP_LEVEL_NOVEL_TEXT",
                "UNKNOWN_MEMORY_BODY",
                "NESTED_CASE_VARIANT_BODY",
                "NESTED_BODY",
                "NESTED_NOVEL_TEXT",
                "REASON_MEMORY_BODY",
                "LIST_MEMORY_BODY",
                "DIAGNOSTIC_MEMORY_BODY",
                "PRIVATE_DATABASE_FAILURE_DETAIL",
                "CONTEXT_USED_MEMORY_BODY",
                "SCALAR_MEMORY_BODY",
                "TRACE_MEMORY_BODY",
                "TRACE_CANDIDATE_BODY"
            );
    }

    @Test
    void shouldPersistOnlySafeTraceProjectionForToolBodiesUploadsAndCredentials() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setAnswer("PRIVATE_RESPONSE_BODY_" + "a".repeat(260));
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of(Map.of("type", "project_retrieve")))),
            Map.entry("toolRuns", List.of(Map.of(
                "name", "project.retrieve",
                "status", "succeeded",
                "durationMs", 18,
                "input", Map.of("query", "PRIVATE_CHAPTER_BODY_" + "b".repeat(260), "apiKey", "sk-private-trace-secret"),
                "output", Map.of(
                    "evidence", List.of(Map.of(
                        "chapterNo", 12,
                        "generationId", 77,
                        "chunkText", "PRIVATE_CHAPTER_BODY_" + "b".repeat(260),
                        "contentHash", "a".repeat(64)
                    )),
                    "uploadedFile", Map.of("name", "private.md", "content", "PRIVATE_UPLOAD_BODY_" + "c".repeat(260))
                )
            ))),
            Map.entry("projectKnowledge", Map.of("retrievedEvidence", List.of(Map.of(
                "chapterNo", 12,
                "generationId", 77,
                "chunkText", "PRIVATE_CHAPTER_BODY_" + "b".repeat(260),
                "contentHash", "a".repeat(64)
            )))),
            Map.entry("retrievalDiagnostics", Map.of(
                "partialFlush", true,
                "vectorLatencyMs", 18,
                "generationId", 77,
                "degradationReasons", List.of("vector_unavailable")
            )),
            Map.entry("trace", Map.of("traceId", "trace-safe-projection", "accessToken", "sk-private-trace-secret"))
        ));

        service.persistFromChat(7L, 11L, "conv-safe-projection", "normal question", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        KnowledgeAgentTraceVO detail = service.detailForAdmin(service.listForAdmin().get(0).getId());

        assertThat(detail.getResultJson())
            .contains("chapterNo", "generationId", "contentHash", "inputHash", "outputHash", "vectorLatencyMs")
            .doesNotContain(
                "PRIVATE_RESPONSE_BODY_",
                "PRIVATE_CHAPTER_BODY_",
                "PRIVATE_UPLOAD_BODY_",
                "sk-private-trace-secret",
                "private.md"
            );
    }

    @Test
    void shouldPersistSanitizedProviderLedgerAndConversationContinuityWithoutBodies() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("answered");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of(Map.of("type", "outline_building")))),
            Map.entry("providerCalls", List.of(Map.ofEntries(
                Map.entry("node", "compose_answer"),
                Map.entry("model", "deepseek-v4-pro"),
                Map.entry("requestedModel", "C:\\private\\prompt.txt"),
                Map.entry("status", "succeeded"),
                Map.entry("durationMs", 149806),
                Map.entry("tokenUsed", 700),
                Map.entry("promptCacheHitTokens", 120),
                Map.entry("wireApi", "responses"),
                Map.entry("providerTransportFallback", Map.of(
                    "from", "responses",
                    "to", "chat_completions",
                    "reason", "model_not_responses_capable",
                    "model", "deepseek-v4-pro"
                )),
                Map.entry("usage", Map.of(
                    "inputTokens", 4096,
                    "outputTokens", 700,
                    "reasoningTokens", 320,
                    "cachedInputTokens", 2048,
                    "totalTokens", 4796
                )),
                Map.entry("requestSummary", Map.of(
                    "messageCount", 3,
                    "roleCounts", Map.of("system", 1, "user", 1, "assistant", 1),
                    "messageChars", 30981,
                    "toolSchemaCount", 0,
                    "reasoningRequested", true,
                    "bodyRedacted", true
                )),
                Map.entry("responseSummary", Map.of(
                    "outputChars", 512,
                    "toolCallCount", 0,
                    "emptyResponse", false,
                    "bodyRedacted", true
                )),
                Map.entry("cacheContinuity", Map.of(
                    "provider", "openai_compatible",
                    "wireApi", "responses",
                    "model", "deepseek-v4-pro",
                    "requestFamily", "answer",
                    "cacheIdentityMode", "provider_user",
                    "bodyRedacted", true
                )),
                Map.entry("prompt", "PRIVATE_PROVIDER_PROMPT"),
                Map.entry("responseBody", "PRIVATE_PROVIDER_RESPONSE")
            ))),
            Map.entry("contextBudget", Map.of(
                "conversationContinuity", Map.of(
                    "historyTotalCount", 6,
                    "historyIncludedCount", 4,
                    "historyIncludedChars", 11215,
                    "historyTruncated", true,
                    "contextSummaryChars", 18114,
                    "contextSummaryTruncated", false
                )
            )),
            Map.entry("trace", Map.of("traceId", "trace-provider-ledger"))
        ));

        service.persistFromChat(7L, 11L, "conv-provider-ledger", "continue the outline", response);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        String resultJson = service.detailForAdmin(service.listForAdmin().get(0).getId()).getResultJson();

        assertThat(resultJson)
            .contains(
                "providerCalls", "compose_answer", "deepseek-v4-pro", "requestSummary", "responseSummary",
                "messageCount", "roleCounts", "messageChars", "toolSchemaCount", "reasoningRequested",
                "bodyRedacted", "outputChars", "toolCallCount", "emptyResponse", "tokenUsed",
                "promptCacheHitTokens", "wireApi", "responses", "providerTransportFallback", "chat_completions",
                "cacheContinuity", "cacheIdentityMode", "provider_user", "requestFamily", "answer",
                "inputTokens", "outputTokens", "reasoningTokens", "cachedInputTokens", "totalTokens",
                "conversationContinuity", "historyTotalCount", "historyIncludedCount",
                "historyIncludedChars", "historyTruncated", "contextSummaryChars", "contextSummaryTruncated"
            )
            .doesNotContain("PRIVATE_PROVIDER_PROMPT", "PRIVATE_PROVIDER_RESPONSE", "prompt.txt", "C:\\private");
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
        assertThat(draft.getAnswer()).isNull();
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
        assertThat(page.getItems().get(0).getHealthSummary()).isEmpty();
    }

    @Test
    void shouldExposeCompactHealthSummaryWithoutDetailPayload() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeAgentTraceService service = new KnowledgeAgentTraceService(jdbcTemplate);
        KnowledgeChatResponseVO response = new KnowledgeChatResponseVO();
        response.setStatus("degraded_model_fallback");
        response.setResultJson(Map.ofEntries(
            Map.entry("taskGraph", Map.of("tasks", List.of(Map.of("type", "outline_building")))),
            Map.entry("toolRuns", List.of(Map.of("name", "rank.lookup", "status", "succeeded"))),
            Map.entry("answer", "DETAIL_ONLY_MARKER_" + "x".repeat(250_000)),
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
        var summary = page.getItems().get(0);

        assertThat(summary.getHealthSummary()).containsEntry("model", "fallback_used");
        assertThat(summary.getHealthSummary()).containsEntry("tools", "succeeded");
        assertThat(summary.getHealthSummary()).containsEntry("memory", "skipped");
        assertThat(summary.getHealthSummary()).containsEntry("experts", "skipped");
        String serializedPage = new ObjectMapper().findAndRegisterModules().writeValueAsString(page);
        assertThat(serializedPage).doesNotContain("resultJson", "DETAIL_ONLY_MARKER_");
        assertThat(serializedPage.length()).isLessThan(5_000);
        assertThat(service.detailForAdmin(summary.getId()).getResultJson()).doesNotContain("DETAIL_ONLY_MARKER_");
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
