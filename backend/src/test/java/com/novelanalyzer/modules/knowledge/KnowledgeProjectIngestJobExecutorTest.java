package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectIngestSubmitRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeProjectIngestJobExecutorTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldKeepPreviousGenerationActiveWhenQdrantFails() throws Exception {
        Fixture fixture = fixture(new KnowledgeProperties());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();

        ProjectIngestJobVO first = fixture.submit(workId, 1, "stable version");
        fixture.executor.execute(first.getIngestJobId(), 1);
        assertThat(fixture.service.getJobById(first.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_READY);

        doThrow(new BusinessException(ResultCode.INTERNAL_ERROR, "qdrant unavailable"))
            .when(fixture.qdrantClient).upsertPoint(anyString(), anyList(), anyMap());
        ProjectIngestJobVO second = fixture.submit(workId, 1, "replacement version");
        fixture.executor.execute(second.getIngestJobId(), 1);

        assertThat(fixture.service.getJobById(second.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_RETRYABLE_FAILED);
        assertThat(fixture.jdbc.queryForObject(
            "select active_generation_id from ai_project_chapter_head where work_id = ? and chapter_no = 1",
            Long.class, workId)).isEqualTo(first.getGenerationId());
        assertThat(fixture.jdbc.queryForObject(
            "select status from ai_project_ingest_generation where generation_id = ?",
            String.class, second.getGenerationId())).isNotEqualTo(KnowledgeProjectIngestService.GEN_ACTIVE);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 2 and status = 'PENDING'",
            Integer.class, second.getIngestJobId())).isEqualTo(1);
    }

    @Test
    void shouldTerminateFourthAutomaticAttemptWithoutCreatingFifthOutbox() throws Exception {
        Fixture fixture = fixture(new KnowledgeProperties());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        doThrow(new BusinessException(ResultCode.INTERNAL_ERROR, "embedding gateway unavailable"))
            .when(fixture.qdrantClient).upsertPoint(anyString(), anyList(), anyMap());
        ProjectIngestJobVO job = fixture.submit(workId, 1, "fourth attempt");
        fixture.jdbc.update(
            "update ai_project_ingest_job set attempt = 4, max_attempts = 4 where ingest_job_id = ?",
            job.getIngestJobId());

        fixture.executor.execute(job.getIngestJobId(), 4);

        assertThat(fixture.service.getJobById(job.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_TERMINAL_FAILED);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 5",
            Integer.class, job.getIngestJobId())).isZero();
        assertThat(fixture.jdbc.queryForMap(
            "select status, cleanup_status from ai_project_ingest_generation where generation_id = ?",
            job.getGenerationId()))
            .containsEntry("status", KnowledgeProjectIngestService.GEN_FAILED)
            .containsEntry("cleanup_status", "QUEUED");
    }

    @Test
    void shouldNotRetryUnknownExecutionFailure() throws Exception {
        Fixture fixture = fixture(new KnowledgeProperties());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        doThrow(new IllegalStateException("unexpected parser failure"))
            .when(fixture.qdrantClient).upsertPoint(anyString(), anyList(), anyMap());
        ProjectIngestJobVO job = fixture.submit(workId, 1, "unknown failure");

        fixture.executor.execute(job.getIngestJobId(), 1);

        assertThat(fixture.service.getJobById(job.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_TERMINAL_FAILED);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 2",
            Integer.class, job.getIngestJobId())).isZero();
    }

    @Test
    void shouldRenewLeaseWhileEmbeddingStageIsBlocked() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getProjectIngest().setHeartbeatSeconds(1);
        properties.getProjectIngest().setLeaseSeconds(15);
        Fixture fixture = fixture(properties);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        when(fixture.embeddingClient.embed(anyString())).thenAnswer(invocation -> {
            embeddingStarted.countDown();
            if (!releaseEmbedding.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("embedding test timeout");
            }
            return List.of(0.1d, 0.2d, 0.3d);
        });
        ProjectIngestJobVO job = fixture.submit(workId, 1, "slow embedding");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> execution = pool.submit(() -> fixture.executor.execute(job.getIngestJobId(), 1));

        assertThat(embeddingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Timestamp firstLease = fixture.jdbc.queryForObject(
            "select lease_expires_at from ai_project_ingest_job where ingest_job_id = ?",
            Timestamp.class, job.getIngestJobId());
        Thread.sleep(1_300L);
        Timestamp renewedLease = fixture.jdbc.queryForObject(
            "select lease_expires_at from ai_project_ingest_job where ingest_job_id = ?",
            Timestamp.class, job.getIngestJobId());
        releaseEmbedding.countDown();
        execution.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(renewedLease).isAfter(firstLease);
        assertThat(fixture.service.getJobById(job.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_READY);
    }

    @Test
    void shouldStopOldWorkerBeforeVectorActivationAfterLeaseTakeover() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getProjectIngest().setHeartbeatSeconds(1);
        properties.getProjectIngest().setLeaseSeconds(15);
        Fixture fixture = fixture(properties);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        when(fixture.embeddingClient.embed(anyString())).thenAnswer(invocation -> {
            embeddingStarted.countDown();
            if (!releaseEmbedding.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("embedding test timeout");
            }
            return List.of(0.1d, 0.2d, 0.3d);
        });
        ProjectIngestJobVO job = fixture.submit(workId, 1, "lease takeover");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> execution = pool.submit(() -> fixture.executor.execute(job.getIngestJobId(), 1));

        try {
            assertThat(embeddingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            int takenOver = fixture.jdbc.update(
                "update ai_project_ingest_job set lease_owner = ?, fencing_token = fencing_token + 1, lease_expires_at = ? where ingest_job_id = ?",
                "replacement-worker", new Timestamp(System.currentTimeMillis() + 60_000L), job.getIngestJobId());
            assertThat(takenOver).isEqualTo(1);
            releaseEmbedding.countDown();
            execution.get(5, TimeUnit.SECONDS);
        } finally {
            releaseEmbedding.countDown();
            pool.shutdownNow();
        }

        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = ? and status = 'ACTIVE'",
            Integer.class, job.getGenerationId())).isZero();
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = ? and status = 'PENDING'",
            Integer.class, job.getGenerationId())).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_generation where generation_id = ? and status = 'ACTIVE'",
            Integer.class, job.getGenerationId())).isZero();
        verifyNoInteractions(fixture.qdrantClient);
    }

    @Test
    void shouldRejectStaleGenerationDeletesAndWritesAfterLeaseTakeover() throws Exception {
        Fixture fixture = fixture(new KnowledgeProperties());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        ProjectIngestJobVO job = fixture.submit(workId, 1, "stale generation write");
        fixture.jdbc.update(
            "update ai_project_ingest_job set status = 'PARSING', stage = 'PARSING', lease_owner = ?, "
                + "fencing_token = 2, lease_expires_at = ? where ingest_job_id = ?",
            "replacement-worker", new Timestamp(System.currentTimeMillis() + 60_000L), job.getIngestJobId());
        fixture.jdbc.update(
            """
                insert into ai_project_world_rule(user_id, project_id, work_id, generation_id, chapter_version,
                    status_proj, rule_type, title, content, first_chapter_no, status, confidence)
                values(?, ?, ?, ?, 1, 'ACTIVE', 'system_rule', 'replacement data', 'keep replacement data', 1, 'ACTIVE', 0.8)
                """,
            7L, 900L, workId, job.getGenerationId());

        KnowledgeProjectWorkService.ExecutionFence staleFence =
            new KnowledgeProjectWorkService.ExecutionFence(job.getIngestJobId(), "stale-worker", 1L);

        assertThatThrownBy(() -> fixture.workService.materializeGenerationArtifacts(
            fixture.service.loadChapter(job.getChapterId()), job.getGenerationId(), () -> {
            }, staleFence))
            .isInstanceOf(KnowledgeProjectWorkService.ExecutionLeaseLostException.class);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_world_rule where generation_id = ? and title = 'replacement data'",
            Integer.class, job.getGenerationId())).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_scene where generation_id = ?",
            Integer.class, job.getGenerationId())).isZero();
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = ?",
            Integer.class, job.getGenerationId())).isZero();
        verifyNoInteractions(fixture.qdrantClient);
    }

    @Test
    void shouldNotInsertCandidateAfterLeaseTakeover() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        Fixture fixture = fixture(properties);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        ProjectIngestJobVO job = fixture.submit(workId, 1, "candidate lease takeover");
        KnowledgeProjectIngestService spiedService = spy(fixture.service);
        doAnswer(invocation -> {
            boolean transitioned = (boolean) invocation.callRealMethod();
            if (transitioned && KnowledgeProjectIngestService.GEN_STRUCTURED_READY.equals(invocation.getArgument(5))) {
                fixture.jdbc.update(
                    "update ai_project_ingest_job set lease_owner = ?, fencing_token = fencing_token + 1, "
                        + "lease_expires_at = ? where ingest_job_id = ?",
                    "replacement-worker", new Timestamp(System.currentTimeMillis() + 60_000L), job.getIngestJobId());
            }
            return transitioned;
        }).when(spiedService).transitionGeneration(
            anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyString());
        KnowledgeProjectIngestJobExecutor executor = new KnowledgeProjectIngestJobExecutor(
            spiedService, fixture.workService, new KnowledgeStoryGraphService(fixture.jdbc), properties, fixture.jdbc);

        executor.execute(job.getIngestJobId(), 1);

        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_extraction_candidate where generation_id = ?",
            Integer.class, job.getGenerationId())).isZero();
        assertThat(fixture.jdbc.queryForObject(
            "select status from ai_project_ingest_job where ingest_job_id = ?",
            String.class, job.getIngestJobId())).isEqualTo(KnowledgeProjectIngestService.JOB_EXTRACTING);
        assertThat(fixture.jdbc.queryForObject(
            "select lease_owner from ai_project_ingest_job where ingest_job_id = ?",
            String.class, job.getIngestJobId())).isEqualTo("replacement-worker");
    }

    @Test
    void shouldApplyDuplicateRabbitAttemptOnlyOnce() throws Exception {
        Fixture fixture = fixture(new KnowledgeProperties());
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = fixture.createWork();
        ProjectIngestJobVO job = fixture.submit(workId, 1, "duplicate delivery");

        fixture.executor.execute(job.getIngestJobId(), 1);
        int sceneCount = fixture.jdbc.queryForObject(
            "select count(*) from ai_project_scene where generation_id = ?",
            Integer.class, job.getGenerationId());
        int vectorCount = fixture.jdbc.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = ?",
            Integer.class, job.getGenerationId());
        clearInvocations(fixture.embeddingClient, fixture.qdrantClient);

        fixture.executor.execute(job.getIngestJobId(), 1);

        assertThat(fixture.service.getJobById(job.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_READY);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_scene where generation_id = ?",
            Integer.class, job.getGenerationId())).isEqualTo(sceneCount);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_vector_chunk where generation_id = ?",
            Integer.class, job.getGenerationId())).isEqualTo(vectorCount);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_generation where generation_id = ? and status = 'ACTIVE'",
            Integer.class, job.getGenerationId())).isEqualTo(1);
        verifyNoInteractions(fixture.embeddingClient, fixture.qdrantClient);
    }

    private Fixture fixture(KnowledgeProperties properties) throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbc);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        KnowledgeProjectWorkService workService = new KnowledgeProjectWorkService(
            jdbc, projectService, embeddingClient, qdrantClient);
        KnowledgeProjectIngestService service = new KnowledgeProjectIngestService(
            jdbc, projectService, workService, properties, emptyProvider(), emptyProvider(), emptyProvider());
        KnowledgeProjectIngestJobExecutor executor = new KnowledgeProjectIngestJobExecutor(
            service, workService, new KnowledgeStoryGraphService(jdbc), properties, jdbc);
        return new Fixture(jdbc, service, executor, embeddingClient, qdrantClient, workService);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectProvider emptyProvider() {
        ObjectProvider provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private JdbcTemplate jdbc() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:project_ingest_executor_" + System.nanoTime()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table ai_project(project_id bigint primary key, user_id bigint not null, name varchar(200), description varchar(500), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.update("insert into ai_project(project_id, user_id, name, description, status) values(900, 7, 'p', 'd', 'ACTIVE')");
        runScript(jdbc, resource("sql/phase16-project-knowledge-rag-h2.sql"));
        runScript(jdbc, resource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        return jdbc;
    }

    private Path resource(String classpathRelative) throws Exception {
        Path candidate = Path.of("src/test/resources").resolve(classpathRelative);
        if (Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }
        return Path.of(getClass().getClassLoader().getResource(classpathRelative).toURI());
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == 0xFEFF) {
            sql = sql.substring(1);
        }
        for (String statement : sql.split(";")) {
            StringBuilder executable = new StringBuilder();
            for (String line : statement.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    executable.append(line).append('\n');
                }
            }
            if (!executable.toString().isBlank()) {
                try {
                    jdbc.execute(executable.toString().trim());
                } catch (RuntimeException ex) {
                    String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                    if (!message.contains("already exists") && !message.contains("duplicate")) {
                        throw ex;
                    }
                }
            }
        }
    }

    private record Fixture(JdbcTemplate jdbc,
                           KnowledgeProjectIngestService service,
                           KnowledgeProjectIngestJobExecutor executor,
                           EmbeddingClient embeddingClient,
                           QdrantClient qdrantClient,
                           KnowledgeProjectWorkService workService) {

        private Long createWork() {
            ProjectWorkRequest request = new ProjectWorkRequest();
            request.setTitle("test-work");
            return workService.createWork(900L, request).getWorkId();
        }

        private ProjectIngestJobVO submit(Long workId, int chapterNo, String content) {
            ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
            request.setChapterNo(chapterNo);
            request.setContent(content);
            return service.submit(900L, workId, request);
        }
    }
}
