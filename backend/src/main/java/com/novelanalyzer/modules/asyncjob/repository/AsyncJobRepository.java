package com.novelanalyzer.modules.asyncjob.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novelanalyzer.modules.asyncjob.mapper.AsyncJobMapper;
import com.novelanalyzer.modules.asyncjob.model.AsyncJobEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AsyncJobRepository {

    private final AsyncJobMapper asyncJobMapper;

    public AsyncJobRepository(AsyncJobMapper asyncJobMapper) {
        this.asyncJobMapper = asyncJobMapper;
    }

    public Long save(AsyncJobEntity entity) {
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(LocalDateTime.now());
        }
        entity.setUpdateTime(LocalDateTime.now());
        entity.setDeleted(entity.getDeleted() == null ? 0 : entity.getDeleted());
        asyncJobMapper.insert(entity);
        return entity.getId();
    }

    public void updateById(AsyncJobEntity entity) {
        entity.setUpdateTime(LocalDateTime.now());
        asyncJobMapper.updateById(entity);
    }

    public boolean markRunningIfPending(Long id) {
        return markRunningIfPending(id, null);
    }

    public boolean markRunningIfPending(Long id, Integer expectedGeneration) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "PENDING")
                .eq(expectedGeneration != null, AsyncJobEntity::getRetryCount, expectedGeneration)
                .set(AsyncJobEntity::getStatus, "RUNNING")
                .set(AsyncJobEntity::getStartedAt, now)
                .set(AsyncJobEntity::getFinishedAt, null)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public boolean resetRunningForRecovery(Long id, LocalDateTime cutoff) {
        if (id == null || cutoff == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .lt(AsyncJobEntity::getStartedAt, cutoff)
                .set(AsyncJobEntity::getStatus, "PENDING")
                .set(AsyncJobEntity::getErrorMessage, "recovered after worker lease timeout")
                .set(AsyncJobEntity::getFinishedAt, null)
                .set(AsyncJobEntity::getQueuePublishedAt, null)
                .set(AsyncJobEntity::getQueuePublishedAttempt, null)
                .setSql("retry_count = COALESCE(retry_count, 0) + 1")
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public boolean heartbeatRunning(Long id, int expectedGeneration) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .eq(AsyncJobEntity::getRetryCount, expectedGeneration)
                .set(AsyncJobEntity::getStartedAt, now)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public boolean isRunningGeneration(Long id, int expectedGeneration) {
        if (id == null) {
            return false;
        }
        Long count = asyncJobMapper.selectCount(
            new LambdaQueryWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .eq(AsyncJobEntity::getRetryCount, expectedGeneration)
        );
        return count != null && count == 1L;
    }

    public boolean lockRunningGeneration(Long id, int expectedGeneration) {
        if (id == null) {
            return false;
        }
        AsyncJobEntity entity = asyncJobMapper.selectOne(
            new LambdaQueryWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .eq(AsyncJobEntity::getRetryCount, expectedGeneration)
                .last("FOR UPDATE")
        );
        return entity != null;
    }

    public boolean markSuccessIfRunning(Long id,
                                        int expectedGeneration,
                                        String resultRefType,
                                        Long resultRefId,
                                        String resultSummary) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .eq(AsyncJobEntity::getRetryCount, expectedGeneration)
                .set(AsyncJobEntity::getStatus, "SUCCESS")
                .set(AsyncJobEntity::getResultRefType, resultRefType)
                .set(AsyncJobEntity::getResultRefId, resultRefId)
                .set(AsyncJobEntity::getResultSummary, resultSummary)
                .set(AsyncJobEntity::getErrorMessage, null)
                .set(AsyncJobEntity::getFinishedAt, now)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public boolean markPendingForRetryIfRunning(Long id,
                                                int expectedGeneration,
                                                String errorMessage) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .eq(AsyncJobEntity::getRetryCount, expectedGeneration)
                .set(AsyncJobEntity::getStatus, "PENDING")
                .set(AsyncJobEntity::getErrorMessage, errorMessage)
                .setSql("retry_count = COALESCE(retry_count, 0) + 1")
                .set(AsyncJobEntity::getFinishedAt, null)
                .set(AsyncJobEntity::getQueuePublishedAt, null)
                .set(AsyncJobEntity::getQueuePublishedAttempt, null)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public boolean markFailedIfRunning(Long id, int expectedGeneration, String errorMessage) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "RUNNING")
                .eq(AsyncJobEntity::getRetryCount, expectedGeneration)
                .set(AsyncJobEntity::getStatus, "FAILED")
                .set(AsyncJobEntity::getErrorMessage, errorMessage)
                .set(AsyncJobEntity::getFinishedAt, now)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public List<AsyncJobEntity> findRecoverableIndexJobs(String jobType,
                                                         LocalDateTime pendingCutoff,
                                                         LocalDateTime runningCutoff,
                                                         int limit) {
        return asyncJobMapper.selectList(
            new LambdaQueryWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getJobType, jobType)
                .eq(AsyncJobEntity::getDeleted, 0)
                .and(wrapper -> wrapper
                    .and(pending -> pending
                        .eq(AsyncJobEntity::getStatus, "PENDING")
                        .and(published -> published
                            .isNull(AsyncJobEntity::getQueuePublishedAt)
                            .or()
                            .lt(AsyncJobEntity::getQueuePublishedAt, pendingCutoff)))
                    .or(running -> running
                        .eq(AsyncJobEntity::getStatus, "RUNNING")
                        .lt(AsyncJobEntity::getStartedAt, runningCutoff)))
                .orderByAsc(AsyncJobEntity::getUpdateTime)
                .last("LIMIT " + Math.max(1, limit))
        );
    }

    public boolean markQueuePublished(Long id, int attempt) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getRetryCount, attempt)
                .set(AsyncJobEntity::getQueuePublishedAt, now)
                .set(AsyncJobEntity::getQueuePublishedAttempt, attempt)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public boolean markPublishFailureIfPending(Long id, String errorMessage) {
        return markPublishFailureIfPending(id, null, errorMessage);
    }

    public boolean markPublishFailureIfPending(Long id, Integer expectedGeneration, String errorMessage) {
        if (id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = asyncJobMapper.update(null,
            new LambdaUpdateWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .eq(AsyncJobEntity::getStatus, "PENDING")
                .eq(expectedGeneration != null, AsyncJobEntity::getRetryCount, expectedGeneration)
                .set(AsyncJobEntity::getErrorMessage, errorMessage)
                .set(AsyncJobEntity::getFinishedAt, null)
                .set(AsyncJobEntity::getQueuePublishedAt, null)
                .set(AsyncJobEntity::getQueuePublishedAttempt, null)
                .set(AsyncJobEntity::getUpdateTime, now)
        );
        return updated == 1;
    }

    public Optional<AsyncJobEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        AsyncJobEntity entity = asyncJobMapper.selectOne(
            new LambdaQueryWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getId, id)
                .eq(AsyncJobEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public Optional<AsyncJobEntity> findLatestByTypeAndKey(String jobType, String jobKey) {
        AsyncJobEntity entity = asyncJobMapper.selectOne(
            new LambdaQueryWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getJobType, jobType)
                .eq(AsyncJobEntity::getJobKey, jobKey)
                .eq(AsyncJobEntity::getDeleted, 0)
                .orderByDesc(AsyncJobEntity::getCreateTime)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public long countByTypeKeyAndUserAfter(String jobType,
                                           String jobKey,
                                           Long triggerUserId,
                                           String status,
                                           LocalDateTime createdAfter) {
        LambdaQueryWrapper<AsyncJobEntity> wrapper = new LambdaQueryWrapper<AsyncJobEntity>()
            .eq(AsyncJobEntity::getJobType, jobType)
            .eq(AsyncJobEntity::getJobKey, jobKey)
            .eq(AsyncJobEntity::getDeleted, 0)
            .eq(triggerUserId != null, AsyncJobEntity::getTriggerUserId, triggerUserId)
            .eq(status != null, AsyncJobEntity::getStatus, status)
            .gt(createdAfter != null, AsyncJobEntity::getCreateTime, createdAfter);
        return asyncJobMapper.selectCount(wrapper);
    }

    public List<AsyncJobEntity> findLatestByResourceKey(String resourceKey, int limit) {
        return asyncJobMapper.selectList(
            new LambdaQueryWrapper<AsyncJobEntity>()
                .eq(AsyncJobEntity::getResourceKey, resourceKey)
                .eq(AsyncJobEntity::getDeleted, 0)
                .orderByDesc(AsyncJobEntity::getCreateTime)
                .last("LIMIT " + Math.max(1, limit))
        );
    }
}
