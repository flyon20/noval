package com.novelanalyzer.modules.knowledge;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
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
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgecontrollerdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
                    {"query":"主角目标是什么","bookId":%d,"platform":"fanqie","limit":3}
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
            .andExpect(jsonPath("$.data.jobKey").value("book:1"));

        verify(knowledgeIndexJobExecutor).submitAndExecute(1L, 1L);
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
}
