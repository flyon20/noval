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
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestReconciliationService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeProjectIngestReconciliationServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateAndExecuteOneRepairGenerationForMissingVectors() throws Exception {
        Fixture fixture = fixture();
        ProjectIngestJobVO active = fixture.ingest(1, "active chapter");
        fixture.jdbc.update(
            "update ai_project_ingest_generation set expected_vector_count = 2, vector_count = 1 where generation_id = ?",
            active.getGenerationId());

        int repaired = fixture.reconciliation.reconcile(20);

        assertThat(repaired).isGreaterThanOrEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_generation where chapter_id = ?",
            Integer.class, active.getChapterId())).isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_job where idempotency_key = ? and status = 'UPLOADED'",
            Integer.class, "repair-vector:" + active.getGenerationId())).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_outbox o join ai_project_ingest_job j on j.ingest_job_id = o.ingest_job_id where j.idempotency_key = ? and o.attempt = 1",
            Integer.class, "repair-vector:" + active.getGenerationId())).isEqualTo(1);

        fixture.reconciliation.reconcile(20);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_job where idempotency_key = ?",
            Integer.class, "repair-vector:" + active.getGenerationId())).isEqualTo(1);

        Long repairJobId = fixture.jdbc.queryForObject(
            "select ingest_job_id from ai_project_ingest_job where idempotency_key = ?",
            Long.class, "repair-vector:" + active.getGenerationId());
        Long repairGenerationId = fixture.jdbc.queryForObject(
            "select generation_id from ai_project_ingest_job where ingest_job_id = ?",
            Long.class, repairJobId);
        fixture.executor.execute(repairJobId, 1);

        assertThat(fixture.ingestService.getJobById(repairJobId).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_READY);
        assertThat(fixture.jdbc.queryForObject(
            "select active_generation_id from ai_project_chapter_head where work_id = ? and chapter_no = 1",
            Long.class, active.getWorkId())).isEqualTo(repairGenerationId);
        assertThat(fixture.jdbc.queryForObject(
            "select count(*) from ai_project_ingest_generation where chapter_id = ? and status = 'ACTIVE'",
            Integer.class, active.getChapterId())).isEqualTo(1);
    }

    @Test
    void shouldPauseRetiredCleanupUnderPressureAndResumeLater() throws Exception {
        Fixture fixture = fixture();
        ProjectIngestJobVO first = fixture.ingest(1, "first version");
        fixture.ingest(1, "second version");
        clearInvocations(fixture.qdrantClient);
        when(fixture.pressureService.shouldSuppressLowPriorityWork()).thenReturn(true);

        fixture.reconciliation.reconcile(20);

        assertThat(fixture.jdbc.queryForObject(
            "select cleanup_status from ai_project_ingest_generation where generation_id = ?",
            String.class, first.getGenerationId())).isEqualTo("QUEUED");
        verifyNoInteractions(fixture.qdrantClient);

        when(fixture.pressureService.shouldSuppressLowPriorityWork()).thenReturn(false);
        fixture.reconciliation.reconcile(20);

        assertThat(fixture.jdbc.queryForObject(
            "select cleanup_status from ai_project_ingest_generation where generation_id = ?",
            String.class, first.getGenerationId())).isEqualTo("COMPLETE");
        verify(fixture.qdrantClient).deletePoints(eq(java.util.Map.of("generation_id", first.getGenerationId())));
    }

    @Test
    void shouldSerializeFailedGenerationCleanupWithExplicitRetry() throws Exception {
        Fixture fixture = fixture();
        ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
        request.setChapterNo(2);
        request.setContent("failed cleanup retry");
        ProjectIngestJobVO job = fixture.ingestService.submit(900L, fixture.workId, request);
        KnowledgeProjectIngestService.ClaimResult claim = fixture.ingestService.claimJob(
            job.getIngestJobId(), 1, "failed-worker", java.time.Duration.ofSeconds(30));
        assertThat(claim).isNotNull();
        assertThat(fixture.ingestService.failAndScheduleRetry(
            job.getIngestJobId(), "failed-worker", claim.fencingToken(),
            "EXECUTE_FAILED", "terminal failure", false))
            .isEqualTo(KnowledgeProjectIngestService.FailureDisposition.TERMINAL);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupStarted.countDown();
            if (!releaseCleanup.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("cleanup test timeout");
            }
            return null;
        }).when(fixture.qdrantClient).deletePoints(eq(java.util.Map.of("generation_id", job.getGenerationId())));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> cleanup = pool.submit(() -> fixture.reconciliation.reconcile(20));

        try {
            assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.jdbc.queryForObject(
                "select cleanup_status from ai_project_ingest_generation where generation_id = ?",
                String.class, job.getGenerationId())).isEqualTo("RUNNING");
            assertThatThrownBy(() -> fixture.ingestService.retry(900L, job.getIngestJobId()))
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.CONFLICT);
            assertThat(fixture.ingestService.getJobById(job.getIngestJobId()).getStatus())
                .isEqualTo(KnowledgeProjectIngestService.JOB_TERMINAL_FAILED);
            releaseCleanup.countDown();
            assertThat(cleanup.get(5, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(1);
        } finally {
            releaseCleanup.countDown();
            pool.shutdownNow();
        }

        ProjectIngestJobVO retried = fixture.ingestService.retry(900L, job.getIngestJobId());
        assertThat(retried.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_UPLOADED);
        assertThat(fixture.jdbc.queryForMap(
            "select status, cleanup_status from ai_project_ingest_generation where generation_id = ?",
            job.getGenerationId()))
            .containsEntry("status", KnowledgeProjectIngestService.GEN_PREPARED)
            .containsEntry("cleanup_status", null);
    }

    private Fixture fixture() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties properties = new KnowledgeProperties();
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbc);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        KnowledgeProjectWorkService workService = new KnowledgeProjectWorkService(
            jdbc, projectService, embeddingClient, qdrantClient);
        KnowledgeProjectIngestService ingestService = new KnowledgeProjectIngestService(
            jdbc, projectService, workService, properties, emptyProvider(), emptyProvider(), emptyProvider());
        KnowledgeProjectIngestJobExecutor executor = new KnowledgeProjectIngestJobExecutor(
            ingestService, workService, new KnowledgeStoryGraphService(jdbc), properties, jdbc);
        AgentResourcePressureService pressureService = mock(AgentResourcePressureService.class);
        KnowledgeProjectIngestReconciliationService reconciliation =
            new KnowledgeProjectIngestReconciliationService(
                jdbc, ingestService, workService, pressureService, properties);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        ProjectWorkRequest workRequest = new ProjectWorkRequest();
        workRequest.setTitle("test-work");
        Long workId = workService.createWork(900L, workRequest).getWorkId();
        return new Fixture(jdbc, ingestService, executor, reconciliation, qdrantClient, pressureService, workId);
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
        dataSource.setUrl("jdbc:h2:mem:project_ingest_reconcile_" + System.nanoTime()
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
                           KnowledgeProjectIngestService ingestService,
                           KnowledgeProjectIngestJobExecutor executor,
                           KnowledgeProjectIngestReconciliationService reconciliation,
                           QdrantClient qdrantClient,
                           AgentResourcePressureService pressureService,
                           Long workId) {

        private ProjectIngestJobVO ingest(int chapterNo, String content) {
            ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
            request.setChapterNo(chapterNo);
            request.setContent(content);
            ProjectIngestJobVO job = ingestService.submit(900L, workId, request);
            executor.execute(job.getIngestJobId(), 1);
            return job;
        }
    }
}
