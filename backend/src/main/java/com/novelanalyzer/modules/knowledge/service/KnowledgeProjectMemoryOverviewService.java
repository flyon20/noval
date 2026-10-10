package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.vo.ForeshadowingAggregateVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectMemoryOverviewVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeProjectMemoryOverviewService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService projectService;
    private final KnowledgeLongFormMemoryService longFormMemoryService;

    public KnowledgeProjectMemoryOverviewService(JdbcTemplate jdbcTemplate,
                                                 KnowledgeProjectService projectService,
                                                 KnowledgeLongFormMemoryService longFormMemoryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
        this.longFormMemoryService = longFormMemoryService;
    }

    @Transactional(readOnly = true)
    public ProjectMemoryOverviewVO overview(Long userId, Long projectId, Long workId) {
        projectService.ensureWorkOwned(projectId, workId, userId);
        List<Integer> chapterNumbers = activeChapterNumbers(userId, projectId, workId);
        ForeshadowingAggregateVO foreshadowing = longFormMemoryService.aggregateForeshadowings(
            userId, projectId, workId);

        ProjectMemoryOverviewVO result = new ProjectMemoryOverviewVO();
        result.setProjectId(projectId);
        result.setWorkId(workId);
        result.setActiveChapterCount(chapterNumbers.size());
        result.setChapterFrom(chapterNumbers.isEmpty() ? null : chapterNumbers.get(0));
        result.setChapterTo(chapterNumbers.isEmpty() ? null : chapterNumbers.get(chapterNumbers.size() - 1));
        result.setIndexedDocumentCount(foreshadowing.getActiveDocumentGenerationCount());
        result.setCharacterStateCount(countCharacterStates(userId, projectId, workId));
        result.setWorldRuleCount(countWorldRules(userId, projectId, workId));
        result.setForeshadowingCount(foreshadowing.getCount());
        result.setForeshadowingStatusCounts(foreshadowing.getBreakdown());
        result.setTimelineEventCount(countTimelineEvents(userId, projectId, workId));
        result.setStoryNodeCount(countStoryNodes(userId, projectId, workId));
        result.setStoryEdgeCount(countStoryEdges(userId, projectId, workId));
        result.setPendingExtractionCount(countPendingExtractions(userId, projectId, workId));

        Map<String, Long> factStatusCounts = longFormFactStatusCounts(userId, projectId, workId);
        result.setLongFormFactStatusCounts(factStatusCounts);
        result.setLongFormFactCount(factStatusCounts.values().stream().mapToLong(Long::longValue).sum());
        result.setPendingLongFormFactCount(factStatusCounts.getOrDefault("PENDING_REVIEW", 0L));

        Map<String, Long> summaryTypeCounts = summaryNodeTypeCounts(userId, projectId, workId);
        long summaryNodeCount = summaryTypeCounts.values().stream().mapToLong(Long::longValue).sum();
        long summaryCoveredChapterCount = countSummaryCoveredChapters(userId, projectId, workId);
        result.setSummaryNodeTypeCounts(summaryTypeCounts);
        result.setSummaryNodeCount(summaryNodeCount);
        result.setSummaryCoveredChapterCount(summaryCoveredChapterCount);
        result.setSummaryCoverageStatus(summaryCoverageStatus(
            chapterNumbers.size(), summaryNodeCount, summaryCoveredChapterCount));
        result.setRecognizedRecordsOnly(true);
        result.setCorpusFingerprint(foreshadowing.getGenerationFingerprint());
        return result;
    }

    private List<Integer> activeChapterNumbers(Long userId, Long projectId, Long workId) {
        return jdbcTemplate.query(
            """
                select h.chapter_no
                from ai_project_chapter_head h
                join ai_project_ingest_generation g
                  on g.generation_id = h.active_generation_id
                 and g.user_id = h.user_id and g.project_id = h.project_id and g.work_id = h.work_id
                 and g.chapter_id = h.active_chapter_id and g.chapter_no = h.chapter_no
                 and g.status = 'ACTIVE'
                where h.user_id = ? and h.project_id = ? and h.work_id = ?
                  and h.tombstoned_at is null
                order by h.chapter_no asc
                """,
            (rs, rowNum) -> rs.getInt("chapter_no"),
            userId, projectId, workId
        );
    }

    private long countCharacterStates(Long userId, Long projectId, Long workId) {
        return countActiveChapterProjection(
            "ai_project_character_state", "s", "s.state_id", "s.status = 'ACTIVE'",
            userId, projectId, workId);
    }

    private long countWorldRules(Long userId, Long projectId, Long workId) {
        return countActiveChapterProjection(
            "ai_project_world_rule", "s", "s.rule_id", "s.status_proj = 'ACTIVE'",
            userId, projectId, workId);
    }

    private long countTimelineEvents(Long userId, Long projectId, Long workId) {
        return countActiveChapterProjection(
            "ai_project_timeline_event", "s", "s.event_id", "s.status = 'ACTIVE'",
            userId, projectId, workId);
    }

    private long countStoryNodes(Long userId, Long projectId, Long workId) {
        return countActiveChapterProjection(
            "ai_project_story_node", "s", "s.node_id", "s.status = 'ACTIVE'",
            userId, projectId, workId);
    }

    private long countStoryEdges(Long userId, Long projectId, Long workId) {
        return countActiveChapterProjection(
            "ai_project_story_edge", "s", "s.edge_id", "s.status = 'ACTIVE'",
            userId, projectId, workId);
    }

    private long countPendingExtractions(Long userId, Long projectId, Long workId) {
        return countActiveChapterProjection(
            "ai_project_extraction_candidate", "s", "s.candidate_id", "s.status = 'PENDING'",
            userId, projectId, workId);
    }

    private long countActiveChapterProjection(String table,
                                              String alias,
                                              String idColumn,
                                              String statusPredicate,
                                              Long userId,
                                              Long projectId,
                                              Long workId) {
        Long count = jdbcTemplate.queryForObject(
            "select count(" + idColumn + ") from " + table + " " + alias + " "
                + "join ai_project_ingest_generation g on g.generation_id = " + alias + ".generation_id "
                + "and g.user_id = " + alias + ".user_id and g.project_id = " + alias + ".project_id "
                + "and g.work_id = " + alias + ".work_id and g.status = 'ACTIVE' "
                + "join ai_project_chapter_head h on h.user_id = g.user_id and h.project_id = g.project_id "
                + "and h.work_id = g.work_id and h.chapter_no = g.chapter_no "
                + "and h.active_generation_id = g.generation_id and h.active_chapter_id = g.chapter_id "
                + "and h.tombstoned_at is null "
                + "where " + alias + ".user_id = ? and " + alias + ".project_id = ? and "
                + alias + ".work_id = ? and " + statusPredicate,
            Long.class,
            userId, projectId, workId
        );
        return count == null ? 0L : count;
    }

    private Map<String, Long> longFormFactStatusCounts(Long userId, Long projectId, Long workId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select f.lifecycle_status, count(*) as fact_count
                from ai_project_memory_fact f
                where f.user_id = ? and f.project_id = ? and f.work_id = ?
                  and f.recorded_to_at is null
                  and f.lifecycle_status not in ('SUPERSEDED', 'REJECTED', 'RETIRED', 'ARCHIVED', 'DELETED')
                  and (f.generation_id is null or exists (
                      select 1
                      from ai_project_ingest_generation g
                      join ai_project_chapter_head h
                        on h.user_id = g.user_id and h.project_id = g.project_id and h.work_id = g.work_id
                       and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                       and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                      where g.generation_id = f.generation_id and g.user_id = f.user_id
                        and g.project_id = f.project_id and g.work_id = f.work_id and g.status = 'ACTIVE'
                  ))
                  and (f.document_generation_id is null or exists (
                      select 1
                      from ai_project_document d
                      join ai_project_document_generation g
                        on g.document_generation_id = d.active_generation_id and g.document_id = d.document_id
                       and g.user_id = d.user_id and g.project_id = d.project_id and g.work_id = d.work_id
                       and g.status = 'ACTIVE'
                      where g.document_generation_id = f.document_generation_id and d.user_id = f.user_id
                        and d.project_id = f.project_id and d.work_id = f.work_id and d.status = 'ACTIVE'
                  ))
                group by f.lifecycle_status
                order by f.lifecycle_status asc
                """,
            rs -> {
                counts.put(rs.getString("lifecycle_status"), rs.getLong("fact_count"));
            },
            userId, projectId, workId
        );
        return counts;
    }

    private Map<String, Long> summaryNodeTypeCounts(Long userId, Long projectId, Long workId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select s.node_type, count(*) as node_count
                from ai_project_summary_node s
                where s.user_id = ? and s.project_id = ? and s.work_id = ?
                  and s.status = 'ACTIVE' and s.recorded_to_at is null
                  and (s.generation_id is null or exists (
                      select 1
                      from ai_project_ingest_generation g
                      join ai_project_chapter_head h
                        on h.user_id = g.user_id and h.project_id = g.project_id and h.work_id = g.work_id
                       and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                       and h.active_chapter_id = g.chapter_id and h.tombstoned_at is null
                      where g.generation_id = s.generation_id and g.user_id = s.user_id
                        and g.project_id = s.project_id and g.work_id = s.work_id and g.status = 'ACTIVE'
                  ))
                  and (s.document_generation_id is null or exists (
                      select 1
                      from ai_project_document d
                      join ai_project_document_generation g
                        on g.document_generation_id = d.active_generation_id and g.document_id = d.document_id
                       and g.user_id = d.user_id and g.project_id = d.project_id and g.work_id = d.work_id
                       and g.status = 'ACTIVE'
                      where g.document_generation_id = s.document_generation_id and d.user_id = s.user_id
                        and d.project_id = s.project_id and d.work_id = s.work_id and d.status = 'ACTIVE'
                  ))
                group by s.node_type
                order by s.node_type asc
                """,
            rs -> {
                counts.put(rs.getString("node_type"), rs.getLong("node_count"));
            },
            userId, projectId, workId
        );
        return counts;
    }

    private long countSummaryCoveredChapters(Long userId, Long projectId, Long workId) {
        Long count = jdbcTemplate.queryForObject(
            """
                select count(distinct h.chapter_no)
                from ai_project_chapter_head h
                join ai_project_ingest_generation cg
                  on cg.generation_id = h.active_generation_id and cg.chapter_id = h.active_chapter_id
                 and cg.user_id = h.user_id and cg.project_id = h.project_id and cg.work_id = h.work_id
                 and cg.chapter_no = h.chapter_no and cg.status = 'ACTIVE'
                where h.user_id = ? and h.project_id = ? and h.work_id = ? and h.tombstoned_at is null
                  and exists (
                      select 1
                      from ai_project_summary_node s
                      where s.user_id = h.user_id and s.project_id = h.project_id and s.work_id = h.work_id
                        and s.status = 'ACTIVE' and s.recorded_to_at is null
                        and s.range_from_chapter is not null and s.range_to_chapter is not null
                        and h.chapter_no between s.range_from_chapter and s.range_to_chapter
                        and (s.generation_id is null or s.generation_id = h.active_generation_id)
                        and (s.document_generation_id is null or exists (
                            select 1
                            from ai_project_document d
                            join ai_project_document_generation dg
                              on dg.document_generation_id = d.active_generation_id and dg.document_id = d.document_id
                             and dg.user_id = d.user_id and dg.project_id = d.project_id and dg.work_id = d.work_id
                             and dg.status = 'ACTIVE'
                            where dg.document_generation_id = s.document_generation_id
                              and d.user_id = s.user_id and d.project_id = s.project_id
                              and d.work_id = s.work_id and d.status = 'ACTIVE'
                        ))
                  )
                """,
            Long.class,
            userId, projectId, workId
        );
        return count == null ? 0L : count;
    }

    private String summaryCoverageStatus(long activeChapterCount,
                                         long summaryNodeCount,
                                         long summaryCoveredChapterCount) {
        if (activeChapterCount == 0L) {
            return "NO_CORPUS";
        }
        if (summaryNodeCount == 0L || summaryCoveredChapterCount == 0L) {
            return "NOT_BUILT";
        }
        return summaryCoveredChapterCount >= activeChapterCount ? "COMPLETE" : "PARTIAL";
    }
}
