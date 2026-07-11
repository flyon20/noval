package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookSearchItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgeinternalcontrollerdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=16379",
        "spring.data.redis.password=CHANGE_ME_WITH_A_STRONG_REDIS_PASSWORD",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100",
        "app.crawler.internal-api-key=crawler-internal-api-key-with-enough-length-1234567890",
        "app.ai.langgraph-worker.internal-api-key=langgraph-internal-key-with-enough-length-1234567890",
        "app.knowledge.index.queue-enabled=false",
        "app.knowledge.eval.queue-enabled=false",
        "app.knowledge.index.rank-incremental-enabled=false",
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
        "classpath:sql/phase12-webnovel-agent-project-trace-h2.sql",
        "classpath:sql/phase13-agent-memory-mcp-h2.sql",
        "classpath:sql/phase16-project-knowledge-rag-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class KnowledgeInternalControllerTest {

    private static final String INTERNAL_TOKEN = "langgraph-internal-key-with-enough-length-1234567890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private PythonCrawlerClient pythonCrawlerClient;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private QdrantClient qdrantClient;

    @BeforeEach
    void prepareState() {
        try {
            RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
            try {
                connection.serverCommands().flushDb();
            } finally {
                connection.close();
            }
        } catch (RuntimeException ignored) {
            // These controller tests do not depend on Redis state. Local Redis may be absent in CI/dev.
        }
    }

    @Test
    void shouldExposeAgentGovernanceConfigForInternalWorkerCaller() throws Exception {
        mockMvc.perform(get("/internal/knowledge/agent/runtime-config")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reasoningModeDefault").value("fast"))
            .andExpect(jsonPath("$.maxParallelSpecialists").value(3))
            .andExpect(jsonPath("$.maxEvidenceItems").value(30));

        mockMvc.perform(get("/internal/knowledge/agent/experts")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].expertName").value("market_scan"))
            .andExpect(jsonPath("$[0].enabled").value(true))
            .andExpect(jsonPath("$[0].allowedTools[0]").value("rank.lookup"));
    }

    @Test
    void shouldExposePublishedRuntimeSkillsFromBackendDbForInternalWorkerCaller() throws Exception {
        jdbcTemplate.update("""
                insert into ai_runtime_skill(candidate_id, skill_id, version, title, content, status,
                    intents_json, triggers_json, allowed_tools_json, required_evidence_json)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            11L,
            "webnovel-market-scan",
            "2026.07.02",
            "Market Scan",
            "Use rank.lookup before synthesis.",
            "ACTIVE",
            "[\"market_scan\"]",
            "[\"rank\",\"trend\"]",
            "[\"rank.lookup\",\"rank.research_pack\"]",
            "[\"fresh_rank\"]"
        );
        jdbcTemplate.update("""
                insert into ai_runtime_skill(candidate_id, skill_id, version, title, content, status)
                values(?, ?, ?, ?, ?, ?)
                """,
            12L,
            "disabled-skill",
            "1.0.0",
            "Disabled",
            "disabled content",
            "DISABLED"
        );

        mockMvc.perform(get("/internal/knowledge/runtime-skills")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].skillId").value("webnovel-market-scan"))
            .andExpect(jsonPath("$[0].version").value("2026.07.02"))
            .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.containsString("rank.lookup")))
            .andExpect(jsonPath("$[0].intents[0]").value("market_scan"))
            .andExpect(jsonPath("$[0].allowedTools[0]").value("rank.lookup"))
            .andExpect(jsonPath("$[0].requiredEvidence[0]").value("fresh_rank"))
            .andExpect(jsonPath("$[0].source").value("backend"));
    }

    @Test
    void shouldIngestRuntimeTelemetryForInternalWorkerCaller() throws Exception {
        mockMvc.perform(post("/internal/knowledge/agent/telemetry")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "traceId":"trace-telemetry-1",
                      "cacheEvents":[
                        {
                          "cacheScope":"tool",
                          "nodeName":"execute_tools",
                          "expertName":"market_scan",
                          "cacheKeyHash":"cache-1",
                          "cacheStatus":"MISS",
                          "promptPrefixHash":"prefix-1",
                          "promptPrefixStable":true,
                          "durationMs":17
                        }
                      ],
                      "tokenMetrics":[
                        {
                          "nodeName":"answer_writer",
                          "expertName":"market_scan",
                          "modelName":"deepseek-chat",
                          "promptTokens":120,
                          "completionTokens":80,
                          "tokenCount":200
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cacheEvents").value(1))
            .andExpect(jsonPath("$.tokenMetrics").value(1));

        Integer cacheRows = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_cache_event where trace_id = ? and cache_status = ?",
            Integer.class,
            "trace-telemetry-1",
            "MISS"
        );
        Integer tokenRows = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_token_metric where trace_id = ? and token_count = ?",
            Integer.class,
            "trace-telemetry-1",
            200
        );
        org.assertj.core.api.Assertions.assertThat(cacheRows).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(tokenRows).isEqualTo(1);
    }

    @Test
    void shouldSearchBooksForInternalWorkerCaller() throws Exception {
        insertBook("fanqie", "101", "Book Alpha", "Author A", "Intro A", "https://fanqienovel.com/page/101");
        when(pythonCrawlerClient.searchBooks("fanqie", "Book", 3)).thenReturn(List.of(
            searchItem("102", "Book Beta", "Author B", "Intro B", "https://fanqienovel.com/page/102")
        ));

        mockMvc.perform(post("/internal/knowledge/books/search")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","keyword":"Book","limit":3}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].bookName").value("Book Alpha"))
            .andExpect(jsonPath("$[1].bookName").value("Book Beta"));
    }

    @Test
    void shouldRejectInternalKnowledgeRequestWithoutServiceToken() throws Exception {
        mockMvc.perform(post("/internal/knowledge/books/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","keyword":"Book","limit":3}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldSearchKnowledgeEvidenceForInternalWorkerCaller() throws Exception {
        long bookId = insertIndexedKnowledge();
        when(embeddingClient.embed("hero goal")).thenReturn(List.of(0.1, 0.2, 0.3));
        when(qdrantClient.search(any(), eq(Map.of("bookId", bookId, "platform", "fanqie")), eq(3)))
            .thenReturn(List.of(new QdrantClient.SearchResult("chunk-point-internal", 0.91, Map.of("chunkId", 1L))));

        mockMvc.perform(post("/internal/knowledge/search")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"hero goal","bookId":1,"platform":"fanqie","limit":3}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].bookId").value(1))
            .andExpect(jsonPath("$[0].title").value("Chapter 1"));
    }

    @Test
    void shouldLookupRankForInternalWorkerCaller() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 201L, "入伍两次！我被原部队拉进黑名单", "朝朝和", "退伍入伍都市脑洞");

        mockMvc.perform(post("/internal/knowledge/rank/lookup")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","category":"都市脑洞","rankNo":1,"limit":5}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].rankNo").value(1))
            .andExpect(jsonPath("$[0].bookName").value("入伍两次！我被原部队拉进黑名单"))
            .andExpect(jsonPath("$[0].author").value("朝朝和"));
    }

    @Test
    void shouldLookupRankTimeWindowForInternalWorkerCaller() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board", 12L);
        insertRank(12L, "male-new", "urban-brain", 1, 603L, "Latest Top One", "Author Latest", "Latest intro");
        insertRankBoardSnapshot(11L, LocalDateTime.now().minusDays(10));
        insertRank(11L, "male-new", "urban-brain", 1, 602L, "Recent Top One", "Author Recent", "Recent intro");
        insertRankBoardSnapshot(10L, LocalDateTime.now().minusDays(40));
        insertRank(10L, "male-new", "urban-brain", 1, 601L, "Too Old Top One", "Author Old", "Too old intro");

        mockMvc.perform(post("/internal/knowledge/rank/lookup")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","rankNo":1,"freshness":"time_window","allowHistorical":true,"timeWindowDays":30,"limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].bookName").value("Latest Top One"))
            .andExpect(jsonPath("$[1].bookName").value("Recent Top One"));
    }

    @Test
    void shouldBuildBookResearchPackForInternalWorkerCaller() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 301L, "Research Book", "Author R", "Rank intro");
        insertRankBoardSnapshot(11L, LocalDateTime.now().plusMinutes(1));
        insertRank(11L, "male-new", "urban-brain", 5, 301L, "Research Book", "Author R", "Newer rank intro");
        insertChapter(301L, 0, "Empty", "");
        insertChapter(301L, 1, "Opening", "Opening scene content with long protagonist setup and market hook.");
        insertChapter(301L, 2, "Conflict", "Second chapter content should be available when chapter limit allows it.");
        insertAnalysisResult(301L, "deconstruct", "Latest analysis content with sell point and pacing notes.");

        mockMvc.perform(post("/internal/knowledge/research-pack/book")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":301,"bookName":"Research Book","chapterLimit":2,"analysisLimit":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.book.bookId").value(301))
            .andExpect(jsonPath("$.book.bookName").value("Research Book"))
            .andExpect(jsonPath("$.chapters.length()").value(2))
            .andExpect(jsonPath("$.chapters[0].chapterNo").value(1))
            .andExpect(jsonPath("$.chapters[0].content").value("Opening scene content with long protagonist setup and market hook."))
            .andExpect(jsonPath("$.ranks.length()").value(1))
            .andExpect(jsonPath("$.ranks[0].rankNo").value(5))
            .andExpect(jsonPath("$.analyses.length()").value(1))
            .andExpect(jsonPath("$.analyses[0].analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.analyses[0].content").value("Latest analysis content with sell point and pacing notes."));
    }

    @Test
    void shouldBuildRankResearchPackWithTopNBooksAndChaptersForInternalWorkerCaller() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board", 10L);
        insertRank(10L, "male-new", "urban-brain", 1, 401L, "Top One", "Author A", "Top one intro");
        insertRank(10L, "male-new", "urban-brain", 2, 402L, "Top Two", "Author B", "Top two intro");
        insertRank(10L, "male-new", "urban-brain", 3, 403L, "Top Three", "Author C", "Top three intro");
        insertChapter(401L, 1, "Top One First", "Top one first chapter excerpt.");
        insertChapter(402L, 1, "Top Two First", "Top two first chapter excerpt.");
        insertChapter(403L, 1, "Top Three First", "Top three first chapter excerpt.");
        insertAnalysisResult(401L, "deconstruct", "Top one analysis notes.");
        insertAnalysisResult(402L, "deconstruct", "Top two analysis notes.");

        mockMvc.perform(post("/internal/knowledge/research-pack/rank")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","category":"Urban Brain","limit":2,"chapterLimitPerBook":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ranks.length()").value(2))
            .andExpect(jsonPath("$.ranks[0].rankNo").value(1))
            .andExpect(jsonPath("$.books.length()").value(2))
            .andExpect(jsonPath("$.books[0].bookName").value("Top One"))
            .andExpect(jsonPath("$.chapters.length()").value(2))
            .andExpect(jsonPath("$.chapters[0].content").value("Top one first chapter excerpt."))
            .andExpect(jsonPath("$.books[1].bookName").value("Top Two"))
            .andExpect(jsonPath("$.chapters[1].content").value("Top two first chapter excerpt."))
            .andExpect(jsonPath("$.analyses.length()").value(2))
            .andExpect(jsonPath("$.analyses[0].content").value("Top one analysis notes."));
    }

    @Test
    void shouldBuildRankResearchPackWithTimeWindowPolicyForInternalWorkerCaller() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "Urban Brain", "Male new book board", 22L);
        insertRank(22L, "male-new", "urban-brain", 1, 703L, "Latest Pack Top One", "Author Latest", "Latest intro");
        insertRankBoardSnapshot(21L, LocalDateTime.now().minusDays(8));
        insertRank(21L, "male-new", "urban-brain", 1, 702L, "Recent Pack Top One", "Author Recent", "Recent intro");
        insertRankBoardSnapshot(20L, LocalDateTime.now().minusDays(50));
        insertRank(20L, "male-new", "urban-brain", 1, 701L, "Too Old Pack Top One", "Author Old", "Too old intro");

        mockMvc.perform(post("/internal/knowledge/research-pack/rank")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","rankNo":1,"freshness":"time_window","allowHistorical":true,"timeWindowDays":30,"limit":10,"chapterLimitPerBook":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ranks.length()").value(2))
            .andExpect(jsonPath("$.ranks[0].bookName").value("Latest Pack Top One"))
            .andExpect(jsonPath("$.ranks[1].bookName").value("Recent Pack Top One"))
            .andExpect(jsonPath("$.books.length()").value(2));
    }

    @Test
    void shouldRefreshRankBoardForInternalWorkerCallerByCategory() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 30L);
        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 10, 20))
            .thenReturn(List.of(rankItem(1, "Fresh Agent Rank", "Agent Author", "https://fanqienovel.com/page/agent-rank")));

        mockMvc.perform(post("/internal/knowledge/rank/refresh")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","category":"都市脑洞","rankFetchCount":10,"refreshMode":"FORCE"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.channelCode").value("male-new"))
            .andExpect(jsonPath("$.boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.reused").value(false))
            .andExpect(jsonPath("$.refreshLimited").value(false));
    }

    @Test
    void shouldDefaultInternalRankRefreshToAutoAndReuseFreshSnapshot() throws Exception {
        insertRankBoardWithSnapshot("fanqie", "male-new", "urban-brain", "都市脑洞", "男频新书榜", 31L);

        mockMvc.perform(post("/internal/knowledge/rank/refresh")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","category":"都市脑洞","rankFetchCount":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.channelCode").value("male-new"))
            .andExpect(jsonPath("$.boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.total").value(30))
            .andExpect(jsonPath("$.reused").value(true))
            .andExpect(jsonPath("$.refreshLimited").value(false));

        verify(pythonCrawlerClient, times(0)).fetchRank("fanqie", "male-new", "urban-brain", 10, 20);
    }

    @Test
    void shouldUpsertAndReadProjectMemoryForInternalWorkerCaller() throws Exception {
        insertProject(900L, 7L, "Urban Brain Project", "ACTIVE");

        mockMvc.perform(post("/internal/knowledge/projects/900/memory")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"memories":{"genre":"urban fantasy","styleConstraints":"no harem"},"sourceTraceId":"trace-1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectId").value(900))
            .andExpect(jsonPath("$.userId").value(7))
            .andExpect(jsonPath("$.memories.genre").value("urban fantasy"))
            .andExpect(jsonPath("$.memories.styleConstraints").value("no harem"));

        mockMvc.perform(post("/internal/knowledge/projects/900/memory")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memories.genre").value("urban fantasy"))
            .andExpect(jsonPath("$.memories.styleConstraints").value("no harem"));
    }

    @Test
    void shouldSearchProjectChaptersForInternalWorkerCaller() throws Exception {
        insertProject(910L, 7L, "Project Knowledge Novel", "ACTIVE");
        insertProjectWork(920L, 910L, 7L, "诸天外包特效师");
        insertProjectChapter(930L, 910L, 920L, 7L, 12, "御剑交付", "洛风用真正的御剑轨迹完成仙侠特效。", "hash-a");
        insertProjectChapter(931L, 910L, 920L, 7L, 13, "魔法交付", "魔法导师完成火球术粒子效果。", "hash-b");
        insertProject(911L, 8L, "Other Novel", "ACTIVE");
        insertProjectWork(921L, 911L, 8L, "Other Work");
        insertProjectChapter(932L, 911L, 921L, 8L, 1, "Wrong User", "御剑线索不该被查到。", "hash-c");

        mockMvc.perform(post("/internal/knowledge/projects/chapters/search")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":910,"workId":920,"query":"御剑","limit":5}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].projectId").value(910))
            .andExpect(jsonPath("$[0].workId").value(920))
            .andExpect(jsonPath("$[0].chapterNo").value(12))
            .andExpect(jsonPath("$[0].title").value("御剑交付"))
            .andExpect(jsonPath("$[0].content").value("洛风用真正的御剑轨迹完成仙侠特效。"));
    }

    @Test
    void shouldResolveOwnedProjectWorkForInternalWorkerCaller() throws Exception {
        insertProject(914L, 7L, "Project Knowledge Novel", "ACTIVE");
        insertProjectWork(924L, 914L, 7L, "Myriad Outsourcing Effects");
        insertProjectWork(925L, 914L, 7L, "Myriad Outsourcing Sequel");
        insertProject(915L, 8L, "Other User Project", "ACTIVE");
        insertProjectWork(926L, 915L, 8L, "Myriad Outsourcing Effects");

        mockMvc.perform(post("/internal/knowledge/projects/resolve")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"query":"Myriad Outsourcing Effects","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("resolved"))
            .andExpect(jsonPath("$.projectId").value(914))
            .andExpect(jsonPath("$.workId").value(924));

        mockMvc.perform(post("/internal/knowledge/projects/resolve")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"query":"Myriad Outsourcing","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ambiguous"))
            .andExpect(jsonPath("$.candidates.length()").value(2));
    }

    @Test
    void shouldLookupStructuredProjectKnowledgeForInternalWorkerCaller() throws Exception {
        insertProject(912L, 7L, "Structured Project", "ACTIVE");
        insertProjectWork(922L, 912L, 7L, "诸天外包特效师");
        jdbcTemplate.update("""
                insert into ai_project_foreshadowing(foreshadowing_id, user_id, project_id, work_id, title, content,
                    status, planted_chapter_no, importance, confidence)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            940L, 7L, 912L, 922L, "月球背面管理员信号", "第30章出现一次异常后台提示。", "OPEN", 30, "HIGH", 0.92);
        jdbcTemplate.update("""
                insert into ai_project_timeline_event(event_id, user_id, project_id, work_id, chapter_no,
                    event_order, title, summary, confidence)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            941L, 7L, 912L, 922L, 30, 1, "后台信号出现", "系统提示有新管理员接入。", 0.91);
        jdbcTemplate.update("""
                insert into ai_project_character_state(state_id, user_id, project_id, work_id, character_name,
                    chapter_no, state_summary, motivation, confidence)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            942L, 7L, 912L, 922L, "林舟", 30, "开始怀疑平台不是单机系统。", "保护工作室", 0.9);
        jdbcTemplate.update("""
                insert into ai_project_world_rule(rule_id, user_id, project_id, work_id, rule_type, title,
                    content, first_chapter_no, confidence)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            943L, 7L, 912L, 922L, "system", "三端一体结算规则", "接收端、执行端、结算端必须闭环。", 1, 0.95);

        mockMvc.perform(post("/internal/knowledge/projects/foreshadowings/list")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":912,"workId":922,"status":"OPEN","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("月球背面管理员信号"))
            .andExpect(jsonPath("$[0].status").value("OPEN"));

        mockMvc.perform(post("/internal/knowledge/projects/timeline/lookup")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":912,"workId":922,"query":"后台","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("后台信号出现"));

        mockMvc.perform(post("/internal/knowledge/projects/character-states/lookup")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":912,"workId":922,"query":"林舟","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].characterName").value("林舟"))
            .andExpect(jsonPath("$[0].stateSummary").value("开始怀疑平台不是单机系统。"));

        mockMvc.perform(post("/internal/knowledge/projects/world-rules/lookup")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":912,"workId":922,"query":"结算","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("三端一体结算规则"));
    }

    @Test
    void shouldRejectProjectMemoryForWrongUser() throws Exception {
        insertProject(901L, 7L, "Scoped Project", "ACTIVE");

        mockMvc.perform(post("/internal/knowledge/projects/901/memory")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":8,"memories":{"genre":"wrong user"},"sourceTraceId":"trace-2"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void shouldCreatePromoteAndSearchScopedMemoryForInternalWorkerCaller() throws Exception {
        String candidateResponse = mockMvc.perform(post("/internal/knowledge/memory/candidates")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":900,"conversationId":"conv-1","scope":"project","memoryType":"fact","content":"three-terminal setting","summary":"project setting","confidence":0.88,"sourceTraceId":"trace-1","ttlDays":30}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Number candidateId = com.jayway.jsonpath.JsonPath.read(candidateResponse, "$.id");

        mockMvc.perform(post("/internal/knowledge/memory/candidates/" + candidateId.longValue() + "/promote")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("confirmed"))
            .andExpect(jsonPath("$.content").value("three-terminal setting"));

        mockMvc.perform(post("/internal/knowledge/memory/search")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":900,"scope":"project","limit":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].content").value("three-terminal setting"));
    }

    @Test
    void shouldUpdateAndReadConversationSummaryForInternalWorkerCaller() throws Exception {
        mockMvc.perform(post("/internal/knowledge/conversation-summary")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"projectId":900,"conversationId":"conv-1","summary":"User is building an urban brain-hole project.","sourceTraceId":"trace-2"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary").value("User is building an urban brain-hole project."))
            .andExpect(jsonPath("$.sourceTraceId").value("trace-2"));

        mockMvc.perform(post("/internal/knowledge/conversation-summary/read")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":7,"conversationId":"conv-1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectId").value(900))
            .andExpect(jsonPath("$.summary").value("User is building an urban brain-hole project."));
    }

    private void insertBook(String platform,
                            String platformBookId,
                            String bookName,
                            String author,
                            String intro,
                            String bookUrl) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform, platformBookId, bookName, author, intro, bookUrl,
            Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
    }

    private long insertIndexedKnowledge() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(id, platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1L, "fanqie", "internal-101", "Internal Book", "Author I", "Intro I", "https://fanqienovel.com/page/internal-101",
            Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
        jdbcTemplate.update(
            "INSERT INTO knowledge_document(id, source_type, source_ref_id, platform, book_id, title, status, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            1L, "CHAPTER", 1L, "fanqie", 1L, "Chapter 1", "INDEXED", 0
        );
        jdbcTemplate.update(
            "INSERT INTO knowledge_chunk(id, document_id, chunk_key, source_type, source_ref_id, book_id, chapter_no, content_hash, chunk_text, token_count, vector_status, qdrant_point_id, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            1L, 1L, "chapter-1-1", "CHAPTER", 1L, 1L, 1, "hash-internal", "hero goal appears in chapter one", 20, "INDEXED", "chunk-point-internal", 0
        );
        return 1L;
    }

    private ExternalBookSearchItem searchItem(String platformBookId,
                                              String bookName,
                                              String author,
                                              String intro,
                                              String bookUrl) {
        ExternalBookSearchItem item = new ExternalBookSearchItem();
        item.setPlatformBookId(platformBookId);
        item.setBookName(bookName);
        item.setAuthor(author);
        item.setIntro(intro);
        item.setBookUrl(bookUrl);
        return item;
    }

    private ExternalRankItem rankItem(int rankNo, String bookName, String author, String url) {
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(rankNo);
        item.setBookName(bookName);
        item.setAuthor(author);
        item.setIntro("intro-" + bookName);
        item.setBookUrl(url);
        item.setPlatformBookId("pid-" + rankNo);
        return item;
    }

    private void insertChapter(long bookId, int chapterNo, String chapterTitle, String content) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO crawl_chapter(platform, book_id, chapter_no, chapter_title, content, word_count, source_word_count, crawl_time, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "fanqie",
            bookId,
            chapterNo,
            chapterTitle,
            content,
            content.length(),
            content.length(),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private void insertProject(long projectId, long userId, String name, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO ai_project(project_id, user_id, name, description, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            projectId,
            userId,
            name,
            "Test project",
            status,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now)
        );
    }

    private void insertProjectWork(long workId, long projectId, long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO ai_project_work(work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            workId,
            userId,
            projectId,
            title,
            null,
            "都市脑洞",
            "ACTIVE",
            Timestamp.valueOf(now),
            Timestamp.valueOf(now)
        );
    }

    private void insertProjectChapter(long chapterId,
                                      long projectId,
                                      long workId,
                                      long userId,
                                      int chapterNo,
                                      String title,
                                      String content,
                                      String contentHash) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO ai_project_chapter(chapter_id, user_id, project_id, work_id, chapter_no, title,
                    content, content_hash, word_count, source_type, version, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            chapterId,
            userId,
            projectId,
            workId,
            chapterNo,
            title,
            content,
            contentHash,
            content.length(),
            "upload",
            1,
            "ACTIVE",
            Timestamp.valueOf(now),
            Timestamp.valueOf(now)
        );
    }

    private void insertAnalysisResult(long bookId, String analysisType, String content) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO analysis_result(user_id, platform, book_id, analysis_type, chapter_count, model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            "fanqie",
            bookId,
            analysisType,
            3,
            "test-model",
            content,
            "{\"summary\":\"Latest analysis content\"}",
            100,
            1000L,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private void insertRankBoardWithSnapshot(String platform,
                                             String channelCode,
                                             String boardCode,
                                             String boardName,
                                             String description,
                                             long snapshotId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO rank_board(id, platform, channel_code, board_code, board_name, description, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            1L,
            platform,
            channelCode,
            boardCode,
            boardName,
            description,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        insertRankBoardSnapshot(snapshotId, now);
    }

    private void insertRankBoardSnapshot(long snapshotId, LocalDateTime snapshotTime) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            """
                INSERT INTO rank_snapshot(id, rank_board_id, snapshot_time, record_count, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            snapshotId,
            1L,
            Timestamp.valueOf(snapshotTime),
            30,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private void insertRank(long snapshotId,
                            String channelCode,
                            String boardCode,
                            int rankNo,
                            long bookId,
                            String bookName,
                            String author,
                            String intro) {
        LocalDateTime now = LocalDateTime.now();
        Integer existingBookCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crawl_book WHERE id = ?",
            Integer.class,
            bookId
        );
        if (existingBookCount == null || existingBookCount == 0) {
            jdbcTemplate.update(
                """
                    INSERT INTO crawl_book(id, platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                bookId,
                "fanqie",
                String.valueOf(bookId),
                bookName,
                author,
                intro,
                "https://fanqienovel.com/page/" + bookId,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                0
            );
        }
        jdbcTemplate.update(
            """
                INSERT INTO crawl_rank(platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "fanqie",
            "都市脑洞",
            channelCode,
            boardCode,
            snapshotId,
            rankNo,
            bookId,
            bookName,
            "https://fanqienovel.com/page/" + bookId,
            author,
            intro,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }
}
