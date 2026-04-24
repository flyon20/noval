package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.novelanalyzer.modules.config.mapper.PromptConfigMapper;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    public Optional<PromptConfigEntity> findActiveByTypeAndName(String promptType, String promptName) {
        PromptConfigEntity entity = promptConfigMapper.selectOne(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getPromptType, promptType)
                .eq(PromptConfigEntity::getPromptName, promptName)
                .eq(PromptConfigEntity::getStatus, 1)
                .eq(PromptConfigEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<PromptConfigEntity> findActiveByType(String promptType) {
        return promptConfigMapper.selectList(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getPromptType, promptType)
                .eq(PromptConfigEntity::getStatus, 1)
                .eq(PromptConfigEntity::getDeleted, 0)
                .orderByDesc(PromptConfigEntity::getIsDefault)
                .orderByAsc(PromptConfigEntity::getPromptName)
                .orderByAsc(PromptConfigEntity::getId)
        );
    }

    public List<PromptConfigEntity> findAllActive() {
        return promptConfigMapper.selectList(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getDeleted, 0)
                .eq(PromptConfigEntity::getStatus, 1)
                .orderByAsc(PromptConfigEntity::getId)
        );
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
            dbEntity.setInputJsonSchema(entity.getInputJsonSchema());
            dbEntity.setInputExampleJson(entity.getInputExampleJson());
            dbEntity.setOutputJsonSchema(entity.getOutputJsonSchema());
            dbEntity.setOutputExampleJson(entity.getOutputExampleJson());
            dbEntity.setPostProcessType(entity.getPostProcessType());
            dbEntity.setParseConfigJson(entity.getParseConfigJson());
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

    public void softDeleteById(Long id) {
        promptConfigMapper.update(
            null,
            new LambdaUpdateWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getId, id)
                .set(PromptConfigEntity::getStatus, 0)
                .set(PromptConfigEntity::getDeleted, 1)
                .set(PromptConfigEntity::getUpdateTime, java.time.LocalDateTime.now())
        );
    }
}
