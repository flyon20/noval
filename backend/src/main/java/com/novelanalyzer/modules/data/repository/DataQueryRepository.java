package com.novelanalyzer.modules.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlRankMapper;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class DataQueryRepository {

    private final AnalysisResultMapper analysisResultMapper;
    private final CrawlBookMapper crawlBookMapper;
    private final CrawlRankMapper crawlRankMapper;

    public DataQueryRepository(AnalysisResultMapper analysisResultMapper,
                               CrawlBookMapper crawlBookMapper,
                               CrawlRankMapper crawlRankMapper) {
        this.analysisResultMapper = analysisResultMapper;
        this.crawlBookMapper = crawlBookMapper;
        this.crawlRankMapper = crawlRankMapper;
    }

    public List<AnalysisResultEntity> findHistory(String platform, Long bookId, String analysisType, int limit) {
        LambdaQueryWrapper<AnalysisResultEntity> wrapper = new LambdaQueryWrapper<AnalysisResultEntity>()
            .eq(AnalysisResultEntity::getDeleted, 0)
            .orderByDesc(AnalysisResultEntity::getCreateTime)
            .last("LIMIT " + limit);
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getPlatform, platform);
        }
        if (bookId != null) {
            wrapper.eq(AnalysisResultEntity::getBookId, bookId);
        }
        if (analysisType != null && !analysisType.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getAnalysisType, analysisType);
        }
        return analysisResultMapper.selectList(wrapper);
    }

    public Map<Long, CrawlBookEntity> findBookMap(List<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Map.of();
        }
        return crawlBookMapper.selectBatchIds(bookIds).stream()
            .filter(item -> item.getDeleted() == null || item.getDeleted() == 0)
            .collect(Collectors.toMap(CrawlBookEntity::getId, Function.identity(), (left, right) -> left));
    }

    public List<AnalysisResultEntity> findAnalysisResultsByPlatform(String platform) {
        LambdaQueryWrapper<AnalysisResultEntity> wrapper = new LambdaQueryWrapper<AnalysisResultEntity>()
            .eq(AnalysisResultEntity::getDeleted, 0)
            .orderByDesc(AnalysisResultEntity::getCreateTime);
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getPlatform, platform);
        }
        return analysisResultMapper.selectList(wrapper);
    }

    public List<CrawlRankEntity> findRanksByPlatform(String platform) {
        LambdaQueryWrapper<CrawlRankEntity> wrapper = new LambdaQueryWrapper<CrawlRankEntity>()
            .eq(CrawlRankEntity::getDeleted, 0)
            .orderByDesc(CrawlRankEntity::getCrawlTime)
            .orderByAsc(CrawlRankEntity::getRankNo);
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(CrawlRankEntity::getPlatform, platform);
        }
        return crawlRankMapper.selectList(wrapper);
    }

    public Optional<AnalysisResultEntity> findLatestAnalysisResult(String platform, String analysisType) {
        AnalysisResultEntity entity = analysisResultMapper.selectOne(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getDeleted, 0)
                .eq(platform != null && !platform.isBlank(), AnalysisResultEntity::getPlatform, platform)
                .eq(AnalysisResultEntity::getAnalysisType, analysisType)
                .orderByDesc(AnalysisResultEntity::getCreateTime)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }
}
