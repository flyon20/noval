package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectRetrievalRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectRetrievalService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.vo.ProjectRetrievalResultVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import com.novelanalyzer.modules.knowledge.vo.StoryGraphResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeProjectRetrievalServiceTest {

    @Test
    void shouldUseOnlyActiveGenerationWithoutUnindexedFulltextFallback() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Long activeGeneration = activeGeneration(jdbc);
        Long chapterId = activeChapter(jdbc);
        jdbc.update(
            "insert into ai_project_ingest_generation(user_id, project_id, work_id, chapter_id, chapter_no, chapter_version, content_hash, parser_version, status) values(7, 900, 1, ?, 1, 1, 'retired-hash', 'test', 'RETIRED')",
            chapterId
        );
        Long retiredGeneration = jdbc.queryForObject("select max(generation_id) from ai_project_ingest_generation", Long.class);
        jdbc.update(
            "insert into ai_project_search_document(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, document_type, document_key, title, aliases, content, content_hash, confidence, status) values(7, 900, 1, ?, ?, 1, 'FACT', 'retired-fact', 'retired', '|signal|', 'retired signal must stay hidden', 'retired-hash', 0.8, 'ACTIVE')",
            chapterId, retiredGeneration
        );
        KnowledgeProjectWorkService workService = scopedWorkService();
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, workService, null, null, new KnowledgeStoryGraphService(jdbc)
        );

        ProjectRetrievalResultVO result = service.retrieve(request("signal"));

        assertThat(result.getEvidence()).isNotEmpty();
        assertThat(result.getEvidence())
            .extracting(item -> String.valueOf(item.get("generationId")))
            .contains(String.valueOf(activeGeneration))
            .doesNotContain(String.valueOf(retiredGeneration));
        assertThat(result.getEvidence())
            .extracting(item -> String.valueOf(item.get("preview")))
            .noneMatch(preview -> preview.contains("retired signal"));
        assertThat(result.getEvidence())
            .extracting(item -> String.valueOf(item.get("backend")))
            .containsOnly("structured");
        assertThat(result.getGaps()).contains("fulltext_unavailable", "vector_unavailable");
        verify(workService).findOwnedWorkPublic(900L, 1L, 7L);
    }

    @Test
    void shouldHonorTypedChannelsFiltersWeightsRerankAndGraphBudget() throws Exception {
        JdbcTemplate jdbc = jdbc();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        KnowledgeStoryGraphService storyGraphService = mock(KnowledgeStoryGraphService.class);
        when(storyGraphService.traverse(anyLong(), anyLong(), anyLong(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(new StoryGraphResultVO());
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, scopedWorkService(), embeddingClient, qdrantClient, storyGraphService
        );
        ProjectRetrievalRequest weighted = request("signal");
        weighted.setChannels(List.of("structured"));
        weighted.setFilters(Map.of("chapterFrom", 1, "chapterTo", 1));
        weighted.setWeights(Map.of("structured", 0.5d));
        weighted.setRerankPolicy("intent_aware");

        ProjectRetrievalResultVO weightedResult = service.retrieve(weighted);

        assertThat(weightedResult.getEvidence()).hasSize(1);
        assertThat(weightedResult.getEvidence().get(0)).containsEntry("backend", "structured");
        assertThat((Double) weightedResult.getEvidence().get(0).get("score")).isEqualTo(0.4625d);
        assertThat(weightedResult.getEvidence().get(0)).containsEntry("channelRank", 1);
        assertThat(weightedResult.getGaps()).isEmpty();
        assertThat(weightedResult.getDiagnostics()).containsEntry("requestedChannels", List.of("structured"));
        verifyNoInteractions(embeddingClient, qdrantClient, storyGraphService);

        ProjectRetrievalRequest rawScore = request("signal");
        rawScore.setChannels(List.of("structured"));
        rawScore.setWeights(Map.of("structured", 0.0d));
        rawScore.setRerankPolicy("raw_score");
        ProjectRetrievalResultVO rawResult = service.retrieve(rawScore);
        assertThat((Double) rawResult.getEvidence().get(0).get("score")).isEqualTo(0.90d);

        ProjectRetrievalRequest graph = request("signal");
        graph.setChannels(List.of("graph"));
        graph.setGraphBudgetMillis(123);
        graph.setTimeoutMillis(2_000);
        service.retrieve(graph);
        verify(storyGraphService).traverse(eq(7L), eq(900L), eq(1L), anyList(), eq(false), eq(123));
    }

    @Test
    void shouldStopBeforeChannelsWhenOverallDeadlineExpires() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectWorkService workService = scopedWorkService();
        doAnswer(invocation -> {
            Thread.sleep(20L);
            return new ProjectWorkVO();
        }).when(workService).findOwnedWorkPublic(900L, 1L, 7L);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        KnowledgeStoryGraphService storyGraphService = mock(KnowledgeStoryGraphService.class);
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, workService, embeddingClient, qdrantClient, storyGraphService
        );
        ProjectRetrievalRequest request = request("signal");
        request.setChannels(List.of("vector", "graph"));
        request.setTimeoutMillis(5);

        ProjectRetrievalResultVO result = service.retrieve(request);

        assertThat(result.getEvidence()).isEmpty();
        assertThat(result.getGaps()).containsExactly("retrieval_timeout");
        verifyNoInteractions(embeddingClient, qdrantClient, storyGraphService);
    }

    @Test
    void shouldFailClosedWhenQdrantPayloadMissesGenerationScope() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Long generationId = activeGeneration(jdbc);
        Long chapterId = activeChapter(jdbc);
        jdbc.update(
            "insert into ai_project_vector_chunk(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, source_type, source_id, content_hash, qdrant_point_id, chunk_text, visibility) values(7, 900, 1, ?, ?, 1, 'ACTIVE', 'chapter', ?, 'vector-hash', 'point-1', 'signal vector evidence', 'private')",
            chapterId, generationId, chapterId
        );
        Long chunkId = jdbc.queryForObject("select max(id) from ai_project_vector_chunk", Long.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed("signal\nred omen\nchapter 12 payoff")).thenReturn(List.of(0.1d, 0.2d));
        when(qdrantClient.searchWithAnyMatch(anyList(), anyMap(), any(), anyList(), anyInt())).thenReturn(List.of(
            new QdrantClient.SearchResult("point-1", 0.91d, Map.of(
                "user_id", 7L, "project_id", 900L, "work_id", 1L, "visibility", "private",
                "project_vector_chunk_id", chunkId, "chapter_id", chapterId,
                "generation_id", generationId, "chapter_version", 1L
            ))
        ));
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, scopedWorkService(), embeddingClient, qdrantClient, new KnowledgeStoryGraphService(jdbc)
        );

        ProjectRetrievalResultVO visible = service.retrieve(request("signal"));

        assertThat(visible.getEvidence()).anySatisfy(item -> assertThat(item.get("backend")).isEqualTo("qdrant"));
        assertThat(visible.getGaps()).doesNotContain("vector_unavailable");

        when(qdrantClient.searchWithAnyMatch(anyList(), anyMap(), any(), anyList(), anyInt())).thenReturn(List.of(
            new QdrantClient.SearchResult("point-1", 0.91d, Map.of(
                "user_id", 7L, "project_id", 900L, "work_id", 1L, "visibility", "private",
                "project_vector_chunk_id", chunkId, "chapter_id", chapterId, "chapter_version", 1L
            ))
        ));
        ProjectRetrievalResultVO rejected = service.retrieve(request("signal"));
        assertThat(rejected.getEvidence()).noneSatisfy(item -> assertThat(item.get("backend")).isEqualTo("qdrant"));
        assertThat(rejected.getGaps()).contains("vector_scope_rejected");
    }

    @Test
    void shouldPreserveDistinctVectorEvidenceAndReportReturnedChannels() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Long generationId = activeGeneration(jdbc);
        Long chapterId = activeChapter(jdbc);
        for (int index = 1; index <= 3; index++) {
            jdbc.update(
                "insert into ai_project_search_document(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, document_type, document_key, title, aliases, content, content_hash, confidence, status) values(7, 900, 1, ?, ?, 1, 'FACT', ?, ?, ?, ?, ?, 0.9, 'ACTIVE')",
                chapterId, generationId, "signal-fact-" + index, "signal fact " + index,
                "|signal-fact-" + index + "|", "structured signal " + index, "structured-hash-" + index
            );
        }
        jdbc.update(
            "insert into ai_project_vector_chunk(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, source_type, source_id, content_hash, qdrant_point_id, chunk_text, visibility) values(7, 900, 1, ?, ?, 1, 'ACTIVE', 'chapter', ?, 'vector-distinct-hash', 'point-distinct', 'semantic vector-only signal', 'private')",
            chapterId, generationId, chapterId
        );
        Long chunkId = jdbc.queryForObject("select max(id) from ai_project_vector_chunk", Long.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed("signal")).thenReturn(List.of(0.1d, 0.2d));
        when(qdrantClient.searchWithAnyMatch(anyList(), anyMap(), any(), anyList(), anyInt())).thenReturn(List.of(
            new QdrantClient.SearchResult("point-distinct", 0.99d, Map.of(
                "user_id", 7L, "project_id", 900L, "work_id", 1L, "visibility", "private",
                "project_vector_chunk_id", chunkId, "chapter_id", chapterId,
                "generation_id", generationId, "chapter_version", 1L
            ))
        ));
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, scopedWorkService(), embeddingClient, qdrantClient, new KnowledgeStoryGraphService(jdbc)
        );
        ProjectRetrievalRequest request = request("signal");
        request.setChannels(List.of("structured", "vector"));
        request.setEntities(List.of("red omen", "chapter 12 payoff"));
        request.setLimit(2);

        ProjectRetrievalResultVO result = service.retrieve(request);

        assertThat(result.getEvidence()).hasSize(2);
        assertThat(result.getEvidence()).extracting(item -> item.get("backend"))
            .containsExactlyInAnyOrder("structured", "qdrant");
        assertThat(result.getEvidence()).filteredOn(item -> "qdrant".equals(item.get("backend")))
            .allSatisfy(item -> assertThat(item).containsEntry("channel", "vector"));
        assertThat(result.getDiagnostics()).containsEntry(
            "returnedChannels", Map.of("structured", 1, "vector", 1)
        );
        assertThat(result.getDiagnostics()).containsEntry(
            "channelStatus", Map.of("structured", "used", "vector", "used")
        );
        assertThat(result.getDiagnostics()).containsEntry("vectorQueryAugmented", true);
        verify(embeddingClient).embed("signal\nred omen\nchapter 12 payoff");
    }

    @Test
    void shouldRetrieveActiveNonProseDocumentSectionsWithoutChapterBinding() throws Exception {
        JdbcTemplate jdbc = jdbc();
        runScript(jdbc, resource("sql/phase29-project-document-batch-h2.sql"));
        jdbc.update(
            "insert into ai_project_document_batch(user_id, project_id, work_id, idempotency_key, manifest_hash, parser_version, status, stage) "
                + "values(7, 900, 1, 'outline-batch', 'manifest', 'test', 'READY', 'ready')"
        );
        Long batchId = jdbc.queryForObject("select max(batch_id) from ai_project_document_batch", Long.class);
        jdbc.update(
            "insert into ai_project_document_file(batch_id, user_id, project_id, work_id, relative_path, original_name, size_bytes, content_hash, status, content_blob) "
                + "values(?, 7, 900, 1, 'materials/outline.md', 'outline.md', 12, 'file-hash', 'ACTIVE', X'01')",
            batchId
        );
        Long fileId = jdbc.queryForObject("select max(file_id) from ai_project_document_file", Long.class);
        jdbc.update(
            "insert into ai_project_document(batch_id, file_id, user_id, project_id, work_id, document_kind, relative_path, title, content_hash, normalized_content, status) "
                + "values(?, ?, 7, 900, 1, 'OUTLINE', 'materials/outline.md', 'Volume outline', 'document-hash', 'hidden contract outline', 'ACTIVE')",
            batchId, fileId
        );
        Long sourceDocumentId = jdbc.queryForObject("select max(document_id) from ai_project_document", Long.class);
        jdbc.update(
            "insert into ai_project_document_generation(document_id, batch_id, user_id, project_id, work_id, parser_version, content_hash, status, section_count, indexed_section_count) "
                + "values(?, ?, 7, 900, 1, 'test', 'generation-hash', 'ACTIVE', 1, 1)",
            sourceDocumentId, batchId
        );
        Long documentGenerationId = jdbc.queryForObject(
            "select max(document_generation_id) from ai_project_document_generation", Long.class
        );
        jdbc.update("update ai_project_document set active_generation_id = ? where document_id = ?",
            documentGenerationId, sourceDocumentId);
        jdbc.update(
            "insert into ai_project_document_section(document_id, document_generation_id, user_id, project_id, work_id, section_ordinal, title, section_kind, start_offset, end_offset, content_hash, content, status) "
                + "values(?, ?, 7, 900, 1, 1, 'Hidden contract', 'OUTLINE', 0, 23, 'section-hash', 'hidden contract outline', 'ACTIVE')",
            sourceDocumentId, documentGenerationId
        );
        Long sectionId = jdbc.queryForObject("select max(section_id) from ai_project_document_section", Long.class);
        jdbc.update(
            "insert into ai_project_search_document(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, source_id, document_type, document_key, title, aliases, content, content_hash, confidence, status, source_document_id, document_generation_id, section_id) "
                + "values(7, 900, 1, null, null, null, ?, 'OUTLINE', ?, 'Hidden contract', '|hidden contract|', 'hidden contract outline', 'search-hash', 1.0, 'ACTIVE', ?, ?, ?)",
            sectionId, "document-section:" + sectionId, sourceDocumentId, documentGenerationId, sectionId
        );
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, scopedWorkService(), null, null, new KnowledgeStoryGraphService(jdbc)
        );
        ProjectRetrievalRequest request = request("Hidden contract");
        request.setChannels(List.of("structured"));

        ProjectRetrievalResultVO result = service.retrieve(request);

        assertThat(result.getEvidence()).anySatisfy(item -> assertThat(item)
            .containsEntry("sourceType", "OUTLINE")
            .containsEntry("sourceDocumentId", sourceDocumentId)
            .containsEntry("documentGenerationId", documentGenerationId)
            .containsEntry("sectionId", sectionId));
    }

    @Test
    void shouldReserveOneActiveRepresentativePerRequestedChapter() throws Exception {
        JdbcTemplate jdbc = jdbc();
        addActiveChapters(jdbc, 2, 10);
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, scopedWorkService(), null, null, new KnowledgeStoryGraphService(jdbc)
        );
        ProjectRetrievalRequest request = request("review the opening ten chapters");
        request.setChannels(List.of("structured"));
        request.setChapterFrom(1);
        request.setChapterTo(10);
        request.setLimit(10);

        ProjectRetrievalResultVO result = service.retrieve(request);

        assertThat(result.getEvidence())
            .extracting(item -> ((Number) item.get("chapterNo")).intValue())
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(result.getEvidence()).allSatisfy(item -> assertThat(item)
            .containsEntry("coverageRepresentative", true)
            .containsEntry("backend", "structured"));
        assertThat(result.isPartial()).isFalse();
        assertThat(result.getDiagnostics())
            .containsEntry("coveragePolicy", "chapter_balanced")
            .containsEntry("requestedChapterCount", 10L)
            .containsEntry("coveredChapters", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
            .containsEntry("missingChapters", List.of());
    }

    @Test
    void shouldReportPartialCoverageWhenEvidenceLimitIsSmallerThanRange() throws Exception {
        JdbcTemplate jdbc = jdbc();
        addActiveChapters(jdbc, 2, 10);
        KnowledgeProjectRetrievalService service = new KnowledgeProjectRetrievalService(
            jdbc, scopedWorkService(), null, null, new KnowledgeStoryGraphService(jdbc)
        );
        ProjectRetrievalRequest request = request("review the opening ten chapters");
        request.setChannels(List.of("structured"));
        request.setChapterFrom(1);
        request.setChapterTo(10);
        request.setLimit(4);

        ProjectRetrievalResultVO result = service.retrieve(request);

        assertThat(result.getEvidence())
            .extracting(item -> ((Number) item.get("chapterNo")).intValue())
            .containsExactly(1, 2, 3, 4);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.getGaps()).contains("chapter_coverage_incomplete");
        assertThat(result.getDiagnostics())
            .containsEntry("coveredChapters", List.of(1, 2, 3, 4))
            .containsEntry("missingChapters", List.of(5, 6, 7, 8, 9, 10))
            .containsEntry("missingChapterCount", 6L);
    }

    private KnowledgeProjectWorkService scopedWorkService() {
        KnowledgeProjectWorkService workService = mock(KnowledgeProjectWorkService.class);
        ProjectWorkVO work = new ProjectWorkVO();
        work.setWorkId(1L);
        work.setProjectId(900L);
        work.setUserId(7L);
        when(workService.findOwnedWorkPublic(900L, 1L, 7L)).thenReturn(work);
        return workService;
    }

    private ProjectRetrievalRequest request(String query) {
        ProjectRetrievalRequest request = new ProjectRetrievalRequest();
        request.setUserId(7L);
        request.setProjectId(900L);
        request.setWorkId(1L);
        request.setQuery(query);
        request.setIntent("chapter_recall");
        request.setLimit(10);
        return request;
    }

    private JdbcTemplate jdbc() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:project_retrieval_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        runScript(jdbc, resource("sql/phase16-project-knowledge-rag-h2.sql"));
        jdbc.update("insert into ai_project_work(user_id, project_id, title, status) values(7, 900, 'work', 'ACTIVE')");
        jdbc.update("insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status) values(7, 900, 1, 1, 'signal chapter', 'signal appears in the active chapter', 'active-hash', 35, 'upload', 1, 'ACTIVE')");
        runScript(jdbc, resource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        runScript(jdbc, resource("sql/phase29-project-document-batch-h2.sql"));
        return jdbc;
    }

    private Long activeGeneration(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select active_generation_id from ai_project_chapter_head where user_id = 7 and project_id = 900 and work_id = 1", Long.class);
    }

    private Long activeChapter(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select active_chapter_id from ai_project_chapter_head where user_id = 7 and project_id = 900 and work_id = 1", Long.class);
    }

    private void addActiveChapters(JdbcTemplate jdbc, int from, int to) {
        for (int chapterNo = from; chapterNo <= to; chapterNo++) {
            String hash = "active-hash-" + chapterNo;
            jdbc.update(
                "insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status) values(7, 900, 1, ?, ?, ?, ?, 35, 'upload', 1, 'ACTIVE')",
                chapterNo, "chapter " + chapterNo, "chapter " + chapterNo + " active content", hash
            );
            Long chapterId = jdbc.queryForObject("select max(chapter_id) from ai_project_chapter", Long.class);
            jdbc.update(
                "insert into ai_project_ingest_generation(user_id, project_id, work_id, chapter_id, chapter_no, chapter_version, content_hash, parser_version, status) values(7, 900, 1, ?, ?, 1, ?, 'test', 'ACTIVE')",
                chapterId, chapterNo, hash
            );
            Long generationId = jdbc.queryForObject("select max(generation_id) from ai_project_ingest_generation", Long.class);
            jdbc.update(
                "insert into ai_project_chapter_head(user_id, project_id, work_id, chapter_no, active_chapter_id, active_generation_id, optimistic_version) values(7, 900, 1, ?, ?, ?, 0)",
                chapterNo, chapterId, generationId
            );
            jdbc.update(
                "insert into ai_project_search_document(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, document_type, document_key, title, aliases, content, content_hash, confidence, status) values(7, 900, 1, ?, ?, 1, 'CHAPTER', ?, ?, ?, ?, ?, 1.0, 'ACTIVE')",
                chapterId, generationId, "chapter:" + chapterId, "chapter " + chapterNo,
                "|chapter " + chapterNo + "|", "chapter " + chapterNo + " active content", hash
            );
        }
    }

    private Path resource(String path) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("src/test/resources").resolve(path),
            Path.of("backend/src/test/resources").resolve(path),
            Path.of("..", "src/test/resources").resolve(path)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        var url = getClass().getClassLoader().getResource(path);
        if (url != null) {
            return Path.of(url.toURI());
        }
        throw new java.nio.file.NoSuchFileException(path);
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        for (String stmt : sql.split(";")) {
            String executable = stmt.lines().filter(line -> !line.trim().startsWith("--")).reduce("", (left, right) -> left + "\n" + right).trim();
            if (!executable.isEmpty()) {
                jdbc.execute(executable);
            }
        }
    }
}
