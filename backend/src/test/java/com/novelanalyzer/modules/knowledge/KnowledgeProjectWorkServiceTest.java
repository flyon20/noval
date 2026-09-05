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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectWorkServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateOneDefaultWorkWhenLegacyProjectHasNoWorks() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        List<ProjectWorkVO> firstRead = service.listWorks(900L);
        List<ProjectWorkVO> secondRead = service.listWorks(900L);

        assertThat(firstRead).singleElement()
            .extracting(ProjectWorkVO::getTitle)
            .isEqualTo("小说项目");
        assertThat(secondRead).extracting(ProjectWorkVO::getWorkId)
            .containsExactly(firstRead.get(0).getWorkId());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_work where project_id = 900 and user_id = 7",
            Integer.class
        )).isEqualTo(1);
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
    void shouldListOnlyActiveWorksOwnedByCurrentUserAcrossProjects() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest current = new ProjectWorkRequest();
        current.setTitle("Current");
        service.createWork(900L, current);
        jdbcTemplate.update("insert into ai_project(project_id, user_id, name, status) values(901, 7, 'Old project', 'ACTIVE')");
        jdbcTemplate.update("insert into ai_project(project_id, user_id, name, status) values(902, 8, 'Private project', 'ACTIVE')");
        jdbcTemplate.update("insert into ai_project_work(user_id, project_id, title, status) values(7, 901, 'Owned reference', 'ACTIVE')");
        jdbcTemplate.update("insert into ai_project_work(user_id, project_id, title, status) values(8, 902, 'Other user reference', 'ACTIVE')");
        jdbcTemplate.update("insert into ai_project_work(user_id, project_id, title, status) values(7, 901, 'Archived reference', 'ARCHIVED')");

        assertThat(service.listMyWorkLibrary()).extracting(ProjectWorkVO::getTitle)
            .containsExactly("Owned reference", "Current")
            .doesNotContain("Other user reference", "Archived reference");
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
    void shouldCreateIngestArtifactsWhenImportingChapter() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        KnowledgeProjectWorkService service = service(jdbcTemplate, embeddingClient, qdrantClient);
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
        assertThat(count(jdbcTemplate, "ai_project_scene", chapter.getChapterId())).isEqualTo(0);
        service.materializeGenerationArtifacts(chapter, 101L);
        activateGeneration(jdbcTemplate, chapter, 101L);
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

        List<String> chunks = jdbcTemplate.queryForList(
            "select chunk_text from ai_project_vector_chunk where user_id = ? and project_id = ? and work_id = ?",
            String.class,
            7L,
            900L,
            work.getWorkId()
        );
        assertThat(chunks)
            .anyMatch(text -> text.contains("unknown admin signal"));
    }

    @Test
    void shouldReadOnlyStructuredFactsFromCurrentChapterHead() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectWorkService service = service(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("Generation-filtered Novel");
        ProjectWorkVO work = service.createWork(900L, workRequest);

        ProjectChapterImportRequest firstRequest = new ProjectChapterImportRequest();
        firstRequest.setChapterNo(3);
        firstRequest.setContent("retired version");
        ProjectChapterVO first = service.importChapter(900L, work.getWorkId(), firstRequest);
        ProjectChapterImportRequest secondRequest = new ProjectChapterImportRequest();
        secondRequest.setChapterNo(3);
        secondRequest.setContent("active version");
        ProjectChapterVO second = service.importChapter(900L, work.getWorkId(), secondRequest);
        insertGeneration(jdbcTemplate, first, 201L, "ACTIVE");
        activateGeneration(jdbcTemplate, second, 202L);

        jdbcTemplate.update(
            "insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, chapter_version, title, content, status) values(7, 900, ?, 201, 1, 'retired clue', 'retired clue body', 'OPEN')",
            work.getWorkId());
        jdbcTemplate.update(
            "insert into ai_project_foreshadowing(user_id, project_id, work_id, generation_id, chapter_version, title, content, status) values(7, 900, ?, 202, 2, 'active clue', 'active clue body', 'OPEN')",
            work.getWorkId());
        jdbcTemplate.update(
            "insert into ai_project_timeline_event(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, chapter_no, event_order, title, summary) values(7, 900, ?, ?, 201, 1, 'ACTIVE', 3, 1, 'retired event', 'retired event body')",
            work.getWorkId(), first.getChapterId());
        jdbcTemplate.update(
            "insert into ai_project_timeline_event(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, status, chapter_no, event_order, title, summary) values(7, 900, ?, ?, 202, 2, 'ACTIVE', 3, 1, 'active event', 'active event body')",
            work.getWorkId(), second.getChapterId());
        jdbcTemplate.update(
            "insert into ai_project_character_state(user_id, project_id, work_id, character_name, chapter_id, generation_id, chapter_version, status, chapter_no, state_summary) values(7, 900, ?, 'Lin', ?, 201, 1, 'ACTIVE', 3, 'retired state')",
            work.getWorkId(), first.getChapterId());
        jdbcTemplate.update(
            "insert into ai_project_character_state(user_id, project_id, work_id, character_name, chapter_id, generation_id, chapter_version, status, chapter_no, state_summary) values(7, 900, ?, 'Lin', ?, 202, 2, 'ACTIVE', 3, 'active state')",
            work.getWorkId(), second.getChapterId());
        jdbcTemplate.update(
            "insert into ai_project_world_rule(user_id, project_id, work_id, generation_id, chapter_version, status_proj, rule_type, title, content, status) values(7, 900, ?, 201, 1, 'ACTIVE', 'system', 'retired rule', 'retired rule body', 'ACTIVE')",
            work.getWorkId());
        jdbcTemplate.update(
            "insert into ai_project_world_rule(user_id, project_id, work_id, generation_id, chapter_version, status_proj, rule_type, title, content, status) values(7, 900, ?, 202, 2, 'ACTIVE', 'system', 'active rule', 'active rule body', 'ACTIVE')",
            work.getWorkId());

        assertThat(service.listForeshadowings(7L, 900L, work.getWorkId(), "OPEN", 10))
            .extracting(item -> item.get("title"))
            .containsExactly("active clue");
        assertThat(service.lookupTimeline(7L, 900L, work.getWorkId(), null, 10))
            .extracting(item -> item.get("summary"))
            .containsExactly("active event body");
        assertThat(service.lookupCharacterStates(7L, 900L, work.getWorkId(), null, 10))
            .extracting(item -> item.get("stateSummary"))
            .containsExactly("active state");
        assertThat(service.lookupWorldRules(7L, 900L, work.getWorkId(), null, 10))
            .extracting(item -> item.get("content"))
            .containsExactly("active rule body");

        jdbcTemplate.update(
            "update ai_project_chapter_head set tombstoned_at = current_timestamp where active_generation_id = 202");
        assertThat(service.listForeshadowings(7L, 900L, work.getWorkId(), null, 10)).isEmpty();
        assertThat(service.lookupTimeline(7L, 900L, work.getWorkId(), null, 10)).isEmpty();
        assertThat(service.lookupCharacterStates(7L, 900L, work.getWorkId(), null, 10)).isEmpty();
        assertThat(service.lookupWorldRules(7L, 900L, work.getWorkId(), null, 10)).isEmpty();
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
        service.materializeGenerationArtifacts(chapter, 102L);

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
                assertThat(payload).containsEntry("generation_id", 102L);
                assertThat(payload).containsEntry("chapter_version", chapter.getVersion());
                assertThat(payload).containsEntry("visibility", "private");
                assertThat(payload).containsKeys("project_vector_chunk_id", "source_type", "content_hash");
            });
    }

    @Test
    void shouldResumeOnlyMissingVectorChunksAfterPartialEmbeddingFailure() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        AtomicInteger upsertAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (upsertAttempts.incrementAndGet() == 2) {
                throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "qdrant interrupted");
            }
            return null;
        }).when(qdrantClient).upsertPoint(anyString(), any(), any());
        KnowledgeProjectWorkService service = service(jdbcTemplate, embeddingClient, qdrantClient);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("Checkpoint Novel");
        ProjectWorkVO work = service.createWork(900L, workRequest);

        ProjectChapterImportRequest request = new ProjectChapterImportRequest();
        request.setChapterNo(7);
        request.setContent("first checkpoint scene\n\nsecond checkpoint scene");
        ProjectChapterVO chapter = service.importChapter(900L, work.getWorkId(), request);

        assertThatThrownBy(() -> service.materializeGenerationArtifacts(chapter, 103L))
            .isInstanceOf(BusinessException.class);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = 103 and status = 'ACTIVE'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = 103 and status = 'PENDING'",
            Integer.class)).isEqualTo(1);

        clearInvocations(embeddingClient, qdrantClient);
        doNothing().when(qdrantClient).upsertPoint(anyString(), any(), any());
        KnowledgeProjectWorkService.ArtifactCounts counts =
            service.materializeGenerationArtifacts(chapter, 103L);

        assertThat(counts.sceneCount()).isEqualTo(2);
        assertThat(counts.vectorCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_scene where generation_id = 103", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = 103 and status = 'ACTIVE'",
            Integer.class)).isEqualTo(3);
        verify(embeddingClient, times(2)).embed(anyString());
        verify(qdrantClient, times(2)).upsertPoint(anyString(), any(), any());
    }

    @Test
    void shouldNamespaceVectorPointsByGenerationForSameChapterRepair() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        KnowledgeProjectWorkService service = service(jdbcTemplate, embeddingClient, qdrantClient);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("Repair Novel");
        ProjectWorkVO work = service.createWork(900L, workRequest);
        ProjectChapterImportRequest request = new ProjectChapterImportRequest();
        request.setChapterNo(8);
        request.setContent("repair the same immutable chapter");
        ProjectChapterVO chapter = service.importChapter(900L, work.getWorkId(), request);

        service.materializeGenerationArtifacts(chapter, 104L);
        service.materializeGenerationArtifacts(chapter, 105L);

        assertThat(jdbcTemplate.queryForObject(
            "select count(distinct qdrant_point_id) from ai_project_vector_chunk where generation_id in (104, 105)",
            Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = 104 and status = 'ACTIVE'",
            Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = 105 and status = 'ACTIVE'",
            Integer.class)).isEqualTo(2);
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

        Map<String, Object> selectedProject = service.resolveWork(7L, 900L, null, null, 10);
        assertThat(selectedProject).containsEntry("status", "resolved");
        assertThat(selectedProject).containsEntry("workId", firstWork.getWorkId());

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

        Map<String, Object> ambiguousSelectedProject = service.resolveWork(7L, 900L, null, null, 10);
        assertThat(ambiguousSelectedProject).containsEntry("status", "ambiguous");
        assertThat((List<?>) ambiguousSelectedProject.get("candidates")).hasSize(2);

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

    private void activateGeneration(JdbcTemplate jdbcTemplate, ProjectChapterVO chapter, Long generationId) {
        insertGeneration(jdbcTemplate, chapter, generationId, "ACTIVE");
        jdbcTemplate.update(
            "insert into ai_project_chapter_head(user_id, project_id, work_id, chapter_no, active_chapter_id, active_generation_id) values(?, ?, ?, ?, ?, ?)",
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterNo(),
            chapter.getChapterId(), generationId);
    }

    private void insertGeneration(JdbcTemplate jdbcTemplate,
                                  ProjectChapterVO chapter,
                                  Long generationId,
                                  String status) {
        jdbcTemplate.update(
            "insert into ai_project_ingest_generation(generation_id, user_id, project_id, work_id, chapter_id, chapter_no, status) values(?, ?, ?, ?, ?, ?, ?)",
            generationId, chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(),
            chapter.getChapterId(), chapter.getChapterNo(), status);
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
            create table ai_project_ingest_generation (
                generation_id bigint primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_id bigint not null,
                chapter_no int not null,
                status varchar(30) not null
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_chapter_head (
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_no int not null,
                active_chapter_id bigint,
                active_generation_id bigint,
                tombstoned_at timestamp,
                unique(user_id, project_id, work_id, chapter_no)
            )
            """);
        jdbcTemplate.execute("""
            create table ai_project_scene (
                scene_id bigint auto_increment primary key,
                user_id bigint not null,
                project_id bigint not null,
                work_id bigint not null,
                chapter_id bigint not null,
                generation_id bigint,
                chapter_version int,
                status varchar(30) default 'ACTIVE',
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
                generation_id bigint,
                chapter_version int,
                status varchar(30) default 'ACTIVE',
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
                generation_id bigint,
                chapter_version int,
                status_proj varchar(30) default 'ACTIVE',
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
                generation_id bigint,
                chapter_version int,
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
                generation_id bigint,
                chapter_version int,
                status varchar(30) default 'ACTIVE',
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
                generation_id bigint,
                chapter_version int,
                status varchar(30) default 'ACTIVE',
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
        jdbcTemplate.execute("create table ai_project_story_edge (generation_id bigint)");
        jdbcTemplate.execute("create table ai_project_story_node (generation_id bigint)");
        jdbcTemplate.execute("create table ai_project_search_document (generation_id bigint)");
        jdbcTemplate.execute("create table ai_project_extraction_candidate (generation_id bigint, status varchar(30))");
        jdbcTemplate.update("""
            insert into ai_project(project_id, user_id, name, description, status)
            values(?, ?, ?, ?, ?)
            """, 900L, 7L, "小说项目", "project", "ACTIVE");
        return jdbcTemplate;
    }
}
