package com.novelanalyzer.modules.knowledge;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgechatdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=never",
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
        "classpath:sql/phase7-knowledge-schema-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class KnowledgeChatServiceTest {

    private static final String ADMIN_PHONE = "15599316908";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private LangGraphWorkerClient langGraphWorkerClient;

    @MockBean
    private KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;

    @MockBean
    private CrawlerService crawlerService;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private QdrantClient qdrantClient;

    @BeforeEach
    void prepareState() {
        jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = 1", ADMIN_PHONE);
        RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldReturnCandidateResponseFromWorkerForBookNameQuestion() throws Exception {
        KnowledgeChatResponseVO.CandidateVO candidate = new KnowledgeChatResponseVO.CandidateVO();
        candidate.setBookId(101L);
        candidate.setPlatform("fanqie");
        candidate.setPlatformBookId("101");
        candidate.setBookName("Book Alpha");
        candidate.setAuthor("Author A");
        candidate.setIntro("Intro A");
        candidate.setLocal(false);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("candidates_required");
        workerResponse.setAnswer("请选择正确作品后继续。");
        workerResponse.setCandidates(List.of(candidate));
        workerResponse.setActions(List.of("select_candidate"));
        workerResponse.setResultJson(Map.of("status", "candidates_required"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"分析《Book Alpha》的开篇卖点","bookName":"Book Alpha"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("candidates_required"))
            .andExpect(jsonPath("$.data.candidates.length()").value(1))
            .andExpect(jsonPath("$.data.candidates[0].bookName").value("Book Alpha"))
            .andExpect(jsonPath("$.data.actions[0]").value("select_candidate"));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(langGraphWorkerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("question", "分析《Book Alpha》的开篇卖点");
        assertThat(payloadCaptor.getValue()).containsEntry("bookName", "Book Alpha");
        assertThat(payloadCaptor.getValue()).containsEntry("userId", 1L);
        verify(knowledgeIndexJobExecutor, never()).submitAndExecute(any(), any());
    }

    @Test
    void shouldSubmitIndexJobWhenSelectedCandidateNeedsIndexing() throws Exception {
        AsyncJobSubmitResponse jobResponse = new AsyncJobSubmitResponse();
        jobResponse.setJobId(88L);
        jobResponse.setJobType("KNOWLEDGE_INDEX_BOOK");
        jobResponse.setJobKey("book:101");
        jobResponse.setStatus("PENDING");
        when(knowledgeIndexJobExecutor.submitAndExecute(101L, 1L)).thenReturn(jobResponse);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("insufficient_evidence");
        workerResponse.setAnswer("证据不足：需要先索引。");
        workerResponse.setActions(List.of("index_book"));
        workerResponse.setResultJson(Map.of("status", "insufficient_evidence"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"继续分析","selectedCandidate":{"bookId":101,"platform":"fanqie","platformBookId":"101","bookName":"Book Alpha","local":false}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("insufficient_evidence"))
            .andExpect(jsonPath("$.data.actions[0]").value("index_book"))
            .andExpect(jsonPath("$.data.resultJson.indexJob.jobId").value(88));

        verify(knowledgeIndexJobExecutor).submitAndExecute(101L, 1L);
    }

    @Test
    void shouldCompleteAndIndexExternalSelectedCandidateBeforeWorkerAnalysis() throws Exception {
        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-202",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-202",
            3
        )).thenReturn(202L);

        AsyncJobSubmitResponse jobResponse = new AsyncJobSubmitResponse();
        jobResponse.setJobId(99L);
        jobResponse.setJobType("KNOWLEDGE_INDEX_BOOK");
        jobResponse.setJobKey("book:202");
        jobResponse.setStatus("RUNNING");
        when(knowledgeIndexJobExecutor.submitAndExecuteBlocking(202L, 1L)).thenReturn(jobResponse);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("开篇卖点来自目标和冲突。[1]");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of("status", "answered"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"继续分析","selectedCandidate":{"platform":"fanqie","platformBookId":"ext-202","bookName":"External Book","author":"Author E","intro":"External intro","bookUrl":"https://fanqienovel.com/page/ext-202","local":false}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultJson.localBookId").value(202))
            .andExpect(jsonPath("$.data.resultJson.indexJob.jobId").value(99));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(langGraphWorkerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("bookId", 202L);
        assertThat(payloadCaptor.getValue()).containsEntry("bookName", "External Book");
        assertThat(payloadCaptor.getValue()).doesNotContainKey("selectedCandidate");
        verify(crawlerService).completeExternalBookCandidate(
            "fanqie",
            "ext-202",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-202",
            3
        );
        verify(knowledgeIndexJobExecutor).submitAndExecuteBlocking(202L, 1L);
        InOrder inOrder = inOrder(crawlerService, langGraphWorkerClient, knowledgeIndexJobExecutor);
        inOrder.verify(crawlerService).completeExternalBookCandidate(
            eq("fanqie"),
            eq("ext-202"),
            eq("External Book"),
            eq("Author E"),
            eq("External intro"),
            eq("https://fanqienovel.com/page/ext-202"),
            eq(3)
        );
        inOrder.verify(knowledgeIndexJobExecutor).submitAndExecuteBlocking(202L, 1L);
        inOrder.verify(langGraphWorkerClient).runKnowledgeChat(any());
    }

    @Test
    void shouldClampSelectedCandidateCompletionToTenChaptersBeforeWorkerAnalysis() throws Exception {
        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-404",
            "Long Book",
            "Author L",
            "Long intro",
            "https://fanqienovel.com/page/ext-404",
            10
        )).thenReturn(404L);

        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("分析完成[1]");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of("status", "answered"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"继续分析","limits":{"chapterCount":99},"selectedCandidate":{"platform":"fanqie","platformBookId":"ext-404","bookName":"Long Book","author":"Author L","intro":"Long intro","bookUrl":"https://fanqienovel.com/page/ext-404","local":false}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("answered"))
            .andExpect(jsonPath("$.data.resultJson.localBookId").value(404));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(langGraphWorkerClient).runKnowledgeChat(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("bookId", 404L);
        assertThat(payloadCaptor.getValue()).containsEntry("bookName", "Long Book");
        verify(crawlerService).completeExternalBookCandidate(
            "fanqie",
            "ext-404",
            "Long Book",
            "Author L",
            "Long intro",
            "https://fanqienovel.com/page/ext-404",
            10
        );
        verify(knowledgeIndexJobExecutor).submitAndExecuteBlocking(404L, 1L);
    }

    @Test
    void shouldCompleteAndIndexExternalCandidateBeforeWorkerEvenWhenWorkerCanAnswer() throws Exception {
        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-303",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-303",
            3
        )).thenReturn(303L);
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("已有证据可回答。");
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of("status", "answered"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"继续分析","selectedCandidate":{"platform":"fanqie","platformBookId":"ext-303","bookName":"External Book","author":"Author E","intro":"External intro","bookUrl":"https://fanqienovel.com/page/ext-303","local":false}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("answered"));

        verify(crawlerService).completeExternalBookCandidate(
            "fanqie",
            "ext-303",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-303",
            3
        );
        verify(knowledgeIndexJobExecutor).submitAndExecuteBlocking(303L, 1L);
    }

    @Test
    void shouldReturnFinalAnswerWithSources() throws Exception {
        KnowledgeChatResponseVO.SourceVO source = new KnowledgeChatResponseVO.SourceVO();
        source.setChunkId(1L);
        source.setBookId(101L);
        source.setBookName("Book Alpha");
        source.setTitle("第一章");
        source.setPreview("主角目标明确。");
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("answered");
        workerResponse.setAnswer("开篇卖点来自目标和冲突。[1]");
        workerResponse.setSources(List.of(source));
        workerResponse.setActions(List.of());
        workerResponse.setResultJson(Map.of("status", "answered", "sourceCount", 1));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"Book Alpha 的爽点是什么？","bookId":101,"bookName":"Book Alpha"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("answered"))
            .andExpect(jsonPath("$.data.answer").value("开篇卖点来自目标和冲突。[1]"))
            .andExpect(jsonPath("$.data.sources.length()").value(1))
            .andExpect(jsonPath("$.data.sources[0].title").value("第一章"));
    }

    @Test
    void shouldReturnBadGatewayWhenCandidateContinuationFails() throws Exception {
        KnowledgeChatResponseVO workerResponse = new KnowledgeChatResponseVO();
        workerResponse.setStatus("insufficient_evidence");
        workerResponse.setAnswer("need index first");
        workerResponse.setActions(List.of("index_book"));
        workerResponse.setResultJson(Map.of("status", "insufficient_evidence"));
        when(langGraphWorkerClient.runKnowledgeChat(any())).thenReturn(workerResponse);
        when(crawlerService.completeExternalBookCandidate(
            "fanqie",
            "ext-500",
            "External Book",
            "Author E",
            "External intro",
            "https://fanqienovel.com/page/ext-500",
            3
        )).thenThrow(new IllegalStateException("qdrant point upsert failed"));

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/knowledge/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question":"continue","selectedCandidate":{"platform":"fanqie","platformBookId":"ext-500","bookName":"External Book","author":"Author E","intro":"External intro","bookUrl":"https://fanqienovel.com/page/ext-500","local":false}}
                    """))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.message").value("knowledge candidate continuation failed"));
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
}
