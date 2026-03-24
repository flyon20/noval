package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.config.mapper.SystemConfigMapper;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SystemConfigRepository {

    private final SystemConfigMapper systemConfigMapper;

    public SystemConfigRepository(SystemConfigMapper systemConfigMapper) {
        this.systemConfigMapper = systemConfigMapper;
    }

    public Optional<SystemConfigEntity> findByKey(String configKey) {
        SystemConfigEntity entity = systemConfigMapper.selectOne(
            new LambdaQueryWrapper<SystemConfigEntity>()
                .eq(SystemConfigEntity::getConfigKey, configKey)
                .eq(SystemConfigEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public SystemConfigEntity saveOrUpdate(SystemConfigEntity entity) {
        Optional<SystemConfigEntity> existing = findByKey(entity.getConfigKey());
        if (existing.isPresent()) {
            SystemConfigEntity dbEntity = existing.get();
            dbEntity.setConfigValue(entity.getConfigValue());
            if (entity.getConfigType() != null) {
                dbEntity.setConfigType(entity.getConfigType());
            }
            if (entity.getDescription() != null) {
                dbEntity.setDescription(entity.getDescription());
            }
            systemConfigMapper.updateById(dbEntity);
            return dbEntity;
        }

        entity.setDeleted(0);
        if (entity.getEditable() == null) {
            entity.setEditable(1);
        }
        systemConfigMapper.insert(entity);
        return entity;
    }
}
