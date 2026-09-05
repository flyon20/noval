package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectExtractionReviewRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectIngestSubmitRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectWorkService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeStoryGraphService;
import com.novelanalyzer.modules.knowledge.vo.ProjectExtractionCandidateVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProjectIngestServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldSubmitIdempotentlyAndRejectParamConflict() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest req = new ProjectIngestSubmitRequest();
        req.setChapterNo(1);
        req.setTitle("ch1");
        req.setContent("hero unlocks system");
        req.setIdempotencyKey("k-1");
        ProjectIngestJobVO first = service.submit(900L, workId, req);
        ProjectIngestJobVO second = service.submit(900L, workId, req);
        assertThat(second.getIngestJobId()).isEqualTo(first.getIngestJobId());
        assertThat(first.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_UPLOADED);

        Integer scenes = jdbc.queryForObject(
            "select count(*) from ai_project_scene where chapter_id = ?", Integer.class, first.getChapterId());
        assertThat(scenes).isEqualTo(0);

        ProjectIngestSubmitRequest conflict = new ProjectIngestSubmitRequest();
        conflict.setChapterNo(1);
        conflict.setContent("different content");
        conflict.setIdempotencyKey("k-1");
        assertThatThrownBy(() -> service.submit(900L, workId, conflict))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    void shouldRejectOversizedChapterAndConcurrentJobs() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        props.getProjectIngest().setMaxChapterChars(20);
        props.getProjectIngest().setMaxConcurrentJobsPerUser(1);
        KnowledgeProjectIngestService service = service(jdbc, props);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest tooLong = new ProjectIngestSubmitRequest();
        tooLong.setChapterNo(1);
        tooLong.setContent("this chapter content is longer than twenty");
        assertThatThrownBy(() -> service.submit(900L, workId, tooLong))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.BAD_REQUEST);

        props.getProjectIngest().setMaxChapterChars(100000);
        ProjectIngestSubmitRequest first = new ProjectIngestSubmitRequest();
        first.setChapterNo(2);
        first.setContent("short");
        service.submit(900L, workId, first);

        ProjectIngestSubmitRequest second = new ProjectIngestSubmitRequest();
        second.setChapterNo(3);
        second.setContent("another");
        assertThatThrownBy(() -> service.submit(900L, workId, second))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    @Test
    void shouldEnforceUtf8ByteLimitAndProjectChapterQuota() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        props.getProjectIngest().setMaxFileBytes(6);
        props.getProjectIngest().setMaxChaptersPerProject(1);
        props.getProjectIngest().setMaxConcurrentJobsPerUser(10);
        KnowledgeProjectIngestService service = service(jdbc, props);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest exactUtf8Boundary = new ProjectIngestSubmitRequest();
        exactUtf8Boundary.setChapterNo(1);
        exactUtf8Boundary.setContent("汉字");
        service.submit(900L, workId, exactUtf8Boundary);

        ProjectIngestSubmitRequest overUtf8Boundary = new ProjectIngestSubmitRequest();
        overUtf8Boundary.setChapterNo(1);
        overUtf8Boundary.setContent("汉字a");
        assertThatThrownBy(() -> service.submit(900L, workId, overUtf8Boundary))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.BAD_REQUEST);

        ProjectIngestSubmitRequest overChapterQuota = new ProjectIngestSubmitRequest();
        overChapterQuota.setChapterNo(2);
        overChapterQuota.setContent("ok");
        assertThatThrownBy(() -> service.submit(900L, workId, overChapterQuota))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectIngestAtNinetyPercentDiskPressure() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        AgentResourcePressureService pressureService = mock(AgentResourcePressureService.class);
        when(pressureService.shouldPauseIndexing()).thenReturn(true);
        when(pressureService.snapshot()).thenReturn(
            new AgentResourcePressureService.PressureSnapshot(50.0d, 90.0d, 0L, 0L));
        KnowledgeProjectIngestService service = service(jdbc, props, null, pressureService);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
        request.setChapterNo(1);
        request.setContent("disk pressure");
        assertThatThrownBy(() -> service.submit(900L, workId, request))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldPublishOutboxOnlyAfterSubmitTransactionCommits() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        KnowledgeProjectIngestQueueService queueService = mock(KnowledgeProjectIngestQueueService.class);
        when(queueService.publishExecute(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(true);
        KnowledgeProjectIngestService service = service(jdbc, props, queueService, null);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(
            new DataSourceTransactionManager(jdbc.getDataSource()));

        ProjectIngestJobVO job = transaction.execute(status -> {
            ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
            request.setChapterNo(1);
            request.setContent("commit first");
            ProjectIngestJobVO submitted = service.submit(900L, workId, request);
            org.mockito.Mockito.verifyNoInteractions(queueService);
            return submitted;
        });

        verify(queueService).publishExecute(job.getIngestJobId(), 1);
        String outboxStatus = jdbc.queryForObject(
            "select status from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 1",
            String.class, job.getIngestJobId());
        assertThat(outboxStatus).isEqualTo("PUBLISHED");
    }

    @Test
    void shouldReuseScheduledAutomaticRetryOutboxForExplicitRetry() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        props.getProjectIngest().setMaxAttempts(4);
        KnowledgeProjectIngestService service = service(jdbc, props);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
        request.setChapterNo(1);
        request.setContent("retry checkpoint");
        ProjectIngestJobVO job = service.submit(900L, workId, request);
        KnowledgeProjectIngestService.ClaimResult claim = service.claimJob(
            job.getIngestJobId(), 1, "worker-1", Duration.ofSeconds(30));
        assertThat(claim).isNotNull();
        assertThat(service.failAndScheduleRetry(
            job.getIngestJobId(), "worker-1", claim.fencingToken(),
            "QDRANT_UNAVAILABLE", "retry later", true))
            .isEqualTo(KnowledgeProjectIngestService.FailureDisposition.RETRY_SCHEDULED);
        Timestamp scheduledAt = jdbc.queryForObject(
            "select available_at from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 2",
            Timestamp.class, job.getIngestJobId());

        ProjectIngestJobVO retried = service.retry(900L, job.getIngestJobId());

        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(retried.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_UPLOADED);
        assertThat(jdbc.queryForObject(
            "select count(*) from ai_project_ingest_outbox where ingest_job_id = ? and event_type = 'EXECUTE' and attempt = 2",
            Integer.class, job.getIngestJobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select available_at from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 2",
            Timestamp.class, job.getIngestJobId())).isBefore(scheduledAt);
    }

    @Test
    void shouldRejectExplicitRetryDuringGenerationCleanupAndAllowItAfterCompletion() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);
        ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
        request.setChapterNo(1);
        request.setContent("cleanup retry fence");
        ProjectIngestJobVO job = service.submit(900L, workId, request);
        KnowledgeProjectIngestService.ClaimResult claim = service.claimJob(
            job.getIngestJobId(), 1, "worker-1", Duration.ofSeconds(30));
        assertThat(claim).isNotNull();
        assertThat(service.failAndScheduleRetry(
            job.getIngestJobId(), "worker-1", claim.fencingToken(),
            "EXECUTE_FAILED", "terminal failure", false))
            .isEqualTo(KnowledgeProjectIngestService.FailureDisposition.TERMINAL);
        jdbc.update(
            "update ai_project_ingest_generation set cleanup_status = 'RUNNING' where generation_id = ?",
            job.getGenerationId());

        assertThatThrownBy(() -> service.retry(900L, job.getIngestJobId()))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.CONFLICT);
        assertThat(service.getJobById(job.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_TERMINAL_FAILED);

        jdbc.update(
            "update ai_project_ingest_generation set cleanup_status = 'COMPLETE' where generation_id = ?",
            job.getGenerationId());
        ProjectIngestJobVO retried = service.retry(900L, job.getIngestJobId());

        assertThat(retried.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_UPLOADED);
        assertThat(jdbc.queryForMap(
            "select status, cleanup_status from ai_project_ingest_generation where generation_id = ?",
            job.getGenerationId()))
            .containsEntry("status", KnowledgeProjectIngestService.GEN_PREPARED)
            .containsEntry("cleanup_status", null);
    }

    @Test
    void shouldRecoverOutboxAfterRabbitPublishFailure() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        props.getProjectIngest().setQueueEnabled(true);
        KnowledgeProjectIngestQueueService queueService = mock(KnowledgeProjectIngestQueueService.class);
        when(queueService.publishExecute(anyLong(), anyInt())).thenReturn(false, true);
        KnowledgeProjectIngestService service = service(jdbc, props, queueService, null);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
        request.setChapterNo(1);
        request.setContent("publish recovery");
        ProjectIngestJobVO job = service.submit(900L, workId, request);
        assertThat(jdbc.queryForObject(
            "select status from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 1",
            String.class, job.getIngestJobId())).isEqualTo("PENDING");
        jdbc.update(
            "update ai_project_ingest_outbox set available_at = ? where ingest_job_id = ? and attempt = 1",
            Timestamp.from(Instant.now().minusSeconds(1)), job.getIngestJobId());

        assertThat(service.dispatchPendingOutbox(10)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "select status from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 1",
            String.class, job.getIngestJobId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
            "select queue_published_attempt from ai_project_ingest_job where ingest_job_id = ?",
            Integer.class, job.getIngestJobId())).isEqualTo(1);
        verify(queueService, times(2)).publishExecute(job.getIngestJobId(), 1);
    }

    @Test
    void shouldRecoverExpiredLeaseFromEveryPersistedStage() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        props.getProjectIngest().setMaxConcurrentJobsPerUser(10);
        KnowledgeProjectIngestService service = service(jdbc, props);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);
        List<String> stages = List.of(
            KnowledgeProjectIngestService.JOB_PARSING,
            KnowledgeProjectIngestService.JOB_EXTRACTING,
            KnowledgeProjectIngestService.JOB_INDEXING,
            KnowledgeProjectIngestService.JOB_VERIFYING
        );

        for (int index = 0; index < stages.size(); index++) {
            ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
            request.setChapterNo(index + 1);
            request.setContent("expired stage " + stages.get(index));
            ProjectIngestJobVO job = service.submit(900L, workId, request);
            KnowledgeProjectIngestService.ClaimResult claim = service.claimJob(
                job.getIngestJobId(), 1, "worker-" + index, Duration.ofSeconds(30));
            assertThat(claim).isNotNull();
            jdbc.update(
                "update ai_project_ingest_job set status = ?, stage = ?, lease_expires_at = ? where ingest_job_id = ?",
                stages.get(index), stages.get(index), Timestamp.from(Instant.now().minusSeconds(1)),
                job.getIngestJobId());

            assertThat(service.recoverExpiredJobs(10)).isEqualTo(1);
            assertThat(service.getJobById(job.getIngestJobId()).getStatus())
                .isEqualTo(KnowledgeProjectIngestService.JOB_RETRYABLE_FAILED);
            assertThat(jdbc.queryForObject(
                "select count(*) from ai_project_ingest_outbox where ingest_job_id = ? and attempt = 2",
                Integer.class, job.getIngestJobId())).isEqualTo(1);
            assertThat(service.recoverExpiredJobs(10)).isZero();
        }
    }

    @Test
    void shouldRollbackGenerationActivationWhenJobCompletionWriteFails() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        KnowledgeProjectIngestJobExecutor executor = executor(jdbc, service);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest firstRequest = new ProjectIngestSubmitRequest();
        firstRequest.setChapterNo(1);
        firstRequest.setContent("active version");
        ProjectIngestJobVO first = service.submit(900L, workId, firstRequest);
        executor.execute(first.getIngestJobId(), 1);

        ProjectIngestSubmitRequest nextRequest = new ProjectIngestSubmitRequest();
        nextRequest.setChapterNo(1);
        nextRequest.setContent("candidate version");
        ProjectIngestJobVO next = service.submit(900L, workId, nextRequest);
        KnowledgeProjectIngestService.ClaimResult claim = service.claimJob(
            next.getIngestJobId(), 1, "activation-worker", Duration.ofSeconds(30));
        assertThat(claim).isNotNull();
        assertThat(service.transitionJob(
            next.getIngestJobId(), KnowledgeProjectIngestService.JOB_UPLOADED,
            KnowledgeProjectIngestService.JOB_VERIFYING, KnowledgeProjectIngestService.JOB_VERIFYING,
            90, "activation-worker", claim.fencingToken())).isTrue();
        jdbc.update(
            "update ai_project_ingest_generation set status = ? where generation_id = ?",
            KnowledgeProjectIngestService.GEN_VERIFYING, next.getGenerationId());

        JdbcTemplate failingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("progress = 100") && args.length > 0
                    && KnowledgeProjectIngestService.JOB_READY.equals(args[0])) {
                    throw new DataAccessResourceFailureException("simulated completion write failure");
                }
                return super.update(sql, args);
            }
        };
        KnowledgeProjectIngestService failingService = service(failingJdbc);
        TransactionTemplate transaction = new TransactionTemplate(
            new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
            failingService.activateGenerationAndCompleteJob(
                next.getIngestJobId(), "activation-worker", claim.fencingToken(), next.getGenerationId(),
                7L, 900L, workId, 1, next.getChapterId())))
            .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(jdbc.queryForObject(
            "select active_generation_id from ai_project_chapter_head where work_id = ? and chapter_no = 1",
            Long.class, workId)).isEqualTo(first.getGenerationId());
        assertThat(jdbc.queryForObject(
            "select status from ai_project_ingest_generation where generation_id = ?",
            String.class, first.getGenerationId())).isEqualTo(KnowledgeProjectIngestService.GEN_ACTIVE);
        assertThat(jdbc.queryForObject(
            "select status from ai_project_ingest_generation where generation_id = ?",
            String.class, next.getGenerationId())).isEqualTo(KnowledgeProjectIngestService.GEN_VERIFYING);
        assertThat(service.getJobById(next.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_VERIFYING);
    }

    @Test
    void shouldRecoverAfterWorkerRestartWithinLocalRto() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProperties props = new KnowledgeProperties();
        props.getProjectIngest().setQueueEnabled(true);
        KnowledgeProjectIngestQueueService queueService = mock(KnowledgeProjectIngestQueueService.class);
        when(queueService.publishExecute(anyLong(), anyInt())).thenReturn(false, true);
        KnowledgeProjectIngestService service = service(jdbc, props, queueService, null);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
        request.setChapterNo(1);
        request.setContent("restart recovery");
        ProjectIngestJobVO job = service.submit(900L, workId, request);
        KnowledgeProjectIngestService.ClaimResult abandoned = service.claimJob(
            job.getIngestJobId(), 1, "stopped-worker", Duration.ofSeconds(30));
        assertThat(abandoned).isNotNull();
        jdbc.update(
            "update ai_project_ingest_job set status = ?, stage = ?, lease_expires_at = ? where ingest_job_id = ?",
            KnowledgeProjectIngestService.JOB_INDEXING, KnowledgeProjectIngestService.JOB_INDEXING,
            Timestamp.from(Instant.now().minusSeconds(1)), job.getIngestJobId());
        jdbc.update(
            "update ai_project_ingest_outbox set status = 'DEAD' where ingest_job_id = ? and attempt = 1",
            job.getIngestJobId());

        long startedAt = System.nanoTime();
        assertThat(service.recoverExpiredJobs(10)).isEqualTo(1);
        jdbc.update(
            "update ai_project_ingest_outbox set available_at = ? where ingest_job_id = ? and attempt = 2",
            Timestamp.from(Instant.now().minusSeconds(1)), job.getIngestJobId());
        assertThat(service.dispatchPendingOutbox(10)).isEqualTo(1);
        executor(jdbc, service).execute(job.getIngestJobId(), 2);
        Duration localRto = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(service.getJobById(job.getIngestJobId()).getStatus())
            .isEqualTo(KnowledgeProjectIngestService.JOB_READY);
        assertThat(localRto).isLessThan(Duration.ofMinutes(5));
    }

    @Test
    void shouldActivateGenerationAndRetirePrevious() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        KnowledgeProjectIngestJobExecutor executor = executor(jdbc, service);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest v1 = new ProjectIngestSubmitRequest();
        v1.setChapterNo(1);
        v1.setContent("version one body");
        ProjectIngestJobVO job1 = service.submit(900L, workId, v1);
        executor.execute(job1.getIngestJobId());
        ProjectIngestJobVO ready1 = service.getJob(900L, job1.getIngestJobId());
        assertThat(ready1.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_READY);

        String active1 = jdbc.queryForObject(
            "select status from ai_project_ingest_generation where generation_id = ?",
            String.class, job1.getGenerationId());
        assertThat(active1).isEqualTo(KnowledgeProjectIngestService.GEN_ACTIVE);

        ProjectIngestSubmitRequest v2 = new ProjectIngestSubmitRequest();
        v2.setChapterNo(1);
        v2.setContent("version two body upgrade");
        ProjectIngestJobVO job2 = service.submit(900L, workId, v2);
        executor.execute(job2.getIngestJobId());

        String statusOld = jdbc.queryForObject(
            "select status from ai_project_ingest_generation where generation_id = ?",
            String.class, job1.getGenerationId());
        String statusNew = jdbc.queryForObject(
            "select status from ai_project_ingest_generation where generation_id = ?",
            String.class, job2.getGenerationId());
        assertThat(statusOld).isEqualTo(KnowledgeProjectIngestService.GEN_RETIRED);
        assertThat(statusNew).isEqualTo(KnowledgeProjectIngestService.GEN_ACTIVE);

        Integer activeCount = jdbc.queryForObject(
            "select count(*) from ai_project_ingest_generation where work_id = ? and chapter_no = 1 and status = 'ACTIVE'",
            Integer.class, workId);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void shouldExposeAndReviewOnlyCandidatesFromActiveGeneration() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        KnowledgeProjectIngestJobExecutor executor = executor(jdbc, service);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest firstRequest = new ProjectIngestSubmitRequest();
        firstRequest.setChapterNo(1);
        firstRequest.setContent("first candidate generation");
        ProjectIngestJobVO first = service.submit(900L, workId, firstRequest);
        executor.execute(first.getIngestJobId());
        ProjectExtractionCandidateVO retiredCandidate = service.listCandidates(900L, workId, "PENDING", 10).get(0);

        ProjectIngestSubmitRequest secondRequest = new ProjectIngestSubmitRequest();
        secondRequest.setChapterNo(1);
        secondRequest.setContent("second candidate generation");
        ProjectIngestJobVO second = service.submit(900L, workId, secondRequest);
        executor.execute(second.getIngestJobId());
        jdbc.update(
            "update ai_project_ingest_generation set status = 'ACTIVE' where generation_id = ?",
            first.getGenerationId());

        assertThat(service.listCandidates(900L, null, null, 10))
            .extracting(ProjectExtractionCandidateVO::getGenerationId)
            .containsExactly(second.getGenerationId());
        ProjectExtractionCandidateVO activeCandidate = service.listCandidates(900L, workId, "PENDING", 10).get(0);
        assertThat(activeCandidate.getGenerationId()).isEqualTo(second.getGenerationId());

        ProjectExtractionReviewRequest review = new ProjectExtractionReviewRequest();
        review.setDecision("CONFIRMED");
        review.setReviewNote("confirmed current generation");
        assertThatThrownBy(() -> service.reviewCandidate(900L, retiredCandidate.getCandidateId(), review))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbc.queryForObject(
            "select status from ai_project_extraction_candidate where candidate_id = ?",
            String.class, retiredCandidate.getCandidateId())).isEqualTo("PENDING");

        ProjectExtractionCandidateVO confirmed = service.reviewCandidate(900L, activeCandidate.getCandidateId(), review);
        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(service.listCandidates(900L, workId, "CONFIRMED", 10))
            .extracting(ProjectExtractionCandidateVO::getCandidateId)
            .containsExactly(activeCandidate.getCandidateId());
    }

    @Test
    void shouldRejectTombstoneActivationAndCrossUserAccess() throws Exception {
        JdbcTemplate jdbc = jdbc();
        KnowledgeProjectIngestService service = service(jdbc);
        KnowledgeProjectIngestJobExecutor executor = executor(jdbc, service);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        Long workId = createWork(jdbc);

        ProjectIngestSubmitRequest req = new ProjectIngestSubmitRequest();
        req.setChapterNo(5);
        req.setContent("to be tombstoned");
        ProjectIngestJobVO job = service.submit(900L, workId, req);
        service.tombstoneChapter(900L, workId, 5);
        executor.execute(job.getIngestJobId());
        ProjectIngestJobVO after = service.getJob(900L, job.getIngestJobId());
        assertThat(after.getStatus()).isEqualTo(KnowledgeProjectIngestService.JOB_TERMINAL_FAILED);

        AuthUserHolder.set(AuthUser.of(8L, "other", Set.of("USER")));
        assertThatThrownBy(() -> service.getJob(900L, job.getIngestJobId()))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private KnowledgeProjectIngestJobExecutor executor(JdbcTemplate jdbc, KnowledgeProjectIngestService service) {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1d, 0.2d, 0.3d));
        return new KnowledgeProjectIngestJobExecutor(
            service,
            new KnowledgeProjectWorkService(
                jdbc, new KnowledgeProjectService(jdbc), embeddingClient, qdrantClient),
            new KnowledgeStoryGraphService(jdbc),
            new KnowledgeProperties(),
            jdbc
        );
    }

    private KnowledgeProjectIngestService service(JdbcTemplate jdbc) {
        return service(jdbc, new KnowledgeProperties());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private KnowledgeProjectIngestService service(JdbcTemplate jdbc, KnowledgeProperties props) {
        return service(jdbc, props, null, null);
    }

    private KnowledgeProjectIngestService service(JdbcTemplate jdbc,
                                                  KnowledgeProperties props,
                                                  KnowledgeProjectIngestQueueService queueService,
                                                  AgentResourcePressureService pressureService) {
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbc);
        KnowledgeProjectWorkService workService = new KnowledgeProjectWorkService(jdbc, projectService);
        ObjectProvider empty = new ObjectProvider() {
            @Override public Object getObject() { return null; }
            @Override public Object getObject(Object... args) { return null; }
            @Override public Object getIfAvailable() { return null; }
            @Override public Object getIfUnique() { return null; }
        };
        return new KnowledgeProjectIngestService(
            jdbc, projectService, workService, props,
            provider(queueService), provider(pressureService), empty
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectProvider provider(Object value) {
        ObjectProvider provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private Long createWork(JdbcTemplate jdbc) {
        KnowledgeProjectWorkService workService = new KnowledgeProjectWorkService(jdbc, new KnowledgeProjectService(jdbc));
        ProjectWorkRequest request = new ProjectWorkRequest();
        request.setTitle("test-work");
        ProjectWorkVO work = workService.createWork(900L, request);
        return work.getWorkId();
    }

    private JdbcTemplate jdbc() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:project_ingest_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table ai_project(project_id bigint primary key, user_id bigint not null, name varchar(200), description varchar(500), status varchar(30), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.update("insert into ai_project(project_id, user_id, name, description, status) values(900, 7, 'p', 'd', 'ACTIVE')");
        runScript(jdbc, resolveResource("sql/phase16-project-knowledge-rag-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase24-project-ingest-generation-h2.sql"));
        runScript(jdbc, resolveResource("sql/phase25-project-hybrid-retrieval-story-graph-h2.sql"));
        return jdbc;
    }

    private Path resolveResource(String classpathRelative) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("src/test/resources").resolve(classpathRelative),
            Path.of("backend/src/test/resources").resolve(classpathRelative),
            Path.of("..", "src/test/resources").resolve(classpathRelative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        var url = getClass().getClassLoader().getResource(classpathRelative.replace("\\", "/"));
        if (url != null) {
            return Path.of(url.toURI());
        }
        throw new java.nio.file.NoSuchFileException(classpathRelative);
    }

    private void runScript(JdbcTemplate jdbc, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == 0xFEFF) {
            sql = sql.substring(1);
        }
        for (String stmt : sql.split(";")) {
            StringBuilder cleaned = new StringBuilder();
            for (String line : stmt.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                cleaned.append(line).append('\n');
            }
            String executable = cleaned.toString().trim();
            if (executable.isEmpty()) {
                continue;
            }
            try {
                jdbc.execute(executable);
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (!(msg.contains("already exists") || msg.contains("duplicate"))) {
                    throw ex;
                }
            }
        }
    }
}
