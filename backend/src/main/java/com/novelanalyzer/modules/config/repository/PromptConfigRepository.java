package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.config.mapper.PromptConfigMapper;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PromptConfigRepository {

    private final PromptConfigMapper promptConfigMapper;

    public PromptConfigRepository(PromptConfigMapper promptConfigMapper) {
        this.promptConfigMapper = promptConfigMapper;
    }

    public Optional<PromptConfigEntity> findDefaultByType(String promptType) {
        PromptConfigEntity entity = promptConfigMapper.selectOne(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getPromptType, promptType)
                .eq(PromptConfigEntity::getStatus, 1)
                .eq(PromptConfigEntity::getDeleted, 0)
                .orderByDesc(PromptConfigEntity::getIsDefault)
                .orderByAsc(PromptConfigEntity::getId)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public Optional<PromptConfigEntity> findByTypeAndName(String promptType, String promptName) {
        PromptConfigEntity entity = promptConfigMapper.selectOne(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getPromptType, promptType)
                .eq(PromptConfigEntity::getPromptName, promptName)
                .eq(PromptConfigEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public Long saveOrUpdate(PromptConfigEntity entity) {
        Optional<PromptConfigEntity> existing = findByTypeAndName(entity.getPromptType(), entity.getPromptName());
        if (existing.isPresent()) {
            PromptConfigEntity dbEntity = existing.get();
            dbEntity.setPromptContent(entity.getPromptContent());
            dbEntity.setModelName(entity.getModelName());
            if (entity.getTemperature() != null) {
                dbEntity.setTemperature(entity.getTemperature());
            }
            if (entity.getMaxTokens() != null) {
                dbEntity.setMaxTokens(entity.getMaxTokens());
            }
            if (entity.getDifyWorkflowId() != null) {
                dbEntity.setDifyWorkflowId(entity.getDifyWorkflowId());
            }
            if (entity.getDifyApiKeyRef() != null) {
                dbEntity.setDifyApiKeyRef(entity.getDifyApiKeyRef());
            }
            dbEntity.setUpdateTime(java.time.LocalDateTime.now());
            promptConfigMapper.updateById(dbEntity);
            return dbEntity.getId();
        }

        entity.setStatus(1);
        entity.setIsDefault(1);
        entity.setDeleted(0);
        promptConfigMapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("failed to save prompt_config");
        }
        return entity.getId();
    }
}
