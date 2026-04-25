package com.novelanalyzer.modules.asyncjob.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
