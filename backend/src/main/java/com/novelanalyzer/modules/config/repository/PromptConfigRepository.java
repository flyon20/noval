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
                .eq(PromptConfigEntity::getScopeType, "SYSTEM")
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

    public List<PromptConfigEntity> findActiveSystemByType(String promptType) {
        return promptConfigMapper.selectList(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getPromptType, promptType)
                .eq(PromptConfigEntity::getScopeType, "SYSTEM")
                .eq(PromptConfigEntity::getStatus, 1)
                .eq(PromptConfigEntity::getDeleted, 0)
                .orderByDesc(PromptConfigEntity::getIsDefault)
                .orderByAsc(PromptConfigEntity::getPromptName)
                .orderByAsc(PromptConfigEntity::getId)
        );
    }

    public List<PromptConfigEntity> findActiveUserCopiesByType(Long ownerUserId, String promptType) {
        return promptConfigMapper.selectList(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getPromptType, promptType)
                .eq(PromptConfigEntity::getScopeType, "USER_COPY")
                .eq(PromptConfigEntity::getOwnerUserId, ownerUserId)
                .eq(PromptConfigEntity::getStatus, 1)
                .eq(PromptConfigEntity::getDeleted, 0)
                .orderByAsc(PromptConfigEntity::getPromptName)
                .orderByAsc(PromptConfigEntity::getId)
        );
    }

    public Optional<PromptConfigEntity> findActiveUserCopyById(Long ownerUserId, Long promptConfigId) {
        PromptConfigEntity entity = promptConfigMapper.selectOne(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getId, promptConfigId)
                .eq(PromptConfigEntity::getScopeType, "USER_COPY")
                .eq(PromptConfigEntity::getOwnerUserId, ownerUserId)
                .eq(PromptConfigEntity::getStatus, 1)
                .eq(PromptConfigEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public Optional<PromptConfigEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        PromptConfigEntity entity = promptConfigMapper.selectOne(
            new LambdaQueryWrapper<PromptConfigEntity>()
                .eq(PromptConfigEntity::getId, id)
                .eq(PromptConfigEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public Long saveOrUpdate(PromptConfigEntity entity) {
        if (entity.getId() != null) {
            Optional<PromptConfigEntity> existingById = findById(entity.getId());
            if (existingById.isPresent()) {
                PromptConfigEntity dbEntity = existingById.get();
                dbEntity.setPromptType(entity.getPromptType());
                dbEntity.setPromptName(entity.getPromptName());
                dbEntity.setScopeType(entity.getScopeType());
                dbEntity.setOwnerUserId(entity.getOwnerUserId());
                dbEntity.setSourcePromptConfigId(entity.getSourcePromptConfigId());
                dbEntity.setPromptContent(entity.getPromptContent());
                dbEntity.setModelName(entity.getModelName());
                dbEntity.setTemperature(entity.getTemperature());
                dbEntity.setMaxTokens(entity.getMaxTokens());
                dbEntity.setStatus(entity.getStatus() == null ? dbEntity.getStatus() : entity.getStatus());
                dbEntity.setIsDefault(entity.getIsDefault() == null ? dbEntity.getIsDefault() : entity.getIsDefault());
                dbEntity.setDifyWorkflowId(entity.getDifyWorkflowId());
                dbEntity.setDifyApiKeyRef(entity.getDifyApiKeyRef());
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
        }

        Optional<PromptConfigEntity> existing = findByTypeAndName(entity.getPromptType(), entity.getPromptName())
            .filter(dbEntity -> sameScope(dbEntity, entity));
        if (existing.isPresent()) {
            PromptConfigEntity dbEntity = existing.get();
            dbEntity.setScopeType(entity.getScopeType());
            dbEntity.setOwnerUserId(entity.getOwnerUserId());
            dbEntity.setSourcePromptConfigId(entity.getSourcePromptConfigId());
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
            dbEntity.setStatus(entity.getStatus() == null ? dbEntity.getStatus() : entity.getStatus());
            dbEntity.setIsDefault(entity.getIsDefault() == null ? dbEntity.getIsDefault() : entity.getIsDefault());
            dbEntity.setUpdateTime(java.time.LocalDateTime.now());
            promptConfigMapper.updateById(dbEntity);
            return dbEntity.getId();
        }

        entity.setStatus(entity.getStatus() == null ? 1 : entity.getStatus());
        entity.setIsDefault(entity.getIsDefault() == null ? 0 : entity.getIsDefault());
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

    private boolean sameScope(PromptConfigEntity left, PromptConfigEntity right) {
        String leftScope = left.getScopeType() == null ? "SYSTEM" : left.getScopeType();
        String rightScope = right.getScopeType() == null ? "SYSTEM" : right.getScopeType();
        if (!leftScope.equals(rightScope)) {
            return false;
        }
        if (!"USER_COPY".equals(leftScope)) {
            return true;
        }
        if (left.getOwnerUserId() == null) {
            return right.getOwnerUserId() == null;
        }
        return left.getOwnerUserId().equals(right.getOwnerUserId());
    }
}
