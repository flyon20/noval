package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.config.CrawlerProperties;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Service
@EnableScheduling
public class CrawlerRankBackfillScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlerRankBackfillScheduler.class);

    private final CrawlerService crawlerService;
    private final CrawlerRepository crawlerRepository;
    private final CrawlerProperties crawlerProperties;

    public CrawlerRankBackfillScheduler(CrawlerService crawlerService,
                                        CrawlerRepository crawlerRepository,
                                        CrawlerProperties crawlerProperties) {
        this.crawlerService = crawlerService;
        this.crawlerRepository = crawlerRepository;
        this.crawlerProperties = crawlerProperties;
    }

    @Scheduled(cron = "${app.crawler.rank-backfill.cron:0 15 2 * * ?}")
    public void backfillStaleRankBoards() {
        CrawlerProperties.RankBackfill config = crawlerProperties.getRankBackfill();
        if (!config.isEnabled()) {
            return;
        }
        String platform = normalizePlatform(config.getPlatform());
        int refreshDays = Math.max(1, config.getRefreshDays());
        int batchSize = Math.max(1, Math.min(config.getBatchSize(), 100));
        int rankFetchCount = normalizeRankFetchCount(config.getRankFetchCount());
        List<RankBoardEntity> boards = crawlerRepository.findBoardsMissingSnapshotBefore(platform, refreshDays, batchSize);
        int submitted = 0;
        int failed = 0;
        for (RankBoardEntity board : boards) {
            try {
                CrawlerRankRequest request = new CrawlerRankRequest();
                request.setPlatform(platform);
                request.setChannelCode(board.getChannelCode());
                request.setBoardCode(board.getBoardCode());
                request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
                request.setRankFetchCount(rankFetchCount);
                request.setIdempotencyKey(CrawlerRankIdempotencyKeyFactory.generate(
                    "rank-backfill",
                    request,
                    LocalDate.now(ZoneOffset.UTC).toString()
                ));
                crawlerService.refreshRankBoard(request);
                submitted++;
            } catch (RuntimeException ex) {
                failed++;
                LOGGER.warn("crawler rank backfill failed platform={} channelCode={} boardCode={} reason={}",
                    platform, board.getChannelCode(), board.getBoardCode(), ex.getMessage());
            }
        }
        LOGGER.info("crawler rank backfill finished platform={} refreshDays={} batchSize={} submitted={} failed={}",
            platform, refreshDays, batchSize, submitted, failed);
    }

    @Scheduled(cron = "${app.crawler.rank-catalog-sync.cron:0 35 2 1 * ?}")
    public void syncRankCatalog() {
        CrawlerProperties.RankCatalogSync config = crawlerProperties.getRankCatalogSync();
        if (!config.isEnabled()) {
            return;
        }
        String platform = normalizePlatform(config.getPlatform());
        try {
            int count = crawlerService.syncRankBoardCatalog(platform).stream()
                .mapToInt(channel -> channel.getBoards().size())
                .sum();
            LOGGER.info("crawler rank catalog sync finished platform={} boardCount={}", platform, count);
        } catch (RuntimeException ex) {
            LOGGER.warn("crawler rank catalog sync failed platform={} reason={}", platform, ex.getMessage());
        }
    }

    private String normalizePlatform(String platform) {
        return platform == null || platform.isBlank() ? "fanqie" : platform.trim();
    }

    private int normalizeRankFetchCount(int rankFetchCount) {
        if (rankFetchCount < 10 || rankFetchCount > 100 || rankFetchCount % 10 != 0) {
            return 50;
        }
        return rankFetchCount;
    }
}
