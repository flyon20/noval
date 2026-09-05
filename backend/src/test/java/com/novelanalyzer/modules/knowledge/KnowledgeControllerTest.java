package com.novelanalyzer.modules.knowledge;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.AgentEvalRunRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectMemoryOverviewService;
import com.novelanalyzer.modules.knowledge.vo.AgentEvalRunVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectMemoryOverviewVO;
import com.novelanalyzer.modules.knowledge.vo.RuntimeSkillVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgecontrollerdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:sql/phase12-webnovel-agent-project-trace-h2.sql,classpath:sql/phase13-agent-memory-mcp-h2.sql,classpath:sql/phase16-project-knowledge-rag-h2.sql,classpath:sql/phase23-skill-memory-lifecycle-h2.sql,classpath:sql/phase24-project-ingest-generation-h2.sql,classpath:sql/phase25-project-hybrid-retrieval-story-graph-h2.sql,classpath:sql/phase26-project-retrieval-eval-observability-h2.sql,classpath:sql/phase27-agent-skill-contract-h2.sql",
        "app.security.rate-limit-per-minute=100",
        "app.security.protected-path-prefixes[0]=/api/auth",
        "app.security.protected-path-prefixes[1]=/api/secure",
        "app.security.protected-path-prefixes[2]=/api/system",
        "app.security.protected-path-prefixes[3]=/api/crawler",
        "app.security.protected-path-prefixes[4]=/api/analysis",
        "app.security.protected-path-prefixes[5]=/api/knowledge",
        "app.auth.jwt-secret=test-jwt-secret-with-enough-length-1234567890",
        "app.crawler.internal-api-key=crawler-internal-api-key-with-enough-length-1234567890",
        "app.ai.langgraph-worker.internal-api-key=langgraph-internal-key-with-enough-length-1234567890",
        "app.knowledge.index.queue-enabled=false",
        "app.knowledge.index.rank-incremental-enabled=false",
        "app.knowledge.eval.queue-enabled=false",
        "app.knowledge.embedding.api-key=test-embedding-key"
    }
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase7-knowledge-schema-h2.sql",
        "classpath:sql/phase11-rag-eval-observability-h2.sql",
        "classpath:sql/phase12-webnovel-agent-project-trace-h2.sql",
        "classpath:sql/phase16-project-knowledge-rag-h2.sql",
        "classpath:sql/phase18-agent-harness-conversation-rag-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class KnowledgeControllerTest {

    private static final String ADMIN_PHONE = "15599316908";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private QdrantClient qdrantClient;

    @MockBean
    private KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;

    @MockBean
    private KnowledgeProjectMemoryOverviewService knowledgeProjectMemoryOverviewService;

    @MockBean
    private LangGraphWorkerClient langGraphWorkerClient;

    @BeforeEach
    void prepareState() {
        jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = 1", ADMIN_PHONE);
        jdbcTemplate.update("""
            merge into system_config(config_key, config_value, config_type, description, is_editable, deleted)
            key(config_key) values('ai.conversation.read-rollout-percent', '100', 'ai',
                'conversation read rollout for controller tests', 1, 0)
            """);
        jdbcTemplate.update("""
            merge into system_config(config_key, config_value, config_type, description, is_editable, deleted)
            key(config_key) values('ai.conversation.legacy-fallback-enabled', 'true', 'ai',
                'conversation legacy fallback for controller tests', 1, 0)
            """);
        RedisConnection connection;
        try {
            connection = stringRedisTemplate.getConnectionFactory().getConnection();
        } catch (RedisConnectionFailureException ex) {
            return;
        }
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldSearchKnowledgeSourcesForAuthenticatedUser() throws Exception {
        long bookId = insertBookAndKnowledge();
        when(embeddingClient.embed("主角目标是什么")).thenReturn(List.of(0.1, 0.2, 0.3));
        when(qdrantClient.search(any(), eq(Map.of("bookId", bookId, "platform", "fanqie")), eq(3)))
            .thenReturn(List.of(new QdrantClient.SearchResult("chunk-point-controller", 0.88, Map.of("chunkId", 1L))));

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":1,"query":"主角目标是什么","bookId":%d,"platform":"fanqie","limit":3}
                    """.formatted(bookId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].bookId").value(bookId))
            .andExpect(jsonPath("$.data[0].bookName").value("控制器测试书"))
            .andExpect(jsonPath("$.data[0].preview").value(org.hamcrest.Matchers.containsString("主角目标明确")));
    }

    @Test
    void shouldRejectBlankKnowledgeSearchQuery() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"   ","limit":3}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldExposeOnlySanitizedSkillShortcutsToAuthenticatedUsers() throws Exception {
        RuntimeSkillVO runtimeSkill = new RuntimeSkillVO();
        runtimeSkill.setSkillId("webnovel-market-scan");
        runtimeSkill.setTitle("榜单分析");
        runtimeSkill.setDescription("分析当前网文榜单与热度结构");
        runtimeSkill.setAppliesTo(List.of("market_scan", "mixed_creation_research"));
        runtimeSkill.setContent("private governed skill body");
        runtimeSkill.setGuardrails("private guardrails");
        runtimeSkill.setRequestedCapabilities(List.of("market.read"));
        runtimeSkill.setSkillMetadata(Map.of(
            "shortcutEnabled", true,
            "shortcutLabel", "榜单分析",
            "shortcutOrder", 10
        ));
        when(langGraphWorkerClient.listRuntimeSkills()).thenReturn(List.of(runtimeSkill));

        String token = loginAndGetToken();
        mockMvc.perform(get("/api/knowledge/skills/shortcuts")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].skillId").value("webnovel-market-scan"))
            .andExpect(jsonPath("$.data[0].title").value("榜单分析"))
            .andExpect(jsonPath("$.data[0].description").value("分析当前网文榜单与热度结构"))
            .andExpect(jsonPath("$.data[0].appliesTo[0]").value("market_scan"))
            .andExpect(jsonPath("$.data[0].content").doesNotExist())
            .andExpect(jsonPath("$.data[0].guardrails").doesNotExist())
            .andExpect(jsonPath("$.data[0].requestedCapabilities").doesNotExist())
            .andExpect(jsonPath("$.data[0].version").doesNotExist())
            .andExpect(jsonPath("$.data[0].status").doesNotExist());
    }

    @Test
    void shouldSubmitKnowledgeIndexJobForAuthenticatedUser() throws Exception {
        AsyncJobSubmitResponse jobResponse = new AsyncJobSubmitResponse();
        jobResponse.setJobId(66L);
        jobResponse.setJobType("KNOWLEDGE_INDEX_BOOK");
        jobResponse.setJobKey("book:1");
        jobResponse.setStatus("RUNNING");
        jobResponse.setReused(false);
        when(knowledgeIndexJobExecutor.submitAndExecute(1L, 1L)).thenReturn(jobResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/index")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookId\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.jobId").value(66))
            .andExpect(jsonPath("$.data.jobType").value("KNOWLEDGE_INDEX_BOOK"))
            .andExpect(jsonPath("$.data.jobKey").value("book:1"))
            .andExpect(jsonPath("$.data.acquired").doesNotExist())
            .andExpect(jsonPath("$.data.lockKey").doesNotExist())
            .andExpect(jsonPath("$.data.lockValue").doesNotExist());

        verify(knowledgeIndexJobExecutor).submitAndExecute(1L, 1L);
    }

    @Test
    void shouldSubmitKnowledgeRebuildForFailedOnlyMode() throws Exception {
        com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse response =
            new com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse("FAILED_ONLY", 2, List.of());
        when(knowledgeIndexJobExecutor.submitRebuild("FAILED_ONLY", 50, 1L)).thenReturn(response);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/rebuild")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"FAILED_ONLY\",\"limit\":50}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.mode").value("FAILED_ONLY"))
            .andExpect(jsonPath("$.data.submittedCount").value(2));

        verify(knowledgeIndexJobExecutor).submitRebuild("FAILED_ONLY", 50, 1L);
    }

    @Test
    void shouldSubmitKnowledgeRebuildForRankIncrementalMode() throws Exception {
        com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse response =
            new com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse("RANK_INCREMENTAL", 3, List.of());
        when(knowledgeIndexJobExecutor.submitRebuild("RANK_INCREMENTAL", 100, 1L)).thenReturn(response);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/rebuild")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"RANK_INCREMENTAL\",\"limit\":100}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.mode").value("RANK_INCREMENTAL"))
            .andExpect(jsonPath("$.data.submittedCount").value(3));

        verify(knowledgeIndexJobExecutor).submitRebuild("RANK_INCREMENTAL", 100, 1L);
    }

    @Test
    void shouldManageProjectWorksAndImportChaptersForAuthenticatedUser() throws Exception {
        String token = loginAndGetToken();
        MvcResult projectResult = mockMvc.perform(post("/api/knowledge/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"诸天特效项目","description":"都市脑洞新书"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.name").value("诸天特效项目"))
            .andReturn();
        Number projectId = JsonPath.read(projectResult.getResponse().getContentAsString(), "$.data.projectId");

        MvcResult workResult = mockMvc.perform(post("/api/knowledge/projects/" + projectId.longValue() + "/works")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"诸天外包特效师","alias":"五毛特效","genre":"都市脑洞"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.title").value("诸天外包特效师"))
            .andReturn();
        Number workId = JsonPath.read(workResult.getResponse().getContentAsString(), "$.data.workId");

        mockMvc.perform(post("/api/knowledge/projects/" + projectId.longValue() + "/works/" + workId.longValue() + "/ingest-jobs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"chapterNo":1,"title":"退稿夜，系统降临","content":"主角被甲方退稿后绑定三端一体系统。","idempotencyKey":"it-import-1"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.chapterNo").value(1))
            .andExpect(jsonPath("$.data.status").isNotEmpty());

        mockMvc.perform(get("/api/knowledge/projects/" + projectId.longValue() + "/ingest-jobs")
                .header("Authorization", "Bearer " + token)
                .param("workId", String.valueOf(workId.longValue())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/knowledge/projects/" + projectId.longValue() + "/works/" + workId.longValue() + "/chapters")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].chapterNo").value(1));
    }

    @Test
    void shouldReturnOwnedProjectMemoryOverviewForAuthenticatedUser() throws Exception {
        ProjectMemoryOverviewVO overview = new ProjectMemoryOverviewVO();
        overview.setProjectId(7L);
        overview.setWorkId(11L);
        overview.setActiveChapterCount(10L);
        overview.setChapterFrom(1);
        overview.setChapterTo(10);
        overview.setIndexedDocumentCount(3L);
        overview.setForeshadowingCount(4L);
        overview.setSummaryCoverageStatus("PARTIAL");
        overview.setSummaryCoveredChapterCount(8L);
        overview.setRecognizedRecordsOnly(true);
        when(knowledgeProjectMemoryOverviewService.overview(1L, 7L, 11L)).thenReturn(overview);

        String token = loginAndGetToken();
        mockMvc.perform(get("/api/knowledge/projects/7/works/11/memory-overview")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.projectId").value(7))
            .andExpect(jsonPath("$.data.workId").value(11))
            .andExpect(jsonPath("$.data.activeChapterCount").value(10))
            .andExpect(jsonPath("$.data.chapterFrom").value(1))
            .andExpect(jsonPath("$.data.chapterTo").value(10))
            .andExpect(jsonPath("$.data.indexedDocumentCount").value(3))
            .andExpect(jsonPath("$.data.summaryCoverageStatus").value("PARTIAL"))
            .andExpect(jsonPath("$.data.recognizedRecordsOnly").value(true));

        verify(knowledgeProjectMemoryOverviewService).overview(1L, 7L, 11L);
    }

    @Test
    void shouldReplayChatRunEventsStrictlyAfterSequence() throws Exception {
        insertTerminalRunWithEvents("run-event-replay");
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/knowledge/chat-runs/run-event-replay/events")
                .param("afterSequence", "1")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].sequenceNo").value(2))
            .andExpect(jsonPath("$.data[1].sequenceNo").value(3));
    }

    @Test
    void shouldResumeChatRunSseFromLastEventId() throws Exception {
        insertTerminalRunWithEvents("run-event-stream");
        String token = loginAndGetToken();

        MvcResult started = mockMvc.perform(get("/api/knowledge/chat-runs/run-event-stream/events/stream")
                .header("Authorization", "Bearer " + token)
                .header("Last-Event-ID", "1"))
            .andExpect(request().asyncStarted())
            .andReturn();

        started.getAsyncResult(7000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();
        String body = completed.getResponse().getContentAsString();
        assertThat(body).contains("id:2", "id:3").doesNotContain("id:1\n");
    }

    @Test
    void shouldResumeCancellationTerminalWithoutSynthesizingDone() throws Exception {
        insertCancelledRunWithEvents("run-cancel-event-stream");
        String token = loginAndGetToken();

        MvcResult started = mockMvc.perform(
                get("/api/knowledge/chat-runs/run-cancel-event-stream/events/stream")
                    .header("Authorization", "Bearer " + token)
                    .header("Last-Event-ID", "1")
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        started.getAsyncResult(7000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();
        String body = completed.getResponse().getContentAsString();
        assertThat(body)
            .contains("id:2", "event:cancel_requested", "id:3", "event:cancelled")
            .doesNotContain("id:1\n", "event:answered", "event:done");
    }

    @Test
    void shouldReplayAnswerSnapshotWhenSseEventHistoryHasGap() throws Exception {
        insertTerminalRunWithEvents("run-event-gap");
        jdbcTemplate.update("""
            update ai_chat_run
            set answer = 'snapshot answer', snapshot_sequence_no = 2
            where run_id = 'run-event-gap'
            """);
        jdbcTemplate.update("""
            delete from ai_chat_run_event
            where run_id = 'run-event-gap' and sequence_no = 2
            """);
        String token = loginAndGetToken();

        MvcResult started = mockMvc.perform(get("/api/knowledge/chat-runs/run-event-gap/events/stream")
                .header("Authorization", "Bearer " + token)
                .header("Last-Event-ID", "1"))
            .andExpect(request().asyncStarted())
            .andReturn();

        started.getAsyncResult(7000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();
        String body = completed.getResponse().getContentAsString();
        assertThat(body).contains("event:snapshot", "id:2", "snapshot answer", "id:3");
    }

    @Test
    void shouldReplaySnapshotForGapInsideReturnedEventBatch() throws Exception {
        insertTerminalRunWithEvents("run-event-internal-gap");
        jdbcTemplate.update("""
            insert into ai_chat_run_event(
                run_id, sequence_no, event_type, event_idempotency_key, payload, created_at
            ) values('run-event-internal-gap', 4, 'PROGRESS', 'run:internal-gap:4', '{}', current_timestamp)
            """);
        jdbcTemplate.update("""
            update ai_chat_run
            set answer = 'internal gap snapshot', snapshot_sequence_no = 3, next_sequence_no = 4
            where run_id = 'run-event-internal-gap'
            """);
        jdbcTemplate.update("""
            delete from ai_chat_run_event
            where run_id = 'run-event-internal-gap' and sequence_no = 3
            """);
        String token = loginAndGetToken();

        MvcResult started = mockMvc.perform(
                get("/api/knowledge/chat-runs/run-event-internal-gap/events/stream")
                    .header("Authorization", "Bearer " + token)
                    .header("Last-Event-ID", "1")
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        started.getAsyncResult(7000);
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();
        String body = completed.getResponse().getContentAsString();
        assertThat(body).contains(
            "event:snapshot", "id:3", "internal gap snapshot", "id:4"
        );
    }

    @Test
    void shouldCreateListLoadMessagesAndArchiveConversation() throws Exception {
        String token = loginAndGetToken();
        MvcResult projectResult = mockMvc.perform(post("/api/knowledge/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Conversation Project\"}"))
            .andExpect(status().isOk())
            .andReturn();
        Number projectId = JsonPath.read(projectResult.getResponse().getContentAsString(), "$.data.projectId");

        MvcResult conversationResult = mockMvc.perform(post("/api/knowledge/conversations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":" + projectId.longValue() + ",\"title\":\"Opening Ideas\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.projectId").value(projectId.longValue()))
            .andExpect(jsonPath("$.data.title").value("Opening Ideas"))
            .andReturn();
        String conversationId = JsonPath.read(
            conversationResult.getResponse().getContentAsString(),
            "$.data.conversationId"
        );

        jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, role, content) values(?, ?, ?, ?, ?)",
            conversationId, 1L, projectId.longValue(), "USER", "Question one"
        );
        jdbcTemplate.update(
            "insert into ai_chat_message(conversation_id, user_id, project_id, role, content) values(?, ?, ?, ?, ?)",
            conversationId, 1L, projectId.longValue(), "ASSISTANT", "Answer one"
        );

        mockMvc.perform(get("/api/knowledge/conversations")
                .header("Authorization", "Bearer " + token)
                .param("projectId", String.valueOf(projectId.longValue())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].conversationId").value(conversationId));

        mockMvc.perform(get("/api/knowledge/conversations/" + conversationId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages.length()").value(2))
            .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
            .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"));

        mockMvc.perform(get("/api/knowledge/conversations/" + conversationId + "/messages")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(post("/api/knowledge/conversations/" + conversationId + "/archive")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/knowledge/conversations")
                .header("Authorization", "Bearer " + token)
                .param("projectId", String.valueOf(projectId.longValue())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void shouldDualWriteBlockingChatMessagesFromAuthenticatedConversationScope() throws Exception {
        String token = loginAndGetToken();
        MvcResult projectResult = mockMvc.perform(post("/api/knowledge/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Blocking Chat Project\"}"))
            .andExpect(status().isOk())
            .andReturn();
        Number projectId = JsonPath.read(projectResult.getResponse().getContentAsString(), "$.data.projectId");
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("Blocking answer");
        workerResponse.setResultJson(Map.of("conversationId", "conv-blocking"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Blocking question\",\"conversationId\":\"conv-blocking\"," +
                    "\"projectId\":" + projectId.longValue() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.answer").value("Blocking answer"));

        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
            "select message_id, user_id, project_id, run_id, role, content from ai_chat_message " +
                "where conversation_id = ? order by message_id",
            "conv-blocking"
        );
        assertThat(messages).extracting(row -> row.get("role")).containsExactly("USER", "ASSISTANT");
        assertThat(messages).extracting(row -> ((Number) row.get("user_id")).longValue()).containsOnly(1L);
        assertThat(messages).extracting(row -> ((Number) row.get("project_id")).longValue())
            .containsOnly(projectId.longValue());
        assertThat(messages).extracting(row -> row.get("run_id"))
            .containsOnly(messages.get(0).get("run_id"));
        assertThat(String.valueOf(messages.get(0).get("run_id"))).startsWith("chatrun-");
        assertThat(messages).extracting(row -> row.get("content"))
            .containsExactly("Blocking question", "Blocking answer");
        String runId = String.valueOf(messages.get(0).get("run_id"));
        Map<String, Object> run = jdbcTemplate.queryForMap(
            "select status, request_id, trigger_message_id, response_message_id " +
                "from ai_chat_run where run_id = ?",
            runId
        );
        assertThat(run.get("status")).isEqualTo("ANSWERED");
        assertThat(run.get("request_id")).isNotNull();
        assertThat(run.get("trigger_message_id")).isEqualTo(messages.get(0).get("message_id"));
        assertThat(run.get("response_message_id")).isEqualTo(messages.get(1).get("message_id"));
    }

    @Test
    void shouldPersistOnlyUserMessageWhenBlockingChatReturnsNoOutput() throws Exception {
        String token = loginAndGetToken();
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer(" ");
        workerResponse.setResultJson(Map.of("conversationId", "conv-blocking-empty"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Blocking question\",\"conversationId\":\"conv-blocking-empty\"}"))
            .andExpect(status().isOk());

        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
            "select project_id, role, content from ai_chat_message " +
                "where conversation_id = ? order by message_id",
            "conv-blocking-empty"
        );
        assertThat(messages).extracting(row -> row.get("role")).containsExactly("USER");
        assertThat(messages).extracting(row -> row.get("project_id")).containsOnlyNulls();
        assertThat(messages).extracting(row -> row.get("content")).containsExactly("Blocking question");
    }

    @Test
    void shouldReuseBlockingChatResultForTheSameRequestId() throws Exception {
        String token = loginAndGetToken();
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("Idempotent answer");
        workerResponse.setResultJson(Map.of("conversationId", "conv-idempotent"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        String requestBody = "{\"question\":\"Idempotent question\"," +
            "\"conversationId\":\"conv-idempotent\",\"requestId\":\"request-idempotent-1\"}";

        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.answer").value("Idempotent answer"));
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.answer").value("Idempotent answer"));

        verify(langGraphWorkerClient, times(1)).runKnowledgeChat(any());
        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
            "select role, content from ai_chat_message where conversation_id = ? order by message_id",
            "conv-idempotent"
        );
        assertThat(messages).extracting(row -> row.get("ROLE"))
            .containsExactly("USER", "ASSISTANT");
        assertThat(messages).extracting(row -> row.get("CONTENT"))
            .containsExactly("Idempotent question", "Idempotent answer");
    }

    @Test
    void shouldRejectReusingBlockingRequestIdWithDifferentPayload() throws Exception {
        String token = loginAndGetToken();
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("Fast answer");
        workerResponse.setResultJson(Map.of("conversationId", "conv-idempotent-conflict"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Same question\",\"conversationId\":\"conv-idempotent-conflict\"," +
                    "\"requestId\":\"request-conflict-1\",\"reasoningMode\":\"fast\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Same question\",\"conversationId\":\"conv-idempotent-conflict\"," +
                    "\"requestId\":\"request-conflict-1\",\"reasoningMode\":\"deep\"}"))
            .andExpect(status().isBadRequest());

        verify(langGraphWorkerClient, times(1)).runKnowledgeChat(any());
    }

    @Test
    void shouldNotExecuteConcurrentBlockingRequestsWithTheSameRequestIdTwice() throws Exception {
        String token = loginAndGetToken();
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("Concurrent answer");
        workerResponse.setResultJson(Map.of("conversationId", "conv-concurrent-idempotent"));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return workerResponse;
        });
        String body = "{\"question\":\"Concurrent question\"," +
            "\"conversationId\":\"conv-concurrent-idempotent\"," +
            "\"requestId\":\"request-concurrent-1\"}";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc.perform(post("/api/knowledge/chat")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andReturn());
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/api/knowledge/chat")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        verify(langGraphWorkerClient, times(1)).runKnowledgeChat(any());
    }

    @Test
    void shouldPersistStreamChatRunAndMessages() throws Exception {
        String token = loginAndGetToken();
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("Stream answer");
        workerResponse.setResultJson(Map.of("conversationId", "conv-stream-persisted"));
        when(langGraphWorkerClient.streamKnowledgeChat(any(), any(), any(), any())).thenReturn(workerResponse);

        mockMvc.perform(post("/api/knowledge/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Stream question\"," +
                    "\"conversationId\":\"conv-stream-persisted\"," +
                    "\"requestId\":\"request-stream-persisted\"}"))
            .andExpect(status().isOk());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String status = null;
        while (System.nanoTime() < deadline) {
            List<String> statuses = jdbcTemplate.queryForList(
                "select status from ai_chat_run where request_id = ?",
                String.class,
                "request-stream-persisted"
            );
            status = statuses.isEmpty() ? null : statuses.get(0);
            if ("ANSWERED".equals(status)) {
                break;
            }
            Thread.sleep(50L);
        }

        assertThat(status).isEqualTo("ANSWERED");
        assertThat(jdbcTemplate.queryForList(
            "select role, content from ai_chat_message where conversation_id = ? order by message_id",
            "conv-stream-persisted"
        )).extracting(row -> row.get("ROLE"))
            .containsExactly("USER", "ASSISTANT");
    }

    @Test
    void shouldCreateInitialConversationWithNewProject() throws Exception {
        String token = loginAndGetToken();
        MvcResult result = mockMvc.perform(post("/api/knowledge/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Project With Conversation\"}"))
            .andExpect(status().isOk())
            .andReturn();
        Number projectId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.projectId");

        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation where project_id = ? and user_id = 1 and status = 'ACTIVE'",
            Integer.class,
            projectId.longValue()
        )).isEqualTo(1);
    }

    @Test
    void shouldReturnKnowledgeHealthDiagnostics() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(id, platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            501L, "fanqie", "health-501", "健康检查书", "作者H", "简介", "https://fanqienovel.com/page/health-501",
            Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
        jdbcTemplate.update(
            "INSERT INTO crawl_rank(id, platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            7001L, "fanqie", "都市脑洞", "male-new", "urban-brain", 901L, 1, 501L, "健康检查书", "https://fanqienovel.com/page/health-501", "作者H", "简介",
            Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
        jdbcTemplate.update(
            "INSERT INTO crawl_chapter(id, platform, book_id, chapter_no, chapter_title, content, word_count, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            8001L, "fanqie", 501L, 1, "第一章", "正文", 2, Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
        jdbcTemplate.update(
            "INSERT INTO knowledge_document(id, source_type, source_ref_id, platform, book_id, title, status, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            9001L, "RANK", 7001L, "fanqie", 501L, "榜单证据", "INDEXED", 0
        );
        jdbcTemplate.update(
            "INSERT INTO knowledge_chunk(id, document_id, chunk_key, source_type, source_ref_id, book_id, content_hash, chunk_text, token_count, embedding_model, embedding_dimension, vector_status, qdrant_point_id, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9101L, 9001L, "rank-7001", "RANK", 7001L, 501L, "health-rank-hash", "榜单证据", 20, "text-embedding-v4", 1024, "INDEXED", "rank-point", 0
        );
        jdbcTemplate.update(
            "INSERT INTO async_job(id, job_type, job_key, resource_key, request_json, status, trigger_user_id, retry_count, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9201L, "KNOWLEDGE_INDEX_BOOK", "book:501:RANK_INCREMENTAL", "book:501", "{\"bookId\":501}", "SUCCESS", 1L, 0,
            Timestamp.valueOf(now), Timestamp.valueOf(now)
        );

        String token = loginAndGetToken();
        mockMvc.perform(get("/api/knowledge/health")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.embeddingModel").value("text-embedding-v4"))
            .andExpect(jsonPath("$.data.embeddingDimension").value(1024))
            .andExpect(jsonPath("$.data.chunkStats[0].sourceType").value("RANK"))
            .andExpect(jsonPath("$.data.chunkStats[0].vectorStatus").value("INDEXED"))
            .andExpect(jsonPath("$.data.chunkStats[0].count").value(1))
            .andExpect(jsonPath("$.data.rankRows.total").value(1))
            .andExpect(jsonPath("$.data.rankRows.indexed").value(1))
            .andExpect(jsonPath("$.data.rankRows.missing").value(0))
            .andExpect(jsonPath("$.data.chapters.total").value(1))
            .andExpect(jsonPath("$.data.chapters.indexed").value(0))
            .andExpect(jsonPath("$.data.chapters.missing").value(1))
            .andExpect(jsonPath("$.data.jobStats[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.jobStats[0].count").value(1));
    }

    @Test
    void shouldCreateDraftGoldenCandidateFromAdminTraceApi() throws Exception {
        createAgentTraceTableIfNeeded();
        jdbcTemplate.update("""
                insert into ai_agent_trace(trace_id, user_id, project_id, conversation_id, question, status,
                    task_graph_json, tool_runs_json, evidence_pack_json, perspective_results_json, result_json)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "trace-api-golden",
            1L,
            11L,
            "conv-api-golden",
            "How should the opening hook work?",
            "answered",
            "{\"nodes\":[{\"name\":\"market_scan\"}]}",
            "[{\"name\":\"rank.lookup\",\"status\":\"succeeded\"}]",
            "{\"factCount\":1}",
            null,
            """
                {
                  "answer":"Open with a concrete conflict backed by fresh rank evidence.",
                  "selectedSkills":["webnovel-market-scan"],
                  "toolRuns":[{"name":"rank.lookup","status":"succeeded"}],
                  "evidenceContract":{"status":"verified_latest"},
                  "supervisorDecision":{"summary":"fresh evidence verified"},
                  "trace":{"traceId":"trace-api-golden"}
                }
                """);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/admin/agent-traces/1/golden-candidate")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.traceId").value("trace-api-golden"))
            .andExpect(jsonPath("$.data.question").value("How should the opening hook work?"))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("fresh rank evidence")))
            .andExpect(jsonPath("$.data.selectedSkills[0]").value("webnovel-market-scan"))
            .andExpect(jsonPath("$.data.selectedTools[0]").value("rank.lookup"))
            .andExpect(jsonPath("$.data.evidenceContract").value(org.hamcrest.Matchers.containsString("verified_latest")));
    }

    @Test
    void shouldCreateManualSkillCandidateFromAdminApi() throws Exception {
        createSkillCandidateTableIfNeeded();

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/admin/skill-candidates")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "skillId":"webnovel-outsourcing-outline",
                      "title":"诸天外包大纲技能",
                      "content":"# 诸天外包大纲技能\\n用于三端一体都市脑洞的大纲扩展。",
                      "evalResultJson":"{\\"version\\":\\"2026.07.04\\",\\"intents\\":[\\"mixed_creation_research\\"],\\"metrics\\":{\\"requiredToolPassRate\\":1.0,\\"evidencePassRate\\":0.95,\\"faithfulnessPassRate\\":0.95}}"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.skillId").value("webnovel-outsourcing-outline"))
            .andExpect(jsonPath("$.data.title").value("诸天外包大纲技能"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.evalStatus").value("PASSED"));
    }

    @Test
    void shouldExposeAdminEvalCenterRunsAndCaseResults() throws Exception {
        createEvalTablesIfNeeded();
        jdbcTemplate.update("""
                insert into ai_eval_run(run_key, suite_name, runner_name, evaluator_name, model_name,
                    status, total_cases, passed_cases, failed_cases, metrics_json)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "controller-eval-run",
            "agent-runtime",
            "worker-golden-runner",
            "rule-based",
            "deepseek-chat",
            "FAILED",
            2,
            1,
            1,
            "{\"trace_completeness_rate\":1.0}"
        );
        jdbcTemplate.update("""
                insert into ai_eval_case_result(run_id, case_key, status, intent, answer_mode,
                    retrieval_metrics, faithfulness_json, failures, trace_id, duration_ms)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            "mixed-001",
            "FAILED",
            "mixed_creation_research",
            "mixed_creation",
            "{\"hit_rate_at_k\":0.0}",
            "{\"passed\":false}",
            "[\"trace:missing_tool:rank.lookup\"]",
            "trace-eval-controller",
            240
        );

        String token = loginAndGetToken();
        mockMvc.perform(get("/api/knowledge/admin/agent/eval-runs")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].runKey").value("controller-eval-run"))
            .andExpect(jsonPath("$.data[0].suiteName").value("agent-runtime"))
            .andExpect(jsonPath("$.data[0].status").value("FAILED"))
            .andExpect(jsonPath("$.data[0].metricsJson").value(org.hamcrest.Matchers.containsString("trace_completeness_rate")));

        mockMvc.perform(get("/api/knowledge/admin/agent/eval-runs/1/cases")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].caseKey").value("mixed-001"))
            .andExpect(jsonPath("$.data[0].failures").value(org.hamcrest.Matchers.containsString("missing_tool")))
            .andExpect(jsonPath("$.data[0].traceId").value("trace-eval-controller"));
    }

    @Test
    void shouldTriggerAdminEvalRunThroughWorker() throws Exception {
        AgentEvalRunVO run = new AgentEvalRunVO();
        run.setId(42L);
        run.setRunKey("agent-runtime:manual-001");
        run.setSuiteName("agent-runtime");
        run.setRunnerName("admin-trigger");
        run.setEvaluatorName("rule-based");
        run.setModelName("deepseek-chat");
        run.setStatus("RUNNING");
        run.setTotalCases(10);
        when(langGraphWorkerClient.startKnowledgeEvalRun(any(AgentEvalRunRequest.class))).thenReturn(run);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/admin/agent/eval-runs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "suiteName": "agent-runtime",
                      "runKey": "agent-runtime:manual-001",
                      "runnerName": "admin-trigger",
                      "evaluatorName": "rule-based",
                      "modelName": "deepseek-chat",
                      "caseLimit": 10
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value(42))
            .andExpect(jsonPath("$.data.runKey").value("agent-runtime:manual-001"))
            .andExpect(jsonPath("$.data.status").value("RUNNING"))
            .andExpect(jsonPath("$.data.totalCases").value(10));

        verify(langGraphWorkerClient).startKnowledgeEvalRun(argThat(payload ->
            "agent-runtime".equals(payload.getSuiteName())
                && "agent-runtime:manual-001".equals(payload.getRunKey())
                && Integer.valueOf(10).equals(payload.getCaseLimit())
        ));
    }

    private void insertTerminalRunWithEvents(String runId) {
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                next_sequence_no, deleted, queued_at, finished_at, update_time
            ) values(?, 1, ?, 'question', '{}', 'ANSWERED', 3, 0,
                current_timestamp, current_timestamp, current_timestamp)
            """,
            runId,
            "conv-" + runId
        );
        for (int sequence = 1; sequence <= 3; sequence++) {
            jdbcTemplate.update("""
                insert into ai_chat_run_event(
                    run_id, sequence_no, event_type, event_idempotency_key, payload, created_at
                ) values(?, ?, ?, ?, '{}', current_timestamp)
                """,
                runId,
                sequence,
                sequence == 3 ? "ANSWERED" : "PROGRESS",
                runId + ":event:" + sequence
            );
        }
    }

    private void insertCancelledRunWithEvents(String runId) {
        jdbcTemplate.update("""
            insert into ai_chat_run(
                run_id, user_id, conversation_id, question, request_json, status,
                next_sequence_no, cancel_requested, deleted, queued_at, finished_at, update_time
            ) values(?, 1, ?, 'question', '{}', 'CANCELLED', 3, true, 0,
                current_timestamp, current_timestamp, current_timestamp)
            """,
            runId,
            "conv-" + runId
        );
        String[] eventTypes = {"PROGRESS", "CANCEL_REQUESTED", "CANCELLED"};
        for (int index = 0; index < eventTypes.length; index++) {
            int sequence = index + 1;
            jdbcTemplate.update("""
                insert into ai_chat_run_event(
                    run_id, sequence_no, event_type, event_idempotency_key, payload, created_at
                ) values(?, ?, ?, ?, '{}', current_timestamp)
                """,
                runId,
                sequence,
                eventTypes[index],
                runId + ":event:" + sequence
            );
        }
    }

    private String loginAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + ADMIN_PHONE + "\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private long insertBookAndKnowledge() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(id, platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1L, "fanqie", "controller-101", "控制器测试书", "作者C", "简介", "https://fanqienovel.com/page/controller-101",
            Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
        jdbcTemplate.update(
            "INSERT INTO knowledge_document(id, source_type, source_ref_id, platform, book_id, title, status, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            1L, "CHAPTER", 1L, "fanqie", 1L, "第一章", "INDEXED", 0
        );
        jdbcTemplate.update(
            "INSERT INTO knowledge_chunk(id, document_id, chunk_key, source_type, source_ref_id, book_id, chapter_no, content_hash, chunk_text, token_count, vector_status, qdrant_point_id, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1L, 1L, "chapter-1-1", "CHAPTER", 1L, 1L, 1, "hash-controller", "主角目标明确，冲突很早出现。", 20, "INDEXED", "chunk-point-controller", 0
        );
        return 1L;
    }

    private void createAgentTraceTableIfNeeded() {
        jdbcTemplate.execute("create table if not exists ai_agent_trace (" +
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
    }

    private void createSkillCandidateTableIfNeeded() {
        jdbcTemplate.execute("create table if not exists ai_skill_candidate (" +
            "id bigint auto_increment primary key," +
            "skill_id varchar(120) not null," +
            "title varchar(200) not null," +
            "content clob," +
            "status varchar(30) not null," +
            "eval_status varchar(30) not null," +
            "eval_result_json clob," +
            "required_tool_pass_rate double," +
            "evidence_pass_rate double," +
            "faithfulness_pass_rate double," +
            "review_note varchar(500)," +
            "source_trace_id varchar(80)," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp)");
    }

    private void createEvalTablesIfNeeded() {
        jdbcTemplate.execute("create table if not exists ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "run_key varchar(128)," +
            "suite_name varchar(100)," +
            "runner_name varchar(100)," +
            "evaluator_name varchar(100)," +
            "model_name varchar(100)," +
            "status varchar(20)," +
            "total_cases int," +
            "passed_cases int," +
            "failed_cases int," +
            "metrics_json clob," +
            "started_at timestamp default current_timestamp," +
            "finished_at timestamp," +
            "deleted tinyint default 0)");
        jdbcTemplate.execute("create table if not exists ai_eval_case_result (" +
            "id bigint auto_increment primary key," +
            "run_id bigint," +
            "case_key varchar(128)," +
            "status varchar(20)," +
            "intent varchar(80)," +
            "answer_mode varchar(80)," +
            "retrieval_metrics clob," +
            "faithfulness_json clob," +
            "failures clob," +
            "trace_id varchar(80)," +
            "duration_ms int," +
            "create_time timestamp default current_timestamp," +
            "deleted tinyint default 0)");
    }
}
