package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.config.mapper.UserPromptBindingMapper;
import com.novelanalyzer.modules.config.model.UserPromptBindingEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserPromptBindingRepository {

    private final UserPromptBindingMapper userPromptBindingMapper;

    public UserPromptBindingRepository(UserPromptBindingMapper userPromptBindingMapper) {
        this.userPromptBindingMapper = userPromptBindingMapper;
    }

    public Long saveOrUpdate(UserPromptBindingEntity entity) {
        Optional<UserPromptBindingEntity> existing = findActiveBinding(entity.getUserId(), entity.getPromptType());
        if (existing.isPresent()) {
            UserPromptBindingEntity dbEntity = existing.get();
            dbEntity.setBindingMode(entity.getBindingMode());
            dbEntity.setBoundPromptConfigId(entity.getBoundPromptConfigId());
            dbEntity.setEffectivePromptConfigId(entity.getEffectivePromptConfigId());
            dbEntity.setLastSelectedPromptConfigId(entity.getLastSelectedPromptConfigId());
            dbEntity.setFallbackWarning(entity.getFallbackWarning());
            dbEntity.setStatus(entity.getStatus());
            userPromptBindingMapper.updateById(dbEntity);
            return dbEntity.getId();
        }
        userPromptBindingMapper.insert(entity);
        return entity.getId();
    }

    public Optional<UserPromptBindingEntity> findActiveBinding(Long userId, String promptType) {
        UserPromptBindingEntity entity = userPromptBindingMapper.selectOne(
            new LambdaQueryWrapper<UserPromptBindingEntity>()
                .eq(UserPromptBindingEntity::getUserId, userId)
                .eq(UserPromptBindingEntity::getPromptType, promptType)
                .eq(UserPromptBindingEntity::getDeleted, 0)
                .eq(UserPromptBindingEntity::getStatus, 1)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }
}
