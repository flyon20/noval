package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentIndexService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectDocumentBatchExecutorTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeProjectDocumentBatchService batchService;
    private KnowledgeProjectIngestService ingestService;
    private KnowledgeProjectDocumentIndexService documentIndexService;
    private KnowledgeProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:document_executor_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        runScript("sql/phase16-project-knowledge-rag-h2.sql");
        runScript("sql/phase24-project-ingest-generation-h2.sql");
        runScript("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql");
        runScript("sql/phase29-project-document-batch-h2.sql");

        KnowledgeProjectWorkService workService = mock(KnowledgeProjectWorkService.class);
        when(workService.findOwnedWorkPublic(anyLong(), anyLong(), anyLong())).thenReturn(null);
        KnowledgeProjectDocumentBatchQueueService queueService = mock(KnowledgeProjectDocumentBatchQueueService.class);
        ingestService = mock(KnowledgeProjectIngestService.class);
        AtomicLong jobId = new AtomicLong(100);
        when(ingestService.submit(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            long id = jobId.getAndIncrement();
            jdbcTemplate.update(
                "insert into ai_project_ingest_job(ingest_job_id, user_id, project_id, work_id, job_type, status) "
                    + "values(?, 7, 11, 22, 'chapter_import_parse', 'UPLOADED')",
                id
            );
            ProjectIngestJobVO job = new ProjectIngestJobVO();
            job.setIngestJobId(id);
            return job;
        });
        properties = new KnowledgeProperties();
        properties.getDocumentBatch().setQueueEnabled(false);
        documentIndexService = mock(KnowledgeProjectDocumentIndexService.class);
        batchService = new KnowledgeProjectDocumentBatchService(
            jdbcTemplate,
            workService,
            ingestService,
            queueService,
            new ProjectDocumentParser(),
            documentIndexService,
            properties
        );
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
    }

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void parsesAndPersistsDocumentSectionsThenDispatchesBoundedChildJobs() {
        var batch = batchService.create(
            11L,
            22L,
            List.of(new MockMultipartFile(
                "files", "novel.md", "text/markdown",
                "# 第一章\n第一段\n# 第二章\n第二段".getBytes(StandardCharsets.UTF_8)
            )),
            List.of("novel.md"),
            null,
            "executor-batch"
        );
        KnowledgeProjectDocumentBatchExecutor executor = new KnowledgeProjectDocumentBatchExecutor(
            batchService,
            new ProjectDocumentParser(),
            jdbcTemplate,
            properties,
            new ObjectMapper()
        );

        executor.execute(batch.getBatchId(), 1);

        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_generation", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_section", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_ingest_job", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_section where ingest_job_id is not null", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
            "select canonical_chapter_no from ai_project_document_section order by section_ordinal", Integer.class
        )).containsExactly(1, 2);
        assertThat(batchService.get(11L, batch.getBatchId()).getStatus())
            .isEqualTo(KnowledgeProjectDocumentBatchService.PARSED_PENDING_INDEX);
    }

    @Test
    void acceptsZipTextEntriesWithoutSendingArchiveBinaryToParser() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("chapters/one.md"));
            zip.write("# 第一章\n内容".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("image.bin"));
            zip.write(new byte[]{0, 1, 2});
            zip.closeEntry();
        }
        var batch = batchService.create(
            11L, 22L,
            List.of(new MockMultipartFile("files", "novel.zip", "application/zip", bytes.toByteArray())),
            List.of("novel.zip"), null, "zip-batch"
        );
        KnowledgeProjectDocumentBatchExecutor executor = new KnowledgeProjectDocumentBatchExecutor(
            batchService, new ProjectDocumentParser(), jdbcTemplate, properties, new ObjectMapper()
        );

        executor.execute(batch.getBatchId(), 1);

        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_document_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void doesNotDispatchOutlineSectionsAsCanonicalChapters() {
        var batch = batchService.create(
            11L,
            22L,
            List.of(new MockMultipartFile(
                "files", "outline.md", "text/markdown",
                "# Volume outline\nThe protagonist discovers the hidden contract.".getBytes(StandardCharsets.UTF_8)
            )),
            List.of("materials/outline.md"),
            "OUTLINE",
            "outline-batch"
        );
        KnowledgeProjectDocumentBatchExecutor executor = new KnowledgeProjectDocumentBatchExecutor(
            batchService,
            new ProjectDocumentParser(),
            jdbcTemplate,
            properties,
            new ObjectMapper()
        );

        executor.execute(batch.getBatchId(), 1);

        verify(ingestService, never()).submit(anyLong(), anyLong(), any());
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_project_chapter", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section where section_kind = 'OUTLINE' and ingest_job_id is null",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void keepsConfirmationStatusScopedToTheAmbiguousSourceFile() {
        var batch = batchService.create(
            11L,
            22L,
            List.of(
                new MockMultipartFile(
                    "files", "mystery.md", "text/markdown",
                    "# Notes\nUnclassified material.".getBytes(StandardCharsets.UTF_8)
                ),
                new MockMultipartFile(
                    "files", "outline.md", "text/markdown",
                    "# Macro outline\nThe protagonist discovers the hidden contract.".getBytes(StandardCharsets.UTF_8)
                )
            ),
            List.of("materials/mystery.md", "materials/outline.md"),
            null,
            "mixed-certainty-batch"
        );
        KnowledgeProjectDocumentBatchExecutor executor = new KnowledgeProjectDocumentBatchExecutor(
            batchService,
            new ProjectDocumentParser(),
            jdbcTemplate,
            properties,
            new ObjectMapper()
        );

        executor.execute(batch.getBatchId(), 1);

        assertThat(jdbcTemplate.queryForList(
            "select status from ai_project_document_file order by file_id", String.class
        )).containsExactly("WAITING_CONFIRMATION", "PARSED_PENDING_INDEX");
        assertThat(batchService.get(11L, batch.getBatchId()).getStatus())
            .isEqualTo(KnowledgeProjectDocumentBatchService.WAITING_CONFIRMATION);
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
