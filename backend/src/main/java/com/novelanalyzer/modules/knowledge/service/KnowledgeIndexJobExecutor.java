package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeRebuildResponse;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.asyncjob.vo.AsyncJobVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class KnowledgeIndexJobExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexJobExecutor.class);
    private static final String QUEUE_PUBLISH_FAILED_MESSAGE = "knowledge index queue publish failed";
    private static final String RETRY_PUBLISH_FAILED_PREFIX = "knowledge index retry publish failed: ";

    private final KnowledgeIndexService knowledgeIndexService;
    private final AsyncJobService asyncJobService;
    private final TaskExecutor knowledgeIndexTaskExecutor;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIndexQueueService queueService;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeIndexJobExecutor(KnowledgeIndexService knowledgeIndexService,
                                     AsyncJobService asyncJobService,
                                     @Qualifier("knowledgeIndexTaskExecutor") TaskExecutor knowledgeIndexTaskExecutor,
                                     KnowledgeRepository knowledgeRepository,
                                     KnowledgeIndexQueueService queueService,
                                     KnowledgeProperties knowledgeProperties) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.asyncJobService = asyncJobService;
        this.knowledgeIndexTaskExecutor = knowledgeIndexTaskExecutor;
        this.knowledgeRepository = knowledgeRepository;
        this.queueService = queueService;
        this.knowledgeProperties = knowledgeProperties;
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
        String normalizedMode = normalizeMode(mode);
        AsyncJobSubmitResponse response = knowledgeProperties.getIndex().isQueueEnabled()
            ? knowledgeIndexService.submitBookIndexPendingJob(bookId, triggerUserId, normalizedMode)
            : knowledgeIndexService.submitBookIndexJob(bookId, triggerUserId, normalizedMode);
        if (response == null || Boolean.TRUE.equals(response.getReused())) {
            return response;
        }
        if (knowledgeProperties.getIndex().isQueueEnabled() && enqueue(bookId, triggerUserId, normalizedMode, response)) {
            return response;
        } else if (knowledgeProperties.getIndex().isQueueEnabled()) {
            try {
                asyncJobService.markFailed(response.getJobId(), QUEUE_PUBLISH_FAILED_MESSAGE);
            } finally {
                asyncJobService.releaseLock(response);
            }
            throw new IllegalStateException(QUEUE_PUBLISH_FAILED_MESSAGE);
        }
        knowledgeIndexTaskExecutor.execute(() -> executeSubmittedJob(bookId, normalizedMode, response, false));
        return response;
    }

    public AsyncJobSubmitResponse submitAndExecuteBlocking(Long bookId, Long triggerUserId) {
        AsyncJobSubmitResponse response = knowledgeIndexService.submitBookIndexJob(bookId, triggerUserId);
        if (response == null || Boolean.TRUE.equals(response.getReused())) {
            return response;
        }
        executeSubmittedJob(bookId, "ALL", response, true);
        return response;
    }

    public KnowledgeRebuildResponse submitRebuild(String mode, int limit, Long triggerUserId) {
        String normalizedMode = normalizeMode(mode);
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

    private boolean enqueue(Long bookId, Long triggerUserId, String mode, AsyncJobSubmitResponse response) {
        String normalizedMode = normalizeMode(mode);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
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
            0,
            null
        );
        return queueService.enqueue(item);
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
                if (isRetryPublishFailed(job.getErrorMessage())) {
                    int retryAttempt = Math.max(1, job.getRetryCount());
                    long retryDelaySeconds = queueService.retryBackoffSeconds(retryAttempt);
                    LOGGER.info("knowledge queued index job retry publish is being recovered: jobId={}, attempt={}, retryCount={}",
                        item.jobId(),
                        item.attempt(),
                        job.getRetryCount());
                    if (queueService.retry(item.withAttempt(retryAttempt), retryAttempt, retryDelaySeconds)) {
                        return QueueConsumeAction.ACK;
                    }
                    return QueueConsumeAction.REQUEUE;
                }
                LOGGER.info("knowledge queued index job skipped because a newer retry is already scheduled: jobId={}, messageAttempt={}, retryCount={}",
                    item.jobId(),
                    item.attempt(),
                    job.getRetryCount());
                asyncJobService.releaseLock(responseFromQueueItem(item));
                return QueueConsumeAction.ACK;
            }
        }
        AsyncJobSubmitResponse response = responseFromQueueItem(item);
        response.setStatus(AsyncJobService.STATUS_RUNNING);
        try {
            asyncJobService.markRunning(item.jobId());
            asyncJobService.renewLock(response, Math.max(60, knowledgeProperties.getIndex().getVisibilityTimeoutSeconds()));
            KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(item.bookId(), item.mode());
            asyncJobService.markSuccess(
                response.getJobId(),
                "knowledge_book",
                item.bookId(),
                "indexedChunks=" + result.indexedChunks() + ", createdChunks=" + result.createdChunks()
            );
            asyncJobService.releaseLock(response);
            return QueueConsumeAction.ACK;
        } catch (Exception ex) {
            return handleQueuedFailure(item, response, ex);
        }
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
                asyncJobService.markPendingForRetry(item.jobId(), ex.getMessage());
                if (queueService.retry(item, nextAttempt, queueService.retryBackoffSeconds(nextAttempt))) {
                    return QueueConsumeAction.ACK;
                }
                asyncJobService.markRetryPublishFailed(item.jobId(), RETRY_PUBLISH_FAILED_PREFIX + ex.getMessage());
            } catch (Exception retryEx) {
                LOGGER.warn("knowledge queued index retry scheduling failed: jobId={}, message={}",
                    item.jobId(),
                    retryEx.getMessage());
                asyncJobService.markRetryPublishFailed(item.jobId(), RETRY_PUBLISH_FAILED_PREFIX + retryEx.getMessage());
            }
            return QueueConsumeAction.REQUEUE;
        }
        boolean markedFailed = false;
        try {
            asyncJobService.markFailed(item.jobId(), ex.getMessage());
            markedFailed = true;
            return QueueConsumeAction.DEAD_LETTER;
        } catch (Exception failureEx) {
            LOGGER.warn("knowledge queued index final failure handling failed: jobId={}, message={}",
                item.jobId(),
                failureEx.getMessage());
            return QueueConsumeAction.REQUEUE;
        } finally {
            asyncJobService.releaseLock(response);
            if (!markedFailed) {
                LOGGER.warn("knowledge queued index final failure lock released after status update failure: jobId={}", item.jobId());
            }
        }
    }

    private void executeSubmittedJob(Long bookId, String mode, AsyncJobSubmitResponse response, boolean rethrow) {
        try {
            KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(bookId, mode);
            asyncJobService.markSuccess(
                response.getJobId(),
                "knowledge_book",
                bookId,
                "indexedChunks=" + result.indexedChunks() + ", createdChunks=" + result.createdChunks()
            );
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge index job failed: jobId={}, bookId={}, message={}",
                response.getJobId(),
                bookId,
                ex.getMessage());
            asyncJobService.markFailed(response.getJobId(), ex.getMessage());
            if (rethrow) {
                throw ex;
            }
        } finally {
            asyncJobService.releaseLock(response);
        }
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

    private boolean isTerminalStatus(String status) {
        return AsyncJobService.STATUS_SUCCESS.equals(status)
            || AsyncJobService.STATUS_FAILED.equals(status)
            || AsyncJobService.STATUS_CANCELLED.equals(status);
    }

    private boolean isRetryPublishFailed(String errorMessage) {
        return errorMessage != null && errorMessage.startsWith(RETRY_PUBLISH_FAILED_PREFIX);
    }
}
