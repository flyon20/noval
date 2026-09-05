package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeProjectIngestJobExecutor implements KnowledgeProjectIngestRabbitConsumer.ExecutionPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectIngestJobExecutor.class);
    private static final ScheduledExecutorService LEASE_RENEWER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "project-ingest-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    private final KnowledgeProjectIngestService ingestService;
    private final KnowledgeProjectWorkService workService;
    private final KnowledgeStoryGraphService storyGraphService;
    private final KnowledgeProperties knowledgeProperties;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeProjectIngestJobExecutor(KnowledgeProjectIngestService ingestService,
                                             KnowledgeProjectWorkService workService,
                                             KnowledgeStoryGraphService storyGraphService,
                                             KnowledgeProperties knowledgeProperties,
                                             JdbcTemplate jdbcTemplate) {
        this.ingestService = ingestService;
        this.workService = workService;
        this.storyGraphService = storyGraphService;
        this.knowledgeProperties = knowledgeProperties == null ? new KnowledgeProperties() : knowledgeProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void execute(Long ingestJobId) {
        if (ingestJobId == null) {
            return;
        }
        ProjectIngestJobVO job = ingestService.getJobById(ingestJobId);
        execute(ingestJobId, job.getAttempt() == null ? 1 : job.getAttempt());
    }

    @Override
    public void execute(Long ingestJobId, int expectedAttempt) {
        if (ingestJobId == null || expectedAttempt <= 0) {
            return;
        }
        String leaseOwner = "ingest-worker-" + UUID.randomUUID();
        Duration lease = Duration.ofSeconds(Math.max(15, knowledgeProperties.getProjectIngest().getLeaseSeconds()));
        KnowledgeProjectIngestService.ClaimResult claim = ingestService.claimJob(
            ingestJobId, expectedAttempt, leaseOwner, lease);
        if (claim == null) {
            LOGGER.info("project ingest claim skipped jobId={} attempt={}", ingestJobId, expectedAttempt);
            return;
        }

        ProjectIngestJobVO job = claim.job();
        long fencingToken = claim.fencingToken();
        KnowledgeProjectWorkService.ExecutionFence executionFence =
            new KnowledgeProjectWorkService.ExecutionFence(ingestJobId, leaseOwner, fencingToken);
        AtomicBoolean leaseHealthy = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = startHeartbeat(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);
        Runnable ownershipCheckpoint = () -> checkpoint(
            ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);
        try {
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);
            validateJob(job);
            if (isTombstoned(job)) {
                ingestService.failAndScheduleRetry(
                    ingestJobId, leaseOwner, fencingToken, "TOMBSTONED", "chapter tombstoned", false);
                return;
            }

            requireTransition(ingestService.transitionJob(
                ingestJobId, job.getStatus(), KnowledgeProjectIngestService.JOB_PARSING,
                KnowledgeProjectIngestService.JOB_PARSING, 10, leaseOwner, fencingToken),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "job to PARSING");
            requireTransition(ingestService.resetGenerationForExecution(
                ingestJobId, leaseOwner, fencingToken, job.getGenerationId()),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "generation reset");
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);

            ProjectChapterVO chapter = ingestService.loadChapter(job.getChapterId());
            KnowledgeProjectWorkService.ArtifactCounts counts =
                workService.materializeGenerationArtifacts(
                    chapter, job.getGenerationId(), ownershipCheckpoint, executionFence);
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);
            storyGraphService.indexGeneration(chapter, job.getGenerationId(), ownershipCheckpoint);
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);

            requireTransition(ingestService.transitionJob(
                ingestJobId, KnowledgeProjectIngestService.JOB_PARSING,
                KnowledgeProjectIngestService.JOB_EXTRACTING,
                KnowledgeProjectIngestService.JOB_EXTRACTING, 40, leaseOwner, fencingToken),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "job to EXTRACTING");
            requireTransition(ingestService.transitionGeneration(
                ingestJobId, leaseOwner, fencingToken, job.getGenerationId(),
                KnowledgeProjectIngestService.GEN_PREPARED,
                KnowledgeProjectIngestService.GEN_STRUCTURED_READY),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "generation to STRUCTURED_READY");
            insertCandidatesFromArtifacts(job, counts, leaseOwner, fencingToken);
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);

            requireTransition(ingestService.transitionJob(
                ingestJobId, KnowledgeProjectIngestService.JOB_EXTRACTING,
                KnowledgeProjectIngestService.JOB_INDEXING,
                KnowledgeProjectIngestService.JOB_INDEXING, 70, leaseOwner, fencingToken),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "job to INDEXING");
            requireTransition(ingestService.transitionGeneration(
                ingestJobId, leaseOwner, fencingToken, job.getGenerationId(),
                KnowledgeProjectIngestService.GEN_STRUCTURED_READY,
                KnowledgeProjectIngestService.GEN_VECTOR_READY),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "generation to VECTOR_READY");
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);

            requireTransition(ingestService.transitionJob(
                ingestJobId, KnowledgeProjectIngestService.JOB_INDEXING,
                KnowledgeProjectIngestService.JOB_VERIFYING,
                KnowledgeProjectIngestService.JOB_VERIFYING, 90, leaseOwner, fencingToken),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "job to VERIFYING");
            requireTransition(ingestService.transitionGeneration(
                ingestJobId, leaseOwner, fencingToken, job.getGenerationId(),
                KnowledgeProjectIngestService.GEN_VECTOR_READY,
                KnowledgeProjectIngestService.GEN_VERIFYING),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "generation to VERIFYING");
            requireTransition(ingestService.recordGenerationCounts(
                ingestJobId, leaseOwner, fencingToken, job.getGenerationId(), counts),
                ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy, "generation counts");
            checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);

            boolean activated = ingestService.activateGenerationAndCompleteJob(
                ingestJobId, leaseOwner, fencingToken, job.getGenerationId(),
                job.getUserId(), job.getProjectId(), job.getWorkId(),
                job.getChapterNo() == null ? 0 : job.getChapterNo(), job.getChapterId());
            if (!activated) {
                throw new BusinessException(ResultCode.CONFLICT, "activation rejected by tombstone or fencing");
            }
        } catch (LeaseLostException | KnowledgeProjectWorkService.ExecutionLeaseLostException ex) {
            LOGGER.info("project ingest stopped after lease loss jobId={} attempt={} token={}",
                ingestJobId, expectedAttempt, fencingToken);
        } catch (RuntimeException ex) {
            LOGGER.error("project ingest execute failed jobId={} attempt={} reason={}",
                ingestJobId, expectedAttempt, ex.getMessage(), ex);
            KnowledgeProjectIngestService.FailureDisposition disposition = ingestService.failAndScheduleRetry(
                ingestJobId,
                leaseOwner,
                fencingToken,
                retryableErrorCode(ex),
                trim(ex.getMessage(), 500),
                isRetryable(ex));
            if (disposition == KnowledgeProjectIngestService.FailureDisposition.STALE) {
                LOGGER.info("project ingest failure ignored after lease loss jobId={} attempt={}",
                    ingestJobId, expectedAttempt);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(Long ingestJobId,
                                              String leaseOwner,
                                              long fencingToken,
                                              Duration lease,
                                              AtomicBoolean leaseHealthy) {
        long configured = Math.max(1L, knowledgeProperties.getProjectIngest().getHeartbeatSeconds());
        long periodSeconds = Math.max(1L, Math.min(configured, Math.max(1L, lease.toSeconds() / 3L)));
        return LEASE_RENEWER.scheduleAtFixedRate(() -> {
            try {
                if (!ingestService.heartbeat(ingestJobId, leaseOwner, fencingToken, lease)) {
                    leaseHealthy.set(false);
                }
            } catch (RuntimeException ex) {
                leaseHealthy.set(false);
                LOGGER.warn("project ingest heartbeat failed jobId={} token={} reason={}",
                    ingestJobId, fencingToken, ex.getMessage());
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    private void checkpoint(Long ingestJobId,
                            String leaseOwner,
                            long fencingToken,
                            Duration lease,
                            AtomicBoolean leaseHealthy) {
        if (!leaseHealthy.get() || !ingestService.heartbeat(ingestJobId, leaseOwner, fencingToken, lease)) {
            leaseHealthy.set(false);
            throw new LeaseLostException();
        }
    }

    private void validateJob(ProjectIngestJobVO job) {
        if (job.getChapterId() == null || job.getGenerationId() == null || job.getChapterNo() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "job missing chapter or generation scope");
        }
    }

    private boolean isTombstoned(ProjectIngestJobVO job) {
        Integer tombstoned = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_chapter_head where user_id = ? and project_id = ? and work_id = ? and chapter_no = ? and tombstoned_at is not null",
            Integer.class, job.getUserId(), job.getProjectId(), job.getWorkId(), job.getChapterNo());
        return tombstoned != null && tombstoned > 0;
    }

    private void requireTransition(boolean transitioned,
                                   Long ingestJobId,
                                   String leaseOwner,
                                   long fencingToken,
                                   Duration lease,
                                   AtomicBoolean leaseHealthy,
                                   String transition) {
        if (transitioned) {
            return;
        }
        checkpoint(ingestJobId, leaseOwner, fencingToken, lease, leaseHealthy);
        throw new BusinessException(ResultCode.CONFLICT, "project ingest state rejected: " + transition);
    }

    private boolean isRetryable(RuntimeException exception) {
        if (!(exception instanceof BusinessException businessException)) {
            return false;
        }
        return businessException.getResultCode() == ResultCode.SERVICE_UNAVAILABLE
            || businessException.getResultCode() == ResultCode.INTERNAL_ERROR;
    }

    private String retryableErrorCode(RuntimeException exception) {
        return isRetryable(exception) ? "DEPENDENCY_UNAVAILABLE" : "EXECUTE_FAILED";
    }

    private void insertCandidatesFromArtifacts(ProjectIngestJobVO job,
                                               KnowledgeProjectWorkService.ArtifactCounts counts,
                                               String leaseOwner,
                                               long fencingToken) {
        String payload = "{\"sceneCount\":" + counts.sceneCount() + ",\"vectorCount\":" + counts.vectorCount()
            + ",\"entityCount\":" + counts.entityCount() + "}";
        int inserted = jdbcTemplate.update(
            """
                insert into ai_project_extraction_candidate(user_id, project_id, work_id, chapter_id, generation_id,
                    entity_type, payload, evidence_refs, confidence, status)
                select j.user_id, j.project_id, j.work_id, j.chapter_id, j.generation_id,
                    'INGEST_SUMMARY', ?, ?, ?, 'PENDING'
                from ai_project_ingest_job j
                join ai_project_ingest_generation g on g.generation_id = j.generation_id
                where j.ingest_job_id = ? and j.generation_id = ? and j.chapter_id = ?
                  and j.status = ? and j.stage = ?
                  and j.lease_owner = ? and j.fencing_token = ?
                  and j.lease_expires_at >= current_timestamp
                  and g.status = ?
                """,
            payload, "{\"chapterId\":" + job.getChapterId() + "}", 0.80d,
            job.getIngestJobId(), job.getGenerationId(), job.getChapterId(),
            KnowledgeProjectIngestService.JOB_EXTRACTING, KnowledgeProjectIngestService.JOB_EXTRACTING,
            leaseOwner, fencingToken, KnowledgeProjectIngestService.GEN_STRUCTURED_READY);
        if (inserted != 1) {
            throw new LeaseLostException();
        }
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class LeaseLostException extends RuntimeException {
    }
}
