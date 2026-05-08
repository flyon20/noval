package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookSearchItem;
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
import static org.mockito.Mockito.when;
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
        RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
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
}
