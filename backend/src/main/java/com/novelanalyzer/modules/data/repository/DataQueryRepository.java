package com.novelanalyzer.modules.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlRankMapper;
import com.novelanalyzer.modules.crawler.mapper.RankBoardMapper;
import com.novelanalyzer.modules.crawler.mapper.RankSnapshotMapper;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
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
    private final RankBoardMapper rankBoardMapper;
    private final RankSnapshotMapper rankSnapshotMapper;

    public DataQueryRepository(AnalysisResultMapper analysisResultMapper,
                               CrawlBookMapper crawlBookMapper,
                               CrawlRankMapper crawlRankMapper,
                               RankBoardMapper rankBoardMapper,
                               RankSnapshotMapper rankSnapshotMapper) {
        this.analysisResultMapper = analysisResultMapper;
        this.crawlBookMapper = crawlBookMapper;
        this.crawlRankMapper = crawlRankMapper;
        this.rankBoardMapper = rankBoardMapper;
        this.rankSnapshotMapper = rankSnapshotMapper;
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

    public Optional<RankBoardEntity> findBoard(String platform, String channelCode, String boardCode) {
        RankBoardEntity entity = rankBoardMapper.selectOne(
            new LambdaQueryWrapper<RankBoardEntity>()
                .eq(RankBoardEntity::getDeleted, 0)
                .eq(RankBoardEntity::getPlatform, platform)
                .eq(RankBoardEntity::getChannelCode, channelCode)
                .eq(RankBoardEntity::getBoardCode, boardCode)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<RankSnapshotEntity> findRecentSnapshots(Long boardId, int limit) {
        return rankSnapshotMapper.selectList(
            new LambdaQueryWrapper<RankSnapshotEntity>()
                .eq(RankSnapshotEntity::getDeleted, 0)
                .eq(RankSnapshotEntity::getRankBoardId, boardId)
                .orderByDesc(RankSnapshotEntity::getSnapshotTime)
                .last("LIMIT " + Math.max(limit, 1))
        );
    }

    public List<CrawlRankEntity> findRanksBySnapshotIds(List<Long> snapshotIds) {
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return List.of();
        }
        return crawlRankMapper.selectList(
            new LambdaQueryWrapper<CrawlRankEntity>()
                .eq(CrawlRankEntity::getDeleted, 0)
                .in(CrawlRankEntity::getSnapshotId, snapshotIds)
                .orderByDesc(CrawlRankEntity::getCrawlTime)
                .orderByAsc(CrawlRankEntity::getRankNo)
        );
    }

    public Optional<AnalysisResultEntity> findLatestBoardThemeResult(String platform,
                                                                     String channelCode,
                                                                     String boardCode) {
        AnalysisResultEntity entity = analysisResultMapper.selectOne(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getDeleted, 0)
                .eq(AnalysisResultEntity::getPlatform, platform)
                .eq(AnalysisResultEntity::getChannelCode, channelCode)
                .eq(AnalysisResultEntity::getBoardCode, boardCode)
                .eq(AnalysisResultEntity::getAnalysisType, "theme")
                .orderByDesc(AnalysisResultEntity::getCreateTime)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }
}
