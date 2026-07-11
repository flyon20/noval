package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectChapterImportRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectWorkServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateWorkAndListOnlyOwnedProjectWorks() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        ProjectWorkRequest request = new ProjectWorkRequest();
        request.setTitle("诸天外包特效师");
        request.setAlias("五毛特效");
        request.setGenre("都市脑洞");
        ProjectWorkVO created = service.createWork(900L, request);

        assertThat(created.getWorkId()).isNotNull();
        assertThat(created.getProjectId()).isEqualTo(900L);
        assertThat(created.getUserId()).isEqualTo(7L);
        assertThat(created.getTitle()).isEqualTo("诸天外包特效师");
        assertThat(service.listWorks(900L)).extracting(ProjectWorkVO::getTitle)
            .containsExactly("诸天外包特效师");

        AuthUserHolder.set(AuthUser.of(8L, "other", Set.of("USER")));

        assertThatThrownBy(() -> service.listWorks(900L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void shouldImportChapterIdempotentlyByContentHashAndVersionChangedChapter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("诸天外包特效师");
        ProjectWorkVO work = service.createWork(900L, workRequest);

        ProjectChapterImportRequest first = new ProjectChapterImportRequest();
        first.setChapterNo(1);
        first.setTitle("退稿夜，系统降临");
        first.setContent("主角在影视城被甲方退稿，诸天外包平台第一次亮起。");
        ProjectChapterVO firstImport = service.importChapter(900L, work.getWorkId(), first);
        ProjectChapterVO duplicate = service.importChapter(900L, work.getWorkId(), first);

        assertThat(duplicate.getChapterId()).isEqualTo(firstImport.getChapterId());
        assertThat(duplicate.getVersion()).isEqualTo(1);

        ProjectChapterImportRequest changed = new ProjectChapterImportRequest();
        changed.setChapterNo(1);
        changed.setTitle("退稿夜，系统降临-修订");
        changed.setContent("主角被退稿后绑定三端一体系统，接到第一个御剑特效单。");
        ProjectChapterVO changedImport = service.importChapter(900L, work.getWorkId(), changed);

        assertThat(changedImport.getChapterId()).isNotEqualTo(firstImport.getChapterId());
        assertThat(changedImport.getVersion()).isEqualTo(2);
        assertThat(service.listChapters(900L, work.getWorkId()))
            .extracting(ProjectChapterVO::getVersion)
            .containsExactly(1, 2);
    }

    @Test
    void shouldSearchChaptersWithinOwnedProjectOnly() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("诸天外包特效师");
        ProjectWorkVO work = service.createWork(900L, workRequest);
        ProjectChapterImportRequest request = new ProjectChapterImportRequest();
        request.setChapterNo(3);
        request.setTitle("剑仙交付");
        request.setContent("洛风接单，用真正的御剑轨迹完成仙侠特效。");
        service.importChapter(900L, work.getWorkId(), request);

        List<ProjectChapterVO> chapters = service.searchChapters(7L, 900L, work.getWorkId(), "御剑", 5);

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).getTitle()).isEqualTo("剑仙交付");
        assertThatThrownBy(() -> service.searchChapters(8L, 900L, work.getWorkId(), "御剑", 5))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void shouldCreateIngestArtifactsWhenImportingChapter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("Project Knowledge Novel");
        ProjectWorkVO work = service.createWork(900L, workRequest);

        ProjectChapterImportRequest request = new ProjectChapterImportRequest();
        request.setChapterNo(2);
        request.setTitle("First Order");
        request.setContent("""
            Lin Zhou receives a three-terminal system order from the project knowledge platform.
            System rule: the receiving terminal parses real-world client material, the execution terminal dispatches work, and the settlement terminal records rewards.

            Foreshadowing: an unknown admin signal remains unresolved after the first delivery.
            Timeline event: Lin Zhou completes the first special-effects order and opens the work studio path.
            """);

        ProjectChapterVO chapter = service.importChapter(900L, work.getWorkId(), request);

        assertThat(count(jdbcTemplate, "ai_project_ingest_job", chapter.getChapterId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_project_ingest_job where chapter_id = ?",
            String.class,
            chapter.getChapterId()
        )).isEqualTo("COMPLETED");
        assertThat(count(jdbcTemplate, "ai_project_scene", chapter.getChapterId())).isGreaterThanOrEqualTo(2);
        assertThat(count(jdbcTemplate, "ai_project_vector_chunk", chapter.getChapterId())).isGreaterThanOrEqualTo(3);

        assertThat(service.listForeshadowings(7L, 900L, work.getWorkId(), "OPEN", 10))
            .extracting(item -> String.valueOf(item.get("content")))
            .anyMatch(content -> content.contains("unknown admin signal"));
        assertThat(service.lookupWorldRules(7L, 900L, work.getWorkId(), "three-terminal", 10))
            .extracting(item -> String.valueOf(item.get("content")))
            .anyMatch(content -> content.contains("receiving terminal"));
        assertThat(service.lookupTimeline(7L, 900L, work.getWorkId(), "special-effects order", 10))
            .extracting(item -> String.valueOf(item.get("summary")))
            .anyMatch(summary -> summary.contains("special-effects order"));

        List<Map<String, Object>> chunks = service.searchVectorChunks(
            7L,
            900L,
            work.getWorkId(),
            "unknown admin signal",
            10
        );
        assertThat(chunks)
            .extracting(item -> String.valueOf(item.get("chunkText")))
            .anyMatch(text -> text.contains("unknown admin signal"));
        assertThatThrownBy(() -> service.searchVectorChunks(8L, 900L, work.getWorkId(), "unknown admin signal", 10))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void shouldEmbedAndUpsertProjectChunksToQdrantWhenImportingChapter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        KnowledgeProjectWorkService service = service(jdbcTemplate, embeddingClient, qdrantClient);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("Project Vector Novel");
        ProjectWorkVO work = service.createWork(900L, workRequest);

        ProjectChapterImportRequest request = new ProjectChapterImportRequest();
        request.setChapterNo(6);
        request.setTitle("Vector Delivery");
        request.setContent("""
            Foreshadowing: an admin signal is hidden in the settlement panel.

            System rule: three terminal delivery must keep receiving, execution, and settlement closed.
            """);

        ProjectChapterVO chapter = service.importChapter(900L, work.getWorkId(), request);

        verify(embeddingClient, atLeastOnce()).embed(anyString());
        verify(qdrantClient, atLeastOnce()).ensureCollection();
        org.mockito.ArgumentCaptor<Map<String, Object>> payloadCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(qdrantClient, atLeastOnce()).upsertPoint(anyString(), any(), payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues())
            .anySatisfy(payload -> {
                assertThat(payload).containsEntry("user_id", 7L);
                assertThat(payload).containsEntry("project_id", 900L);
                assertThat(payload).containsEntry("work_id", work.getWorkId());
                assertThat(payload).containsEntry("chapter_id", chapter.getChapterId());
                assertThat(payload).containsEntry("chapter_no", 6);
                assertThat(payload).containsEntry("visibility", "private");
                assertThat(payload).containsKeys("project_vector_chunk_id", "source_type", "content_hash");
            });
    }

    @Test
    void shouldSearchProjectChunksWithQdrantFirstAndFallbackToLexical() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        KnowledgeProjectWorkService service = service(jdbcTemplate, embeddingClient, qdrantClient);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("Project Vector Novel");
        ProjectWorkVO work = service.createWork(900L, workRequest);
        ProjectChapterImportRequest request = new ProjectChapterImportRequest();
        request.setChapterNo(7);
        request.setTitle("Admin Signal");
        request.setContent("Foreshadowing: the unknown admin signal remains unresolved in the backstage log.");
        service.importChapter(900L, work.getWorkId(), request);
        Long chunkId = jdbcTemplate.queryForObject(
            "select id from ai_project_vector_chunk where work_id = ? order by id asc limit 1",
            Long.class,
            work.getWorkId()
        );

        reset(embeddingClient, qdrantClient);
        when(embeddingClient.embed("admin signal")).thenReturn(List.of(0.9d, 0.1d));
        when(qdrantClient.search(any(), anyMap(), anyInt())).thenReturn(List.of(
            new QdrantClient.SearchResult("qdrant-point", 0.91d, Map.of("project_vector_chunk_id", chunkId))
        ));

        List<Map<String, Object>> qdrantResults = service.searchVectorChunks(
            7L, 900L, work.getWorkId(), "admin signal", 5
        );

        assertThat(qdrantResults).hasSize(1);
        assertThat(qdrantResults.get(0)).containsEntry("retrievalBackend", "qdrant");
        assertThat(qdrantResults.get(0)).containsEntry("score", 0.91d);
        org.mockito.ArgumentCaptor<Map<String, Object>> filterCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(qdrantClient).search(any(), filterCaptor.capture(), anyInt());
        assertThat(filterCaptor.getValue()).containsEntry("user_id", 7L)
            .containsEntry("project_id", 900L)
            .containsEntry("work_id", work.getWorkId())
            .containsEntry("visibility", "private");

        reset(embeddingClient, qdrantClient);
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("embedding unavailable"));
        List<Map<String, Object>> lexicalResults = service.searchVectorChunks(
            7L, 900L, work.getWorkId(), "admin signal", 5
        );

        assertThat(lexicalResults).isNotEmpty();
        assertThat(lexicalResults.get(0)).containsEntry("retrievalBackend", "lexical");
    }

    @Test
    void shouldResolveOwnedProjectWorkByUniqueTitleOrAliasAndReportAmbiguousMatches() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest first = new ProjectWorkRequest();
        first.setTitle("Myriad Outsourcing Effects");
        first.setAlias("Five Cent VFX");
        ProjectWorkVO firstWork = service.createWork(900L, first);
        ProjectWorkRequest second = new ProjectWorkRequest();
        second.setTitle("Myriad Outsourcing Sequel");
        service.createWork(900L, second);

        Map<String, Object> resolved = service.resolveWork(7L, null, null, "Five Cent VFX", 10);
        assertThat(resolved).containsEntry("status", "resolved");
        assertThat(resolved).containsEntry("workId", firstWork.getWorkId());
        assertThat(resolved).containsEntry("projectId", 900L);

        Map<String, Object> ambiguous = service.resolveWork(7L, null, null, "Myriad Outsourcing", 10);
        assertThat(ambiguous).containsEntry("status", "ambiguous");
        assertThat((List<?>) ambiguous.get("candidates")).hasSize(2);

        Map<String, Object> wrongUser = service.resolveWork(8L, null, null, "Five Cent VFX", 10);
        assertThat(wrongUser).containsEntry("status", "not_found");
    }

    private int count(JdbcTemplate jdbcTemplate, String table, Long chapterId) {
        Integer value = jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where chapter_id = ?",
            Integer.class,
            chapterId
        );
        return value == null ? 0 : value;
    }

    private KnowledgeProjectWorkService service(JdbcTemplate jdbcTemplate) {
        return new KnowledgeProjectWorkService(jdbcTemplate, new KnowledgeProjectService(jdbcTemplate));
    }

    private KnowledgeProjectWorkService service(JdbcTemplate jdbcTemplate,
                                                EmbeddingClient embeddingClient,
                                                QdrantClient qdrantClient) {
        return new KnowledgeProjectWorkService(
            jdbcTemplate,
            new KnowledgeProjectService(jdbcTemplate),
            embeddingClient,
            qdrantClient
        );
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:project-work-test-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table ai_project (
                project_id bigint auto_increment primary key,
                user_id bigint not null,
                name varchar(120) not null,
                description varchar(500),
                status varchar(20) not null,
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_conversation (
                id bigint auto_increment primary key,
                project_id bigint not null,
                user_id bigint not null,
                conversation_id varchar(80) not null,
                created_at timestamp default current_timestamp,
                unique(project_id, conversation_id)
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_work (
                work_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                title varchar(200) not null,
                alias varchar(500),
                genre varchar(80),
                status varchar(30) not null default 'ACTIVE',
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_chapter (
                chapter_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_no int not null,
                title varchar(200),
                content clob not null,
                content_hash varchar(128) not null,
                word_count int not null default 0,
                source_type varchar(40) not null default 'upload',
                version int not null default 1,
                status varchar(30) not null default 'ACTIVE',
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp,
                unique(work_id, chapter_no, content_hash)
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_scene (
                scene_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_id bigint not null,
                scene_no int not null,
                summary clob,
                pov varchar(120),
                location varchar(200),
                time_marker varchar(200),
                start_offset int,
                end_offset int,
                confidence decimal(5,4),
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_character_state (
                state_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                character_id bigint,
                character_name varchar(120) not null,
                chapter_id bigint,
                chapter_no int,
                scene_id bigint,
                state_summary clob,
                motivation clob,
                conflict_note clob,
                confidence decimal(5,4),
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_world_rule (
                rule_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                rule_type varchar(80),
                title varchar(200) not null,
                content clob,
                first_chapter_no int,
                status varchar(30) not null default 'ACTIVE',
                confidence decimal(5,4),
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_foreshadowing (
                foreshadowing_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                title varchar(200) not null,
                content clob,
                status varchar(30) not null default 'OPEN',
                planted_chapter_no int,
                paid_off_chapter_no int,
                importance varchar(30),
                evidence_refs clob,
                confidence decimal(5,4),
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_timeline_event (
                event_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_id bigint,
                chapter_no int,
                scene_id bigint,
                event_order int,
                title varchar(200) not null,
                summary clob,
                causal_refs clob,
                confidence decimal(5,4),
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_ingest_job (
                ingest_job_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_id bigint,
                job_type varchar(60) not null,
                status varchar(30) not null default 'PENDING',
                progress int not null default 0,
                error_summary varchar(500),
                result_json clob,
                created_at timestamp default current_timestamp,
                updated_at timestamp default current_timestamp
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_vector_chunk (
                id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_id bigint,
                scene_id bigint,
                source_type varchar(60) not null,
                source_id bigint,
                content_hash varchar(128) not null,
                qdrant_point_id varchar(160) not null,
                chunk_text clob,
                visibility varchar(30) not null default 'private',
                created_at timestamp default current_timestamp,
                unique(qdrant_point_id)
            )
            """);
        jdbcTemplate.update("""
            insert into ai_project(project_id, user_id, name, description, status)
            values(?, ?, ?, ?, ?)
            """, 900L, 7L, "小说项目", "project", "ACTIVE");
        return jdbcTemplate;
    }
}
