package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.ProjectDocumentQuestionAnswerRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentIndexService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentBatchVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectDocumentBatchServiceTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeProjectWorkService workService;
    private KnowledgeProjectDocumentBatchQueueService queueService;
    private KnowledgeProjectDocumentIndexService documentIndexService;
    private KnowledgeProperties properties;
    private KnowledgeProjectDocumentBatchService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:document_batch_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        runScript("sql/phase16-project-knowledge-rag-h2.sql");
        runScript("sql/phase24-project-ingest-generation-h2.sql");
        runScript("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql");
        runScript("sql/phase29-project-document-batch-h2.sql");
        workService = mock(KnowledgeProjectWorkService.class);
        queueService = mock(KnowledgeProjectDocumentBatchQueueService.class);
        when(queueService.publish(anyLong(), any(Integer.class))).thenReturn(false);
        KnowledgeProjectIngestService ingestService = mock(KnowledgeProjectIngestService.class);
        properties = new KnowledgeProperties();
        properties.getDocumentBatch().setQueueEnabled(false);
        when(workService.findOwnedWorkPublic(anyLong(), anyLong(), anyLong())).thenReturn(null);
        documentIndexService = mock(KnowledgeProjectDocumentIndexService.class);
        service = new KnowledgeProjectDocumentBatchService(
            jdbcTemplate,
            workService,
            ingestService,
            queueService,
            new ProjectDocumentParser(),
            documentIndexService,
            properties
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", "test-session", java.util.Set.of("USER")));
    }

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void storesManifestAndReturnsSameBatchForIdempotentReplay() {
        MockMultipartFile file = file("chapter-1.md", "# 第一章\n正文");

        ProjectDocumentBatchVO first = service.create(11L, 22L, List.of(file), List.of("novel/chapter-1.md"), null, "batch-1");
        ProjectDocumentBatchVO replay = service.create(11L, 22L, List.of(file), List.of("novel/chapter-1.md"), null, "batch-1");

        assertThat(replay.getBatchId()).isEqualTo(first.getBatchId());
        assertThat(first.getStatus()).isEqualTo(KnowledgeProjectDocumentBatchService.STORED);
        assertThat(first.getTotalFiles()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_file", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select length(content_blob) from ai_project_document_file", Integer.class)).isGreaterThan(0);
    }

    @Test
    void rejectsPathTraversalAndUnsupportedBinaryFilesBeforePersistence() {
        assertThatThrownBy(() -> service.create(
            11L, 22L, List.of(file("chapter.md", "ok")), List.of("../chapter.md"), null, null
        )).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(
            11L, 22L, List.of(file("chapter.pdf", "not supported")), List.of("chapter.pdf"), null, null
        )).isInstanceOf(BusinessException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_batch", Integer.class)).isZero();
    }

    @Test
    void rejectsManifestReuseWithDifferentContent() {
        service.create(11L, 22L, List.of(file("chapter.md", "one")), List.of("chapter.md"), null, "batch-1");

        assertThatThrownBy(() -> service.create(
            11L, 22L, List.of(file("chapter.md", "two")), List.of("chapter.md"), null, "batch-1"
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void retryingFailedDocumentIndexOnlyResetsStateForBackgroundProgression() {
        ProjectDocumentBatchVO batch = service.create(
            11L,
            22L,
            List.of(file("outline.md", "# Macro outline\nThe protagonist discovers the hidden contract.")),
            List.of("materials/outline.md"),
            "OUTLINE",
            "retry-document-index"
        );
        long batchId = batch.getBatchId();
        jdbcTemplate.update(
            "insert into ai_project_document(document_id, batch_id, file_id, user_id, project_id, work_id, "
                + "document_kind, relative_path, content_hash, normalized_content, status) "
                + "select 100, batch_id, file_id, user_id, project_id, work_id, 'OUTLINE', relative_path, "
                + "content_hash, 'outline', 'PARSED_PENDING_INDEX' from ai_project_document_file where batch_id = ?",
            batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_generation(document_generation_id, document_id, batch_id, user_id, "
                + "project_id, work_id, parser_version, content_hash, status, section_count) "
                + "values(200, 100, ?, 7, 11, 22, 'test', 'hash', 'PARSED_PENDING_INDEX', 1)",
            batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_section(document_id, document_generation_id, user_id, project_id, "
                + "work_id, section_ordinal, section_kind, start_offset, end_offset, content_hash, content, status) "
                + "values(100, 200, 7, 11, 22, 1, 'OUTLINE', 0, 7, 'hash', 'outline', 'INDEX_FAILED')"
        );
        jdbcTemplate.update(
            "update ai_project_document_batch set status = 'RETRYABLE_FAILED', stage = 'index_failed' "
                + "where batch_id = ?",
            batchId
        );

        ProjectDocumentBatchVO retried = service.retry(11L, batchId);

        assertThat(retried.getStatus()).isEqualTo(KnowledgeProjectDocumentBatchService.PARSED_PENDING_INDEX);
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_project_document_section where document_generation_id = 200", String.class
        )).isEqualTo(KnowledgeProjectDocumentBatchService.PARSED_PENDING_INDEX);
        verify(documentIndexService, never()).indexPendingSections(any(), any(Integer.class));
    }

    @Test
    void storesConfirmedDocumentKindAsAValidJsonString() {
        ProjectDocumentBatchVO batch = createWaitingBatch("confirm-json");
        long questionId = insertPendingQuestion(batch.getBatchId());
        ProjectDocumentQuestionAnswerRequest request = new ProjectDocumentQuestionAnswerRequest();
        request.setAnswer("OUTLINE");

        service.answerQuestion(11L, batch.getBatchId(), questionId, request);

        assertThat(jdbcTemplate.queryForObject(
            "select answer_json from ai_project_document_question where question_id = ?",
            String.class,
            questionId
        )).isEqualTo("\"OUTLINE\"");
        assertThat(service.get(11L, batch.getBatchId()).getStatus())
            .isEqualTo(KnowledgeProjectDocumentBatchService.STORED);
    }

    @Test
    void rejectsConfirmationWhenBatchIsNoLongerWaiting() {
        ProjectDocumentBatchVO batch = createWaitingBatch("confirm-cancelled");
        long questionId = insertPendingQuestion(batch.getBatchId());
        jdbcTemplate.update(
            "update ai_project_document_batch set status = 'CANCELLED', stage = 'cancelled' where batch_id = ?",
            batch.getBatchId()
        );
        ProjectDocumentQuestionAnswerRequest request = new ProjectDocumentQuestionAnswerRequest();
        request.setAnswer("OUTLINE");

        assertThatThrownBy(() -> service.answerQuestion(11L, batch.getBatchId(), questionId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not waiting for confirmation");
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_project_document_question where question_id = ?", String.class, questionId
        )).isEqualTo("PENDING");
    }

    @Test
    void cancellationDiscardsPendingQuestionsAndClearsCounter() {
        ProjectDocumentBatchVO batch = createWaitingBatch("cancel-questions");
        long questionId = insertPendingQuestion(batch.getBatchId());

        ProjectDocumentBatchVO cancelled = service.cancel(11L, batch.getBatchId());

        assertThat(cancelled.getStatus()).isEqualTo(KnowledgeProjectDocumentBatchService.CANCELLED);
        assertThat(cancelled.getPendingQuestions()).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_project_document_question where question_id = ?", String.class, questionId
        )).isEqualTo("DISCARDED");
    }

    @Test
    void discardsOnlyCancelledUnindexedDraftRowsAndConvergesOnRepeat() {
        ProjectDocumentBatchVO batch = service.create(
            11L, 22L, List.of(file("outline.md", "# Outline")), List.of("outline.md"), "OUTLINE", "discard-draft"
        );
        long batchId = batch.getBatchId();
        long fileId = jdbcTemplate.queryForObject(
            "select file_id from ai_project_document_file where batch_id = ?", Long.class, batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document(document_id, batch_id, file_id, user_id, project_id, work_id, "
                + "document_kind, relative_path, content_hash, normalized_content, status) "
                + "values(300, ?, ?, 7, 11, 22, 'OUTLINE', 'outline.md', 'hash', 'outline', 'WAITING_CONFIRMATION')",
            batchId, fileId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_generation(document_generation_id, document_id, batch_id, user_id, "
                + "project_id, work_id, parser_version, content_hash, status, section_count) "
                + "values(400, 300, ?, 7, 11, 22, 'test', 'hash', 'WAITING_CONFIRMATION', 1)",
            batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_section(document_id, document_generation_id, user_id, project_id, "
                + "work_id, section_ordinal, section_kind, start_offset, end_offset, content_hash, content, status) "
                + "values(300, 400, 7, 11, 22, 1, 'OUTLINE', 0, 7, 'hash', 'outline', 'PARSED_PENDING_INDEX')"
        );
        jdbcTemplate.update(
            "update ai_project_document_batch set status = 'CANCELLED', stage = 'cancelled' where batch_id = ?",
            batchId
        );

        service.discard(11L, batchId);

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_batch where batch_id = ?", Integer.class, batchId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_file where batch_id = ?", Integer.class, batchId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document where batch_id = ?", Integer.class, batchId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_generation where batch_id = ?", Integer.class, batchId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section where document_generation_id = 400", Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_batch_outbox where batch_id = ?", Integer.class, batchId
        )).isZero();
        assertThatCode(() -> service.discard(11L, batchId)).doesNotThrowAnyException();
    }

    @Test
    void refusesToDiscardCancelledBatchWithSubmittedKnowledge() {
        ProjectDocumentBatchVO batch = service.create(
            11L, 22L, List.of(file("chapter.md", "# Chapter")), List.of("chapter.md"), "NOVEL_TEXT", "keep-indexed"
        );
        long batchId = batch.getBatchId();
        long fileId = jdbcTemplate.queryForObject(
            "select file_id from ai_project_document_file where batch_id = ?", Long.class, batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document(document_id, batch_id, file_id, user_id, project_id, work_id, "
                + "document_kind, relative_path, content_hash, normalized_content, status) "
                + "values(500, ?, ?, 7, 11, 22, 'NOVEL_TEXT', 'chapter.md', 'hash', 'chapter', 'WAITING_CONFIRMATION')",
            batchId, fileId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_generation(document_generation_id, document_id, batch_id, user_id, "
                + "project_id, work_id, parser_version, content_hash, status, section_count) "
                + "values(600, 500, ?, 7, 11, 22, 'test', 'hash', 'WAITING_CONFIRMATION', 1)",
            batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_section(document_id, document_generation_id, user_id, project_id, "
                + "work_id, section_ordinal, section_kind, start_offset, end_offset, content_hash, content, "
                + "ingest_job_id, status) "
                + "values(500, 600, 7, 11, 22, 1, 'NOVEL_TEXT', 0, 7, 'hash', 'chapter', 999, 'INDEXING')"
        );
        jdbcTemplate.update(
            "update ai_project_document_batch set status = 'CANCELLED', stage = 'cancelled' where batch_id = ?",
            batchId
        );

        assertThatThrownBy(() -> service.discard(11L, batchId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("indexed knowledge");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_batch where batch_id = ?", Integer.class, batchId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section where document_generation_id = 600", Integer.class
        )).isEqualTo(1);
    }

    private ProjectDocumentBatchVO createWaitingBatch(String key) {
        ProjectDocumentBatchVO batch = service.create(
            11L, 22L, List.of(file("outline.md", "# Outline")), List.of("outline.md"), "AUTO", key
        );
        jdbcTemplate.update(
            "update ai_project_document_batch set status = 'WAITING_CONFIRMATION', stage = 'waiting_confirmation', "
                + "progress = 70, pending_questions = 1 where batch_id = ?",
            batch.getBatchId()
        );
        return service.get(11L, batch.getBatchId());
    }

    private long insertPendingQuestion(long batchId) {
        long fileId = jdbcTemplate.queryForObject(
            "select file_id from ai_project_document_file where batch_id = ?", Long.class, batchId
        );
        jdbcTemplate.update(
            "insert into ai_project_document_question(batch_id, file_id, user_id, project_id, work_id, "
                + "question_type, prompt, options_json, status) "
                + "values(?, ?, 7, 11, 22, 'DOCUMENT_KIND', 'confirm type', '[\"OUTLINE\",\"REFERENCE\"]', 'PENDING')",
            batchId, fileId
        );
        return jdbcTemplate.queryForObject(
            "select question_id from ai_project_document_question where batch_id = ? order by question_id desc limit 1",
            Long.class,
            batchId
        );
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private void runScript(String resource) throws Exception {
        Path path = Path.of(getClass().getClassLoader().getResource(resource).toURI());
        for (String statement : Files.readString(path, StandardCharsets.UTF_8).split(";")) {
            if (!statement.trim().isEmpty()) {
                jdbcTemplate.execute(statement.trim());
            }
        }
    }
}
