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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
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
        "app.knowledge.index.queue-enabled=false",
        "app.knowledge.index.rank-incremental-enabled=false",
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
        request.setUserId(7L);
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
        assertThat(results.get(0).getRetrievalBackend()).isEqualTo("qdrant");
        verify(embeddingClient).embed("hero goal");
        verify(qdrantClient).ensureCollection();
    }

    @Test
    void shouldEnsureCollectionBeforeSearchingQdrant() {
        when(embeddingClient.embed("test query")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(any(), eq(Map.of()), eq(3))).thenReturn(List.of());

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("test query");
        request.setLimit(3);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).isEmpty();
        verify(qdrantClient).ensureCollection();
    }

    @Test
    void shouldFallbackToCrawledChaptersWhenChapterVectorSearchReturnsEmptyForBook() {
        long bookId = insertBook();
        insertCrawledChapter(bookId, 1, "第一章 直播曝光", "妹妹直播时拍到主角收藏的古物，弹幕质疑主角真实身份。");
        insertCrawledChapter(bookId, 2, "第二章 热搜爆发", "专家认出传国玉玺和恐龙蛋，全球网友开始追问长生秘密。");
        when(embeddingClient.embed("前三章 金手指 钩子")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(
            eq(List.of(0.1, 0.2, 0.3)),
            eq(Map.of("bookId", bookId, "platform", "fanqie", "sourceType", "CHAPTER")),
            eq(5)
        )).thenReturn(List.of());

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("前三章 金手指 钩子");
        request.setBookId(bookId);
        request.setPlatform("fanqie");
        request.setSourceType("CHAPTER");
        request.setLimit(5);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getBookId()).isEqualTo(bookId);
        assertThat(results.get(0).getSourceType()).isEqualTo("CHAPTER");
        assertThat(results.get(0).getChapterNo()).isEqualTo(1);
        assertThat(results.get(0).getRetrievalBackend()).isEqualTo("crawler_fallback");
        assertThat(results.get(0).getTitle()).isEqualTo("第一章 直播曝光");
        assertThat(results.get(0).getPreview()).contains("妹妹直播");
        assertThat(results.get(1).getChapterNo()).isEqualTo(2);
    }

    @Test
    void shouldExcludeOwnerlessAnalysisChunksFromLexicalFallback() {
        long bookId = insertBook();
        long documentId = insertDocument(bookId, "Urban Brainhole Trend", "ANALYSIS", 2002L);
        long chunkId = insertChunk(
            documentId,
            bookId,
            "lexical-point-1",
            "analysis-1",
            "ANALYSIS",
            2002L,
            null,
            "trend",
            "Life simulator counterattack topic has strong feedback and opening hooks."
        );
        when(embeddingClient.embed("life simulator counterattack")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(
            eq(List.of(0.1, 0.2, 0.3)),
            eq(Map.of("bookId", bookId, "platform", "fanqie", "sourceType", "ANALYSIS", "analysisType", "trend")),
            eq(5)
        )).thenReturn(List.of());

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("life simulator counterattack");
        request.setBookId(bookId);
        request.setPlatform("fanqie");
        request.setSourceType("ANALYSIS");
        request.setAnalysisType("trend");
        request.setLimit(5);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldExcludeOwnerlessAnalysisChunksBeforeEmbeddingFallback() {
        long bookId = insertBook();
        long documentId = insertDocument(bookId, "Simulator Outline", "ANALYSIS", 2003L);
        long chunkId = insertChunk(
            documentId,
            bookId,
            "lexical-point-embedding-failed",
            "analysis-embedding-failed",
            "ANALYSIS",
            2003L,
            null,
            "trend",
            "Simulator counterattack outline evidence remains searchable without vector embedding."
        );
        when(embeddingClient.embed("simulator counterattack outline"))
            .thenThrow(new RuntimeException("embedding provider unavailable"));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("simulator counterattack outline");
        request.setBookId(bookId);
        request.setPlatform("fanqie");
        request.setSourceType("ANALYSIS");
        request.setAnalysisType("trend");
        request.setLimit(5);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).isEmpty();
        verify(embeddingClient, never()).embed(any());
        verify(qdrantClient, never()).ensureCollection();
        verify(qdrantClient, never()).search(any(), any(), any(Integer.class));
    }

    @Test
    void shouldExcludeOwnerlessAnalysisChunksReturnedByQdrantAndLexicalSearch() {
        long bookId = insertBook();
        long documentId = insertDocument(bookId, "Other Tenant Analysis", "ANALYSIS", 2004L);
        long chunkId = insertChunk(
            documentId,
            bookId,
            "analysis-point-other-tenant",
            "analysis-other-tenant",
            "ANALYSIS",
            2004L,
            null,
            "trend",
            "private tenant analysis secret"
        );
        when(embeddingClient.embed("private tenant analysis secret")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(any(), eq(Map.of("bookId", bookId)), eq(5)))
            .thenReturn(List.of(new QdrantClient.SearchResult(
                "analysis-point-other-tenant",
                0.95,
                Map.of("chunkId", chunkId, "sourceType", "CHAPTER")
            )));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("private tenant analysis secret");
        request.setBookId(bookId);
        request.setLimit(5);

        assertThat(knowledgeRetrievalService.search(request)).isEmpty();
    }

    @Test
    void shouldRejectMissingTrustedUserScopeBeforeRetrieval() {
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery("opening hook");

        assertThatThrownBy(() -> knowledgeRetrievalService.search(request))
            .hasMessageContaining("user scope required");
    }

    @Test
    void shouldFilterQdrantResultsBelowRequestedMinScore() {
        long bookId = insertBook();
        long documentId = insertDocument(bookId);
        long strongChunkId = insertChunk(documentId, bookId, "chunk-point-strong", "chapter-1-1");
        long weakChunkId = insertChunk(documentId, bookId, "chunk-point-weak", "chapter-1-2");
        when(embeddingClient.embed("trend signal")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(any(), eq(Map.of()), eq(5))).thenReturn(List.of(
            new QdrantClient.SearchResult("chunk-point-strong", 0.82, Map.of("chunkId", strongChunkId)),
            new QdrantClient.SearchResult("chunk-point-weak", 0.41, Map.of("chunkId", weakChunkId))
        ));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("trend signal");
        request.setLimit(5);
        request.setMinScore(0.6);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(strongChunkId);
        assertThat(results.get(0).getScore()).isEqualTo(0.82);
    }

    @Test
    void shouldApplyDefaultMinScoreWhenRequestDoesNotSpecifyOne() {
        long bookId = insertBook();
        long documentId = insertDocument(bookId);
        long strongChunkId = insertChunk(documentId, bookId, "chunk-point-default-strong", "chapter-1-strong");
        long weakChunkId = insertChunk(documentId, bookId, "chunk-point-default-weak", "chapter-1-weak");
        when(embeddingClient.embed("default threshold query")).thenReturn(List.of(0.1, 0.2, 0.3));
        doNothing().when(qdrantClient).ensureCollection();
        when(qdrantClient.search(any(), eq(Map.of()), eq(5))).thenReturn(List.of(
            new QdrantClient.SearchResult("chunk-point-default-strong", 0.52, Map.of("chunkId", strongChunkId)),
            new QdrantClient.SearchResult("chunk-point-default-weak", 0.19, Map.of("chunkId", weakChunkId))
        ));

        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setUserId(7L);
        request.setQuery("default threshold query");
        request.setLimit(5);

        var results = knowledgeRetrievalService.search(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(strongChunkId);
        assertThat(results.get(0).getScore()).isEqualTo(0.52);
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
        return insertDocument(bookId, "Chapter 1", "CHAPTER", 1001L);
    }

    private long insertDocument(long bookId, String title, String sourceType, long sourceRefId) {
        jdbcTemplate.update(
            "INSERT INTO knowledge_document(source_type, source_ref_id, platform, book_id, title, status, deleted) VALUES (?, ?, ?, ?, ?, ?, ?)",
            sourceType, sourceRefId, "fanqie", bookId, title, "INDEXED", 0
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM knowledge_document WHERE book_id = ? AND source_type = ? AND source_ref_id = ?",
            Long.class,
            bookId,
            sourceType,
            sourceRefId
        );
    }

    private long insertChunk(long documentId, long bookId, String pointId) {
        return insertChunk(documentId, bookId, pointId, "chapter-1-1");
    }

    private long insertChunk(long documentId, long bookId, String pointId, String chunkKey) {
        return insertChunk(
            documentId,
            bookId,
            pointId,
            chunkKey,
            "CHAPTER",
            1001L,
            1,
            "deconstruct",
            "Book: Retrieval Test Book\nhero goal appears in chapter one."
        );
    }

    private long insertChunk(long documentId,
                             long bookId,
                             String pointId,
                             String chunkKey,
                             String sourceType,
                             Long sourceRefId,
                             Integer chapterNo,
                             String analysisType,
                             String chunkText) {
        jdbcTemplate.update(
            "INSERT INTO knowledge_chunk(document_id, chunk_key, source_type, source_ref_id, book_id, chapter_no, analysis_type, content_hash, chunk_text, token_count, vector_status, qdrant_point_id, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            documentId,
            chunkKey,
            sourceType,
            sourceRefId,
            bookId,
            chapterNo,
            analysisType,
            "hash-" + chunkKey,
            chunkText,
            20,
            "INDEXED",
            pointId,
            0
        );
        return jdbcTemplate.queryForObject("SELECT id FROM knowledge_chunk WHERE qdrant_point_id = ?", Long.class, pointId);
    }

    private void insertCrawledChapter(long bookId, int chapterNo, String title, String content) {
        jdbcTemplate.update(
            "INSERT INTO crawl_chapter(platform, book_id, chapter_no, chapter_title, content, word_count, source_word_count, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie",
            bookId,
            chapterNo,
            title,
            content,
            content.length(),
            content.length(),
            0
        );
    }
}
