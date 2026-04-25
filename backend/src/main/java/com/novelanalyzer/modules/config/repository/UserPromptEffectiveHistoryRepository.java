package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.config.mapper.UserPromptEffectiveHistoryMapper;
import com.novelanalyzer.modules.config.model.UserPromptEffectiveHistoryEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserPromptEffectiveHistoryRepository {

    private final UserPromptEffectiveHistoryMapper userPromptEffectiveHistoryMapper;

    public UserPromptEffectiveHistoryRepository(UserPromptEffectiveHistoryMapper userPromptEffectiveHistoryMapper) {
        this.userPromptEffectiveHistoryMapper = userPromptEffectiveHistoryMapper;
    }

    public Long save(UserPromptEffectiveHistoryEntity entity) {
        userPromptEffectiveHistoryMapper.insert(entity);
        return entity.getId();
    }

    public Optional<UserPromptEffectiveHistoryEntity> findLatestByUserIdAndPromptType(Long userId, String promptType) {
        UserPromptEffectiveHistoryEntity entity = userPromptEffectiveHistoryMapper.selectOne(
            new LambdaQueryWrapper<UserPromptEffectiveHistoryEntity>()
                .eq(UserPromptEffectiveHistoryEntity::getUserId, userId)
                .eq(UserPromptEffectiveHistoryEntity::getPromptType, promptType)
                .eq(UserPromptEffectiveHistoryEntity::getDeleted, 0)
                .orderByDesc(UserPromptEffectiveHistoryEntity::getCreateTime)
                .orderByDesc(UserPromptEffectiveHistoryEntity::getId)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<UserPromptEffectiveHistoryEntity> findByUserIdAndPromptType(Long userId, String promptType) {
        return userPromptEffectiveHistoryMapper.selectList(
            new LambdaQueryWrapper<UserPromptEffectiveHistoryEntity>()
                .eq(UserPromptEffectiveHistoryEntity::getUserId, userId)
                .eq(UserPromptEffectiveHistoryEntity::getPromptType, promptType)
                .eq(UserPromptEffectiveHistoryEntity::getDeleted, 0)
                .orderByDesc(UserPromptEffectiveHistoryEntity::getCreateTime)
                .orderByDesc(UserPromptEffectiveHistoryEntity::getId)
        );
    }
}
