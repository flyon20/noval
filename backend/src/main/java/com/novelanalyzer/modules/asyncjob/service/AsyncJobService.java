package com.novelanalyzer.modules.asyncjob.service;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.model.AsyncJobEntity;
import com.novelanalyzer.modules.asyncjob.repository.AsyncJobRepository;
import com.novelanalyzer.modules.asyncjob.vo.AsyncJobVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Transactional
public class AsyncJobService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final AsyncJobRepository asyncJobRepository;
    private final AsyncJobLockService asyncJobLockService;

    public AsyncJobService(AsyncJobRepository asyncJobRepository,
                           AsyncJobLockService asyncJobLockService) {
        this.asyncJobRepository = asyncJobRepository;
        this.asyncJobLockService = asyncJobLockService;
    }

    public AsyncJobSubmitResponse submitOrReuse(String jobType,
                                                String jobKey,
                                                String resourceKey,
                                                String requestJson,
                                                Long triggerUserId,
                                                long lockTtlSeconds) {
        return submitOrReuse(
            jobType,
            jobKey,
            resourceKey,
            requestJson,
            triggerUserId,
            lockTtlSeconds,
            STATUS_RUNNING,
            false
        );
    }

    public AsyncJobSubmitResponse submitOrReuseSuccessful(String jobType,
                                                          String jobKey,
                                                          String resourceKey,
                                                          String requestJson,
                                                          Long triggerUserId,
                                                          long lockTtlSeconds) {
        return submitOrReuse(
            jobType,
            jobKey,
            resourceKey,
            requestJson,
            triggerUserId,
            lockTtlSeconds,
            STATUS_RUNNING,
            true
        );
    }

    public AsyncJobSubmitResponse submitOrReusePending(String jobType,
                                                       String jobKey,
                                                       String resourceKey,
                                                       String requestJson,
                                                       Long triggerUserId,
                                                       long lockTtlSeconds) {
        return submitOrReuse(
            jobType,
            jobKey,
            resourceKey,
            requestJson,
            triggerUserId,
            lockTtlSeconds,
            STATUS_PENDING,
            false
        );
    }

    public AsyncJobSubmitResponse submitOrReuseSuccessfulPending(String jobType,
                                                                 String jobKey,
                                                                 String resourceKey,
                                                                 String requestJson,
                                                                 Long triggerUserId,
                                                                 long lockTtlSeconds) {
        return submitOrReuse(
            jobType,
            jobKey,
            resourceKey,
            requestJson,
            triggerUserId,
            lockTtlSeconds,
            STATUS_PENDING,
            true
        );
    }

    private AsyncJobSubmitResponse submitOrReuse(String jobType,
                                                 String jobKey,
                                                 String resourceKey,
                                                 String requestJson,
                                                 Long triggerUserId,
                                                 long lockTtlSeconds,
                                                 String initialStatus,
                                                 boolean reuseSuccessful) {
        Optional<AsyncJobEntity> latest = asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey);
        if (latest.isPresent() && isReusableStatus(latest.get().getStatus(), reuseSuccessful)) {
            return toSubmitResponse(latest.get(), true, false, null, null);
        }

        String lockKey = buildLockKey(jobType, jobKey);
        String lockValue = UUID.randomUUID().toString();
        boolean acquired = asyncJobLockService.tryAcquire(lockKey, lockValue, lockTtlSeconds);
        if (!acquired) {
            Optional<AsyncJobEntity> reused = asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey);
            if (reused.isPresent() && isReusableStatus(reused.get().getStatus(), reuseSuccessful)) {
                return toSubmitResponse(reused.get(), true, false, lockKey, null);
            }
            throw new IllegalStateException("async job idempotency lock is held before a reusable job is visible");
        }

        latest = asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey);
        if (latest.isPresent() && isReusableStatus(latest.get().getStatus(), reuseSuccessful)) {
            asyncJobLockService.release(lockKey, lockValue);
            return toSubmitResponse(latest.get(), true, false, lockKey, null);
        }

        AsyncJobEntity entity = latest.orElseGet(AsyncJobEntity::new);
        initializeForSubmission(entity, jobType, jobKey, resourceKey, requestJson, triggerUserId, initialStatus);
        if (entity.getId() == null) {
            try {
                Long id = asyncJobRepository.save(entity);
                entity.setId(id);
            } catch (DuplicateKeyException ex) {
                Optional<AsyncJobEntity> raced = asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey);
                asyncJobLockService.release(lockKey, lockValue);
                if (raced.isPresent()) {
                    return toSubmitResponse(raced.get(), true, false, lockKey, null);
                }
                throw ex;
            }
        } else {
            asyncJobRepository.updateById(entity);
        }
        return toSubmitResponse(entity, false, true, lockKey, lockValue);
    }

    private void initializeForSubmission(AsyncJobEntity entity,
                                         String jobType,
                                         String jobKey,
                                         String resourceKey,
                                         String requestJson,
                                         Long triggerUserId,
                                         String initialStatus) {
        entity.setJobType(jobType);
        entity.setJobKey(jobKey);
        entity.setResourceKey(resourceKey);
        entity.setRequestJson(requestJson);
        entity.setStatus(initialStatus);
        entity.setTriggerUserId(triggerUserId);
        entity.setResultRefType(null);
        entity.setResultRefId(null);
        entity.setResultSummary(null);
        entity.setErrorMessage(null);
        entity.setRetryCount(0);
        entity.setStartedAt(STATUS_RUNNING.equals(initialStatus) ? LocalDateTime.now() : null);
        entity.setFinishedAt(null);
        entity.setQueuePublishedAt(null);
        entity.setQueuePublishedAttempt(null);
    }

    public Optional<AsyncJobVO> getJob(Long jobId) {
        return asyncJobRepository.findById(jobId).map(this::toVO);
    }

    public Optional<AsyncJobVO> getLatestJob(String jobType, String jobKey) {
        return asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey).map(this::toVO);
    }

    public long countSuccessfulJobs(String jobType, String jobKey, Long triggerUserId, LocalDateTime createdAfter) {
        return asyncJobRepository.countByTypeKeyAndUserAfter(jobType, jobKey, triggerUserId, STATUS_SUCCESS, createdAfter);
    }

    public void markSuccess(Long jobId, String resultRefType, Long resultRefId, String resultSummary) {
        AsyncJobEntity entity = asyncJobRepository.findById(jobId).orElseThrow();
        entity.setStatus(STATUS_SUCCESS);
        entity.setResultRefType(resultRefType);
        entity.setResultRefId(resultRefId);
        entity.setResultSummary(resultSummary);
        entity.setFinishedAt(LocalDateTime.now());
        asyncJobRepository.updateById(entity);
    }

    public void markRunning(Long jobId) {
        AsyncJobEntity entity = asyncJobRepository.findById(jobId).orElseThrow();
        entity.setStatus(STATUS_RUNNING);
        entity.setStartedAt(LocalDateTime.now());
        entity.setFinishedAt(null);
        asyncJobRepository.updateById(entity);
    }

    public boolean tryMarkRunning(Long jobId) {
        return asyncJobRepository.markRunningIfPending(jobId);
    }

    public boolean tryMarkRunning(Long jobId, int expectedGeneration) {
        return asyncJobRepository.markRunningIfPending(jobId, expectedGeneration);
    }

    public boolean heartbeatRunning(Long jobId, int expectedGeneration) {
        return asyncJobRepository.heartbeatRunning(jobId, expectedGeneration);
    }

    public boolean isRunningGeneration(Long jobId, int expectedGeneration) {
        return asyncJobRepository.isRunningGeneration(jobId, expectedGeneration);
    }

    public <T> T executeIfRunningGeneration(Long jobId,
                                            int expectedGeneration,
                                            Supplier<T> sideEffect) {
        if (!asyncJobRepository.lockRunningGeneration(jobId, expectedGeneration)) {
            throw new GenerationLostException(jobId, expectedGeneration);
        }
        return sideEffect.get();
    }

    public boolean markSuccess(Long jobId,
                               String resultRefType,
                               Long resultRefId,
                               String resultSummary,
                               int expectedGeneration) {
        return asyncJobRepository.markSuccessIfRunning(
            jobId,
            expectedGeneration,
            resultRefType,
            resultRefId,
            resultSummary
        );
    }

    public void markPendingForRetry(Long jobId, String errorMessage) {
        AsyncJobEntity entity = asyncJobRepository.findById(jobId).orElseThrow();
        entity.setStatus(STATUS_PENDING);
        entity.setErrorMessage(truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH));
        entity.setRetryCount((entity.getRetryCount() == null ? 0 : entity.getRetryCount()) + 1);
        entity.setFinishedAt(null);
        entity.setQueuePublishedAt(null);
        entity.setQueuePublishedAttempt(null);
        asyncJobRepository.updateById(entity);
    }

    public boolean markPendingForRetry(Long jobId, String errorMessage, int expectedGeneration) {
        return asyncJobRepository.markPendingForRetryIfRunning(
            jobId,
            expectedGeneration,
            truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH)
        );
    }

    public void markRetryPublishFailed(Long jobId, String errorMessage) {
        markRetryPublishFailed(jobId, errorMessage, null);
    }

    public void markRetryPublishFailed(Long jobId, String errorMessage, Integer expectedGeneration) {
        asyncJobRepository.markPublishFailureIfPending(
            jobId,
            expectedGeneration,
            truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH)
        );
    }

    public void markQueuePublished(Long jobId, int attempt) {
        if (!asyncJobRepository.markQueuePublished(jobId, attempt)) {
            throw new IllegalStateException("async job disappeared while recording queue publication");
        }
    }

    public boolean resetRunningForRecovery(Long jobId, LocalDateTime cutoff) {
        return asyncJobRepository.resetRunningForRecovery(jobId, cutoff);
    }

    public java.util.List<AsyncJobVO> findRecoverableIndexJobs(LocalDateTime pendingCutoff,
                                                               LocalDateTime runningCutoff,
                                                               int limit) {
        return asyncJobRepository.findRecoverableIndexJobs(
                "KNOWLEDGE_INDEX_BOOK",
                pendingCutoff,
                runningCutoff,
                limit
            ).stream()
            .map(this::toVO)
            .toList();
    }

    public void markFailed(Long jobId, String errorMessage) {
        AsyncJobEntity entity = asyncJobRepository.findById(jobId).orElseThrow();
        entity.setStatus(STATUS_FAILED);
        entity.setErrorMessage(truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH));
        entity.setFinishedAt(LocalDateTime.now());
        asyncJobRepository.updateById(entity);
    }

    public boolean markFailed(Long jobId, String errorMessage, int expectedGeneration) {
        return asyncJobRepository.markFailedIfRunning(
            jobId,
            expectedGeneration,
            truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH)
        );
    }

    public void releaseLock(AsyncJobSubmitResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.getAcquired())) {
            return;
        }
        asyncJobLockService.release(response.getLockKey(), response.getLockValue());
    }

    public boolean renewLock(AsyncJobSubmitResponse response, long ttlSeconds) {
        if (response == null || response.getLockKey() == null || response.getLockValue() == null) {
            return true;
        }
        return asyncJobLockService.renewStrict(response.getLockKey(), response.getLockValue(), ttlSeconds);
    }

    public static final class GenerationLostException extends IllegalStateException {

        public GenerationLostException(Long jobId, int generation) {
            super("async job generation lost: jobId=" + jobId + ", generation=" + generation);
        }
    }

    private String buildLockKey(String jobType, String jobKey) {
        return "lock:" + jobType + ":" + jobKey;
    }

    private boolean isActiveStatus(String status) {
        return STATUS_PENDING.equals(status) || STATUS_RUNNING.equals(status);
    }

    private boolean isReusableStatus(String status, boolean reuseSuccessful) {
        return isActiveStatus(status) || (reuseSuccessful && STATUS_SUCCESS.equals(status));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private AsyncJobSubmitResponse toSubmitResponse(AsyncJobEntity entity,
                                                    boolean reused,
                                                    boolean acquired,
                                                    String lockKey,
                                                    String lockValue) {
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(entity.getId());
        response.setJobType(entity.getJobType());
        response.setJobKey(entity.getJobKey());
        response.setStatus(entity.getStatus());
        response.setReused(reused);
        response.setAcquired(acquired);
        response.setLockKey(lockKey);
        response.setLockValue(lockValue);
        return response;
    }

    private AsyncJobVO toVO(AsyncJobEntity entity) {
        AsyncJobVO vo = new AsyncJobVO();
        vo.setId(entity.getId());
        vo.setJobType(entity.getJobType());
        vo.setJobKey(entity.getJobKey());
        vo.setResourceKey(entity.getResourceKey());
        vo.setStatus(entity.getStatus());
        vo.setTriggerUserId(entity.getTriggerUserId());
        vo.setResultRefType(entity.getResultRefType());
        vo.setResultRefId(entity.getResultRefId());
        vo.setResultSummary(entity.getResultSummary());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setRetryCount(entity.getRetryCount());
        vo.setStartedAt(entity.getStartedAt());
        vo.setFinishedAt(entity.getFinishedAt());
        vo.setQueuePublishedAt(entity.getQueuePublishedAt());
        vo.setQueuePublishedAttempt(entity.getQueuePublishedAttempt());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
