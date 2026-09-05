package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.asyncjob.vo.AsyncJobVO;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeIndexJobExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexJobExecutor.class);
    private static final String QUEUE_PUBLISH_FAILED_MESSAGE = "knowledge index queue publish failed";
    private static final String RETRY_PUBLISH_FAILED_PREFIX = "knowledge index retry publish failed: ";
    private static final ScheduledExecutorService INDEX_LEASE_RENEWER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "knowledge-index-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    private final KnowledgeIndexService knowledgeIndexService;
    private final AsyncJobService asyncJobService;
    private final TaskExecutor knowledgeIndexTaskExecutor;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIndexQueueService queueService;
    private final KnowledgeProperties knowledgeProperties;
    private final AgentResourcePressureService resourcePressureService;

    @Autowired
    public KnowledgeIndexJobExecutor(KnowledgeIndexService knowledgeIndexService,
                                     AsyncJobService asyncJobService,
                                     @Qualifier("knowledgeIndexTaskExecutor") TaskExecutor knowledgeIndexTaskExecutor,
                                     KnowledgeRepository knowledgeRepository,
                                     KnowledgeIndexQueueService queueService,
                                     KnowledgeProperties knowledgeProperties,
                                     AgentResourcePressureService resourcePressureService) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.asyncJobService = asyncJobService;
        this.knowledgeIndexTaskExecutor = knowledgeIndexTaskExecutor;
        this.knowledgeRepository = knowledgeRepository;
        this.queueService = queueService;
        this.knowledgeProperties = knowledgeProperties;
        this.resourcePressureService = resourcePressureService;
    }

    public KnowledgeIndexJobExecutor(KnowledgeIndexService knowledgeIndexService,
                                     AsyncJobService asyncJobService,
                                     @Qualifier("knowledgeIndexTaskExecutor") TaskExecutor knowledgeIndexTaskExecutor,
                                     KnowledgeRepository knowledgeRepository,
                                     KnowledgeIndexQueueService queueService,
                                     KnowledgeProperties knowledgeProperties) {
        this(
            knowledgeIndexService,
            asyncJobService,
            knowledgeIndexTaskExecutor,
            knowledgeRepository,
            queueService,
            knowledgeProperties,
            AgentResourcePressureService.permissive(knowledgeProperties)
        );
    }

    public enum QueueConsumeAction {
        ACK,
        REQUEUE,
        DEAD_LETTER
    }

    public AsyncJobSubmitResponse submitAndExecute(Long bookId, Long triggerUserId) {
        return submitAndExecute(bookId, triggerUserId, "ALL");
    }

    public AsyncJobSubmitResponse submitAndExecute(Long bookId, Long triggerUserId, String mode) {
        return submitAndExecute(bookId, triggerUserId, mode, null);
    }

    public AsyncJobSubmitResponse submitAndExecute(Long bookId,
                                                   Long triggerUserId,
                                                   String mode,
                                                   String actionIdempotencyKey) {
        String normalizedMode = normalizeMode(mode);
        boolean durableAction = actionIdempotencyKey != null && !actionIdempotencyKey.isBlank();
        if (!knowledgeProperties.getIndex().isQueueEnabled()) {
            assertIndexingAvailable();
        }
        AsyncJobSubmitResponse response;
        if (knowledgeProperties.getIndex().isQueueEnabled()) {
            response = durableAction
                ? knowledgeIndexService.submitBookIndexPendingJob(
                    bookId,
                    triggerUserId,
                    normalizedMode,
                    actionIdempotencyKey
                )
                : knowledgeIndexService.submitBookIndexPendingJob(bookId, triggerUserId, normalizedMode);
        } else {
            response = durableAction
                ? knowledgeIndexService.submitBookIndexJob(
                    bookId,
                    triggerUserId,
                    normalizedMode,
                    actionIdempotencyKey
                )
                : knowledgeIndexService.submitBookIndexJob(bookId, triggerUserId, normalizedMode);
        }
        if (response == null) {
            return response;
        }
        if (knowledgeProperties.getIndex().isQueueEnabled()) {
            if (Boolean.TRUE.equals(response.getReused())
                && !AsyncJobService.STATUS_PENDING.equals(response.getStatus())) {
                return response;
            }
            int attempt = currentQueueAttempt(response);
            if (enqueueAndRecord(bookId, triggerUserId, normalizedMode, response, attempt)) {
                return response;
            }
            try {
                asyncJobService.markRetryPublishFailed(
                    response.getJobId(),
                    QUEUE_PUBLISH_FAILED_MESSAGE,
                    attempt
                );
            } finally {
                asyncJobService.releaseLock(response);
            }
            throw new IllegalStateException(QUEUE_PUBLISH_FAILED_MESSAGE);
        }
        if (Boolean.TRUE.equals(response.getReused())) {
            return response;
        }
        knowledgeIndexTaskExecutor.execute(() -> executeSubmittedJob(bookId, normalizedMode, response, false));
        return response;
    }

    private int currentQueueAttempt(AsyncJobSubmitResponse response) {
        if (response == null || response.getJobId() == null || !Boolean.TRUE.equals(response.getReused())) {
            return 0;
        }
        return asyncJobService.getJob(response.getJobId())
            .map(AsyncJobVO::getRetryCount)
            .map(value -> Math.max(0, value))
            .orElse(0);
    }

    public AsyncJobSubmitResponse submitAndExecuteBlocking(Long bookId, Long triggerUserId) {
        assertIndexingAvailable();
        AsyncJobSubmitResponse response = knowledgeIndexService.submitBookIndexJob(bookId, triggerUserId);
        if (response == null || Boolean.TRUE.equals(response.getReused())) {
            return response;
        }
        executeSubmittedJob(bookId, "ALL", response, true);
        return response;
    }

    public KnowledgeRebuildResponse submitRebuild(String mode, int limit, Long triggerUserId) {
        String normalizedMode = normalizeMode(mode);
        if (resourcePressureService.shouldPauseIndexing()) {
            if (triggerUserId == null) {
                return new KnowledgeRebuildResponse(normalizedMode, 0, java.util.List.of());
            }
            throw indexingPaused();
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        java.util.List<AsyncJobSubmitResponse> jobs = knowledgeRepository.findBookIdsForKnowledgeRebuild(
                normalizedMode,
                safeLimit,
                currentEmbeddingModel(),
                currentEmbeddingDimension(),
                knowledgeProperties.getIndex().getMaxChapters()
            )
            .stream()
            .map(bookId -> submitAndExecute(bookId, triggerUserId, normalizedMode))
            .toList();
        return new KnowledgeRebuildResponse(normalizedMode, jobs.size(), jobs);
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "FAILED_ONLY" : mode.trim().toUpperCase();
        if (!"ALL".equals(normalized)
            && !"FAILED_ONLY".equals(normalized)
            && !"FULL_REINDEX".equals(normalized)
            && !"RANK_MISSING".equals(normalized)
            && !"RANK_INCREMENTAL".equals(normalized)
            && !"CHAPTER_MISSING".equals(normalized)) {
            return "FAILED_ONLY";
        }
        return normalized;
    }

    private String currentEmbeddingModel() {
        String model = knowledgeProperties.getEmbedding() == null ? null : knowledgeProperties.getEmbedding().getModel();
        return model == null || model.isBlank() ? "unknown" : model.trim();
    }

    private int currentEmbeddingDimension() {
        return knowledgeProperties.getEmbedding() == null ? 0 : knowledgeProperties.getEmbedding().getDimension();
    }

    private boolean enqueueAndRecord(Long bookId,
                                     Long triggerUserId,
                                     String mode,
                                     AsyncJobSubmitResponse response,
                                     int attempt) {
        KnowledgeIndexQueueService.IndexQueueItem item = queueItem(
            bookId,
            triggerUserId,
            mode,
            response,
            attempt
        );
        if (!queueService.enqueue(item)) {
            return false;
        }
        try {
            asyncJobService.markQueuePublished(response.getJobId(), attempt);
            return true;
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge index queue publication was confirmed but not recorded: jobId={}, message={}",
                response.getJobId(),
                ex.getMessage());
            return false;
        }
    }

    private KnowledgeIndexQueueService.IndexQueueItem queueItem(Long bookId,
                                                                 Long triggerUserId,
                                                                 String mode,
                                                                 AsyncJobSubmitResponse response,
                                                                 int attempt) {
        String normalizedMode = normalizeMode(mode);
        return new KnowledgeIndexQueueService.IndexQueueItem(
            response.getJobId(),
            response.getJobType(),
            response.getJobKey(),
            "book:" + bookId,
            "{\"bookId\":" + bookId + ",\"mode\":\"" + normalizedMode + "\"}",
            bookId,
            triggerUserId,
            normalizedMode,
            response.getLockKey(),
            response.getLockValue(),
            attempt,
            null
        );
    }

    public QueueConsumeAction handleQueuedJob(KnowledgeIndexQueueService.IndexQueueItem item) {
        if (item == null || item.jobId() == null || item.bookId() == null) {
            LOGGER.warn("knowledge queued index job discarded because payload is incomplete");
            return QueueConsumeAction.DEAD_LETTER;
        }
        Optional<AsyncJobVO> currentJob = asyncJobService.getJob(item.jobId());
        if (currentJob != null && currentJob.isPresent()) {
            AsyncJobVO job = currentJob.get();
            if (isTerminalStatus(job.getStatus())) {
                LOGGER.info("knowledge queued index job skipped because it is already terminal: jobId={}, status={}",
                    item.jobId(),
                    job.getStatus());
                asyncJobService.releaseLock(responseFromQueueItem(item));
                return QueueConsumeAction.ACK;
            }
            if (AsyncJobService.STATUS_PENDING.equals(job.getStatus())
                && job.getRetryCount() != null
                && job.getRetryCount() > item.attempt()) {
                int retryAttempt = Math.max(1, job.getRetryCount());
                if (job.getQueuePublishedAttempt() != null
                    && job.getQueuePublishedAttempt() >= retryAttempt) {
                    LOGGER.info("knowledge queued index job skipped because a newer retry is confirmed: jobId={}, messageAttempt={}, retryCount={}",
                        item.jobId(),
                        item.attempt(),
                        job.getRetryCount());
                    return QueueConsumeAction.ACK;
                }
                long retryDelaySeconds = queueService.retryBackoffSeconds(retryAttempt);
                LOGGER.info("knowledge queued index job retry publication is being recovered: jobId={}, attempt={}, retryCount={}",
                    item.jobId(),
                    item.attempt(),
                    job.getRetryCount());
                if (queueService.retry(item.withAttempt(retryAttempt), retryAttempt, retryDelaySeconds)) {
                    asyncJobService.markQueuePublished(item.jobId(), retryAttempt);
                    return QueueConsumeAction.ACK;
                }
                asyncJobService.markRetryPublishFailed(
                    item.jobId(),
                    RETRY_PUBLISH_FAILED_PREFIX + defaultError(job.getErrorMessage()),
                    retryAttempt
                );
                return QueueConsumeAction.REQUEUE;
            }
        }
        if (resourcePressureService.shouldPauseIndexing()) {
            long delaySeconds = Math.max(60L, queueService.retryBackoffSeconds(Math.max(1, item.attempt() + 1)));
            if (queueService.retry(item, item.attempt(), delaySeconds)) {
                LOGGER.info("knowledge queued index delayed by resource pressure: jobId={}, generation={}, delaySeconds={}",
                    item.jobId(), item.attempt(), delaySeconds);
                return QueueConsumeAction.ACK;
            }
            return QueueConsumeAction.REQUEUE;
        }
        AsyncJobSubmitResponse response = responseFromQueueItem(item);
        response.setStatus(AsyncJobService.STATUS_RUNNING);
        ScheduledFuture<?> heartbeat = null;
        AtomicBoolean leaseHealthy = new AtomicBoolean(true);
        try {
            if (!asyncJobService.tryMarkRunning(item.jobId(), item.attempt())) {
                Optional<AsyncJobVO> latest = asyncJobService.getJob(item.jobId());
                if (latest.isPresent()
                    && (isTerminalStatus(latest.get().getStatus())
                        || AsyncJobService.STATUS_RUNNING.equals(latest.get().getStatus()))) {
                    return QueueConsumeAction.ACK;
                }
                return QueueConsumeAction.REQUEUE;
            }
            heartbeat = startIndexHeartbeat(item, response, leaseHealthy);
            KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(
                item.bookId(),
                item.mode(),
                generationGuard(item.jobId(), item.attempt())
            );
            if (!leaseHealthy.get() || !asyncJobService.markSuccess(
                response.getJobId(),
                "knowledge_book",
                item.bookId(),
                "indexedChunks=" + result.indexedChunks() + ", createdChunks=" + result.createdChunks(),
                item.attempt()
            )) {
                LOGGER.warn("knowledge queued index completion ignored after execution lease loss: jobId={}, generation={}",
                    item.jobId(), item.attempt());
                return QueueConsumeAction.ACK;
            }
            asyncJobService.releaseLock(response);
            return QueueConsumeAction.ACK;
        } catch (Exception ex) {
            return handleQueuedFailure(item, response, ex);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> startIndexHeartbeat(KnowledgeIndexQueueService.IndexQueueItem item,
                                                   AsyncJobSubmitResponse response,
                                                   AtomicBoolean leaseHealthy) {
        return startIndexHeartbeat(item.jobId(), item.attempt(), response, leaseHealthy);
    }

    private QueueConsumeAction handleQueuedFailure(KnowledgeIndexQueueService.IndexQueueItem item,
                                                   AsyncJobSubmitResponse response,
                                                   Exception ex) {
        int nextAttempt = item.attempt() + 1;
        int maxRetries = Math.max(0, knowledgeProperties.getIndex().getMaxRetries());
        LOGGER.warn("knowledge queued index job failed: jobId={}, bookId={}, attempt={}, message={}",
            item.jobId(),
            item.bookId(),
            nextAttempt,
            ex.getMessage());
        if (nextAttempt <= maxRetries) {
            try {
                if (!asyncJobService.markPendingForRetry(item.jobId(), ex.getMessage(), item.attempt())) {
                    LOGGER.info("knowledge queued index retry ignored after execution lease loss: jobId={}, generation={}",
                        item.jobId(), item.attempt());
                    return QueueConsumeAction.ACK;
                }
                if (queueService.retry(item, nextAttempt, queueService.retryBackoffSeconds(nextAttempt))) {
                    asyncJobService.markQueuePublished(item.jobId(), nextAttempt);
                    return QueueConsumeAction.ACK;
                }
                asyncJobService.markRetryPublishFailed(
                    item.jobId(),
                    RETRY_PUBLISH_FAILED_PREFIX + ex.getMessage(),
                    nextAttempt
                );
            } catch (Exception retryEx) {
                LOGGER.warn("knowledge queued index retry scheduling failed: jobId={}, message={}",
                    item.jobId(),
                    retryEx.getMessage());
                asyncJobService.markRetryPublishFailed(
                    item.jobId(),
                    RETRY_PUBLISH_FAILED_PREFIX + retryEx.getMessage(),
                    nextAttempt
                );
            }
            return QueueConsumeAction.REQUEUE;
        }
        try {
            if (!asyncJobService.markFailed(item.jobId(), ex.getMessage(), item.attempt())) {
                LOGGER.info("knowledge queued index final failure ignored after execution lease loss: jobId={}, generation={}",
                    item.jobId(), item.attempt());
                return QueueConsumeAction.ACK;
            }
            asyncJobService.releaseLock(response);
            return QueueConsumeAction.DEAD_LETTER;
        } catch (Exception failureEx) {
            LOGGER.warn("knowledge queued index final failure handling failed: jobId={}, message={}",
                item.jobId(),
                failureEx.getMessage());
            return QueueConsumeAction.REQUEUE;
        }
    }

    private void executeSubmittedJob(Long bookId, String mode, AsyncJobSubmitResponse response, boolean rethrow) {
        AtomicBoolean leaseHealthy = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = startIndexHeartbeat(
            response.getJobId(),
            0,
            response,
            leaseHealthy
        );
        try {
            assertIndexingAvailable();
            KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(
                bookId,
                mode,
                generationGuard(response.getJobId(), 0)
            );
            if (!leaseHealthy.get() || !asyncJobService.markSuccess(
                response.getJobId(),
                "knowledge_book",
                bookId,
                "indexedChunks=" + result.indexedChunks() + ", createdChunks=" + result.createdChunks(),
                0
            )) {
                LOGGER.warn("knowledge index completion ignored after execution lease loss: jobId={}, generation={}",
                    response.getJobId(), 0);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge index job failed: jobId={}, bookId={}, message={}",
                response.getJobId(),
                bookId,
                ex.getMessage());
            if (!asyncJobService.markFailed(response.getJobId(), ex.getMessage(), 0)) {
                LOGGER.info("knowledge index failure ignored after execution lease loss: jobId={}, generation={}",
                    response.getJobId(), 0);
            }
            if (rethrow) {
                throw ex;
            }
        } finally {
            heartbeat.cancel(false);
            asyncJobService.releaseLock(response);
        }
    }

    private ScheduledFuture<?> startIndexHeartbeat(Long jobId,
                                                   int generation,
                                                   AsyncJobSubmitResponse response,
                                                   AtomicBoolean leaseHealthy) {
        long visibilitySeconds = Math.max(60L, knowledgeProperties.getIndex().getVisibilityTimeoutSeconds());
        long periodSeconds = Math.max(10L, Math.min(60L, visibilitySeconds / 3L));
        try {
            if (!asyncJobService.renewLock(response, visibilitySeconds)) {
                LOGGER.warn("knowledge index Redis lease renewal lost: jobId={}, generation={}",
                    jobId, generation);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge index Redis lease renewal failed: jobId={}, generation={}, message={}",
                jobId, generation, ex.getMessage());
        }
        return INDEX_LEASE_RENEWER.scheduleAtFixedRate(() -> {
            try {
                if (!asyncJobService.heartbeatRunning(jobId, generation)) {
                    leaseHealthy.set(false);
                    return;
                }
            } catch (RuntimeException ex) {
                leaseHealthy.set(false);
                LOGGER.warn("knowledge index database heartbeat failed: jobId={}, generation={}, message={}",
                    jobId, generation, ex.getMessage());
                return;
            }
            try {
                if (!asyncJobService.renewLock(response, visibilitySeconds)) {
                    LOGGER.warn("knowledge index Redis lease renewal lost: jobId={}, generation={}",
                        jobId, generation);
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("knowledge index Redis lease renewal failed: jobId={}, generation={}, message={}",
                    jobId, generation, ex.getMessage());
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    private AsyncJobSubmitResponse responseFromQueueItem(KnowledgeIndexQueueService.IndexQueueItem item) {
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(item.jobId());
        response.setJobType(item.jobType());
        response.setJobKey(item.jobKey());
        response.setLockKey(item.lockKey());
        response.setLockValue(item.lockValue());
        response.setAcquired(item.lockKey() != null && !item.lockKey().isBlank()
            && item.lockValue() != null && !item.lockValue().isBlank());
        return response;
    }

    private void assertIndexingAvailable() {
        if (resourcePressureService.shouldPauseIndexing()) {
            throw indexingPaused();
        }
    }

    private KnowledgeIndexService.IndexExecutionGuard generationGuard(Long jobId, int generation) {
        return new KnowledgeIndexService.IndexExecutionGuard() {
            @Override
            public void checkpoint() {
                if (!asyncJobService.isRunningGeneration(jobId, generation)) {
                    throw new AsyncJobService.GenerationLostException(jobId, generation);
                }
            }

            @Override
            public <T> T mysqlSideEffect(java.util.function.Supplier<T> sideEffect) {
                return asyncJobService.executeIfRunningGeneration(jobId, generation, sideEffect);
            }

            @Override
            public String pointId(Long chunkId) {
                String seed = "knowledge-index-point:" + chunkId + ":" + jobId + ":" + generation;
                return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
            }

            @Override
            public Map<String, Object> payloadMetadata() {
                return Map.of(
                    "indexJobId", jobId,
                    "indexGeneration", generation
                );
            }
        };
    }

    private BusinessException indexingPaused() {
        return new BusinessException(
            ResultCode.SERVICE_UNAVAILABLE,
            "\u7cfb\u7edf\u8d44\u6e90\u7d27\u5f20\uff0c\u5df2\u6682\u505c\u77e5\u8bc6\u5e93\u7d22\u5f15\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"
        );
    }

    private boolean isTerminalStatus(String status) {
        return AsyncJobService.STATUS_SUCCESS.equals(status)
            || AsyncJobService.STATUS_FAILED.equals(status)
            || AsyncJobService.STATUS_CANCELLED.equals(status);
    }

    public int recoverQueuedJobs(int limit) {
        if (!knowledgeProperties.getIndex().isQueueEnabled()) {
            return 0;
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        long visibilitySeconds = Math.max(60L, knowledgeProperties.getIndex().getVisibilityTimeoutSeconds());
        LocalDateTime now = LocalDateTime.now();
        java.util.List<AsyncJobVO> candidates = asyncJobService.findRecoverableIndexJobs(
            now.minusSeconds(visibilitySeconds),
            now.minusSeconds(visibilitySeconds),
            safeLimit
        );
        int recovered = 0;
        for (AsyncJobVO candidate : candidates) {
            try {
                AsyncJobVO job = candidate;
                boolean recoveredRunning = AsyncJobService.STATUS_RUNNING.equals(job.getStatus());
                if (recoveredRunning && !asyncJobService.resetRunningForRecovery(
                    job.getId(),
                    now.minusSeconds(visibilitySeconds)
                )) {
                    continue;
                }
                if (AsyncJobService.STATUS_RUNNING.equals(job.getStatus())) {
                    job.setStatus(AsyncJobService.STATUS_PENDING);
                    job.setRetryCount(Math.max(0, job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1);
                }
                Long bookId = parseBookId(job.getResourceKey());
                if (bookId == null) {
                    asyncJobService.markFailed(job.getId(), "invalid knowledge index resource key");
                    continue;
                }
                int attempt = Math.max(0, job.getRetryCount() == null ? 0 : job.getRetryCount());
                AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
                response.setJobId(job.getId());
                response.setJobType(job.getJobType());
                response.setJobKey(job.getJobKey());
                response.setStatus(AsyncJobService.STATUS_PENDING);
                if (enqueueAndRecord(
                    bookId,
                    job.getTriggerUserId(),
                    modeFromJobKey(job.getJobKey()),
                    response,
                    attempt
                )) {
                    recovered++;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("knowledge index queue recovery failed: jobId={}, message={}",
                    candidate.getId(),
                    ex.getMessage());
            }
        }
        return recovered;
    }

    private Long parseBookId(String resourceKey) {
        if (resourceKey == null || !resourceKey.startsWith("book:")) {
            return null;
        }
        try {
            return Long.parseLong(resourceKey.substring("book:".length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String modeFromJobKey(String jobKey) {
        if (jobKey == null) {
            return "ALL";
        }
        for (String mode : java.util.List.of(
            "FULL_REINDEX", "RANK_INCREMENTAL", "RANK_MISSING", "CHAPTER_MISSING", "FAILED_ONLY"
        )) {
            if (jobKey.contains(":" + mode)) {
                return mode;
            }
        }
        return "ALL";
    }

    private String defaultError(String errorMessage) {
        return errorMessage == null || errorMessage.isBlank() ? "queue publication was not recorded" : errorMessage;
    }
}
