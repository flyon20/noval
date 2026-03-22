package com.novelanalyzer.modules.analysis.repository;

import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class AnalysisRepository {

    private final AnalysisResultMapper analysisResultMapper;

    public AnalysisRepository(AnalysisResultMapper analysisResultMapper) {
        this.analysisResultMapper = analysisResultMapper;
    }

    public Long save(Long userId,
                     String platform,
                     Long bookId,
                     String analysisType,
                     Integer chapterCount,
                     Long promptConfigId,
                     String modelName,
                     String resultContent,
                     String resultJson,
                     Integer tokenUsed,
                     Long costTime) {
        AnalysisResultEntity entity = new AnalysisResultEntity();
        entity.setUserId(userId);
        entity.setPlatform(platform);
        entity.setBookId(bookId);
        entity.setAnalysisType(analysisType);
        entity.setChapterCount(chapterCount);
        entity.setPromptConfigId(promptConfigId);
        entity.setModelName(modelName);
        entity.setResultContent(resultContent);
        entity.setResultJson(resultJson);
        entity.setTokenUsed(tokenUsed);
        entity.setCostTime(costTime);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        entity.setDeleted(0);
        analysisResultMapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("failed to persist analysis result");
        }
        return entity.getId();
    }

    public Optional<AnalysisResultEntity> findLatestReusable(String platform,
                                                             Long bookId,
                                                             String analysisType,
                                                             Integer chapterCount,
                                                             Long promptConfigId,
                                                             LocalDateTime validAfter) {
        AnalysisResultEntity entity = analysisResultMapper.selectOne(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getDeleted, 0)
                .eq(AnalysisResultEntity::getPlatform, platform)
                .eq(AnalysisResultEntity::getBookId, bookId)
                .eq(AnalysisResultEntity::getAnalysisType, analysisType)
                .eq(AnalysisResultEntity::getChapterCount, chapterCount)
                .eq(AnalysisResultEntity::getPromptConfigId, promptConfigId)
                .ge(validAfter != null, AnalysisResultEntity::getCreateTime, validAfter)
                .orderByDesc(AnalysisResultEntity::getCreateTime)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }
}
