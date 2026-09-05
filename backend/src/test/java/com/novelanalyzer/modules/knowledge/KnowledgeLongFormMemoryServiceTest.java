package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.service.KnowledgeLongFormMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.vo.ForeshadowingAggregateVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeLongFormMemoryServiceTest {

    @Test
    void aggregatesOnlyRecognizedForeshadowingsFromActiveCorpusGenerations() {
        JdbcTemplate jdbc = jdbcTemplate();
        insertScope(jdbc);
        insertChapterGeneration(jdbc, 101L, 201L, 1, "chapter-hash-1", "ACTIVE", true);
        insertChapterGeneration(jdbc, 102L, 202L, 2, "chapter-hash-2", "ACTIVE", true);
        insertChapterGeneration(jdbc, 103L, 203L, 2, "retired-hash", "RETIRED", false);
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 201, 'open clue', 'OPEN')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 202, 'paid clue', 'PAID_OFF')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 202, 'disputed clue', 'DISPUTED')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 202, 'archived clue', 'ARCHIVED')");
        jdbc.update("insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, title, status) values(7, 91, 911, 203, 'retired clue', 'OPEN')");
        insertActiveDocument(jdbc, 301L, 401L, "document-hash-1");

        ForeshadowingAggregateVO aggregate = service(jdbc).aggregateForeshadowings(7L, 91L, 911L);

        assertThat(aggregate.getMetric()).isEqualTo("foreshadowing_count");
        assertThat(aggregate.getCount()).isEqualTo(3L);
        assertThat(aggregate.getBreakdown()).containsExactly(
            org.assertj.core.data.MapEntry.entry("DISPUTED", 1L),
            org.assertj.core.data.MapEntry.entry("OPEN", 1L),
            org.assertj.core.data.MapEntry.entry("PAID_OFF", 1L)
        );
        assertThat(aggregate.isComplete()).isTrue();
        assertThat(aggregate.isPartial()).isFalse();
        assertThat(aggregate.isRecognizedRecordsOnly()).isTrue();
        assertThat(aggregate.getActiveChapterGenerationCount()).isEqualTo(2L);
        assertThat(aggregate.getActiveDocumentGenerationCount()).isEqualTo(1L);
        assertThat(aggregate.getGenerationFingerprint()).startsWith("sha256:").hasSize(71);

        String before = aggregate.getGenerationFingerprint();
        jdbc.update("update ai_project_ingest_generation set content_hash = 'chapter-hash-2-revised' where generation_id = 202");
        String after = service(jdbc).aggregateForeshadowings(7L, 91L, 911L).getGenerationFingerprint();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void rejectsWrongUserProjectOrWorkScope() {
        JdbcTemplate jdbc = jdbcTemplate();
        insertScope(jdbc);

        assertThatThrownBy(() -> service(jdbc).aggregateForeshadowings(8L, 91L, 911L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service(jdbc).aggregateForeshadowings(7L, 92L, 911L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service(jdbc).aggregateForeshadowings(7L, 91L, 912L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private KnowledgeLongFormMemoryService service(JdbcTemplate jdbc) {
        return new KnowledgeLongFormMemoryService(jdbc, new KnowledgeProjectService(jdbc));
    }

    private void insertScope(JdbcTemplate jdbc) {
        jdbc.update("insert into ai_project(project_id, user_id, name, status) values(91, 7, 'Novel', 'ACTIVE')");
        jdbc.update("insert into ai_project_work(work_id, user_id, project_id, title, status) values(911, 7, 91, 'Novel Work', 'ACTIVE')");
    }

    private void insertChapterGeneration(JdbcTemplate jdbc, long chapterId, long generationId,
                                         int chapterNo, String contentHash, String status, boolean activeHead) {
        jdbc.update(
            "insert into ai_project_ingest_generation(generation_id, user_id, project_id, work_id, chapter_id, chapter_no, content_hash, status) values(?, 7, 91, 911, ?, ?, ?, ?)",
            generationId, chapterId, chapterNo, contentHash, status);
        if (activeHead) {
            jdbc.update(
                "insert into ai_project_chapter_head(user_id, project_id, work_id, chapter_no, active_chapter_id, active_generation_id) values(7, 91, 911, ?, ?, ?)",
                chapterNo, chapterId, generationId);
        }
    }

    private void insertActiveDocument(JdbcTemplate jdbc, long documentId, long generationId, String contentHash) {
        jdbc.update(
            "insert into ai_project_document(document_id, user_id, project_id, work_id, active_generation_id, status) values(?, 7, 91, 911, ?, 'ACTIVE')",
            documentId, generationId);
        jdbc.update(
            "insert into ai_project_document_generation(document_generation_id, document_id, user_id, project_id, work_id, content_hash, status) values(?, ?, 7, 91, 911, ?, 'ACTIVE')",
            generationId, documentId, contentHash);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:long-form-memory-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa", ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table ai_project(project_id bigint primary key, user_id bigint not null, name varchar(120), description varchar(500), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.execute("create table ai_project_work(work_id bigint primary key, user_id bigint not null, project_id bigint not null, title varchar(200), alias varchar(500), genre varchar(80), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.execute("create table ai_project_ingest_generation(generation_id bigint primary key, user_id bigint not null, project_id bigint not null, work_id bigint not null, chapter_id bigint not null, chapter_no int not null, content_hash varchar(128), status varchar(30))");
        jdbc.execute("create table ai_project_chapter_head(user_id bigint not null, project_id bigint not null, work_id bigint not null, chapter_no int not null, active_chapter_id bigint, active_generation_id bigint, tombstoned_at timestamp)");
        jdbc.execute("create table ai_project_foreshadowing(foreshadowing_id bigint auto_increment primary key, user_id bigint not null, project_id bigint not null, work_id bigint not null, generation_id bigint, title varchar(200), status varchar(30))");
        jdbc.execute("create table ai_project_document(document_id bigint primary key, user_id bigint not null, project_id bigint not null, work_id bigint not null, active_generation_id bigint, status varchar(40))");
        jdbc.execute("create table ai_project_document_generation(document_generation_id bigint primary key, document_id bigint not null, user_id bigint not null, project_id bigint not null, work_id bigint not null, content_hash varchar(128), status varchar(40))");
        return jdbc;
    }
}
