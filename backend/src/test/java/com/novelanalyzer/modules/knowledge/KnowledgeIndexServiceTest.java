package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexService;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledgeindexdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
        "spring.sql.init.mode=never",
        "app.auth.jwt-secret=test-jwt-secret-with-enough-length-1234567890",
        "app.crawler.internal-api-key=crawler-internal-api-key-with-enough-length-1234567890",
        "app.ai.langgraph-worker.internal-api-key=langgraph-internal-key-with-enough-length-1234567890",
        "app.knowledge.index.max-chapters=2",
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
class KnowledgeIndexServiceTest {

    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private QdrantClient qdrantClient;

    @Test
    void shouldIndexBookIntroAndChaptersWithIdempotentContentHashesAndChapterCap() {
        long bookId = insertBook();
        insertChapter(bookId, 1, "第一章 开局", "主角醒来，发现世界已经变化。第一段提供人物目标。第二段给出冲突。");
        insertChapter(bookId, 2, "第二章 选择", "主角做出选择，代价逐渐浮现。配角提出反对意见，矛盾升级。");
        insertChapter(bookId, 3, "第三章 不应索引", "这一章超过 max-chapters 限制，不应该被索引。");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        KnowledgeIndexService.IndexResult first = knowledgeIndexService.indexBook(bookId);
        KnowledgeIndexService.IndexResult second = knowledgeIndexService.indexBook(bookId);

        assertThat(first.createdChunks()).isEqualTo(3);
        assertThat(first.indexedChunks()).isEqualTo(3);
        assertThat(second.createdChunks()).isZero();
        assertThat(second.indexedChunks()).isZero();
        Integer documentCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM knowledge_document WHERE book_id = ?", Integer.class, bookId);
        Integer chunkCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM knowledge_chunk WHERE book_id = ?", Integer.class, bookId);
        Integer thirdChapterChunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM knowledge_chunk WHERE book_id = ? AND chapter_no = 3",
            Integer.class,
            bookId
        );
        assertThat(documentCount).isEqualTo(3);
        assertThat(chunkCount).isEqualTo(3);
        assertThat(thirdChapterChunkCount).isZero();
        verify(embeddingClient, times(3)).embed(anyString());
        verify(qdrantClient, times(3)).upsertPoint(anyString(), any(), anyMap());
    }

    @Test
    void shouldIndexAnalysisResultAndSubmitDedupedBookIndexJob() {
        long bookId = insertBook();
        long analysisId = insertAnalysisResult(bookId);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        KnowledgeIndexService.IndexResult analysisResult = knowledgeIndexService.indexAnalysisResult(analysisId);
        AsyncJobSubmitResponse firstJob = knowledgeIndexService.submitBookIndexJob(bookId, 7L);
        AsyncJobSubmitResponse secondJob = knowledgeIndexService.submitBookIndexJob(bookId, 7L);

        assertThat(analysisResult.createdChunks()).isEqualTo(1);
        assertThat(analysisResult.indexedChunks()).isEqualTo(1);
        Integer analysisChunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM knowledge_chunk WHERE source_type = ? AND source_ref_id = ?",
            Integer.class,
            "ANALYSIS",
            analysisId
        );
        assertThat(analysisChunkCount).isEqualTo(1);
        assertThat(firstJob.getJobType()).isEqualTo("KNOWLEDGE_INDEX_BOOK");
        assertThat(firstJob.getReused()).isFalse();
        assertThat(secondJob.getReused()).isTrue();
        verify(embeddingClient, times(1)).embed(anyString());
        verify(qdrantClient, times(1)).upsertPoint(anyString(), any(), anyMap());
    }

    @Test
    void shouldReusePendingChunkWhenRetryingAfterPartialIndexFailure() {
        long bookId = insertBook();
        long documentId = insertKnowledgeDocument(bookId, "INTRO", bookId, "intro");
        insertPendingChunk(documentId, bookId, "intro-1");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(bookId);

        Integer chunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM knowledge_chunk WHERE document_id = ? AND chunk_key = ?",
            Integer.class,
            documentId,
            "intro-1"
        );
        String vectorStatus = jdbcTemplate.queryForObject(
            "SELECT vector_status FROM knowledge_chunk WHERE document_id = ? AND chunk_key = ?",
            String.class,
            documentId,
            "intro-1"
        );

        assertThat(result.createdChunks()).isZero();
        assertThat(result.indexedChunks()).isEqualTo(1);
        assertThat(chunkCount).isEqualTo(1);
        assertThat(vectorStatus).isEqualTo("INDEXED");
        verify(embeddingClient, times(1)).embed(anyString());
        verify(qdrantClient, times(1)).upsertPoint(anyString(), any(), anyMap());
    }

    @Test
    void shouldSplitLongChapterByParagraphsWithOverlap() {
        long bookId = insertBook();
        String firstParagraph = "第一段介绍主角目标。" + "目标明确且冲突逐步抬升。".repeat(35);
        String secondParagraph = "第二段展示对手压力。" + "对手不断逼迫主角做选择。".repeat(35);
        String thirdParagraph = "第三段给出章节钩子。" + "结尾留下下一章必须解决的问题。".repeat(35);
        insertChapter(bookId, 1, "第一章 长章节", firstParagraph + "\n\n" + secondParagraph + "\n\n" + thirdParagraph);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(bookId);

        Integer chapterChunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM knowledge_chunk WHERE book_id = ? AND source_type = 'CHAPTER' AND chapter_no = 1",
            Integer.class,
            bookId
        );
        List<String> chunkKeys = jdbcTemplate.queryForList(
            "SELECT chunk_key FROM knowledge_chunk WHERE book_id = ? AND source_type = 'CHAPTER' ORDER BY chunk_key",
            String.class,
            bookId
        );
        List<String> chunkTexts = jdbcTemplate.queryForList(
            "SELECT chunk_text FROM knowledge_chunk WHERE book_id = ? AND source_type = 'CHAPTER' ORDER BY id",
            String.class,
            bookId
        );

        assertThat(result.createdChunks()).isGreaterThan(2);
        assertThat(chapterChunkCount).isGreaterThanOrEqualTo(2);
        assertThat(chunkKeys).contains("chapter-1-1", "chapter-1-2");
        assertThat(chunkTexts.get(0)).contains("第一段介绍主角目标");
        assertThat(chunkTexts).anySatisfy(text -> assertThat(text).contains("第二段展示对手压力"));
        assertThat(chunkTexts).anySatisfy(text -> assertThat(text).contains("目标明确且冲突逐步抬升"));
        verify(embeddingClient, times(result.indexedChunks())).embed(anyString());
    }

    @Test
    void shouldSplitLongChineseParagraphNearSentenceBoundary() {
        long bookId = insertBook();
        String firstSentence = "第一句给出主角目标和开篇危机。" + "目标逐步升级。".repeat(78);
        String secondSentence = "第二句承接上一段冲突并安排新的代价。" + "代价继续扩大。".repeat(78);
        insertChapter(bookId, 1, "第一章 单段长文", firstSentence + secondSentence);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        knowledgeIndexService.indexBook(bookId);

        List<String> chunkTexts = jdbcTemplate.queryForList(
            "SELECT chunk_text FROM knowledge_chunk WHERE book_id = ? AND source_type = 'CHAPTER' ORDER BY id",
            String.class,
            bookId
        );

        assertThat(chunkTexts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunkTexts.get(0)).contains("第一句给出主角目标和开篇危机");
        assertThat(chunkTexts.get(0)).doesNotEndWith("目标逐");
        assertThat(chunkTexts.get(0).trim()).endsWith("。");
        assertThat(chunkTexts.get(1)).contains("代价继续扩大");
    }

    @Test
    void shouldEmbedReadableChineseMetadataHeaderWithoutMojibake() {
        long bookId = insertReadableBook();
        insertChapter(bookId, 1, "第一章 开局钩子", "主角获得明确目标，并在第一章遇到直接冲突。");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));

        knowledgeIndexService.indexBook(bookId);

        verify(embeddingClient).embed(argThat(text ->
            text.contains("书名：测试小说")
                && text.contains("作者：作者A")
                && text.contains("来源：fanqie")
                && text.contains("类型：INTRO")
                && !text.contains("涔﹀悕")
                && !text.contains("浣滆€")
        ));
        verify(embeddingClient).embed(argThat(text ->
            text.contains("章节：第1章 第一章 开局钩子")
                && text.contains("类型：CHAPTER")
                && !text.contains("绔犺妭")
        ));
    }

    private long insertBook() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie",
            "book-101",
            "测试小说",
            "作者A",
            "这是一本用于索引测试的小说简介，包含题材、主角目标和核心卖点。",
            "https://fanqienovel.com/page/book-101",
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        Long id = jdbcTemplate.queryForObject("SELECT id FROM crawl_book WHERE platform_book_id = ?", Long.class, "book-101");
        assertThat(id).isNotNull();
        return id;
    }

    private long insertReadableBook() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie",
            "readable-book-101",
            "测试小说",
            "作者A",
            "这是一本用于测试向量 header 的小说简介。",
            "https://fanqienovel.com/page/readable-book-101",
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        Long id = jdbcTemplate.queryForObject("SELECT id FROM crawl_book WHERE platform_book_id = ?", Long.class, "readable-book-101");
        assertThat(id).isNotNull();
        return id;
    }

    private void insertChapter(long bookId, int chapterNo, String title, String content) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_chapter(platform, book_id, chapter_no, chapter_title, content, word_count, source_word_count, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie",
            bookId,
            chapterNo,
            title,
            content,
            content.length(),
            content.length(),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }

    private long insertAnalysisResult(long bookId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO analysis_result(user_id, platform, book_id, analysis_type, chapter_count, model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            7L,
            "fanqie",
            bookId,
            "deconstruct",
            2,
            "test-model",
            "分析结果：开篇目标清晰，冲突出现较早，适合继续扩写。",
            "{\"summary\":\"开篇目标清晰\"}",
            128,
            3000L,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        Long id = jdbcTemplate.queryForObject("SELECT id FROM analysis_result WHERE book_id = ?", Long.class, bookId);
        assertThat(id).isNotNull();
        return id;
    }

    private long insertKnowledgeDocument(long bookId, String sourceType, long sourceRefId, String title) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO knowledge_document(source_type, source_ref_id, platform, book_id, title, status, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sourceType,
            sourceRefId,
            "fanqie",
            bookId,
            title,
            "INDEXED",
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM knowledge_document WHERE book_id = ? AND source_type = ? AND source_ref_id = ?",
            Long.class,
            bookId,
            sourceType,
            sourceRefId
        );
        assertThat(id).isNotNull();
        return id;
    }

    private void insertPendingChunk(long documentId, long bookId, String chunkKey) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO knowledge_chunk(document_id, chunk_key, source_type, source_ref_id, book_id, content_hash, chunk_text, token_count, vector_status, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            documentId,
            chunkKey,
            "INTRO",
            bookId,
            bookId,
            "old-hash",
            "old text",
            1,
            "PENDING",
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            0
        );
    }
}
