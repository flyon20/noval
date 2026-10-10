package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentBatchVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeProjectDocumentIndexService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;

    public KnowledgeProjectDocumentIndexService(JdbcTemplate jdbcTemplate,
                                                EmbeddingClient embeddingClient,
                                                QdrantClient qdrantClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
    }

    public int indexPendingSections(ProjectDocumentBatchVO batch, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 64));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select s.section_id, s.document_id source_document_id, s.document_generation_id, s.title, "
                + "s.section_kind, s.content, s.content_hash from ai_project_document_section s "
                + "join ai_project_document d on d.document_id = s.document_id "
                + "where d.batch_id = ? and s.user_id = ? and s.project_id = ? and s.work_id = ? "
                + "and s.section_kind <> 'NOVEL_TEXT' and s.status = 'PARSED_PENDING_INDEX' "
                + "order by s.document_id, s.section_ordinal limit ?",
            batch.getBatchId(), batch.getUserId(), batch.getProjectId(), batch.getWorkId(), safeLimit
        );
        if (rows.isEmpty()) {
            return 0;
        }
        List<PreparedSection> sections = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            sections.add(prepare(batch, row));
        }
        try {
            List<List<Double>> vectors = embeddingClient.embedAll(
                sections.stream().map(section -> trim(section.content(), 8000)).toList()
            );
            qdrantClient.ensureCollection();
            List<QdrantClient.UpsertPoint> points = new ArrayList<>(sections.size());
            for (int index = 0; index < sections.size(); index++) {
                PreparedSection section = sections.get(index);
                points.add(new QdrantClient.UpsertPoint(
                    section.qdrantPointId(),
                    vectors.get(index),
                    payload(batch, section)
                ));
            }
            qdrantClient.upsertPoints(points);
            for (PreparedSection section : sections) {
                jdbcTemplate.update(
                    "update ai_project_search_document set status = 'ACTIVE', updated_at = current_timestamp "
                        + "where document_id = ? and status = 'PENDING'",
                    section.searchDocumentId()
                );
                jdbcTemplate.update(
                    "update ai_project_vector_chunk set status = 'ACTIVE' where id = ? and status = 'PENDING'",
                    section.vectorChunkId()
                );
                jdbcTemplate.update(
                    "update ai_project_document_section set status = 'ACTIVE', updated_at = current_timestamp "
                        + "where section_id = ? and status = 'PARSED_PENDING_INDEX'",
                    section.sectionId()
                );
            }
            return sections.size();
        } catch (RuntimeException ex) {
            List<Long> sectionIds = sections.stream().map(PreparedSection::sectionId).toList();
            for (Long sectionId : sectionIds) {
                jdbcTemplate.update(
                    "update ai_project_document_section set status = 'INDEX_FAILED', updated_at = current_timestamp "
                        + "where section_id = ? and status = 'PARSED_PENDING_INDEX'",
                    sectionId
                );
            }
            throw ex;
        }
    }

    private PreparedSection prepare(ProjectDocumentBatchVO batch, Map<String, Object> row) {
        long sectionId = number(row, "section_id");
        long sourceDocumentId = number(row, "source_document_id");
        long documentGenerationId = number(row, "document_generation_id");
        String kind = String.valueOf(row.get("section_kind"));
        String title = trim((String) row.get("title"), 500);
        String content = String.valueOf(row.get("content"));
        String contentHash = String.valueOf(row.get("content_hash"));
        Long searchDocumentId = existingId(
            "select document_id from ai_project_search_document where user_id = ? and project_id = ? and work_id = ? "
                + "and document_generation_id = ? and section_id = ? order by document_id limit 1",
            batch, documentGenerationId, sectionId
        );
        if (searchDocumentId == null) {
            KeyHolder key = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    "insert into ai_project_search_document(user_id, project_id, work_id, chapter_id, generation_id, "
                        + "chapter_version, source_id, document_type, document_key, title, aliases, content, content_hash, "
                        + "confidence, status, source_document_id, document_generation_id, section_id) "
                        + "values(?, ?, ?, null, null, null, ?, ?, ?, ?, ?, ?, ?, 1.0, 'PENDING', ?, ?, ?)",
                    new String[]{"document_id"}
                );
                statement.setLong(1, batch.getUserId());
                statement.setLong(2, batch.getProjectId());
                statement.setLong(3, batch.getWorkId());
                statement.setLong(4, sectionId);
                statement.setString(5, kind);
                statement.setString(6, "document-section:" + sectionId);
                statement.setString(7, title);
                statement.setString(8, title == null ? null : "|" + title + "|");
                statement.setString(9, content);
                statement.setString(10, contentHash);
                statement.setLong(11, sourceDocumentId);
                statement.setLong(12, documentGenerationId);
                statement.setLong(13, sectionId);
                return statement;
            }, key);
            searchDocumentId = key.getKey().longValue();
        }
        Long vectorChunkId = existingId(
            "select id from ai_project_vector_chunk where user_id = ? and project_id = ? and work_id = ? "
                + "and document_generation_id = ? and section_id = ? order by id limit 1",
            batch, documentGenerationId, sectionId
        );
        String pointId = UUID.nameUUIDFromBytes(
            ("project-document-section:" + batch.getUserId() + ":" + batch.getProjectId() + ":"
                + documentGenerationId + ":" + sectionId).getBytes(StandardCharsets.UTF_8)
        ).toString();
        if (vectorChunkId == null) {
            KeyHolder key = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    "insert into ai_project_vector_chunk(user_id, project_id, work_id, chapter_id, generation_id, "
                        + "chapter_version, status, scene_id, source_type, source_id, content_hash, qdrant_point_id, "
                        + "chunk_text, visibility, document_id, document_generation_id, section_id, profile_type, evidence_scope) "
                        + "values(?, ?, ?, null, null, null, 'PENDING', null, ?, ?, ?, ?, ?, 'private', ?, ?, ?, ?, 'PROJECT_DOCUMENT')",
                    new String[]{"id"}
                );
                statement.setLong(1, batch.getUserId());
                statement.setLong(2, batch.getProjectId());
                statement.setLong(3, batch.getWorkId());
                statement.setString(4, "document_" + kind.toLowerCase(Locale.ROOT));
                statement.setLong(5, sectionId);
                statement.setString(6, contentHash);
                statement.setString(7, pointId);
                statement.setString(8, trim(content, 4000));
                statement.setLong(9, sourceDocumentId);
                statement.setLong(10, documentGenerationId);
                statement.setLong(11, sectionId);
                statement.setString(12, kind);
                return statement;
            }, key);
            vectorChunkId = key.getKey().longValue();
        }
        return new PreparedSection(
            sectionId, sourceDocumentId, documentGenerationId, vectorChunkId, searchDocumentId,
            pointId, kind, title, content, contentHash
        );
    }

    private Long existingId(String sql,
                            ProjectDocumentBatchVO batch,
                            long documentGenerationId,
                            long sectionId) {
        List<Long> ids = jdbcTemplate.queryForList(
            sql, Long.class, batch.getUserId(), batch.getProjectId(), batch.getWorkId(), documentGenerationId, sectionId
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> payload(ProjectDocumentBatchVO batch, PreparedSection section) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", batch.getUserId());
        payload.put("project_id", batch.getProjectId());
        payload.put("work_id", batch.getWorkId());
        payload.put("visibility", "private");
        payload.put("project_vector_chunk_id", section.vectorChunkId());
        payload.put("document_id", section.sourceDocumentId());
        payload.put("document_generation_id", section.documentGenerationId());
        payload.put("section_id", section.sectionId());
        payload.put("source_type", "document_" + section.kind().toLowerCase(Locale.ROOT));
        payload.put("source_id", section.sectionId());
        payload.put("content_hash", section.contentHash());
        payload.put("chunk_text_preview", trim(section.content(), 1000));
        return payload;
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private record PreparedSection(long sectionId,
                                   long sourceDocumentId,
                                   long documentGenerationId,
                                   long vectorChunkId,
                                   long searchDocumentId,
                                   String qdrantPointId,
                                   String kind,
                                   String title,
                                   String content,
                                   String contentHash) {
    }
}
