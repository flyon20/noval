package com.novelanalyzer.modules.asyncjob.service;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.model.AsyncJobEntity;
import com.novelanalyzer.modules.asyncjob.repository.AsyncJobRepository;
import com.novelanalyzer.modules.asyncjob.vo.AsyncJobVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
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
        Optional<AsyncJobEntity> latest = asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey);
        if (latest.isPresent() && isActiveStatus(latest.get().getStatus())) {
            return toSubmitResponse(latest.get(), true, false, null, null);
        }

        String lockKey = buildLockKey(jobType, jobKey);
        String lockValue = UUID.randomUUID().toString();
        boolean acquired = asyncJobLockService.tryAcquire(lockKey, lockValue, lockTtlSeconds);
        if (!acquired) {
            Optional<AsyncJobEntity> reused = asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey);
            if (reused.isPresent()) {
                return toSubmitResponse(reused.get(), true, false, lockKey, null);
            }
        }

        AsyncJobEntity entity = new AsyncJobEntity();
        entity.setJobType(jobType);
        entity.setJobKey(jobKey);
        entity.setResourceKey(resourceKey);
        entity.setRequestJson(requestJson);
        entity.setStatus(STATUS_RUNNING);
        entity.setTriggerUserId(triggerUserId);
        entity.setRetryCount(0);
        entity.setStartedAt(LocalDateTime.now());
        Long id = asyncJobRepository.save(entity);
        entity.setId(id);
        return toSubmitResponse(entity, false, true, lockKey, lockValue);
    }

    public Optional<AsyncJobVO> getJob(Long jobId) {
        return asyncJobRepository.findById(jobId).map(this::toVO);
    }

    public Optional<AsyncJobVO> getLatestJob(String jobType, String jobKey) {
        return asyncJobRepository.findLatestByTypeAndKey(jobType, jobKey).map(this::toVO);
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

    public void markFailed(Long jobId, String errorMessage) {
        AsyncJobEntity entity = asyncJobRepository.findById(jobId).orElseThrow();
        entity.setStatus(STATUS_FAILED);
        entity.setErrorMessage(truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH));
        entity.setFinishedAt(LocalDateTime.now());
        asyncJobRepository.updateById(entity);
    }

    public void releaseLock(AsyncJobSubmitResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.getAcquired())) {
            return;
        }
        asyncJobLockService.release(response.getLockKey(), response.getLockValue());
    }

    private String buildLockKey(String jobType, String jobKey) {
        return "lock:" + jobType + ":" + jobKey;
    }

    private boolean isActiveStatus(String status) {
        return STATUS_PENDING.equals(status) || STATUS_RUNNING.equals(status);
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
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
