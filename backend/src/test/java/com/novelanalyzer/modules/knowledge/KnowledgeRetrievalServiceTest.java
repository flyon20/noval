package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgeretrievaldb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=never",
        "app.auth.jwt-secret=test-jwt-secret-with-enough-length-1234567890",
        "app.crawler.internal-api-key=crawler-internal-api-key-with-enough-length-1234567890",
        "app.ai.langgraph-worker.internal-api-key=langgraph-internal-key-with-enough-length-1234567890",
        "app.knowledge.embedding.api-key=test-embedding-key"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase7-knowledge-schema-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class KnowledgeRetrievalServiceTest {

    @Autowired
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private QdrantClient qdrantClient;

    @Test
    void shouldEmbedQuerySearchQdrantWithFiltersAndHydrateChunkSources() {
        long bookId = insertBook();
        long documentId = insertDocument(bookId);
        long chunkId = insertChunk(documentId, bookId, "chunk-point-1");
        when(embeddingClient.embed("hero goal")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(
            eq(List.of(0.1, 0.2, 0.3)),
            eq(Map.of(
                "bookId", bookId,
                "platform", "fanqie",
                "sourceType", "CHAPTER",
                "chapterNo", 1,
                "analysisType", "deconstruct"
            )),
            eq(5)
        )).thenReturn(List.of(new QdrantClient.SearchResult("chunk-point-1", 0.93, Map.of("chunkId", chunkId))));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("hero goal");
        request.setBookId(bookId);
        request.setPlatform("fanqie");
        request.setSourceType("CHAPTER");
        request.setChapterNo(1);
        request.setAnalysisType("deconstruct");
        request.setLimit(5);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(chunkId);
        assertThat(results.get(0).getScore()).isEqualTo(0.93);
        assertThat(results.get(0).getBookName()).isEqualTo("Retrieval Test Book");
        assertThat(results.get(0).getChapterNo()).isEqualTo(1);
        assertThat(results.get(0).getPreview()).contains("hero goal appears");
        verify(embeddingClient).embed("hero goal");
        verify(qdrantClient).ensureCollection();
    }

    @Test
    void shouldEnsureCollectionBeforeSearchingQdrant() {
        when(embeddingClient.embed("test query")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(any(), eq(Map.of()), eq(3))).thenReturn(List.of());

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("test query");
        request.setLimit(3);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).isEmpty();
        verify(qdrantClient).ensureCollection();
    }

    private long insertBook() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie", "retrieval-101", "Retrieval Test Book", "Author R", "Intro R", "https://fanqienovel.com/page/retrieval-101",
            Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
        return jdbcTemplate.queryForObject("SELECT id FROM crawl_book WHERE platform_book_id = ?", Long.class, "retrieval-101");
    }

    private long insertDocument(long bookId) {
        jdbcTemplate.update(
            "INSERT INTO knowledge_document(source_type, source_ref_id, platform, book_id, title, status, deleted) VALUES (?, ?, ?, ?, ?, ?, ?)",
            "CHAPTER", 1001L, "fanqie", bookId, "Chapter 1", "INDEXED", 0
        );
        return jdbcTemplate.queryForObject("SELECT id FROM knowledge_document WHERE book_id = ?", Long.class, bookId);
    }

    private long insertChunk(long documentId, long bookId, String pointId) {
        jdbcTemplate.update(
            "INSERT INTO knowledge_chunk(document_id, chunk_key, source_type, source_ref_id, book_id, chapter_no, analysis_type, content_hash, chunk_text, token_count, vector_status, qdrant_point_id, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            documentId,
            "chapter-1-1",
            "CHAPTER",
            1001L,
            bookId,
            1,
            "deconstruct",
            "hash-1",
            "Book: Retrieval Test Book\nhero goal appears in chapter one.",
            20,
            "INDEXED",
            pointId,
            0
        );
        return jdbcTemplate.queryForObject("SELECT id FROM knowledge_chunk WHERE qdrant_point_id = ?", Long.class, pointId);
    }
}
