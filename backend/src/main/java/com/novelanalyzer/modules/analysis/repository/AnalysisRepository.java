package com.novelanalyzer.modules.analysis.repository;

import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

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
}
