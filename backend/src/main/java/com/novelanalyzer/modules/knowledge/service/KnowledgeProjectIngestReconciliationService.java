package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeProjectIngestReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectIngestReconciliationService.class);
    private static final int DEFAULT_LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectIngestService ingestService;
    private final KnowledgeProjectWorkService workService;
    private final AgentResourcePressureService resourcePressureService;

    public KnowledgeProjectIngestReconciliationService(JdbcTemplate jdbcTemplate,
                                                       KnowledgeProjectIngestService ingestService,
                                                       KnowledgeProjectWorkService workService,
                                                       AgentResourcePressureService resourcePressureService,
                                                       KnowledgeProperties knowledgeProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.ingestService = ingestService;
        this.workService = workService;
        this.resourcePressureService = resourcePressureService;
    }

    @Scheduled(
        fixedDelayString = "${app.knowledge.project-ingest.reconciliation-interval-millis:30000}",
        initialDelayString = "${app.knowledge.project-ingest.reconciliation-initial-delay-millis:30000}"
    )
    public void reconcileScheduled() {
        try {
            int fixed = reconcile(DEFAULT_LIMIT);
            if (fixed > 0) {
                LOGGER.info("project ingest reconciliation repaired items={}", fixed);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("project ingest reconciliation failed: {}", ex.getMessage());
        }
    }

    public int reconcile(int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT);
        int fixed = ingestService.dispatchPendingOutbox(safeLimit);
        fixed += ingestService.recoverExpiredJobs(safeLimit);
        fixed += retireDuplicateActiveHeads(safeLimit);

        if (resourcePressureService == null || !resourcePressureService.shouldPauseIndexing()) {
            fixed += scheduleMissingVectorRepairs(safeLimit);
        }
        if (resourcePressureService == null || !resourcePressureService.shouldSuppressLowPriorityWork()) {
            fixed += cleanupGenerations(safeLimit);
            fixed += cleanupTombstones(safeLimit);
        }
        return fixed;
    }

    private int retireDuplicateActiveHeads(int limit) {
        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
            """
                select g.user_id, g.project_id, g.work_id, g.chapter_no,
                       h.active_generation_id
                from ai_project_ingest_generation g
                join ai_project_chapter_head h on h.user_id = g.user_id
                  and h.project_id = g.project_id and h.work_id = g.work_id
                  and h.chapter_no = g.chapter_no
                where g.status = 'ACTIVE' and h.active_generation_id is not null
                group by g.user_id, g.project_id, g.work_id, g.chapter_no, h.active_generation_id
                having count(*) > 1
                limit ?
                """,
            limit);
        int fixed = 0;
        for (Map<String, Object> duplicate : duplicates) {
            fixed += jdbcTemplate.update(
                """
                    update ai_project_ingest_generation
                    set status = 'RETIRED', retired_at = current_timestamp,
                        cleanup_status = 'QUEUED', updated_at = current_timestamp
                    where user_id = ? and project_id = ? and work_id = ? and chapter_no = ?
                      and status = 'ACTIVE' and generation_id <> ?
                    """,
                duplicate.get("user_id"), duplicate.get("project_id"), duplicate.get("work_id"),
                duplicate.get("chapter_no"), duplicate.get("active_generation_id"));
        }
        return fixed;
    }

    private int scheduleMissingVectorRepairs(int limit) {
        List<Long> generationIds = jdbcTemplate.query(
            """
                select g.generation_id
                from ai_project_ingest_generation g
                join ai_project_chapter_head h on h.active_generation_id = g.generation_id
                where g.status = 'ACTIVE' and h.tombstoned_at is null
                  and g.expected_vector_count is not null
                  and g.vector_count < g.expected_vector_count
                order by g.generation_id asc
                limit ?
                """,
            (rs, rowNum) -> rs.getLong(1), limit);
        int scheduled = 0;
        for (Long generationId : generationIds) {
            if (ingestService.scheduleVectorRepair(generationId)) {
                scheduled++;
            }
        }
        return scheduled;
    }

    private int cleanupGenerations(int limit) {
        List<Long> generationIds = jdbcTemplate.query(
            """
                select generation_id
                from ai_project_ingest_generation
                where status in ('RETIRED', 'FAILED')
                  and (cleanup_status is null or cleanup_status in ('QUEUED', 'FAILED'))
                order by generation_id asc
                limit ?
                """,
            (rs, rowNum) -> rs.getLong(1), limit);
        int cleaned = 0;
        for (Long generationId : generationIds) {
            int claimed = jdbcTemplate.update(
                """
                    update ai_project_ingest_generation
                    set cleanup_status = 'RUNNING', cleanup_error = null, updated_at = current_timestamp
                    where generation_id = ? and status in ('RETIRED', 'FAILED')
                      and (cleanup_status is null or cleanup_status in ('QUEUED', 'FAILED'))
                    """,
                generationId);
            if (claimed != 1) {
                continue;
            }
            try {
                workService.cleanupGenerationArtifacts(generationId);
                int completed = jdbcTemplate.update(
                    "update ai_project_ingest_generation set cleanup_status = 'COMPLETE', cleanup_error = null, "
                        + "updated_at = current_timestamp where generation_id = ? and status in ('RETIRED', 'FAILED') "
                        + "and cleanup_status = 'RUNNING'",
                    generationId);
                if (completed == 1) {
                    cleaned++;
                }
            } catch (RuntimeException ex) {
                jdbcTemplate.update(
                    "update ai_project_ingest_generation set cleanup_status = 'FAILED', cleanup_error = ?, "
                        + "updated_at = current_timestamp where generation_id = ? and status in ('RETIRED', 'FAILED') "
                        + "and cleanup_status = 'RUNNING'",
                    trim(ex.getMessage(), 500), generationId);
                LOGGER.warn("project generation cleanup failed generationId={} reason={}", generationId, ex.getMessage());
            }
        }
        return cleaned;
    }

    private int cleanupTombstones(int limit) {
        List<Map<String, Object>> tombstones = jdbcTemplate.queryForList(
            """
                select tombstone_id, user_id, project_id, work_id, chapter_no, scope_type
                from ai_project_tombstone
                where cleanup_stage in ('QUEUED', 'FAILED')
                order by tombstone_id asc
                limit ?
                """,
            limit);
        int cleaned = 0;
        for (Map<String, Object> tombstone : tombstones) {
            try {
                cleanupTombstoneScope(tombstone);
                jdbcTemplate.update(
                    "update ai_project_tombstone set cleanup_stage = 'COMPLETE', last_error = null, updated_at = current_timestamp where tombstone_id = ?",
                    tombstone.get("tombstone_id"));
                cleaned++;
            } catch (RuntimeException ex) {
                jdbcTemplate.update(
                    "update ai_project_tombstone set cleanup_stage = 'FAILED', retry_count = retry_count + 1, last_error = ?, updated_at = current_timestamp where tombstone_id = ?",
                    trim(ex.getMessage(), 500), tombstone.get("tombstone_id"));
                LOGGER.warn("project tombstone cleanup failed tombstoneId={} reason={}",
                    tombstone.get("tombstone_id"), ex.getMessage());
            }
        }
        return cleaned;
    }

    private void cleanupTombstoneScope(Map<String, Object> tombstone) {
        String scopeType = String.valueOf(tombstone.get("scope_type"));
        if (!"CHAPTER".equals(scopeType)) {
            throw new IllegalStateException("unsupported project tombstone scope: " + scopeType);
        }
        List<Long> generationIds = jdbcTemplate.query(
            """
                select generation_id from ai_project_ingest_generation
                where user_id = ? and project_id = ? and work_id = ? and chapter_no = ?
                order by generation_id asc
                """,
            (rs, rowNum) -> rs.getLong(1),
            tombstone.get("user_id"), tombstone.get("project_id"),
            tombstone.get("work_id"), tombstone.get("chapter_no"));
        for (Long generationId : generationIds) {
            workService.cleanupGenerationArtifacts(generationId);
            jdbcTemplate.update(
                "update ai_project_ingest_generation set cleanup_status = 'COMPLETE', cleanup_error = null, updated_at = current_timestamp where generation_id = ?",
                generationId);
        }
        jdbcTemplate.update(
            "update ai_project_chapter set status = 'ARCHIVED', updated_at = current_timestamp where user_id = ? and project_id = ? and work_id = ? and chapter_no = ?",
            tombstone.get("user_id"), tombstone.get("project_id"),
            tombstone.get("work_id"), tombstone.get("chapter_no"));
        jdbcTemplate.update(
            "update ai_project_chapter_head set active_chapter_id = null, active_generation_id = null, updated_at = current_timestamp where user_id = ? and project_id = ? and work_id = ? and chapter_no = ? and tombstoned_at is not null",
            tombstone.get("user_id"), tombstone.get("project_id"),
            tombstone.get("work_id"), tombstone.get("chapter_no"));
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
