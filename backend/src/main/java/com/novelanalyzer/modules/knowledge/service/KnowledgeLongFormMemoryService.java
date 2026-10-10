package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.vo.ForeshadowingAggregateVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeLongFormMemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService projectService;

    public KnowledgeLongFormMemoryService(JdbcTemplate jdbcTemplate, KnowledgeProjectService projectService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
    }

    public ForeshadowingAggregateVO aggregateForeshadowings(Long userId, Long projectId, Long workId) {
        projectService.ensureWorkOwned(projectId, workId, userId);
        Map<String, Long> breakdown = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select f.status, count(*) as fact_count
                from ai_project_foreshadowing f
                join ai_project_ingest_generation g
                  on g.generation_id = f.generation_id
                 and g.user_id = f.user_id and g.project_id = f.project_id and g.work_id = f.work_id
                 and g.status = 'ACTIVE'
                join ai_project_chapter_head h
                  on h.user_id = f.user_id and h.project_id = f.project_id and h.work_id = f.work_id
                 and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                 and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                where f.user_id = ? and f.project_id = ? and f.work_id = ? and f.status <> 'ARCHIVED'
                group by f.status
                order by f.status asc
                """,
            rs -> {
                breakdown.put(rs.getString("status"), rs.getLong("fact_count"));
            },
            userId, projectId, workId
        );

        List<String> generationIdentities = new ArrayList<>();
        jdbcTemplate.query(
            """
                select g.chapter_no, g.generation_id, g.chapter_id, g.content_hash
                from ai_project_ingest_generation g
                join ai_project_chapter_head h
                  on h.user_id = g.user_id and h.project_id = g.project_id and h.work_id = g.work_id
                 and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                 and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                where g.user_id = ? and g.project_id = ? and g.work_id = ? and g.status = 'ACTIVE'
                order by g.chapter_no asc, g.generation_id asc
                """,
            rs -> {
                generationIdentities.add("C|" + rs.getInt("chapter_no") + "|"
                    + rs.getLong("generation_id") + "|" + rs.getLong("chapter_id") + "|"
                    + nullToEmpty(rs.getString("content_hash")));
            },
            userId, projectId, workId
        );
        long chapterGenerationCount = generationIdentities.size();
        jdbcTemplate.query(
            """
                select d.document_id, g.document_generation_id, g.content_hash
                from ai_project_document d
                join ai_project_document_generation g
                  on g.document_generation_id = d.active_generation_id
                 and g.document_id = d.document_id
                 and g.user_id = d.user_id and g.project_id = d.project_id and g.work_id = d.work_id
                 and g.status = 'ACTIVE'
                where d.user_id = ? and d.project_id = ? and d.work_id = ? and d.status = 'ACTIVE'
                order by d.document_id asc, g.document_generation_id asc
                """,
            rs -> {
                generationIdentities.add("D|" + rs.getLong("document_id") + "|"
                    + rs.getLong("document_generation_id") + "|" + nullToEmpty(rs.getString("content_hash")));
            },
            userId, projectId, workId
        );
        long documentGenerationCount = generationIdentities.size() - chapterGenerationCount;

        ForeshadowingAggregateVO result = new ForeshadowingAggregateVO();
        result.setUserId(userId);
        result.setProjectId(projectId);
        result.setWorkId(workId);
        result.setMetric("foreshadowing_count");
        result.setBreakdown(breakdown);
        result.setCount(breakdown.values().stream().mapToLong(Long::longValue).sum());
        result.setComplete(true);
        result.setPartial(false);
        result.setRecognizedRecordsOnly(true);
        result.setGenerationFingerprint("sha256:" + sha256(String.join("\n", generationIdentities)));
        result.setActiveChapterGenerationCount(chapterGenerationCount);
        result.setActiveDocumentGenerationCount(documentGenerationCount);
        return result;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
