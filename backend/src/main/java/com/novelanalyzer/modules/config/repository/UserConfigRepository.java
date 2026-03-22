package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.config.mapper.UserConfigMapper;
import com.novelanalyzer.modules.config.model.UserConfigEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserConfigRepository {

    private final UserConfigMapper userConfigMapper;

    public UserConfigRepository(UserConfigMapper userConfigMapper) {
        this.userConfigMapper = userConfigMapper;
    }

    public Optional<UserConfigEntity> findByUserIdAndKey(Long userId, String configKey) {
        UserConfigEntity entity = userConfigMapper.selectOne(
            new LambdaQueryWrapper<UserConfigEntity>()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getConfigKey, configKey)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public UserConfigEntity saveOrUpdate(Long userId, String configKey, String configValue) {
        Optional<UserConfigEntity> existing = findByUserIdAndKey(userId, configKey);
        if (existing.isPresent()) {
            UserConfigEntity entity = existing.get();
            entity.setConfigValue(configValue);
            userConfigMapper.updateById(entity);
            return entity;
        }

        UserConfigEntity entity = new UserConfigEntity();
        entity.setUserId(userId);
        entity.setConfigKey(configKey);
        entity.setConfigValue(configValue);
        userConfigMapper.insert(entity);
        return entity;
    }
}
