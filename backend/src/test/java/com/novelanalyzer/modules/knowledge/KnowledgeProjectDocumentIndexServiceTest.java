package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentIndexService;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentBatchVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectDocumentIndexServiceTest {

    @Test
    void indexesNonProseSectionsInOneEmbeddingAndQdrantBatch() throws Exception {
        JdbcTemplate jdbc = jdbc();
        jdbc.update(
            "insert into ai_project_document_batch(user_id, project_id, work_id, idempotency_key, manifest_hash, parser_version, status, stage) "
                + "values(7, 11, 22, 'batch', 'manifest', 'test', 'PARSED_PENDING_INDEX', 'indexing')"
        );
        long batchId = jdbc.queryForObject("select max(batch_id) from ai_project_document_batch", Long.class);
        jdbc.update(
            "insert into ai_project_document_file(batch_id, user_id, project_id, work_id, relative_path, original_name, size_bytes, content_hash, status, content_blob) "
                + "values(?, 7, 11, 22, 'materials/outline.md', 'outline.md', 10, 'file-hash', 'PARSED_PENDING_INDEX', X'01')",
            batchId
        );
        long fileId = jdbc.queryForObject("select max(file_id) from ai_project_document_file", Long.class);
        jdbc.update(
            "insert into ai_project_document(batch_id, file_id, user_id, project_id, work_id, document_kind, relative_path, title, content_hash, normalized_content, status) "
                + "values(?, ?, 7, 11, 22, 'OUTLINE', 'materials/outline.md', 'Outline', 'document-hash', 'alpha beta', 'PARSED_PENDING_INDEX')",
            batchId, fileId
        );
        long documentId = jdbc.queryForObject("select max(document_id) from ai_project_document", Long.class);
        jdbc.update(
            "insert into ai_project_document_generation(document_id, batch_id, user_id, project_id, work_id, parser_version, content_hash, status, section_count) "
                + "values(?, ?, 7, 11, 22, 'test', 'generation-hash', 'PREPARED', 2)",
            documentId, batchId
        );
        long generationId = jdbc.queryForObject(
            "select max(document_generation_id) from ai_project_document_generation", Long.class
        );
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            jdbc.update(
                "insert into ai_project_document_section(document_id, document_generation_id, user_id, project_id, work_id, section_ordinal, title, section_kind, start_offset, end_offset, content_hash, content, status) "
                    + "values(?, ?, 7, 11, 22, ?, ?, 'OUTLINE', 0, 5, ?, ?, 'PARSED_PENDING_INDEX')",
                documentId, generationId, ordinal, "Section " + ordinal, "hash-" + ordinal,
                ordinal == 1 ? "alpha" : "beta"
            );
        }
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedAll(List.of("alpha", "beta"))).thenReturn(List.of(
            List.of(0.1d, 0.2d), List.of(0.3d, 0.4d)
        ));
        QdrantClient qdrantClient = mock(QdrantClient.class);
        KnowledgeProjectDocumentIndexService service = new KnowledgeProjectDocumentIndexService(
            jdbc, embeddingClient, qdrantClient
        );
        ProjectDocumentBatchVO batch = new ProjectDocumentBatchVO();
        batch.setBatchId(batchId);
        batch.setUserId(7L);
        batch.setProjectId(11L);
        batch.setWorkId(22L);

        int indexed = service.indexPendingSections(batch, 32);

        assertThat(indexed).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_project_search_document where document_generation_id = ? and status = 'ACTIVE'",
            Integer.class, generationId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_project_vector_chunk where document_generation_id = ? and status = 'ACTIVE'",
            Integer.class, generationId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_project_document_section where document_generation_id = ? and status = 'ACTIVE'",
            Integer.class, generationId
        )).isEqualTo(2);
        verify(embeddingClient).embedAll(List.of("alpha", "beta"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QdrantClient.UpsertPoint>> points = ArgumentCaptor.forClass(List.class);
        verify(qdrantClient).upsertPoints(points.capture());
        assertThat(points.getValue()).hasSize(2).allSatisfy(point -> assertThat(point.payload())
            .containsEntry("user_id", 7L)
            .containsEntry("project_id", 11L)
            .containsEntry("work_id", 22L)
            .containsEntry("visibility", "private")
            .containsKey("document_generation_id")
            .containsKey("section_id"));
    }

    private JdbcTemplate jdbc() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:document_index_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        runScript(jdbc, resource("sql/phase16-project-knowledge-rag-h2.sql"));
        runScript(jdbc, resource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        runScript(jdbc, resource("sql/phase29-project-document-batch-h2.sql"));
        return jdbc;
    }

    private Path resource(String path) throws Exception {
        for (Path candidate : List.of(
            Path.of("src/test/resources").resolve(path),
            Path.of("backend/src/test/resources").resolve(path),
            Path.of("..", "src/test/resources").resolve(path)
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new java.nio.file.NoSuchFileException(path);
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        for (String statement : Files.readString(path, StandardCharsets.UTF_8).split(";")) {
            String executable = statement.trim();
            if (!executable.isEmpty()) {
                jdbc.execute(executable);
            }
        }
    }
}
