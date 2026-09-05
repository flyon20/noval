package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.service.KnowledgeLongFormMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectMemoryOverviewService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.vo.ProjectMemoryOverviewVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeProjectMemoryOverviewServiceTest {

    @Test
    void returnsOnlyCurrentOwnedCorpusMemoryAndCoverage() {
        JdbcTemplate jdbc = jdbcTemplate();
        createSchema(jdbc);
        insertScope(jdbc);
        insertGeneration(jdbc, 101L, 201L, 1, "chapter-1", "ACTIVE", false);
        insertGeneration(jdbc, 102L, 202L, 2, "chapter-2", "ACTIVE", false);
        insertGeneration(jdbc, 103L, 203L, 2, "old-chapter-2", "RETIRED", null);
        insertGeneration(jdbc, 104L, 204L, 3, "deleted-chapter-3", "ACTIVE", true);
        insertDocument(jdbc, 301L, 401L, "doc-1", "ACTIVE", "ACTIVE");
        insertDocument(jdbc, 302L, 402L, "doc-old", "ACTIVE", "RETIRED");

        jdbc.update("insert into ai_project_character_state(user_id, project_id, work_id, generation_id, status) values(7, 91, 911, 201, 'ACTIVE')");
        jdbc.update("insert into ai_project_character_state(user_id, project_id, work_id, generation_id, status) values(7, 91, 911, 203, 'ACTIVE')");
        jdbc.update("insert into ai_project_world_rule(user_id, project_id, work_id, generation_id, status_proj) values(7, 91, 911, 202, 'ACTIVE')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 201, 'open clue', 'OPEN')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 202, 'paid clue', 'PAID_OFF')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 202, 'archived clue', 'ARCHIVED')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 203, 'old clue', 'OPEN')");
        jdbc.update("insert into ai_project_timeline_event(user_id, project_id, work_id, generation_id, status) values(7, 91, 911, 201, 'ACTIVE')");
        jdbc.update("insert into ai_project_story_node(node_id, user_id, project_id, work_id, generation_id, status) values(1, 7, 91, 911, 201, 'ACTIVE')");
        jdbc.update("insert into ai_project_story_node(node_id, user_id, project_id, work_id, generation_id, status) values(2, 7, 91, 911, 203, 'ACTIVE')");
        jdbc.update("insert into ai_project_story_edge(user_id, project_id, work_id, generation_id, status) values(7, 91, 911, 202, 'ACTIVE')");
        jdbc.update("insert into ai_project_extraction_candidate(user_id, project_id, work_id, generation_id, status) values(7, 91, 911, 202, 'PENDING')");
        jdbc.update("insert into ai_project_extraction_candidate(user_id, project_id, work_id, generation_id, status) values(7, 91, 911, 201, 'CONFIRMED')");

        insertFact(jdbc, 201L, null, "CONFIRMED", null);
        insertFact(jdbc, null, 401L, "PENDING_REVIEW", null);
        insertFact(jdbc, null, null, "CONFIRMED", null);
        insertFact(jdbc, 202L, null, "SUPERSEDED", null);
        insertFact(jdbc, 203L, null, "CONFIRMED", null);
        insertFact(jdbc, null, 402L, "CONFIRMED", null);

        insertSummary(jdbc, 201L, null, "CHAPTER", 1, 1, "ACTIVE", null);
        insertSummary(jdbc, null, null, "ARC", 1, 2, "ACTIVE", null);
        insertSummary(jdbc, null, 401L, "CHAPTER", 2, 2, "ACTIVE", null);
        insertSummary(jdbc, 203L, null, "CHAPTER", 2, 2, "ACTIVE", null);
        insertSummary(jdbc, null, null, "WORK", 1, 2, "RETIRED", null);
        insertSummary(jdbc, null, 402L, "CHAPTER", 1, 1, "ACTIVE", null);

        ProjectMemoryOverviewVO overview = service(jdbc).overview(7L, 91L, 911L);

        assertThat(overview.getActiveChapterCount()).isEqualTo(2L);
        assertThat(overview.getChapterFrom()).isEqualTo(1);
        assertThat(overview.getChapterTo()).isEqualTo(2);
        assertThat(overview.getIndexedDocumentCount()).isEqualTo(1L);
        assertThat(overview.getCharacterStateCount()).isEqualTo(1L);
        assertThat(overview.getWorldRuleCount()).isEqualTo(1L);
        assertThat(overview.getForeshadowingCount()).isEqualTo(2L);
        assertThat(overview.getForeshadowingStatusCounts()).containsExactly(
            org.assertj.core.data.MapEntry.entry("OPEN", 1L),
            org.assertj.core.data.MapEntry.entry("PAID_OFF", 1L)
        );
        assertThat(overview.getTimelineEventCount()).isEqualTo(1L);
        assertThat(overview.getStoryNodeCount()).isEqualTo(1L);
        assertThat(overview.getStoryEdgeCount()).isEqualTo(1L);
        assertThat(overview.getPendingExtractionCount()).isEqualTo(1L);
        assertThat(overview.getLongFormFactCount()).isEqualTo(3L);
        assertThat(overview.getPendingLongFormFactCount()).isEqualTo(1L);
        assertThat(overview.getLongFormFactStatusCounts()).containsExactly(
            org.assertj.core.data.MapEntry.entry("CONFIRMED", 2L),
            org.assertj.core.data.MapEntry.entry("PENDING_REVIEW", 1L)
        );
        assertThat(overview.getSummaryNodeCount()).isEqualTo(3L);
        assertThat(overview.getSummaryNodeTypeCounts()).containsExactly(
            org.assertj.core.data.MapEntry.entry("ARC", 1L),
            org.assertj.core.data.MapEntry.entry("CHAPTER", 2L)
        );
        assertThat(overview.getSummaryCoveredChapterCount()).isEqualTo(2L);
        assertThat(overview.getSummaryCoverageStatus()).isEqualTo("COMPLETE");
        assertThat(overview.isRecognizedRecordsOnly()).isTrue();
        assertThat(overview.getCorpusFingerprint()).startsWith("sha256:").hasSize(71);
    }

    @Test
    void rejectsAnotherUsersProjectOrWork() {
        JdbcTemplate jdbc = jdbcTemplate();
        createSchema(jdbc);
        insertScope(jdbc);

        assertThatThrownBy(() -> service(jdbc).overview(8L, 91L, 911L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service(jdbc).overview(7L, 91L, 912L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private KnowledgeProjectMemoryOverviewService service(JdbcTemplate jdbc) {
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbc);
        return new KnowledgeProjectMemoryOverviewService(
            jdbc,
            projectService,
            new KnowledgeLongFormMemoryService(jdbc, projectService)
        );
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("create table ai_project(project_id bigint primary key, user_id bigint not null, name varchar(120), description varchar(500), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.execute("create table ai_project_work(work_id bigint primary key, user_id bigint not null, project_id bigint not null, title varchar(200), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.execute("create table ai_project_ingest_generation(generation_id bigint primary key, user_id bigint not null, project_id bigint not null, work_id bigint not null, chapter_id bigint not null, chapter_no int not null, content_hash varchar(128), status varchar(30))");
        jdbc.execute("create table ai_project_chapter_head(user_id bigint not null, project_id bigint not null, work_id bigint not null, chapter_no int not null, active_chapter_id bigint, active_generation_id bigint, tombstoned_at timestamp)");
        jdbc.execute("create table ai_project_document(document_id bigint primary key, user_id bigint not null, project_id bigint not null, work_id bigint not null, active_generation_id bigint, status varchar(40))");
        jdbc.execute("create table ai_project_document_generation(document_generation_id bigint primary key, document_id bigint not null, user_id bigint not null, project_id bigint not null, work_id bigint not null, content_hash varchar(128), status varchar(40))");
        jdbc.execute("create table ai_project_character_state(state_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, status varchar(30))");
        jdbc.execute("create table ai_project_world_rule(rule_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, status_proj varchar(30))");
        jdbc.execute("create table ai_project_foreshadowing(foreshadowing_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, title varchar(200), status varchar(30))");
        jdbc.execute("create table ai_project_timeline_event(event_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, status varchar(30))");
        jdbc.execute("create table ai_project_story_node(node_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, status varchar(30))");
        jdbc.execute("create table ai_project_story_edge(edge_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, status varchar(30))");
        jdbc.execute("create table ai_project_extraction_candidate(candidate_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, status varchar(30))");
        jdbc.execute("create table ai_project_memory_fact(fact_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, document_generation_id bigint, lifecycle_status varchar(40), recorded_to_at timestamp)");
        jdbc.execute("create table ai_project_summary_node(summary_node_id bigint auto_increment primary key, user_id bigint, project_id bigint, work_id bigint, generation_id bigint, document_generation_id bigint, node_type varchar(30), range_from_chapter int, range_to_chapter int, status varchar(30), recorded_to_at timestamp)");
    }

    private void insertScope(JdbcTemplate jdbc) {
        jdbc.update("insert into ai_project(project_id, user_id, name, status) values(91, 7, 'Novel', 'ACTIVE')");
        jdbc.update("insert into ai_project_work(work_id, user_id, project_id, title, status) values(911, 7, 91, 'Novel Work', 'ACTIVE')");
    }

    private void insertGeneration(JdbcTemplate jdbc,
                                  long chapterId,
                                  long generationId,
                                  int chapterNo,
                                  String contentHash,
                                  String status,
                                  Boolean tombstoned) {
        jdbc.update(
            "insert into ai_project_ingest_generation(generation_id, user_id, project_id, work_id, chapter_id, chapter_no, content_hash, status) values(?, 7, 91, 911, ?, ?, ?, ?)",
            generationId, chapterId, chapterNo, contentHash, status);
        if (tombstoned != null) {
            jdbc.update(
                "insert into ai_project_chapter_head(user_id, project_id, work_id, chapter_no, active_chapter_id, active_generation_id, tombstoned_at) values(7, 91, 911, ?, ?, ?, ?)",
                chapterNo, chapterId, generationId,
                tombstoned ? java.sql.Timestamp.valueOf("2026-08-14 00:00:00") : null);
        }
    }

    private void insertDocument(JdbcTemplate jdbc,
                                long documentId,
                                long generationId,
                                String contentHash,
                                String documentStatus,
                                String generationStatus) {
        jdbc.update(
            "insert into ai_project_document(document_id, user_id, project_id, work_id, active_generation_id, status) values(?, 7, 91, 911, ?, ?)",
            documentId, generationId, documentStatus);
        jdbc.update(
            "insert into ai_project_document_generation(document_generation_id, document_id, user_id, project_id, work_id, content_hash, status) values(?, ?, 7, 91, 911, ?, ?)",
            generationId, documentId, contentHash, generationStatus);
    }

    private void insertFact(JdbcTemplate jdbc,
                            Long generationId,
                            Long documentGenerationId,
                            String status,
                            java.sql.Timestamp recordedToAt) {
        jdbc.update(
            "insert into ai_project_memory_fact(user_id, project_id, work_id, generation_id, document_generation_id, lifecycle_status, recorded_to_at) values(7, 91, 911, ?, ?, ?, ?)",
            generationId, documentGenerationId, status, recordedToAt);
    }

    private void insertSummary(JdbcTemplate jdbc,
                               Long generationId,
                               Long documentGenerationId,
                               String nodeType,
                               int chapterFrom,
                               int chapterTo,
                               String status,
                               java.sql.Timestamp recordedToAt) {
        jdbc.update(
            "insert into ai_project_summary_node(user_id, project_id, work_id, generation_id, document_generation_id, node_type, range_from_chapter, range_to_chapter, status, recorded_to_at) values(7, 91, 911, ?, ?, ?, ?, ?, ?, ?)",
            generationId, documentGenerationId, nodeType, chapterFrom, chapterTo, status, recordedToAt);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:project-memory-overview-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa", ""
        );
        return new JdbcTemplate(source);
    }
}
