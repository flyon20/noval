package com.novelanalyzer.modules.config.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.config.mapper.PromptPublishItemMapper;
import com.novelanalyzer.modules.config.mapper.PromptPublishVersionMapper;
import com.novelanalyzer.modules.config.model.PromptPublishItemEntity;
import com.novelanalyzer.modules.config.model.PromptPublishVersionEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PromptPublishRepository {

    private final PromptPublishVersionMapper promptPublishVersionMapper;
    private final PromptPublishItemMapper promptPublishItemMapper;

    public PromptPublishRepository(PromptPublishVersionMapper promptPublishVersionMapper,
                                   PromptPublishItemMapper promptPublishItemMapper) {
        this.promptPublishVersionMapper = promptPublishVersionMapper;
        this.promptPublishItemMapper = promptPublishItemMapper;
    }

    public Long saveVersion(PromptPublishVersionEntity entity) {
        promptPublishVersionMapper.insert(entity);
        return entity.getId();
    }

    public void saveItem(PromptPublishItemEntity entity) {
        promptPublishItemMapper.insert(entity);
    }

    public Optional<PromptPublishVersionEntity> findVersionById(Long id) {
        PromptPublishVersionEntity entity = promptPublishVersionMapper.selectOne(
            new LambdaQueryWrapper<PromptPublishVersionEntity>()
                .eq(PromptPublishVersionEntity::getId, id)
                .eq(PromptPublishVersionEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<PromptPublishItemEntity> findItemsByVersionId(Long publishVersionId) {
        return promptPublishItemMapper.selectList(
            new LambdaQueryWrapper<PromptPublishItemEntity>()
                .eq(PromptPublishItemEntity::getPublishVersionId, publishVersionId)
                .eq(PromptPublishItemEntity::getDeleted, 0)
                .orderByAsc(PromptPublishItemEntity::getPromptType)
                .orderByAsc(PromptPublishItemEntity::getId)
        );
    }

    public Optional<PromptPublishVersionEntity> findLatestVersion() {
        PromptPublishVersionEntity entity = promptPublishVersionMapper.selectOne(
            new LambdaQueryWrapper<PromptPublishVersionEntity>()
                .eq(PromptPublishVersionEntity::getDeleted, 0)
                .orderByDesc(PromptPublishVersionEntity::getVersionNo)
                .orderByDesc(PromptPublishVersionEntity::getId)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<PromptPublishVersionEntity> findAllVersions() {
        return promptPublishVersionMapper.selectList(
            new LambdaQueryWrapper<PromptPublishVersionEntity>()
                .eq(PromptPublishVersionEntity::getDeleted, 0)
                .orderByDesc(PromptPublishVersionEntity::getVersionNo)
                .orderByDesc(PromptPublishVersionEntity::getId)
        );
    }
}
